package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.Calculator
import net.g1project.tmcalc.OcrParser
import net.g1project.tmcalc.ParsedScreen
import net.g1project.tmcalc.Pet
import net.g1project.tmcalc.PetDb
import net.g1project.tmcalc.PetDraft
import net.g1project.tmcalc.Preset
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.image.BufferedImage
import java.util.Locale
import java.util.prefs.Preferences
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JMenu
import javax.swing.JMenuBar
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

/**
 * PC 계산 창. 폰 앱 결과창과 같은 배치: 결과(총 성장률) → 이름/레벨/현재 강 → 능력치/대비 → 인게임 % → 자세히.
 * 계산은 폰 앱과 같은 Calculator / OcrParser 를 쓴다.
 */
class MainWindow(
    private val pets: DesktopPets,
    /** 창 설정(대상 창, 단축키, 항상 위). 테스트에서는 따로 만든 저장소를 넘긴다 */
    private val prefs: Preferences = Preferences.userNodeForPackage(MainWindow::class.java),
) : JFrame(TITLE) {

    var ocr: PaddleOcr? = null

    private val targets = JComboBox<WindowCapture.Target>().apply {
        font = UI_FONT.deriveFont(12f)
        // 창 제목은 다른 프로그램이 정한다 → "<html><img src=http://…>" 같은 제목을 HTML 로 그리지 않게
        renderer = javax.swing.DefaultListCellRenderer().apply { putClientProperty("html.disable", true) }
    }
    private val scanBtn = button("계산").apply { font = font.deriveFont(Font.BOLD, 15f) }
    private val hotkey = GlobalHotkey()
    private var hotkeyKey = GlobalHotkey.Key(prefs.getInt("hotkeyMods", GlobalHotkey.DEFAULT.mods), prefs.getInt("hotkeyVk", GlobalHotkey.DEFAULT.vk))
    private val status = label("OCR 준비 중…", 11f, MUTED)

    private val totalLbl = label("-", 30f, Color.WHITE).apply { font = font.deriveFont(Font.BOLD) }
    private val gradeLbl = RainbowLabel().apply { font = UI_FONT.deriveFont(Font.BOLD, 18f); foreground = Color.WHITE }
    private val subLbl = label("앱플레이어에서 소환수 정보 화면을 띄우고 [계산] 또는 단축키", 11f, SUB, allowHtml = true)
    private val scoreLbl = label("", 12f, Color.WHITE)
    /** 같은 종 도감(S 100%) 개체보다 전투 능력치가 몇 % 높은지 (총 능력치 점수 보너스 포함) */
    private val combatLbl = label("", 15f, GREEN).apply {
        font = font.deriveFont(Font.BOLD); isVisible = false
        // 누르면 설명 (앱만 받아 쓰는 사람도 뜻을 알 수 있게)
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        toolTipText = "눌러서 설명 보기"
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                JOptionPane.showMessageDialog(this@MainWindow, javax.swing.JTextArea(Calculator.COMBAT_HELP).apply {
                    isEditable = false; lineWrap = true; wrapStyleWord = true; columns = 34; isOpaque = false
                    font = UI_FONT.deriveFont(13f); putClientProperty("html.disable", true)
                }, "종 대비 전투 능력치", JOptionPane.INFORMATION_MESSAGE)
            }
        })
    }
    private val warnLbl = label("", 12f, WARN, allowHtml = true)
    private val suggestBox = FitPanel(GridLayout(0, 2, 6, 6)).apply { isOpaque = false; isVisible = false }
    private val suggestHint = row(label("혹시 이 소환수인가요? (눌러서 선택)", 11f, MUTED))

    private val nameF = field(); private val levelF = field(); private val breakF = field()
    private val statF = Array(4) { field() }
    private val deltaF = Array(4) { field().apply { foreground = GREEN } }
    private val gameF = field()

    private val detail = vbox().apply { isVisible = false }
    private val results = vbox()
    private val enhance = vbox()

    private var filling = false
    @Volatile private var busy = false
    /** 강화 표에서 눌러서 능력치별 전투를 펼친 강 */
    private var expandedBreak: Int? = null
    private var lastParsed: ParsedScreen? = null
    /** 강화별 표의 '최저치로 보기' / '이전 강화 보기'. 기억하지 않고 새로 계산할 때마다 꺼진 상태로 */
    private var worstOn = false
    private var lowerOn = false
    /** 비교 기준(A). 지금 계산이나 저장 목록에서 고정한다. A/B 를 볼 강 (B 가 null 이면 찍힌 강) */
    private var pinned: Preset? = null
    private var pinTarget = 0
    private var curTarget: Int? = null
    /** 비교 대상(B)을 저장 목록에서 골랐으면 그 소환수, 아니면 null(= 지금 계산) */
    private var compareB: Preset? = null
    private val compareBox = vbox()

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        isAlwaysOnTop = prefs.getBoolean("onTop", true)
        jMenuBar = menu()

        val top = vbox().apply {
            border = BorderFactory.createEmptyBorder(8, 10, 6, 10)
            add(row(label("대상 창", 11f, MUTED)))
            add(JPanel(BorderLayout(6, 0)).apply {
                isOpaque = false
                add(targets, BorderLayout.CENTER)
                add(button("새로고침") { refreshTargets() }, BorderLayout.EAST)
            })
            add(Box.createVerticalStrut(6))
            add(JPanel(BorderLayout(8, 0)).apply {
                isOpaque = false
                add(scanBtn, BorderLayout.WEST)
                add(status, BorderLayout.CENTER)
                add(JCheckBox("항상 위", isAlwaysOnTop).apply {
                    isOpaque = false; foreground = MUTED
                    addActionListener { this@MainWindow.isAlwaysOnTop = isSelected; prefs.putBoolean("onTop", isSelected) }
                }, BorderLayout.EAST)
            })
        }
        scanBtn.addActionListener { scan() }
        // 사용자가 직접 고른 창만 기억한다 (목록을 채우면서 자동으로 고른 창은 기억하지 않음)
        targets.addActionListener { if (!refreshing) (targets.selectedItem as? WindowCapture.Target)?.let { prefs.put("target", it.title) } }

        val body = vbox().apply {
            border = BorderFactory.createEmptyBorder(4, 12, 12, 12)
            add(row(label("총 성장률 (계산기)", 11f, MUTED)))
            add(row(totalLbl, gradeLbl))
            add(row(subLbl)); add(row(combatLbl)); add(row(scoreLbl)); add(row(warnLbl))
            add(compareBox.apply { isVisible = false })
            add(JSeparator().apply { foreground = CHIP; maximumSize = Dimension(Int.MAX_VALUE, 2) })
            add(Box.createVerticalStrut(4))
            add(suggestHint.apply { isVisible = false })
            add(suggestBox.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(grid(listOf("이름" to nameF, "레벨" to levelF, "현재 강" to breakF), weights = listOf(2.2, 1.0, 1.0)))
            add(FitPanel(GridLayout(1, 4, 6, 0)).apply {
                isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
                for (i in 0..3) add(vbox().apply { add(row(label(STAT_NAMES[i], 10f, MUTED))); add(statF[i]); add(deltaF[i]) })
            })
            add(grid(listOf("인게임 총 성장 점수 (%)" to gameF), weights = listOf(1.0)))
            add(Box.createVerticalStrut(4))
            add(row(button("저장") { savePreset() }, button("기준으로 고정") { pinCurrent() }, button("저장 목록…") { showPresets() }))
            add(Box.createVerticalStrut(6))
            add(button("자세히 ▼").apply {
                alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 32)
                addActionListener { detail.isVisible = !detail.isVisible; text = if (detail.isVisible) "자세히 ▲" else "자세히 ▼"; revalidate() }
            })
            add(detail.apply {
                add(results)
                // 지금 이름으로 도감 관리 열기. 150레벨이면 화면의 도감값(현재 - 도감 대비)을 만렙S 칸에 채워 준다
                add(button("도감에 추가/수정") { openPetEditor(fromScan = true) }.apply {
                    alignmentX = Component.LEFT_ALIGNMENT; maximumSize = Dimension(Int.MAX_VALUE, 32)
                })
                add(enhance)
            })
            add(Box.createVerticalGlue())
        }
        (listOf(nameF, levelF, breakF, gameF) + statF + deltaF).forEach { it.onChange { recompute() } }

        contentPane = JPanel(BorderLayout()).apply {
            background = BG
            add(top.apply { background = BG; isOpaque = true }, BorderLayout.NORTH)
            add(JScrollPane(body.apply { background = BG; isOpaque = true }).apply {
                border = null; verticalScrollBar.unitIncrement = 16; viewport.background = BG
            }, BorderLayout.CENTER)
            // 오른쪽 아래 버전
            add(label("v$VERSION", 10f, MUTED).apply {
                horizontalAlignment = JLabel.RIGHT; border = BorderFactory.createEmptyBorder(0, 0, 4, 10)
            }, BorderLayout.SOUTH)
        }
        size = Dimension(420, 760)
        setLocationByPlatform(true)
        refreshTargets()
        applyHotkey(hotkeyKey)
        if (!prefs.getBoolean("saveLastScan", false)) clearLastScan()
    }

    fun setStatus(t: String) { status.text = t }

    private var refreshing = false

    /** 창 목록 다시 읽기. 고르는 순서: 지난번에 직접 고른 창 → 앱플레이어 → 없으면 비워 둠 (아무 창이나 고르지 않게) */
    private fun refreshTargets() {
        refreshing = true
        try {
            val list = WindowCapture.list(TITLE)
            val want = prefs.get("target", null)
            targets.removeAllItems()
            list.forEach { targets.addItem(it) }
            val pick = list.firstOrNull { it.title == want } ?: list.firstOrNull { it.isEmulator }
            if (pick != null) targets.selectedItem = pick else targets.selectedIndex = -1
        } finally {
            refreshing = false
        }
    }

    // ------------------------------------------------------------------ 계산

    private fun scan() {
        val engine = ocr ?: return setStatus("OCR 준비 중입니다. 잠시 후 다시 눌러 주세요.")
        val t = targets.selectedItem as? WindowCapture.Target ?: return setStatus("대상 창을 골라 주세요 (새로고침)")
        if (busy) return
        busy = true
        scanBtn.isEnabled = false
        setStatus("읽는 중…")
        Thread {
            val t0 = System.currentTimeMillis()
            val result = runCatching {
                val img = WindowCapture.capture(t) ?: error("창을 캡처하지 못했습니다 (최소화돼 있거나 닫힘)")
                val lines = engine.recognize(img)
                val parsed = OcrParser.parse(lines, pets.db)
                if (prefs.getBoolean("saveLastScan", false)) saveLastScan(img, lines, parsed)
                // 괄호 숫자의 +/- 는 글자색(빨강/초록)으로 한 번 더 확인 (폰 앱과 같음)
                val deltas = Array(4) { i ->
                    val d = parsed.deltas[i] ?: return@Array null
                    val box = parsed.deltaBoxes[i] ?: return@Array d
                    when (colorSign(img, box)) { -1 -> -Math.abs(d); 1 -> Math.abs(d); else -> d }
                }
                parsed to deltas
            }
            SwingUtilities.invokeLater {
                busy = false
                scanBtn.isEnabled = true
                result.onSuccess { (p, d) ->
                    lastParsed = p
                    worstOn = false; lowerOn = false
                    fill(p, d)
                    setStatus(if (p.pet == null && p.stats.all { it == null }) "소환수 정보 화면을 찾지 못했습니다."
                        else "완료 (${System.currentTimeMillis() - t0} ms)")
                }.onFailure { setStatus(it.message ?: "실패") }
            }
        }.start()
    }

    /**
     * 문제가 생겼을 때 원인을 볼 수 있게, 마지막 계산 한 번의 캡처와 글자 인식 결과를 PC 안에 남긴다 (매번 덮어씀, 어디에도 보내지 않음).
     * %APPDATA%\TM-calc\마지막계산\캡처.png, 인식결과.txt
     */
    /** 저장해 둔 마지막 계산 화면 지우기 (저장을 끄면, 그리고 꺼진 채로 켜질 때) */
    private fun clearLastScan() {
        java.io.File(pets.dir, "마지막계산").listFiles()?.forEach { it.delete() }
    }

    private fun saveLastScan(img: BufferedImage, lines: List<net.g1project.tmcalc.OcrLine>, p: ParsedScreen) {
        runCatching {
            val dir = java.io.File(pets.dir, "마지막계산").apply { mkdirs() }
            javax.imageio.ImageIO.write(img, "png", java.io.File(dir, "캡처.png"))
            java.io.File(dir, "인식결과.txt").writeText(
                "# 해석: 이름=${p.pet?.name ?: p.nameRaw} Lv=${p.level} 능력치=${p.stats.toList()} 대비=${p.deltas.toList()} 총성장=${p.gameTotal}\n" +
                    "# 캡처 ${img.width}x${img.height}\n" +
                    lines.joinToString("\n") { "${it.text}|${it.left}|${it.top}|${it.right}|${it.bottom}" })
        }
    }

    private fun colorSign(img: BufferedImage, box: IntArray): Int {
        val l = box[0].coerceIn(0, img.width - 1); val r = box[2].coerceIn(l + 1, img.width)
        val t = box[1].coerceIn(0, img.height - 1); val b = box[3].coerceIn(t + 1, img.height)
        var red = 0; var green = 0
        for (y in t until b step 2) for (x in l until r step 2) {
            val c = img.getRGB(x, y)
            val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
            if (maxOf(cr, cg, cb) - minOf(cr, cg, cb) < 60) continue
            if (cr > cg + 50 && cr > cb) red++ else if (cg > cr + 40 && cg >= cb) green++
        }
        return when { red >= 8 && red > green * 2 -> -1; green >= 8 && green > red * 2 -> 1; else -> 0 }
    }

    private fun fill(p: ParsedScreen, deltas: Array<Int?>) {
        filling = true
        nameF.text = p.pet?.name ?: p.nameRaw?.let { PetDb.displayName(it) } ?: ""
        levelF.text = p.level?.toString() ?: ""
        breakF.text = (p.breaks ?: 0).toString()
        gameF.text = p.gameTotal?.toString() ?: ""
        for (i in 0..3) {
            val s = p.stats[i]
            var d = deltas[i]
            // 150 레벨이고 도감에 있으면: 부호만 틀리게 읽혔거나 괄호를 못 읽었을 때 시트 도감으로 채운다
            if (p.pet != null && s != null && p.level == Calculator.MAX_LEVEL) {
                val sheet = s - p.pet!!.maxS[i]
                if (d == null || Math.abs(d) == Math.abs(sheet)) d = sheet
            }
            statF[i].text = s?.toString() ?: ""
            deltaF[i].text = d?.toString() ?: ""
        }
        curTarget = null; compareB = null
        filling = false
        recompute()
    }

    // ------------------------------------------------------------------ 저장 목록 / 비교

    private val presetFile get() = java.io.File(pets.dir, "presets.json")

    fun presets(): List<Preset> = runCatching { Preset.listFromJson(presetFile.readText()) }.getOrDefault(emptyList())

    private fun setPresets(list: List<Preset>) {
        pets.dir.mkdirs()
        presetFile.writeText(Preset.listToJson(list.take(Preset.MAX_COUNT)))
    }

    /** 지금 계산 화면의 값 (능력치 4개와 도감값을 알 수 있을 때만). 도감값 = 현재 − 도감 대비, 비었으면 150레벨 도감 */
    private fun currentPreset(forSave: Boolean = false): Preset? {
        val pet = pets.db.find(nameF.text)
        val level = levelF.text.trim().toIntOrNull() ?: return null
        val breaks = (breakF.text.trim().toIntOrNull() ?: 0).coerceIn(0, Calculator.MAX_BREAKS)
        val stats = statF.map { num(it) }
        val deltas = deltaF.map { num(it) }
        if (stats.any { it == null || it <= 0 }) return null
        val base = IntArray(4) { i ->
            deltas[i]?.let { stats[i]!! - it } ?: pet?.maxS?.get(i)?.takeIf { level == Calculator.MAX_LEVEL } ?: return null
        }
        if (base.any { it <= 0 }) return null
        val name = pet?.name ?: nameF.text.trim()
        val game = num(gameF)
        // 저장할 때만 같은 이름 뒤에 (2), (3) 을 붙인다
        return Preset(Preset.defaultLabel(name, breaks, game, if (forSave) presets() else emptyList()), name, level, breaks,
            IntArray(4) { stats[it]!! }, base, game)
    }

    private fun savePreset() {
        val p = currentPreset(forSave = true) ?: return setStatus("능력치와 도감 대비를 먼저 채워 주세요 (150레벨이고 도감에 있으면 도감 대비는 비워도 됨)")
        val list = presets()
        if (list.size >= Preset.MAX_COUNT) return setStatus("저장은 ${Preset.MAX_COUNT}개까지입니다. 저장 목록에서 지워 주세요.")
        setPresets(list + p)
        setStatus("저장했습니다: ${p.label}")
        presetDialog?.takeIf { it.isDisplayable }?.let { refreshPresetList() }
        renderCompare(); revalidate(); repaint() // 비교 칸의 고르기 목록에도 바로 나오게
    }

    private fun setCompareB(p: Preset?) {
        if (pinned == null) return setStatus("먼저 [기준으로]로 기준을 고정해 주세요")
        compareB = p; curTarget = null
        renderCompare(); revalidate(); repaint()
    }

    private fun pinCurrent() {
        val p = currentPreset() ?: return setStatus("능력치와 도감 대비를 먼저 채워 주세요")
        pin(p)
    }

    private fun pin(p: Preset) {
        pinned = p; pinTarget = p.breaks
        setStatus("기준 고정: ${p.label} — 다른 소환수를 계산하면 나란히 비교됩니다")
        renderCompare(); revalidate(); repaint()
    }

    /** 저장한 값을 계산 화면에 그대로 띄운다 (게임에서 다시 찍지 않아도 됨) */
    private fun loadPreset(p: Preset) {
        filling = true
        nameF.text = p.name; levelF.text = p.level.toString(); breakF.text = p.breaks.toString()
        gameF.text = p.gameTotal?.toString() ?: ""
        val d = p.deltas
        for (i in 0..3) { statF[i].text = p.stats[i].toString(); deltaF[i].text = d[i].toString() }
        lastParsed = null
        worstOn = false; lowerOn = false; curTarget = null; compareB = null
        filling = false
        recompute()
    }

    private var presetDialog: javax.swing.JDialog? = null
    private val presetModel = javax.swing.DefaultListModel<Preset>()

    private fun refreshPresetList() { presetModel.clear(); presets().forEach { presetModel.addElement(it) } }

    /** 저장 목록: 보기 / 기준으로 / 삭제 */
    private fun showPresets() {
        presetDialog?.takeIf { it.isDisplayable }?.let { refreshPresetList(); it.isVisible = true; it.toFront(); return }
        val dlg = javax.swing.JDialog(this, "저장 목록", false)
        refreshPresetList()
        val list = javax.swing.JList(presetModel).apply {
            background = FIELD; foreground = Color.WHITE; font = UI_FONT.deriveFont(13f)
            // 이름은 사용자가 붙인 글자라 HTML 로 그리지 않는다
            cellRenderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(l: javax.swing.JList<*>?, v: Any?, i: Int, sel: Boolean, f: Boolean): Component =
                    super.getListCellRendererComponent(l, (v as? Preset)?.let {
                        "${it.label}   (Lv.${it.level} · 총능 ${fmt(Calculator.abilityScore(it.stats), 1)})" + (if (it.memo.isNotEmpty()) "  — ${it.memo}" else "")
                    }, i, sel, f)
                        .also { (it as JLabel).putClientProperty("html.disable", true); it.border = BorderFactory.createEmptyBorder(4, 6, 4, 6) }
            }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { if (e.clickCount == 2) selectedValue?.let { loadPreset(it) } }
            })
        }
        fun sel(f: (Preset) -> Unit) = list.selectedValue?.let(f) ?: setStatus("목록에서 하나를 골라 주세요")
        dlg.contentPane = JPanel(BorderLayout()).apply {
            background = BG
            add(JScrollPane(list).apply { preferredSize = Dimension(380, 260); border = null }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply {
                isOpaque = false
                add(button("보기") { sel { loadPreset(it) } })
                add(button("기준으로") { sel { pin(it) } })
                add(button("비교") { sel { setCompareB(it) } })
                add(button("이름/메모") { sel { editPreset(dlg, it) } })
                add(button("삭제") { sel { p ->
                    // 실수로 지우지 않게 한 번 묻는다
                    if (JOptionPane.showConfirmDialog(dlg, "'${p.label}' 을(를) 저장 목록에서 삭제할까요?", "삭제",
                            JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) {
                        setPresets(presets().filterNot { it.label == p.label }); refreshPresetList()
                        renderCompare(); revalidate(); repaint()
                    }
                } })
            }, BorderLayout.SOUTH)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false; border = BorderFactory.createEmptyBorder(6, 8, 2, 8)
                add(label("두 번 누르면 계산 화면에 띄웁니다", 11f, MUTED), BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(button("내보내기") { exportPresets(dlg) })
                    add(button("올리기") { importPresets(dlg) })
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
        }
        dlg.pack(); dlg.setLocationRelativeTo(this); dlg.isVisible = true
        presetDialog = dlg
    }

    /** 이름·메모 고치기 */
    private fun editPreset(parent: java.awt.Component, p: Preset) {
        val nameE = JTextField(p.label, 24); val memoE = JTextField(p.memo, 24)
        val panel = JPanel(GridLayout(0, 1, 0, 4)).apply {
            add(JLabel("이름").apply { putClientProperty("html.disable", true) }); add(nameE)
            add(JLabel("메모 (예: PVP용, 재료 후보)").apply { putClientProperty("html.disable", true) }); add(memoE)
        }
        if (JOptionPane.showConfirmDialog(parent, panel, "이름 / 메모", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        val list = presets()
        val label = Preset.uniqueLabel(nameE.text, list.filterNot { it.label == p.label })
        setPresets(list.map { if (it.label == p.label) it.renamed(label, memoE.text) else it })
        if (pinned?.label == p.label) pinned = pinned!!.renamed(label, memoE.text)
        refreshPresetList(); renderCompare(); revalidate(); repaint()
    }

    /** 저장 목록을 JSON 파일로 (폰 앱과 같은 형식) */
    private fun exportPresets(parent: java.awt.Component) {
        val list = presets()
        if (list.isEmpty()) return setStatus("저장한 소환수가 없습니다")
        val fc = javax.swing.JFileChooser().apply { selectedFile = java.io.File("저장목록.json") }
        if (fc.showSaveDialog(parent) != javax.swing.JFileChooser.APPROVE_OPTION) return
        runCatching { fc.selectedFile.writeText(Preset.listToJson(list)) }
            .onSuccess { setStatus("${list.size}개를 저장했습니다: ${fc.selectedFile.name}") }
            .onFailure { JOptionPane.showMessageDialog(parent, "저장하지 못했습니다: ${it.message}", "내보내기", JOptionPane.WARNING_MESSAGE) }
    }

    /** 파일의 저장 목록을 지금 목록 뒤에 붙인다 (똑같은 소환수는 건너뜀) */
    private fun importPresets(parent: java.awt.Component) {
        val fc = javax.swing.JFileChooser().apply { fileFilter = javax.swing.filechooser.FileNameExtensionFilter("저장 목록 JSON", "json") }
        if (fc.showOpenDialog(parent) != javax.swing.JFileChooser.APPROVE_OPTION) return
        runCatching {
            val f = fc.selectedFile
            require(f.length() <= 1 shl 20) { "파일이 너무 큽니다 (1MB 이하)" }
            val incoming = Preset.listFromJson(f.readText())
            require(incoming.isNotEmpty()) { "저장 목록 파일이 아니거나 비어 있습니다" }
            val (merged, added) = Preset.merge(presets(), incoming)
            setPresets(merged); added to merged.size
        }.onSuccess { (added, total) -> refreshPresetList(); renderCompare(); setStatus("새로 ${added}개를 넣었습니다 (모두 ${total}개)") }
            .onFailure { JOptionPane.showMessageDialog(parent, "올리지 못했습니다: ${it.message}", "올리기", JOptionPane.WARNING_MESSAGE) }
    }

    /**
     * 기준(A)과 지금 계산(B)을 나란히: 능력치와 "B 의 실제 전투 능력치가 A 보다 몇 %".
     * 강은 찍힌 강이 기본이고 옆 칸에서 0~5강을 고르면 그 강의 예상 능력치로 비교한다.
     */
    private fun renderCompare() {
        compareBox.removeAll()
        val a = pinned
        compareBox.isVisible = a != null
        if (a == null) return
        compareBox.add(section("비교"))
        val saved = presets()
        // 이름 칸: 세로 목록에서 저장한 소환수 고르기 (A: 기준 바꾸기, B: 지금 계산 / 저장한 소환수)
        fun nameBox(items: List<String>, sel: Int, onPick: (Int) -> Unit) = JComboBox(items.toTypedArray()).apply {
            selectedIndex = sel; font = UI_FONT.deriveFont(Font.BOLD, 12f)
            (renderer as? JLabel)?.putClientProperty("html.disable", true)
            // 표는 네 칸이 같은 너비라, 긴 이름 때문에 창이 옆으로 늘어나지 않게 폭을 고정 (펼친 목록은 전체 이름)
            preferredSize = Dimension(84, preferredSize.height)
            prototypeDisplayValue = null
            addActionListener { if (selectedIndex != sel) { onPick(selectedIndex); SwingUtilities.invokeLater { renderCompare(); revalidate(); repaint() } } }
        }
        fun breakBox(sel: Int, onPick: (Int) -> Unit) = JComboBox((0..Calculator.MAX_BREAKS).map { "${it}강" }.toTypedArray()).apply {
            selectedIndex = sel; font = UI_FONT.deriveFont(12f)
            preferredSize = Dimension(84, preferredSize.height)
            addActionListener { onPick(selectedIndex); SwingUtilities.invokeLater { renderCompare(); revalidate(); repaint() } }
        }
        val b = compareB ?: currentPreset()
        val bT = b?.let { curTarget ?: it.breaks }
        val c = b?.let { Calculator.compare(a.stats, a.base, a.breaks, pinTarget, it.stats, it.base, it.breaks, bT!!, growthAmp()) }
        // 열 머리에 이름, 그 아래 강 고르기, 그 아래 능력치 (이름은 사용자가 붙인 글자라 HTML 로 그리지 않음)
        val grid = FitPanel(GridLayout(0, 4, 4, 2)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        fun cell(t: String, color: Color = Color.WHITE, bold: Boolean = false, left: Boolean = false) = label(t, 12f, color).apply {
            horizontalAlignment = if (left) JLabel.LEFT else JLabel.CENTER
            if (bold) font = font.deriveFont(Font.BOLD)
        }
        grid.add(cell(""))
        grid.add(nameBox(listOf("${a.name}(기준)") + saved.map { it.label }, 0) { if (it > 0) pin(saved[it - 1]) })
        val bSel = compareB?.let { cb -> saved.indexOfFirst { it.label == cb.label } + 1 } ?: 0
        grid.add(nameBox(listOf(if (compareB == null) (b?.name?.ifBlank { null } ?: "지금 계산") else "지금 계산") + saved.map { it.label }, bSel.coerceAtLeast(0)) {
            setCompareB(if (it == 0) null else saved[it - 1])
        })
        grid.add(cell("실전투", MUTED))
        grid.add(cell("")); grid.add(breakBox(pinTarget) { pinTarget = it })
        grid.add(if (b != null) breakBox(bT!!) { curTarget = it } else cell("")); grid.add(cell(""))
        for (i in 0..3) {
            grid.add(cell(STAT_NAMES[i], SUB, left = true))
            grid.add(cell("${c?.a?.get(i) ?: Calculator.statsAtBreaks(a.stats, a.base, a.breaks, pinTarget)[i]}"))
            grid.add(cell(c?.b?.get(i)?.toString() ?: "-"))
            val p = c?.pct?.get(i)
            grid.add(cell(p?.let { combatPct(it, c.approx) } ?: "-", if (p == null) MUTED else if (p >= 0) GREEN else Color(0xF2, 0x8B, 0x82), bold = true))
        }
        grid.add(cell("총능", SUB, left = true))
        grid.add(cell(fmt(c?.scoreA ?: Calculator.abilityScore(Calculator.statsAtBreaks(a.stats, a.base, a.breaks, pinTarget)), 1), MUTED))
        grid.add(cell(c?.let { fmt(it.scoreB, 1) } ?: "-", MUTED)); grid.add(cell(""))
        compareBox.add(grid)
        compareBox.add(wrapNote(when {
            b == null -> "다른 소환수를 계산하거나, 오른쪽 이름 칸에서 저장한 소환수를 고르면 기준과 비교됩니다."
            c?.reason != null -> c.reason!!
            else -> "실전투 = 성장증폭 ${ampText(growthAmp())}%와 총능 보너스를 넣은 실제 전투 능력치가 기준보다 몇 % 높은지. 강을 바꾸면 그 강의 예상 능력치로 비교합니다."
        }))
        compareBox.add(row(*listOfNotNull(
            b?.let { bb -> button("A↔B 바꾸기") {
                val oldA = pinned!!; val oldAT = pinTarget
                pinned = bb; pinTarget = bT!!
                if (compareB != null) compareB = oldA else loadPreset(oldA)
                curTarget = oldAT; renderCompare(); revalidate(); repaint()
            } },
            button("기준 해제") { pinned = null; compareB = null; renderCompare(); revalidate(); repaint() },
        ).toTypedArray()))
        compareBox.add(Box.createVerticalStrut(6))
    }

    private fun recompute() {
        if (filling) return
        Calculator.showBeyondGame = prefs.getBoolean("showSss", false)
        val db = pets.db
        val pet = db.find(nameF.text)
        updateSuggestions(pet, db)
        val level = levelF.text.trim().toIntOrNull()
        val breaks = breakF.text.trim().toIntOrNull() ?: 0
        val stats = statF.map { num(it) }
        val deltas = deltaF.map { num(it) }
        val atMax = level == Calculator.MAX_LEVEL
        val base = List(4) { i ->
            val s = stats[i] ?: return@List null
            deltas[i]?.let { s - it } ?: pet?.maxS?.get(i)?.takeIf { atMax }
        }
        val warns = ArrayList<String>()
        lastParsed?.let { lp ->
            if (lp.pet != null && lp.nameScore < 0.99 && pet == lp.pet) warns += "이름을 '${lp.nameRaw}'(으)로 읽어 ${lp.pet!!.name}(으)로 맞췄습니다."
        }
        pet?.let { p -> PetDb.oddValues(p).takeIf { it.isNotEmpty() }?.let {
            warns += "도감 값 확인 필요: ${it.joinToString(", ")}"
        } }
        if (pet != null && atMax) for (i in 0..3) {
            val b = base[i] ?: continue
            if (b != pet.maxS[i]) warns += "${STAT_NAMES[i]}: 화면 기준 도감값 $b ≠ 시트 도감 ${pet.maxS[i]}"
        }
        val screenGame = num(gameF)
        enhance.removeAll()
        if (base.all { it != null && it > 0 }) {
            val cur = IntArray(4) { stats[it]!! }
            val b = IntArray(4) { base[it]!! }
            val t = Calculator.total(cur, b)
            val range = screenGame?.let { Calculator.realRangeForGame(cur, b, it) }
            val real = range?.let { t.realTotal.coerceIn(it.min, it.max) } ?: t.realTotal
            val realGrade = range?.let {
                val lo = Calculator.totalGrade(it.min); val hi = Calculator.totalGrade(it.max)
                if (lo == hi) lo else "$lo~$hi"
            } ?: t.realTotalGrade
            val game = screenGame?.toDouble() ?: t.gameTotal
            totalLbl.text = "${fmt(real, 2)}%"
            gradeLbl.text = realGrade; gradeLbl.foreground = gradeColor(realGrade)
            subLbl.text = html("인게임 ${fmt(game, 0)}% ${Calculator.totalGrade(game)}" +
                (if (range != null && fmt(range.min, 2) != fmt(range.max, 2)) " · 실제 ${fmt(range.min, 2)}~${fmt(range.max, 2)}%" else "") +
                "<br>" + (0..3).joinToString("&nbsp;&nbsp;") { "${STAT_NAMES[it]} ${fmt(t.percentile[it], 1)}" })
            if (screenGame != null && range == null) warns += "화면의 인게임 ${screenGame}%와 능력치·도감 대비 값이 맞지 않습니다"
            scoreLbl.text = "총 능력치 점수 ${fmt(Calculator.abilityScore(cur), 1)}  ·  기본(도감) ${fmt(Calculator.abilityScore(b), 1)}"
            val combat = (if (showCombat()) Calculator.combat(cur, b, growthAmp()) else null)
            combatLbl.isVisible = showCombat()
            if (combat != null) {
                combatLbl.text = "종 대비 전투 능력치 ${combatPct(combat.avg, combat.approx)}  (성장증폭 ${ampText(growthAmp())}%)  (?)"
                combatLbl.foreground = if (combat.avg >= 0) GREEN else Color(0xF2, 0x8B, 0x82)
            } else {
                // 숨기지 않고 왜 안 나오는지 알려 준다 (총능이 계산 범위 밖 등)
                combatLbl.text = "종 대비 전투 능력치: " + (Calculator.combatUnavailableReason(cur, b) ?: "계산할 수 없음")
                combatLbl.foreground = MUTED
            }
            renderEnhance(cur, b, breaks, level, real, realGrade)
        } else {
            combatLbl.isVisible = false
            totalLbl.text = "-"; gradeLbl.text = ""
            subLbl.text = html("능력치와 (도감 대비)를 채워 주세요.<br>150레벨이고 도감에 있는 소환수면 도감 대비는 비워도 됩니다")
            scoreLbl.text = ""
        }
        results.removeAll()
        when {
            pet == null -> results.add(note("성장률 등급·총성 상세는 도감(${db.pets.size}종)에 있는 소환수만 계산됩니다."))
            level == null || level < 2 -> results.add(note("상세 계산에는 레벨(2 이상)이 필요합니다."))
            stats.any { it == null } -> {}
            else -> {
                val b = if (base.all { it != null && it > 0 }) IntArray(4) { base[it]!! } else pet.maxS
                val cur = IntArray(4) { stats[it]!! }
                renderResult(pet, Calculator.compute(Calculator.Input(pet, level, cur, breaks, b)), screenGame, (if (showCombat()) Calculator.combat(cur, b, growthAmp()) else null))
            }
        }
        // 경고에는 화면에서 읽은 글자(OCR)와 도감 이름이 들어가므로 이스케이프
        // 긴 경고는 창 너비 안에서 줄바꿈 (안 그러면 창이 옆으로 늘어나 입력칸이 잘린다)
        warnLbl.text = if (warns.isEmpty()) "" else "<html><div style='width:290px'>" + warns.joinToString("<br>") { esc(it) } + "</div></html>"
        renderCompare()
        revalidate(); repaint()
    }

    private fun renderResult(pet: Pet, r: Calculator.Result, screenGame: Int?, combat: Calculator.Combat?) {
        results.add(section("${pet.name} · ${pet.element}/${pet.type}"))
        results.add(table(listOf(listOf("", "성장률", "등급", "분위") + (if (combat != null) listOf("전투") else emptyList())) +
            (0..3).map { listOf(STAT_NAMES[it], fmt(r.growth[it], 2), r.statGrades[it], fmt(r.percentile[it], 1)) +
                (combat?.let { c -> listOf(combatPct(c.pct[it], c.approx)) } ?: emptyList()) }))
        results.add(section("총성 ${fmt(r.myTotal, 1)}"))
        results.add(table(listOf(
            listOf("", "등급/%", "총성", "진행"),
            listOf("현 등급", r.curGrade, r.curGradeTotal?.let { fmt(it, 1) } ?: "-", ""),
            listOf("다음성장", "${screenGame?.plus(1) ?: fmt(r.nextGrowthPct, 0)}%", r.nextGrowthTotal?.let { fmt(it, 1) } ?: "-",
                r.toNextGrowth?.let { "${fmt(it * 100, 1)}%" } ?: "-"),
            listOf("다음등급", r.nextGrade, r.nextGradeTotal?.let { fmt(it, 1) } ?: "-", r.toNextGrade?.let { "${fmt(it * 100, 1)}%" } ?: "-"),
        )))
    }

    /** 강화별 표: 강화 1번당 1% (게임 강화 화면 기준), 등급 = 능력치 ÷ 도감값 */
    private fun renderEnhance(cur: IntArray, base: IntArray, breaks: Int, level: Int?, curReal: Double, curGrade: String) {
        if (level != Calculator.MAX_LEVEL) {
            enhance.add(section("강화별")); enhance.add(note("강화 예상은 ${Calculator.MAX_LEVEL}레벨일 때만 표시됩니다.")); return
        }
        val worst = worstOn
        enhance.add(section("강화별 (최대 +${MAX_BREAKS}강, " + (if (worst) "최저치)" else "예상)")))
        // 최저치로 보기: 게임 속 소수점을 모두 낮게 잡은 경우 (실제 강화 결과는 이보다 낮지 않음)
        if (breaks < MAX_BREAKS) enhance.add(row(JCheckBox("최저치로 보기", worst).apply {
            isOpaque = false; font = UI_FONT.deriveFont(12f); foreground = if (worst) Color(0x4FC3F7) else SUB
            addActionListener { worstOn = isSelected; recompute() }
        }))
        val rows = mutableListOf(listOf("강", "공", "방", "순", "체", if (showCombat()) "총점\n전투" else "총점", "성장률"))
        val rowBreak = mutableListOf<Int?>() // 표의 데이터 줄 → 몇 강 줄인지 (펼친 전투 줄은 null)
        val styles = mutableListOf<RowStyle>()
        // 현재 강보다 아래(0강~): 강마다 도감값의 1% 를 빼면 된다 (위로 갈 때와 같은 정확도). 누르면 펼치고 접는다
        val lower = breaks > 0 && lowerOn
        if (breaks > 0) enhance.add(row(label(if (lower) "▼ 이전 강화 접기" else "▶ 이전 강화 보기", 12f, Color(0x4FC3F7)).apply {
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            border = BorderFactory.createEmptyBorder(2, 0, 4, 0)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) { lowerOn = !lower; recompute() }
            })
        }))
        for (k in (if (lower) 0 else breaks)..maxOf(breaks, MAX_BREAKS)) {
            // 최저치는 위로 올릴 때만 (아래 강에 쓰면 반대로 '가장 높았을 경우'가 된다)
            val s = Calculator.statsAtBreaks(cur, base, breaks, k, worst && k > breaks)
            val t = Calculator.total(s, base)
            val g = Calculator.statGrades(s, base)
            val c = (if (showCombat()) Calculator.combat(s, base, growthAmp()) else null)
            rows += listOf(if (k == breaks) "${k}강\n현재" else "${k}강") + (0..3).map { "${s[it]}\n${g[it]}" } +
                (fmt(Calculator.abilityScore(s), 1) + (c?.let { "\n" + combatPct(it.avg, it.approx) } ?: "")) +
                (if (k == breaks) "${fmt(curReal, 2)}%\n$curGrade" else "${fmt(t.realTotal, 2)}%\n${t.realTotalGrade}")
            rowBreak += k
            // 현재 강은 굵게, 이전 강화는 연하게 (이후 강화는 그대로)
            val style = when { k == breaks -> RowStyle.BOLD; k < breaks -> RowStyle.DIM; else -> RowStyle.NORMAL }
            styles += style
            // 누른 줄 아래에 그 강의 능력치별 전투 (위 공·방·순·체 칸에 맞춰 숫자만)
            if (c != null && expandedBreak == k) {
                rows += listOf("전투") + (0..3).map { combatPct(c.pct[it], c.approx) } + listOf("", "")
                rowBreak += null
                styles += if (style == RowStyle.DIM) RowStyle.DIM else RowStyle.NORMAL
            }
        }
        enhance.add(table(rows, small = true, styles = styles) { r ->
            val k = rowBreak.getOrNull(r) ?: return@table
            if (showCombat()) { expandedBreak = if (expandedBreak == k) null else k; recompute() }
        })
        enhance.add(note(when {
            breaks >= MAX_BREAKS -> "이미 최대 강화(+${MAX_BREAKS}강)입니다."
            worst -> "최저치: 소수점을 모두 낮게 잡은 값 (실제는 이보다 낮지 않음)"
            else -> "강화 수치는 게임과 1 정도 차이 날 수 있어요."
        }))
        // 한 줄이 길면 창이 옆으로 밀리므로 짧게 나눈다
        if (showCombat()) {
            enhance.add(note("전투 = 같은 종 도감(S 100%)보다 전투 능력치가 몇 % 높은지"))
            enhance.add(note("줄을 누르면 그 강의 능력치별 전투가 아래에 나옵니다"))
        }
    }

    /** 설정에서 끄면 종 대비 전투 능력치를 아예 보여 주지 않는다 */
    private fun showCombat() = prefs.getBoolean("showCombat", true)

    /** 종 대비 전투 능력치 % 표시. 곡선을 확인한 범위 밖이면 앞에 ≈ */
    private fun combatPct(v: Double, approx: Boolean): String {
        val n = Math.round(v)
        return (if (approx) "≈" else "") + (if (n > 0) "+" else "") + "$n%"
    }

    /** 설정에서 넣은 내 성장증폭 (안 넣었으면 기본값) */
    private fun growthAmp() = prefs.getDouble("growthAmp", Calculator.DEFAULT_GROWTH_AMP)

    private fun ampText(a: Double): String {
        val p = Math.round(a * 1000) / 10.0
        return if (p == Math.floor(p)) p.toLong().toString() else p.toString()
    }

    /** 설정 > 표시 설정: SS++ 를 넘는 능력치를 계산기 전용 등급 SSS(무지개)로 (기본 꺼짐) */
    private fun showDisplaySettings() {
        val box = JCheckBox("SS++ 넘는 등급을 SSS(무지개)로 표시", prefs.getBoolean("showSss", false)).apply { putClientProperty("html.disable", true) }
        val panel = JPanel(java.awt.BorderLayout(0, 8)).apply {
            add(box, java.awt.BorderLayout.NORTH)
            add(JLabel("<html><div style='width:300px'>" + esc(Calculator.BEYOND_HELP).replace("\n", "<br>") + "</div></html>"), java.awt.BorderLayout.CENTER)
        }
        if (JOptionPane.showConfirmDialog(this, panel, "표시 설정", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return
        prefs.putBoolean("showSss", box.isSelected)
        recompute()
    }

    /** 설정 > 내 성장증폭: 종 대비 전투 능력치를 내 캐릭터 기준으로 */
    private fun showGrowthAmpDialog() {
        val msg = "내 성장증폭 (%)\n캐릭터 능력치의 성장증폭을 넣으면 '종 대비 전투 능력치'가 내 캐릭터 기준으로 계산됩니다."
        val input = JOptionPane.showInputDialog(this, msg, "성장증폭 설정", JOptionPane.PLAIN_MESSAGE, null, null, ampText(growthAmp())) as String? ?: return
        val pct = input.trim().removeSuffix("%").trim().toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
            ?: return JOptionPane.showMessageDialog(this, "0 ~ 100 사이 숫자를 넣어 주세요.", "성장증폭 설정", JOptionPane.WARNING_MESSAGE)
        prefs.putDouble("growthAmp", pct / 100)
        setStatus("성장증폭 ${ampText(growthAmp())}% 기준으로 계산합니다")
        recompute()
    }

    private fun updateSuggestions(exact: Pet?, db: PetDb) {
        suggestBox.removeAll()
        val q = nameF.text
        val list = if (exact != null || q.isBlank()) emptyList() else db.suggest(q, limit = 4)
        list.forEach { p -> suggestBox.add(button(p.name) { nameF.text = p.name }) }
        suggestBox.isVisible = list.isNotEmpty()
        suggestHint.isVisible = list.isNotEmpty()
    }

    // ------------------------------------------------------------------ 메뉴

    private fun menu() = JMenuBar().apply {
        add(JMenu("도감").apply {
            add(JMenuItem("도감 보기 / 수정 (추가, JSON 저장/올리기)").apply { addActionListener { openPetEditor(fromScan = false) } })
            add(JMenuItem("성장률로 추정…").apply { addActionListener { showEstimateDialog() } })
            add(JMenuItem("내 도감 폴더 열기").apply { addActionListener { pets.dir.mkdirs(); Desktop.getDesktop().open(pets.dir) } })
        })
        add(JMenu("설정").apply {
            add(JMenuItem("계산 단축키 바꾸기…").apply { addActionListener { showHotkeyDialog() } })
            // 성장증폭 메뉴는 종 대비 전투 능력치를 켤 때만 보인다
            val ampItem = JMenuItem("성장증폭 설정…").apply { addActionListener { showGrowthAmpDialog() }; isVisible = showCombat() }
            add(javax.swing.JCheckBoxMenuItem("종 대비 전투 능력치 보기", showCombat()).apply {
                addActionListener {
                    prefs.putBoolean("showCombat", isSelected)
                    ampItem.isVisible = isSelected
                    recompute()
                }
            })
            add(ampItem)
            addSeparator()
            add(JMenuItem("표시 설정…").apply { addActionListener { showDisplaySettings() } })
        })
        add(JMenu("도움말").apply {
            add(javax.swing.JCheckBoxMenuItem("문제 확인용: 마지막 계산 화면 저장", prefs.getBoolean("saveLastScan", false)).apply {
                addActionListener {
                    prefs.putBoolean("saveLastScan", isSelected)
                    if (!isSelected) clearLastScan()
                    setStatus(if (isSelected) "다음 계산부터 마지막 화면을 저장합니다 (PC 안에만)" else "저장한 화면을 지웠습니다")
                }
            })
            add(JMenuItem("마지막 계산 화면 폴더 열기").apply {
                addActionListener { java.io.File(pets.dir, "마지막계산").apply { mkdirs() }.let { Desktop.getDesktop().open(it) } }
            })
            add(JMenuItem("정보").apply {
                addActionListener {
                    JOptionPane.showMessageDialog(this@MainWindow,
                        "테이밍 계산 (PC) v$VERSION\n\n" +
                            "· 고른 창(앱플레이어)의 화면만 캡처해서 글자를 읽습니다.\n" +
                            "· 게임/앱플레이어에 입력을 보내거나 메모리를 읽지 않습니다.\n" +
                            "· 글자 인식은 PC 안에서만 하고 인터넷으로 보내지 않습니다.\n" +
                            "· 계산 단축키: $hotkeyKey (설정 메뉴에서 바꿀 수 있음)\n\n" +
                            "도감: 기본 ${pets.db.pets.size - pets.userCount}종 + 내 도감 ${pets.userCount}종",
                        "정보", JOptionPane.INFORMATION_MESSAGE)
                }
            })
        })
    }

    // ------------------------------------------------------------------ 단축키

    /** 단축키 등록. 다른 프로그램이 이미 쓰는 조합이면 버튼만 쓰게 안내 */
    private fun applyHotkey(k: GlobalHotkey.Key): Boolean {
        val ok = hotkey.set(k) { SwingUtilities.invokeLater { scan() } }
        hotkeyKey = k
        prefs.putInt("hotkeyMods", k.mods); prefs.putInt("hotkeyVk", k.vk)
        scanBtn.text = if (k.off || !ok) "계산" else "계산 ($k)"
        if (!ok) setStatus("$k 는 다른 프로그램이 쓰고 있어요 (설정 > 단축키에서 바꿔 주세요)")
        return ok
    }

    /** 원하는 키 조합을 직접 눌러서 정한다 */
    private fun showHotkeyDialog() {
        val dlg = javax.swing.JDialog(this, "계산 단축키", true)
        var picked: GlobalHotkey.Key = hotkeyKey
        val shown = label(hotkeyKey.toString(), 20f, Color.WHITE).apply { font = font.deriveFont(Font.BOLD) }
        val msg = label(" ", 11f, WARN, allowHtml = true)
        // 설정하는 동안은 지금 단축키를 풀어 둔다 (안 그러면 그 키를 눌러도 여기로 안 온다)
        hotkey.stop()
        val catcher = JTextField("여기를 누르고 원하는 키 조합을 누르세요").apply {
            isEditable = false; font = UI_FONT.deriveFont(12f); foreground = MUTED; background = FIELD
            border = BorderFactory.createEmptyBorder(8, 8, 8, 8); focusTraversalKeysEnabled = false
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    e.consume()
                    val (k, err) = GlobalHotkey.fromKeyEvent(e)
                    if (k != null) { picked = k; shown.text = k.toString(); msg.text = " " } else if (err != null) msg.text = html(esc(err))
                }
                override fun keyReleased(e: java.awt.event.KeyEvent) = e.consume()
            })
        }
        var closed = false
        fun done(k: GlobalHotkey.Key?) {
            if (closed) return
            closed = true
            dlg.dispose()
            if (k == null) { applyHotkey(hotkeyKey); return }
            if (!applyHotkey(k)) JOptionPane.showMessageDialog(this, "$k 는 다른 프로그램이 이미 쓰고 있어서 등록하지 못했습니다.\n다른 조합을 골라 주세요.",
                "단축키", JOptionPane.WARNING_MESSAGE)
            else setStatus("단축키: $k")
        }
        dlg.contentPane = vbox().apply {
            isOpaque = true; background = BG
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            add(row(label("<html>F1~F24 는 그대로 쓸 수 있고, 문자·숫자·숫자패드는<br>Ctrl / Alt / Shift 와 같이 눌러야 합니다.</html>", 11f, SUB, allowHtml = true)))
            add(Box.createVerticalStrut(8)); add(catcher.apply { alignmentX = Component.LEFT_ALIGNMENT })
            add(Box.createVerticalStrut(8)); add(row(label("선택한 단축키: ", 12f, MUTED), shown)); add(row(msg))
            add(Box.createVerticalStrut(8))
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
                add(button("기본값 (F9)") { picked = GlobalHotkey.DEFAULT; shown.text = picked.toString() })
                add(button("사용 안 함") { picked = GlobalHotkey.Key(0, 0); shown.text = picked.toString() })
                add(button("저장") { done(picked) })
                add(button("취소") { done(null) })
            })
        }
        dlg.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) = done(null)
        })
        dlg.pack(); dlg.setLocationRelativeTo(this)
        SwingUtilities.invokeLater { catcher.requestFocusInWindow() }
        dlg.isVisible = true
    }

    /** 도감 > 성장률로 추정: 도감 성장률 4개 → 초기치·만렙S·총능 (참고용, 도감에 저장하지 않음) */
    private fun showEstimateDialog() {
        val dlg = javax.swing.JDialog(this, "성장률로 추정", false)
        val inputs = List(4) { field().apply { columns = 5 } }
        val table = JPanel(GridLayout(5, 3, 18, 4)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }
        val score = label(" ", 13f, Color.WHITE)
        fun update() {
            val e = Calculator.estimate(inputs.map { it.text.trim().toDoubleOrNull() ?: 0.0 }.toDoubleArray())
            table.removeAll()
            if (e == null) score.text = "성장률 4개를 모두 넣으면 결과가 나옵니다."
            else {
                Calculator.estimateRows(e).forEachIndexed { r, cells ->
                    cells.forEachIndexed { c, t ->
                        table.add(label(t, 14f, if (r == 0) MUTED else Color.WHITE).apply {
                            horizontalAlignment = if (c == 0) JLabel.LEFT else JLabel.RIGHT
                        })
                    }
                }
                score.text = Calculator.estimateScoreText(e)
            }
            table.revalidate(); table.repaint(); dlg.pack()
        }
        inputs.forEach { f ->
            f.document.addDocumentListener(object : DocumentListener {
                override fun insertUpdate(e: DocumentEvent) = update()
                override fun removeUpdate(e: DocumentEvent) = update()
                override fun changedUpdate(e: DocumentEvent) = update()
            })
        }
        dlg.contentPane = vbox().apply {
            isOpaque = true; background = BG
            border = BorderFactory.createEmptyBorder(12, 14, 12, 14)
            add(row(label("도감 성장률 (S 100%)", 12f, SUB)))
            add(grid(listOf("공", "방", "순", "체").zip(inputs), listOf(1.0, 1.0, 1.0, 1.0)))
            add(Box.createVerticalStrut(10)); add(table)
            add(Box.createVerticalStrut(8)); add(row(score))
            add(Box.createVerticalStrut(8))
            add(row(label("<html><div style='width:270px'>" + esc(Calculator.ESTIMATE_HELP).replace("\n", "<br>") + "</div></html>", 11f, MUTED, allowHtml = true)))
        }
        update()
        dlg.setLocationRelativeTo(this)
        dlg.isVisible = true
    }

    private var editor: PetEditorDialog? = null

    private fun openPetEditor(fromScan: Boolean) {
        val dlg = editor?.takeIf { it.isDisplayable } ?: PetEditorDialog(this, pets, { growthAmp() }) { recompute() }.also { editor = it }
        if (!fromScan) return dlg.open(null, null)
        val name = nameF.text.trim().ifEmpty { null }
        // 게임 도감 화면이면 성장 평균(= S성장률)으로 초기치·만렙S 까지 채운다. 아니면 150레벨일 때 만렙S(현재 − 도감 대비)만
        val lp = lastParsed
        val dex = PetDb.dexFromScreen(levelF.text.trim().toIntOrNull(), statF.map { num(it) }, deltaF.map { num(it) },
            lp?.growthAvgs?.toList() ?: List(4) { null })
        dlg.open(name, PetDraft(dex.maxS, dex.init, dex.growth, lp?.grade, lp?.element, lp?.type,
            dex.notes.takeIf { it.isNotEmpty() }?.joinToString(", ")))
    }

    // ------------------------------------------------------------------ 화면 도구

    private fun num(f: JTextField) = f.text.replace(",", "").replace("+", "").trim().toIntOrNull()

    /**
     * Swing 은 "<html>" 로 시작하는 글자를 HTML 로 그리고, HTML 의 <img src=http://…> 는 인터넷에서 그림을 받아 온다.
     * 그래서 기본은 HTML 을 끄고, 일부러 HTML 을 쓰는 곳만 [allowHtml] 로 켠다 (그때 바깥 글자는 [esc] 로 넣는다).
     */
    private fun label(t: String, size: Float, color: Color, allowHtml: Boolean = false) = JLabel().apply {
        putClientProperty("html.disable", !allowHtml)
        text = t; font = UI_FONT.deriveFont(size); foreground = color
    }

    private fun note(t: String) = row(label(html(esc(t)), 11f, MUTED, allowHtml = true))

    /** 긴 안내는 창 너비 안에서 줄바꿈 (안 그러면 창이 옆으로 늘어난다) */
    private fun wrapNote(t: String) = row(label("<html><div style='width:290px'>" + esc(t) + "</div></html>", 11f, MUTED, allowHtml = true))

    private fun section(t: String) = row(label(t, 13f, Color.WHITE).apply {
        font = font.deriveFont(Font.BOLD); border = BorderFactory.createEmptyBorder(10, 0, 4, 0)
    })

    private fun button(t: String, onClick: (() -> Unit)? = null) = JButton().apply {
        putClientProperty("html.disable", true) // 추천 버튼에는 도감 이름이 들어간다
        text = t
        font = UI_FONT.deriveFont(12f); foreground = Color.WHITE; background = CHIP
        isFocusPainted = false; border = BorderFactory.createEmptyBorder(6, 10, 6, 10)
        isFocusable = false // 키보드 입력으로 눌리지 않게 (마우스로만)
        onClick?.let { f -> addActionListener { f() } }
    }

    private fun field() = JTextField().apply {
        font = UI_FONT.deriveFont(14f); foreground = Color.WHITE; background = FIELD; caretColor = Color.WHITE
        border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, MUTED), BorderFactory.createEmptyBorder(3, 4, 3, 4))
    }

    /** BoxLayout 안에서 세로로 늘어나지 않고 지금 내용 높이만큼만 차지하는 패널 (글이 바뀌면 같이 바뀜) */
    private class FitPanel(lm: java.awt.LayoutManager) : JPanel(lm) {
        override fun getMaximumSize() = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

    private fun vbox() = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT }

    private fun row(vararg c: JComponent) = FitPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        c.forEachIndexed { i, x -> if (i > 0) add(Box.createHorizontalStrut(10)); add(x) }
    }

    private fun grid(items: List<Pair<String, JTextField>>, weights: List<Double>) = FitPanel(java.awt.GridBagLayout()).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        items.forEachIndexed { i, (lab, f) ->
            add(vbox().apply { add(row(label(lab, 10f, MUTED))); add(f) }, java.awt.GridBagConstraints().apply {
                gridx = i; weightx = weights[i]; fill = java.awt.GridBagConstraints.HORIZONTAL; insets = java.awt.Insets(4, 0, 2, 6)
            })
        }
    }

    /** 표: 셀 안의 "\n" 뒤는 등급으로 보고 색을 입힌다 */
    /** [onRowClick] 가 있으면 데이터 줄(머리줄 제외, 0부터)을 누를 때 불린다 */
    private enum class RowStyle { NORMAL, BOLD, DIM }

    /** [styles]: 데이터 줄마다 굵게(현재 강) / 연하게(이전 강화) */
    private fun table(rows: List<List<String>>, small: Boolean = false, styles: List<RowStyle> = emptyList(), onRowClick: ((Int) -> Unit)? = null) =
        FitPanel(GridLayout(rows.size, rows[0].size, 2, 2)).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        rows.forEachIndexed { r, cells ->
            val style = if (r == 0) RowStyle.NORMAL else styles.getOrNull(r - 1) ?: RowStyle.NORMAL
            // 연하게: 배경색 쪽으로 섞는다
            fun tone(col: Color) = if (style != RowStyle.DIM) col else Color(
                (col.red * 0.45 + BG.red * 0.55).toInt(), (col.green * 0.45 + BG.green * 0.55).toInt(), (col.blue * 0.45 + BG.blue * 0.55).toInt())
            cells.forEachIndexed { c, v ->
                val header = r == 0
                val parts = v.split("\n")
                // SS++ 를 넘는 계산기 전용 등급은 무지개로 직접 그린다 (HTML 로는 그라데이션을 못 그림)
                if (!header && Calculator.isBeyondGame(parts.last())) {
                    add(RainbowCell(if (parts.size == 2) parts[0] else null, parts.last(),
                        UI_FONT.deriveFont(if (style == RowStyle.BOLD) Font.BOLD else Font.PLAIN, if (small) 11f else 13f),
                        tone(Color.WHITE), dim = style == RowStyle.DIM).apply {
                        if (onRowClick != null) addMouseListener(object : java.awt.event.MouseAdapter() {
                            override fun mouseClicked(e: java.awt.event.MouseEvent) = onRowClick(r - 1)
                        })
                    })
                    return@forEachIndexed
                }
                val text = if (parts.size == 2) html("${esc(parts[0])}<br><font color='${hex(tone(gradeColor(parts[1])))}'>${esc(parts[1])}</font>")
                    else v
                add(label(text, if (small) 11f else 13f, tone(when {
                    header -> MUTED; c == 0 -> if (style == RowStyle.BOLD) Color.WHITE else SUB; parts.size == 1 -> gradeColor(v); else -> Color.WHITE
                }), allowHtml = parts.size == 2).apply {
                    if (style == RowStyle.BOLD) font = font.deriveFont(Font.BOLD)
                    horizontalAlignment = if (c == 0) JLabel.LEFT else JLabel.CENTER
                    if (onRowClick != null && !header) {
                        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                        addMouseListener(object : java.awt.event.MouseAdapter() {
                            override fun mouseClicked(e: java.awt.event.MouseEvent) = onRowClick(r - 1)
                        })
                    }
                })
            }
        }
    }

    private fun JTextField.onChange(f: () -> Unit) = document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = f()
        override fun removeUpdate(e: DocumentEvent) = f()
        override fun changedUpdate(e: DocumentEvent) = f()
    })

    private fun gradeColor(c: String): Color {
        val g = c.substringAfterLast(' ')
        return when {
            Calculator.isBeyondGame(g) -> Color.WHITE // 실제로는 무지개 (RainbowCell / RainbowLabel)
            g.startsWith("SS") -> Color(0xF2B84B)
            g.startsWith("S") -> Color(0xFF8A7A)
            g.startsWith("A") -> Color(0x8FD48A)
            g.startsWith("B") || g == "C" || g == "D" -> MUTED
            else -> Color.WHITE
        }
    }

    private fun hex(c: Color) = String.format("#%02X%02X%02X", c.red, c.green, c.blue)
    private fun html(t: String) = if (t.isEmpty()) "" else "<html>$t</html>"
    private fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun fmt(v: Double, digits: Int) = String.format(Locale.US, "%.${digits}f", v)

    companion object {
        const val TITLE = "테이밍 계산 (PC)"
        /** 설치 버전 (build.gradle.kts 의 appVersion) */
        val VERSION: String = MainWindow::class.java.getResource("/tmcalc-version.txt")?.readText()?.trim() ?: "개발"
        private val STAT_NAMES = listOf("공", "방", "순", "체")
        private const val MAX_BREAKS = 5
        val UI_FONT = Font("Malgun Gothic", Font.PLAIN, 12)
        val BG = Color(0x2B2D31); val FIELD = Color(0x1E1F22); val CHIP = Color(0x3A3D44)
        val MUTED = Color(0x9A9DA3); val SUB = Color(0xC9CBD0); val WARN = Color(0xFFD479); val GREEN = Color(0x7FD18B)
    }
}

