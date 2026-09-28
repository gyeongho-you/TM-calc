package net.g1project.tmcalc

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 도감 관리: 기본 도감(엑셀) 위에 내 도감을 추가/수정한다.
 * 레이어의 "도감에 추가/수정" 버튼으로 열면 [EXTRA_NAME], [EXTRA_MAXS] 로 받은 값을 채운 편집 창이 바로 뜬다.
 */
class PetEditorActivity : Activity() {

    private lateinit var list: LinearLayout
    private lateinit var search: EditText
    private lateinit var countView: TextView
    private val dp get() = resources.displayMetrics.density

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(16), px(16), px(16), px(16))
        }
        root.addView(TextView(this).apply {
            text = "도감 관리"
            textSize = 20f
            setTextColor(Color.WHITE)
        })
        root.addView(TextView(this).apply {
            text = "기본 도감(엑셀 시트)에 없거나 값이 다른 소환수를 추가/수정하면 능력치 등급과 강화 등급이 정확하게 계산됩니다. " +
                "내가 추가/수정한 것은 따로 저장되어 앱을 업데이트해도 남습니다."
            textSize = 13f
            setPadding(0, px(4), 0, px(8))
        })
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("+ 추가") { edit(null, null, null) }, weight())
            addView(button("JSON 올리기") { importFile() }, weight())
            addView(button("JSON 저장") { exportFile() }, weight())
        })
        search = EditText(this).apply {
            hint = "이름 검색"
            setSingleLine()
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
        }
        root.addView(search)
        countView = TextView(this).apply { textSize = 12f; setPadding(0, px(4), 0, px(4)) }
        root.addView(countView)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)

        // MainActivity 와 같은 방식으로 상태바/내비게이션바만큼 여백
        val scroll = ScrollView(this).apply { addView(root) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            val base = intArrayOf(root.paddingLeft, root.paddingTop, root.paddingRight, root.paddingBottom)
            scroll.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                root.setPadding(base[0] + bars.left, base[1] + bars.top, base[2] + bars.right, base[3] + bars.bottom)
                insets
            }
        }
        setContentView(scroll)
        refresh()
        if (savedInstanceState == null) openFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openFromIntent(intent)
    }

    private fun openFromIntent(i: Intent) {
        val name = i.getStringExtra(EXTRA_NAME) ?: return
        val existing = PetStore.load(this).find(name)
        edit(existing, name, i.getIntArrayExtra(EXTRA_MAXS))
    }

    private fun refresh() {
        val builtIn = PetStore.builtIn(this).associateBy { PetDb.key(it.name) }
        val mine = PetStore.user(this).map { PetDb.key(it.name) }.toSet()
        val all = PetStore.load(this).pets
        val q = PetDb.key(search.text.toString())
        // 내 도감을 먼저, 그다음 기본 도감 순서
        val shown = all.filter { q.isEmpty() || PetDb.key(it.name).contains(q) }
            .sortedBy { if (PetDb.key(it.name) in mine) 0 else 1 }
        countView.text = "전체 ${all.size}종 (내 도감 ${mine.size}종)" + if (q.isNotEmpty()) " · 검색 ${shown.size}종" else ""
        list.removeAllViews()
        for (p in shown) {
            val k = PetDb.key(p.name)
            val tag = when {
                k !in mine -> ""
                k in builtIn -> "  [수정함]"
                else -> "  [추가함]"
            }
            list.addView(TextView(this).apply {
                text = "${p.name}$tag\n" +
                    listOf(p.grade, p.element, p.type).filter { it.isNotBlank() }.joinToString("/")
                        .let { if (it.isEmpty()) "" else "$it · " } +
                    "만렙S ${p.maxS.joinToString(" / ")}"
                textSize = 14f
                setTextColor(if (tag.isEmpty()) Color.parseColor("#C9CBD0") else Color.parseColor("#F2B84B"))
                setPadding(0, px(8), 0, px(8))
                setOnClickListener { edit(p, null, null) }
            })
        }
    }

    /**
     * 편집 창. [pet] 이 있으면 그 값으로, 없으면 [name]/[maxS] 만 채운 새 소환수로.
     * 성장률(S)과 만렙S 중 하나만 적으면 나머지는 시트와 같은 식으로 채운다: 만렙S = ROUND(초기치 + S성장률 × 149).
     */
    private fun edit(pet: Pet?, name: String?, maxS: IntArray?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20), px(8), px(20), 0)
        }
        fun field(hint: String, value: String, type: Int) = EditText(this).apply {
            this.hint = hint
            setText(value)
            inputType = type
            setSingleLine()
            textSize = 14f
        }
        val num = InputType.TYPE_CLASS_NUMBER
        val dec = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val nameE = field("이름", pet?.name ?: name ?: "", InputType.TYPE_CLASS_TEXT)
        val gradeE = field("등급 (예: 전설)", pet?.grade ?: "", InputType.TYPE_CLASS_TEXT)
        val elemE = field("속성", pet?.element ?: "", InputType.TYPE_CLASS_TEXT)
        val typeE = field("타입", pet?.type ?: "", InputType.TYPE_CLASS_TEXT)
        form.addView(nameE)
        form.addView(LinearLayout(this).apply {
            addView(gradeE, weight()); addView(elemE, weight()); addView(typeE, weight())
        })
        val stats = listOf("공", "방", "순", "체")
        val initE = List(4) { field(stats[it], pet?.init?.get(it)?.toString() ?: "", num) }
        val sE = List(4) { field(stats[it], pet?.sGrowth?.get(it)?.let { v -> fmt(v) } ?: "", dec) }
        val mE = List(4) { field(stats[it], (pet?.maxS ?: maxS)?.get(it)?.toString() ?: "", num) }
        for ((label, row) in listOf("초기치 (1레벨)" to initE, "S등급 성장률 (레벨당)" to sE, "만렙S (150레벨 도감값)" to mE)) {
            form.addView(TextView(this).apply { text = label; textSize = 12f; setPadding(0, px(8), 0, 0) })
            form.addView(LinearLayout(this).apply { row.forEach { addView(it, weight()) } })
        }
        form.addView(TextView(this).apply {
            text = "· 초기치는 꼭 필요합니다.\n· 성장률(S)과 만렙S 중 하나만 적으면 나머지는 자동으로 채웁니다.\n" +
                "· 150레벨 화면에서 열면 만렙S 칸에 화면의 도감값(현재 − 도감 대비)이 들어갑니다."
            textSize = 12f
            setPadding(0, px(8), 0, px(8))
        })

        val mine = pet != null && PetStore.user(this).any { PetDb.key(it.name) == PetDb.key(pet.name) }
        val inBase = pet != null && PetStore.builtIn(this).any { PetDb.key(it.name) == PetDb.key(pet.name) }
        val dlg = AlertDialog.Builder(this)
            .setTitle(if (pet == null) "소환수 추가" else "${pet.name} 수정")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("저장", null)
            .setNegativeButton("취소", null)
            .apply {
                if (mine) setNeutralButton(if (inBase) "기본값으로" else "삭제") { _, _ ->
                    PetStore.remove(this@PetEditorActivity, pet!!.name)
                    toast(if (inBase) "기본 도감 값으로 되돌렸습니다." else "삭제했습니다.")
                    refresh()
                }
            }
            .create()
        dlg.setOnShowListener {
            // 저장 버튼은 입력이 틀리면 창을 닫지 않는다
            dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val n = nameE.text.toString().trim()
                if (PetDb.key(n).isEmpty()) return@setOnClickListener toast("이름을 적어 주세요.")
                val init = IntArray(4)
                val s = DoubleArray(4)
                val m = IntArray(4)
                for (i in 0..3) {
                    val iv = initE[i].text.toString().trim().toIntOrNull()
                        ?: return@setOnClickListener toast("${stats[i]} 초기치를 적어 주세요.")
                    val sv = sE[i].text.toString().trim().toDoubleOrNull()
                    val mv = mE[i].text.toString().trim().toIntOrNull()
                    if (sv == null && mv == null) return@setOnClickListener toast("${stats[i]}: 성장률(S) 또는 만렙S 를 적어 주세요.")
                    init[i] = iv
                    // 시트: 계산성장 = (만렙S - 초기치) / 149, 만렙S = 초기치 + S성장률 × 149
                    s[i] = sv ?: Calculator.round((mv!! - iv) / (Calculator.MAX_LEVEL - 1.0), 2)
                    m[i] = mv ?: Calculator.round(iv + sv!! * (Calculator.MAX_LEVEL - 1), 0).toInt()
                    if (m[i] <= 0 || s[i] <= 0) return@setOnClickListener toast("${stats[i]} 값을 확인해 주세요.")
                }
                val p = Pet(n, gradeE.text.toString().trim(), elemE.text.toString().trim(), typeE.text.toString().trim(), init, s, m)
                PetStore.put(this, p, oldName = pet?.name)
                toast("${p.name} 저장했습니다.")
                dlg.dismiss()
                refresh()
            }
        }
        dlg.show()
    }

    private fun importFile() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }, REQ_IMPORT)
    }

    /** 저장할 범위: true 면 전체 도감(기본 + 내 도감), false 면 내 도감만 */
    private var exportAll = true

    private fun exportFile() {
        val mine = PetStore.user(this).size
        AlertDialog.Builder(this)
            .setTitle("JSON 파일로 저장")
            .setMessage("전체 도감: 기본 도감 + 내 도감을 모두 저장합니다. PC에서 고쳐서 다시 올리면 바뀐 소환수만 반영됩니다.\n\n" +
                "내 도감만: 내가 추가/수정한 ${mine}종만 저장합니다. 친구와 나누거나 백업할 때 쓰세요.")
            .setPositiveButton("전체 도감") { _, _ -> exportAll = true; pickExportFile("도감.json") }
            .setNeutralButton("내 도감만") { _, _ ->
                if (mine == 0) toast("내 도감이 비어 있습니다.")
                else { exportAll = false; pickExportFile("내도감.json") }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun pickExportFile(name: String) {
        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, name)
        }, REQ_EXPORT)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        runCatching {
            when (requestCode) {
                REQ_IMPORT -> {
                    // 도감 파일은 수십 KB. 엉뚱한 큰 파일을 골라 앱이 멈추지 않도록 1MB 까지만 읽는다
                    val out = java.io.ByteArrayOutputStream()
                    contentResolver.openInputStream(uri)!!.use { input ->
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            require(out.size() <= MAX_IMPORT_BYTES) { "파일이 너무 큽니다 (1MB 이하만 올릴 수 있습니다)" }
                        }
                    }
                    val json = out.toString("UTF-8")
                    val r = PetStore.import(this, json)
                    AlertDialog.Builder(this)
                        .setTitle("JSON 올리기 완료")
                        .setMessage("새로 추가 ${r.added}종 · 값 변경 ${r.changed}종 · 그대로 ${r.same}종")
                        .setPositiveButton("확인", null)
                        .show()
                    refresh()
                }
                REQ_EXPORT -> {
                    val pets = if (exportAll) PetStore.load(this).pets else PetStore.user(this)
                    contentResolver.openOutputStream(uri, "wt")!!.bufferedWriter().use {
                        it.write(PetStore.exportJson(pets))
                    }
                    toast("${pets.size}종을 저장했습니다.")
                }
            }
        }.onFailure {
            AlertDialog.Builder(this)
                .setTitle("파일을 처리하지 못했습니다")
                .setMessage("${it.message}\n\n형식: [{\"이름\":\"...\",\"초기치\":[공,방,순,체],\"S성장률\":[...],\"만렙S\":[...]}, ...]")
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun button(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { onClick() }
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)

    private fun px(v: Int) = (v * dp).toInt()

    private fun fmt(v: Double) = if (v == Math.floor(v)) v.toInt().toString() else v.toString()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_MAXS = "maxS"
        private const val REQ_IMPORT = 1
        private const val REQ_EXPORT = 2
        private const val MAX_IMPORT_BYTES = 1 shl 20

        fun intent(ctx: Context, name: String?, maxS: IntArray?) = Intent(ctx, PetEditorActivity::class.java).apply {
            if (name != null) putExtra(EXTRA_NAME, name)
            if (maxS != null) putExtra(EXTRA_MAXS, maxS)
        }
    }
}
