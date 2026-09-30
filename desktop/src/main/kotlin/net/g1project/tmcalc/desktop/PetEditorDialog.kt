package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.Calculator
import net.g1project.tmcalc.Pet
import net.g1project.tmcalc.PetDb
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.io.File
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 도감 관리 (폰 앱 PetEditorActivity 와 같은 기능): 목록·검색, 추가/수정, 기본값으로 되돌리기/삭제, JSON 저장/올리기.
 * 성장률(S)과 만렙S 중 하나만 적으면 나머지는 시트와 같은 식으로 채운다: 만렙S = ROUND(초기치 + S성장률 × 149).
 */
class PetEditorDialog(owner: JFrame, private val pets: DesktopPets, private val onChanged: () -> Unit) :
    JDialog(owner, "도감 관리", false) {

    private val search = field()
    private val count = label("", 11f, MainWindow.MUTED)
    private val model = DefaultListModel<Pet>()
    private val list = JList(model)

    init {
        list.background = MainWindow.FIELD
        list.cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(l: JList<*>, v: Any?, i: Int, sel: Boolean, focus: Boolean): Component {
                val p = v as Pet
                val tag = when { !pets.isMine(p.name) -> ""; pets.inBase(p.name) -> "  [수정함]"; else -> "  [추가함]" }
                val info = listOf(p.grade, p.element, p.type).filter { it.isNotBlank() }.joinToString("/")
                val c = super.getListCellRendererComponent(l, "<html><b>${esc(p.name)}</b>$tag<br>" +
                    "<font color='#9A9DA3'>${if (info.isEmpty()) "" else "$info · "}만렙S ${p.maxS.joinToString(" / ")}</font></html>", i, sel, focus)
                (c as JLabel).apply {
                    font = MainWindow.UI_FONT.deriveFont(13f)
                    foreground = if (tag.isEmpty()) MainWindow.SUB else Color(0xF2B84B)
                    background = if (sel) MainWindow.CHIP else MainWindow.FIELD
                    border = BorderFactory.createEmptyBorder(5, 8, 5, 8)
                }
                return c
            }
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) { if (e.clickCount == 2) list.selectedValue?.let { edit(it, null, null) } }
        })
        search.onChange { refresh() }

        contentPane = JPanel(BorderLayout(0, 8)).apply {
            background = MainWindow.BG
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            add(vbox().apply {
                add(row(label("<html><div style='width:350px'>기본 도감(엑셀 시트)에 없거나 값이 다른 소환수를 추가/수정하면 등급과 강화 예상이 정확해집니다. " +
                    "내 도감은 따로 저장되어 프로그램을 업데이트해도 남습니다.</div></html>", 11f, MainWindow.MUTED)))
                add(JPanel(GridLayout(1, 3, 6, 0)).apply {
                    isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
                    add(button("+ 추가") { edit(null, null, null) })
                    add(button("JSON 올리기") { importFile() })
                    add(button("JSON 저장") { exportFile() })
                })
                add(labeled("이름 검색", search))
                add(row(count))
            }, BorderLayout.NORTH)
            add(JScrollPane(list).apply { border = null }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                isOpaque = false
                add(button("수정") { list.selectedValue?.let { edit(it, null, null) } })
                add(button("닫기") { dispose() })
            }, BorderLayout.SOUTH)
        }
        size = Dimension(440, 640)
        setLocationRelativeTo(owner)
        refresh()
    }

    private fun refresh() {
        val q = PetDb.key(search.text)
        val all = pets.db.pets
        val shown = all.filter { q.isEmpty() || PetDb.key(it.name).contains(q) }.sortedBy { if (pets.isMine(it.name)) 0 else 1 }
        model.clear(); shown.forEach { model.addElement(it) }
        count.text = "전체 ${all.size}종 (내 도감 ${pets.userCount}종)" + if (q.isNotEmpty()) " · 검색 ${shown.size}종" else ""
    }

    /** 계산 창의 [도감에 추가/수정]: 이름과 (150레벨이면) 화면의 도감값을 채워서 연다 */
    fun open(name: String?, maxS: IntArray?) {
        isVisible = true
        if (name != null) edit(pets.db.find(name), name, maxS)
    }

    // ------------------------------------------------------------------ 편집 창

    private fun edit(pet: Pet?, name: String?, maxS: IntArray?) {
        val dlg = JDialog(this, if (pet == null) "소환수 추가" else "${pet.name} 수정", true)
        val nameE = field(pet?.name ?: name ?: "")
        val gradeE = field(pet?.grade ?: ""); val elemE = field(pet?.element ?: ""); val typeE = field(pet?.type ?: "")
        val initE = List(4) { field(pet?.init?.get(it)?.toString() ?: "") }
        val sE = List(4) { field(pet?.sGrowth?.get(it)?.let { v -> if (v == Math.floor(v)) v.toInt().toString() else v.toString() } ?: "") }
        val mE = List(4) { field((pet?.maxS ?: maxS)?.get(it)?.toString() ?: "") }
        val stats = listOf("공", "방", "순", "체")

        val form = vbox().apply {
            border = BorderFactory.createEmptyBorder(10, 12, 6, 12)
            add(labeled("이름", nameE))
            add(JPanel(GridLayout(1, 3, 6, 0)).apply {
                isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
                add(labeled("등급 (예: 전설)", gradeE)); add(labeled("속성", elemE)); add(labeled("타입", typeE))
            })
            for ((title, fields) in listOf("초기치 (1레벨)" to initE, "S등급 성장률 (레벨당)" to sE, "만렙S (150레벨 도감값)" to mE)) {
                add(row(label(title, 12f, Color.WHITE).apply { border = BorderFactory.createEmptyBorder(8, 0, 0, 0) }))
                add(JPanel(GridLayout(1, 4, 6, 0)).apply {
                    isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
                    fields.forEachIndexed { i, f -> add(labeled(stats[i], f)) }
                })
            }
            add(row(label("<html>· 초기치는 꼭 필요합니다.<br>· 성장률(S)과 만렙S 중 하나만 적으면 나머지는 자동으로 채웁니다.<br>" +
                "· 150레벨 화면에서 열면 만렙S 칸에 화면의 도감값(현재 − 도감 대비)이 들어갑니다.</html>", 11f, MainWindow.MUTED)
                .apply { border = BorderFactory.createEmptyBorder(8, 0, 0, 0) }))
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 6)).apply { isOpaque = false }
        if (pet != null && pets.isMine(pet.name)) {
            val inBase = pets.inBase(pet.name)
            buttons.add(button(if (inBase) "기본값으로" else "삭제") {
                pets.remove(pet.name); dlg.dispose(); changed()
                info(if (inBase) "기본 도감 값으로 되돌렸습니다." else "삭제했습니다.")
            })
        }
        buttons.add(button("저장") {
            val err = save(pet, nameE, gradeE, elemE, typeE, initE, sE, mE)
            if (err != null) warn(dlg, err) else { dlg.dispose(); changed() }
        })
        buttons.add(button("취소") { dlg.dispose() })

        dlg.contentPane = JPanel(BorderLayout()).apply {
            background = MainWindow.BG
            add(form, BorderLayout.CENTER); add(buttons, BorderLayout.SOUTH)
        }
        dlg.pack(); dlg.minimumSize = Dimension(420, dlg.height); dlg.setLocationRelativeTo(this)
        dlg.isVisible = true
    }

    /** 저장. 문제가 있으면 안내 문구를 돌려준다 */
    private fun save(pet: Pet?, nameE: JTextField, gradeE: JTextField, elemE: JTextField, typeE: JTextField,
                     initE: List<JTextField>, sE: List<JTextField>, mE: List<JTextField>): String? {
        val stats = listOf("공", "방", "순", "체")
        val n = nameE.text.trim()
        if (PetDb.key(n).isEmpty()) return "이름을 적어 주세요."
        val init = IntArray(4); val s = DoubleArray(4); val m = IntArray(4)
        for (i in 0..3) {
            val iv = initE[i].text.trim().toIntOrNull() ?: return "${stats[i]} 초기치를 적어 주세요."
            val sv = sE[i].text.trim().toDoubleOrNull()
            val mv = mE[i].text.trim().toIntOrNull()
            if (sv == null && mv == null) return "${stats[i]}: 성장률(S) 또는 만렙S 를 적어 주세요."
            // 자동 계산 전에 막는다 (너무 큰 값이면 계산 중 무한대가 됨)
            if (sv != null && (!sv.isFinite() || sv <= 0 || sv > 10_000)) return "${stats[i]} 성장률 값을 확인해 주세요."
            init[i] = iv
            // 시트: 계산성장 = (만렙S - 초기치) / 149, 만렙S = 초기치 + S성장률 × 149
            s[i] = sv ?: Calculator.round((mv!! - iv) / (Calculator.MAX_LEVEL - 1.0), 2)
            m[i] = mv ?: Calculator.round(iv + sv!! * (Calculator.MAX_LEVEL - 1), 0).toInt()
            if (m[i] <= 0 || s[i] <= 0) return "${stats[i]} 값을 확인해 주세요."
        }
        val p = Pet(n, gradeE.text.trim(), elemE.text.trim(), typeE.text.trim(), init, s, m)
        PetDb.check(p)?.let { return it }
        pets.put(p, oldName = pet?.name)
        return null
    }

    // ------------------------------------------------------------------ JSON

    private fun importFile() {
        val fc = JFileChooser().apply { fileFilter = FileNameExtensionFilter("도감 JSON", "json") }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return
        runCatching { pets.import(fc.selectedFile) }
            .onSuccess { changed(); info("새로 추가 ${it.added}종 · 값 변경 ${it.changed}종 · 그대로 ${it.same}종") }
            .onFailure {
                warn(this, "파일을 읽지 못했습니다: ${it.message}\n\n형식: [{\"이름\":\"...\",\"초기치\":[공,방,순,체],\"S성장률\":[...],\"만렙S\":[...]}, ...]")
            }
    }

    private fun exportFile() {
        val choice = JOptionPane.showOptionDialog(this,
            "전체 도감: 기본 도감 + 내 도감을 모두 저장합니다. 고쳐서 다시 올리면 바뀐 소환수만 반영됩니다.\n" +
                "내 도감만: 내가 추가/수정한 ${pets.userCount}종만 저장합니다. 폰 앱에 올리거나 백업할 때 쓰세요.",
            "JSON 파일로 저장", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null,
            arrayOf("전체 도감", "내 도감만", "취소"), "전체 도감")
        if (choice !in 0..1) return
        val all = choice == 0
        if (!all && pets.userCount == 0) return info("내 도감이 비어 있습니다.")
        val fc = JFileChooser().apply {
            fileFilter = FileNameExtensionFilter("도감 JSON", "json")
            selectedFile = File(if (all) "도감.json" else "내도감.json")
        }
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return
        val f = fc.selectedFile.let { if (it.extension.lowercase() == "json") it else File(it.path + ".json") }
        runCatching { pets.export(f, all) }
            .onSuccess { info("${it}종을 저장했습니다.\n${f.path}") }
            .onFailure { warn(this, "저장하지 못했습니다: ${it.message}") }
    }

    private fun changed() { refresh(); onChanged() }

    // ------------------------------------------------------------------ 화면 도구

    private fun info(msg: String) = JOptionPane.showMessageDialog(this, msg)
    private fun warn(parent: Component, msg: String) = JOptionPane.showMessageDialog(parent, msg, "확인", JOptionPane.WARNING_MESSAGE)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun label(t: String, size: Float, color: Color) = JLabel(t).apply { font = MainWindow.UI_FONT.deriveFont(size); foreground = color }

    private fun field(text: String = "") = JTextField(text).apply {
        font = MainWindow.UI_FONT.deriveFont(14f); foreground = Color.WHITE; background = MainWindow.FIELD; caretColor = Color.WHITE
        border = BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, MainWindow.MUTED), BorderFactory.createEmptyBorder(3, 4, 3, 4))
    }

    private fun button(t: String, onClick: () -> Unit) = JButton(t).apply {
        font = MainWindow.UI_FONT.deriveFont(12f); foreground = Color.WHITE; background = MainWindow.CHIP
        isFocusPainted = false; isFocusable = false; border = BorderFactory.createEmptyBorder(6, 12, 6, 12)
        addActionListener { onClick() }
    }

    private fun vbox() = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }

    private fun row(c: JLabel) = JPanel(FlowLayout(FlowLayout.LEFT, 0, 2)).apply { isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT; add(c) }

    private fun labeled(t: String, f: JTextField) = JPanel(BorderLayout()).apply {
        isOpaque = false; alignmentX = Component.LEFT_ALIGNMENT
        add(label(t, 10f, MainWindow.MUTED).apply { font = font.deriveFont(Font.PLAIN) }, BorderLayout.NORTH)
        add(f, BorderLayout.CENTER)
        border = BorderFactory.createEmptyBorder(4, 0, 2, 0)
    }

    private fun JTextField.onChange(f: () -> Unit) = document.addDocumentListener(object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = f()
        override fun removeUpdate(e: DocumentEvent) = f()
        override fun changedUpdate(e: DocumentEvent) = f()
    })
}
