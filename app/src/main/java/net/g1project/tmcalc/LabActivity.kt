package net.g1project.tmcalc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.Toast
import org.json.JSONObject

/**
 * 메뉴 > 연구실: 틀·세션 관리, 기록 보기/수정, 공식 찾기 (assets/lab.html).
 * 자료는 [LabData] (폰 안 lab.json) 에 저장하고, 진행 중인 세션은 [AppPrefs.labActive].
 */
class LabActivity : Activity() {
    private lateinit var web: WebView
    private var pendingSave: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(0xFF17181B.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            webChromeClient = WebChromeClient()
            addJavascriptInterface(Bridge(), "TMCalc")
            loadUrl("file:///android_asset/lab.html")
        }
        // 화면 위아래(상태 바, 노치, 키보드)에 가리지 않게: WebView 는 자기 padding 을 무시하고 그 밑까지 그리므로
        // 바깥 틀에 여백을 준다
        val frame = android.widget.FrameLayout(this).apply {
            setBackgroundColor(0xFF17181B.toInt())
            addView(web, android.widget.FrameLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(b.left, b.top, b.right, b.bottom)
                insets
            }
        }
        setContentView(frame)
    }

    override fun onResume() {
        super.onResume()
        // 게임 위 [연구] 창에서 넣은 기록을 바로 보이게
        web.evaluateJavascript("window.reloadData&&reloadData()", null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        web.evaluateJavascript("window.onBack?onBack():false") { r -> if (r != "true") finish() }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun toast(m: String) = runOnUiThread { Toast.makeText(this, m, Toast.LENGTH_SHORT).show() }

    /** 실전투 능력치(공·방·순·체) = 능력치 × (1 + 성장증폭 × 총능 보너스). 캐릭터 배율은 덱 안에서 같아서 뺌 */
    private fun combatOf(stats: IntArray, amp: Double): org.json.JSONArray {
        val f = runCatching { 1 + amp * Calculator.scoreFactor(Calculator.abilityScore(stats)) }.getOrDefault(1.0)
        return org.json.JSONArray(stats.map { Math.round(it * f * 10) / 10.0 })
    }

    private inner class Bridge {
        @JavascriptInterface fun load(): String = LabData.load(this@LabActivity).toString()

        @JavascriptInterface fun save(json: String) {
            runCatching { LabData.save(this@LabActivity, JSONObject(json)) }.onFailure { toast("저장하지 못했습니다: ${it.message}") }
        }

        /** 저장한 소환수 목록 (최적화 › 덱에 불러오기): 이름·속성·타입·능력치(공·방·순·체) */
        @JavascriptInterface fun savedPets(): String {
            val db = PetStore.load(this@LabActivity)
            val arr = org.json.JSONArray()
            for (p in AppPrefs.presets(this@LabActivity)) {
                val pet = db.find(p.name)
                arr.put(JSONObject().put("label", p.label).put("name", p.name).put("level", p.level)
                    .put("element", pet?.element ?: "").put("type", pet?.type ?: "").put("grade", pet?.grade ?: "")
                    .put("stats", org.json.JSONArray(p.stats.toList()))
                    .put("combat", combatOf(p.stats, AppPrefs.growthAmp(this@LabActivity)))
                    .put("amp", Math.round(AppPrefs.growthAmp(this@LabActivity) * 1000) / 10.0))
            }
            return arr.toString()
        }

        /** 도감 (이름·속성·타입·도감 S 능력치) — 최적화 › 덱의 공속 비중 */
        @JavascriptInterface fun petDb(): String {
            val arr = org.json.JSONArray()
            for (pt in PetStore.load(this@LabActivity).pets) arr.put(JSONObject().put("n", pt.name).put("e", pt.element).put("t", pt.type).put("m", org.json.JSONArray(pt.maxS.toList())).put("k", PetDb.skillObj(pt)))
            return arr.toString()
        }

        @JavascriptInterface fun getActive(): String = AppPrefs.labActive(this@LabActivity).orEmpty()

        @JavascriptInterface fun setActive(id: String) = AppPrefs.setLabActive(this@LabActivity, id.ifBlank { null })

        @JavascriptInterface fun toast(msg: String) = this@LabActivity.toast(msg)

        @JavascriptInterface fun copy(text: String) = runOnUiThread {
            getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("공식", text))
            Toast.makeText(this@LabActivity, "복사했습니다.", Toast.LENGTH_SHORT).show()
        }

        @JavascriptInterface fun saveFile(name: String, text: String) = runOnUiThread {
            pendingSave = text
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "application/json"; putExtra(Intent.EXTRA_TITLE, name)
            }, REQ_SAVE)
        }

        @JavascriptInterface fun pickFile() = runOnUiThread {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, REQ_PICK)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) { pendingSave = null; return }
        runCatching {
            when (requestCode) {
                REQ_SAVE -> {
                    contentResolver.openOutputStream(uri, "wt")!!.use { it.write(pendingSave.orEmpty().toByteArray(Charsets.UTF_8)) }
                    toast("저장했습니다.")
                }
                REQ_PICK -> {
                    val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    require(bytes.size <= 5 shl 20) { "파일이 너무 큽니다 (5MB 이하)" }
                    web.evaluateJavascript("window.onPickedFile&&onPickedFile(" + JSONObject.quote(String(bytes, Charsets.UTF_8)) + ")", null)
                }
            }
        }.onFailure { toast("파일을 처리하지 못했습니다: ${it.message}") }
        pendingSave = null
    }

    private companion object {
        const val REQ_SAVE = 31
        const val REQ_PICK = 32
    }
}
