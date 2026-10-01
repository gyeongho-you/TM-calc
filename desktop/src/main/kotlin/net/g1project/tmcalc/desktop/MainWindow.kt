package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.Calculator
import net.g1project.tmcalc.OcrParser
import net.g1project.tmcalc.ParsedScreen
import net.g1project.tmcalc.Pet
import net.g1project.tmcalc.PetDb
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
    private val gradeLbl = label("", 18f, Color.WHITE).apply { font = font.deriveFont(Font.BOLD) }
    private val subLbl = label("앱플레이어에서 소환수 정보 화면을 띄우고 [계산] 또는 단축키", 11f, SUB, allowHtml = true)
    private val scoreLbl = label("", 12f, Color.WHITE)
    /** 같은 종 도감(S 100%) 개체보다 전투 능력치가 몇 % 높은지 (총 능력치 점수 보너스 포함) */
    private val combatLbl = label("", 15f, GREEN).apply { font = font.deriveFont(Font.BOLD); isVisible = false }
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
    private var lastParsed: ParsedScreen? = null

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
        nameF.text = p.pet?.name ?: p.nameRaw?.let { PetDb.key(it) } ?: ""
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
        filling = false
        recompute()
    }

    private fun recompute() {
        if (filling) return
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
            val combat = Calculator.combat(cur, b, growthAmp())
            combatLbl.isVisible = combat != null
            if (combat != null) {
                combatLbl.text = "종 대비 전투 능력치 ${combatPct(combat.avg, combat.approx)}  (성장증폭 ${ampText(growthAmp())}%)"
                combatLbl.foreground = if (combat.avg >= 0) GREEN else Color(0xF2, 0x8B, 0x82)
            }
            renderEnhance(cur, b, breaks, level, real, realGrade)
        } else {
            combatLbl.isVisible = false
            totalLbl.text = "-"; gradeLbl.text = ""
            subLbl.text = "능력치와 (도감 대비) 값을 확인해 주세요"
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
                renderResult(pet, Calculator.compute(Calculator.Input(pet, level, cur, breaks, b)), screenGame, Calculator.combat(cur, b, growthAmp()))
            }
        }
        // 경고에는 화면에서 읽은 글자(OCR)와 도감 이름이 들어가므로 이스케이프
        warnLbl.text = html(warns.joinToString("<br>") { esc(it) })
        revalidate(); repaint()
    }

    private fun renderResult(pet: Pet, r: Calculator.Result, screenGame: Int?, combat: Calculator.Combat?) {
        results.add(section("${pet.name} · ${pet.element}/${pet.type}"))
        results.add(table(listOf(listOf("", "성장률", "등급", "분위", "전투")) +
            (0..3).map { listOf(STAT_NAMES[it], fmt(r.growth[it], 2), r.statGrades[it], fmt(r.percentile[it], 1),
                combat?.let { c -> combatPct(c.pct[it], c.approx) } ?: "-") }))
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
        enhance.add(section("강화별 (최대 +${MAX_BREAKS}강, 예상)"))
        val rows = mutableListOf(listOf("강", "공", "방", "순", "체", "총점\n전투", "성장률"))
        for (k in breaks..maxOf(breaks, MAX_BREAKS)) {
            val s = Calculator.statsAtBreaks(cur, breaks, k)
            val t = Calculator.total(s, base)
            val g = Calculator.statGrades(s, base)
            val c = Calculator.combat(s, base, growthAmp())
            rows += listOf(if (k == breaks) "${k}강\n현재" else "${k}강") + (0..3).map { "${s[it]}\n${g[it]}" } +
                (fmt(Calculator.abilityScore(s), 1) + (c?.let { "\n" + combatPct(it.avg, it.approx) } ?: "")) +
                (if (k == breaks) "${fmt(curReal, 2)}%\n$curGrade" else "${fmt(t.realTotal, 2)}%\n${t.realTotalGrade}")
        }
        enhance.add(table(rows, small = true))
        enhance.add(note(if (breaks >= MAX_BREAKS) "이미 최대 강화(+${MAX_BREAKS}강)입니다." else "강화 수치는 게임과 1 정도 차이 날 수 있어요."))
        enhance.add(note("전투 = 종 대비 전투 능력치: 같은 종 도감(S 100%) 개체보다 전투 능력치가 몇 % 높은지. 총 능력치 점수가 높을수록 크게 오릅니다."))
    }

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

    /** 설정 > 내 성장증폭: 종 대비 전투 능력치를 내 캐릭터 기준으로 */
    private fun showGrowthAmpDialog() {
        val cur = if (prefs.get("growthAmp", null) != null) ampText(growthAmp()) else ""
        val msg = "캐릭터 능력치의 성장증폭(%)을 넣으면 '종 대비 전투 능력치'가 내 캐릭터 기준으로 계산됩니다.\n" +
            "비워 두면 ${ampText(Calculator.DEFAULT_GROWTH_AMP)}% 로 계산합니다."
        val input = JOptionPane.showInputDialog(this, msg, "내 성장증폭", JOptionPane.PLAIN_MESSAGE, null, null, cur) as String? ?: return
        val v = input.trim().removeSuffix("%").trim()
        if (v.isEmpty()) prefs.remove("growthAmp")
        else {
            val pct = v.toDoubleOrNull()?.takeIf { it in 0.0..100.0 }
                ?: return JOptionPane.showMessageDialog(this, "0 ~ 100 사이 숫자를 넣어 주세요.", "내 성장증폭", JOptionPane.WARNING_MESSAGE)
            prefs.putDouble("growthAmp", pct / 100)
        }
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
            add(JMenuItem("도감 관리 (추가/수정, JSON 저장/올리기)").apply { addActionListener { openPetEditor(fromScan = false) } })
            add(JMenuItem("내 도감 폴더 열기").apply { addActionListener { pets.dir.mkdirs(); Desktop.getDesktop().open(pets.dir) } })
        })
        add(JMenu("설정").apply {
            add(JMenuItem("계산 단축키 바꾸기…").apply { addActionListener { showHotkeyDialog() } })
            add(JMenuItem("내 성장증폭…").apply { addActionListener { showGrowthAmpDialog() } })
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
                        "테이밍 계산 (PC)\n\n" +
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

    private var editor: PetEditorDialog? = null

    private fun openPetEditor(fromScan: Boolean) {
        val dlg = editor?.takeIf { it.isDisplayable } ?: PetEditorDialog(this, pets) { recompute() }.also { editor = it }
        if (!fromScan) return dlg.open(null, null)
        val name = nameF.text.trim().ifEmpty { null }
        val maxS = if (levelF.text.trim().toIntOrNull() == Calculator.MAX_LEVEL) {
            val b = List(4) { i -> num(statF[i])?.let { s -> num(deltaF[i])?.let { s - it } } }
            if (b.all { it != null && it > 0 }) IntArray(4) { b[it]!! } else null
        } else null
        dlg.open(name, maxS)
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
    private fun table(rows: List<List<String>>, small: Boolean = false) = FitPanel(GridLayout(rows.size, rows[0].size, 2, 2)).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        rows.forEachIndexed { r, cells ->
            cells.forEachIndexed { c, v ->
                val header = r == 0
                val parts = v.split("\n")
                val text = if (parts.size == 2) html("${esc(parts[0])}<br><font color='${hex(gradeColor(parts[1]))}'>${esc(parts[1])}</font>")
                    else v
                add(label(text, if (small) 11f else 13f, when {
                    header -> MUTED; c == 0 -> SUB; parts.size == 1 -> gradeColor(v); else -> Color.WHITE
                }).apply { horizontalAlignment = if (c == 0) JLabel.LEFT else JLabel.CENTER })
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
        private val STAT_NAMES = listOf("공", "방", "순", "체")
        private const val MAX_BREAKS = 5
        val UI_FONT = Font("Malgun Gothic", Font.PLAIN, 12)
        val BG = Color(0x2B2D31); val FIELD = Color(0x1E1F22); val CHIP = Color(0x3A3D44)
        val MUTED = Color(0x9A9DA3); val SUB = Color(0xC9CBD0); val WARN = Color(0xFFD479); val GREEN = Color(0x7FD18B)
    }
}
