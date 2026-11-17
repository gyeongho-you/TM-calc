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
        // 화면 위아래(상태 바, 키보드)에 가리지 않게
        web.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(web)
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

    private inner class Bridge {
        @JavascriptInterface fun load(): String = LabData.load(this@LabActivity).toString()

        @JavascriptInterface fun save(json: String) {
            runCatching { LabData.save(this@LabActivity, JSONObject(json)) }.onFailure { toast("저장하지 못했습니다: ${it.message}") }
        }

        @JavascriptInterface fun getActive(): String = AppPrefs.labActive(this@LabActivity).orEmpty()

        @JavascriptInterface fun setActive(id: String) = AppPrefs.setLabActive(this@LabActivity, id.ifBlank { null })

        @JavascriptInterface fun toast(msg: String) = this@LabActivity.toast(msg)

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