/** 무지개 색 (빨강 → 보라). SS++ 를 넘는 계산기 전용 등급(SSS…)에 쓴다 */
private val RAINBOW = arrayOf(Color(0xFF6A6A), Color(0xFFB86A), Color(0xF2E06A), Color(0x7FD48A), Color(0x5FE3F0), Color(0x6A9CFF), Color(0xC27FFF))
private val RAINBOW_STOPS = floatArrayOf(0f, 1f / 6, 2f / 6, 3f / 6, 4f / 6, 5f / 6, 1f)

private fun java.awt.Graphics2D.drawRainbow(text: String, x: Int, y: Int, width: Int) {
    paint = java.awt.LinearGradientPaint(x.toFloat(), 0f, (x + maxOf(width, 2)).toFloat(), 0f, RAINBOW_STOPS, RAINBOW)
    drawString(text, x, y)
}

/** 총 성장률 옆 등급: SSS 면 무지개, 아니면 보통 글자 */
class RainbowLabel : JLabel() {
    init { putClientProperty("html.disable", true) }
    override fun paintComponent(g: java.awt.Graphics) {
        val t = text.orEmpty()
        if (!Calculator.isBeyondGame(t)) return super.paintComponent(g)
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.font = font
        val fm = g2.fontMetrics
        g2.drawRainbow(t, insets.left, (height - fm.height) / 2 + fm.ascent, fm.stringWidth(t))
        g2.dispose()
    }
}

/** 표 칸: 위 줄 숫자(있으면) + 아래 줄 무지개 등급 */
class RainbowCell(private val value: String?, private val grade: String, private val f: Font, private val valueColor: Color,
                  private val dim: Boolean) : javax.swing.JComponent() {
    override fun getPreferredSize(): Dimension {
        val fm = getFontMetrics(f)
        val w = maxOf(fm.stringWidth(grade), value?.let { fm.stringWidth(it) } ?: 0) + 4
        return Dimension(w, fm.height * (if (value != null) 2 else 1))
    }

    override fun paintComponent(g: java.awt.Graphics) {
        val g2 = g.create() as java.awt.Graphics2D
        g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        if (dim) g2.composite = java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.45f)
        g2.font = f
        val fm = g2.fontMetrics
        val lines = if (value != null) 2 else 1
        var y = (height - fm.height * lines) / 2 + fm.ascent
        if (value != null) {
            g2.color = valueColor
            g2.drawString(value, (width - fm.stringWidth(value)) / 2, y)
            y += fm.height
        }
        val w = fm.stringWidth(grade)
        g2.drawRainbow(grade, (width - w) / 2, y, w)
        g2.dispose()
    }
}
