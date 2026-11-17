package net.g1project.tmcalc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 연구실 자료 (폰 안 lab.json 하나): 틀(분류·잴 것)과 세션(기록 목록).
 * 게임 위 [연구] 창(OverlayService)과 메뉴 > 연구실 화면(LabActivity, assets/lab.html)이 같이 쓴다.
 * 고칠 때는 항상 새로 읽고 → 고치고 → 바로 저장해서 서로 덮어쓰지 않게 한다.
 */
object LabData {
    private fun file(c: Context) = File(c.filesDir, "lab.json")

    @Synchronized
    fun load(c: Context): JSONObject {
        val o = runCatching { JSONObject(file(c).readText()) }.getOrNull() ?: JSONObject()
        if (!o.has("templates")) o.put("templates", JSONArray())
        if (!o.has("sessions")) o.put("sessions", JSONArray())
        return o.put("v", 1)
    }

    @Synchronized
    fun save(c: Context, o: JSONObject) {
        val f = file(c)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    @Synchronized
    fun update(c: Context, fn: (JSONObject) -> Unit) {
        val o = load(c); fn(o); save(c, o)
    }

    private fun find(arr: JSONArray?, id: String?): JSONObject? {
        if (arr == null || id == null) return null
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("id") == id) return it }
        return null
    }

    fun session(o: JSONObject, id: String?) = find(o.optJSONArray("sessions"), id)
    fun template(o: JSONObject, id: String?) = find(o.optJSONArray("templates"), id)

    /** "3억5952만", "191만9961", "16.6만", "+44.38%", "-1,200" → 숫자 */
    fun number(raw: String): Double? {
        var s = raw.replace(" ", "").replace(",", "").removeSuffix("%")
        if (s.isEmpty()) return null
        val neg = s.startsWith("-")
        s = s.removePrefix("+").removePrefix("-")
        var total = 0.0
        for ((unit, mul) in listOf("억" to 1e8, "만" to 1e4)) {
            val i = s.indexOf(unit)
            if (i >= 0) {
                val a = s.substring(0, i)
                total += (if (a.isEmpty()) 1.0 else a.toDoubleOrNull() ?: return null) * mul
                s = s.substring(i + 1)
            }
        }
        if (s.isNotEmpty()) total += s.toDoubleOrNull() ?: return null
        return if (neg) -total else total
    }

    /** 12345.0 → "12,345", 1.5 → "1.5" */
    fun fmt(v: Double): String =
        if (v == Math.rint(v) && Math.abs(v) < 1e15) String.format(java.util.Locale.US, "%,d", v.toLong())
        else String.format(java.util.Locale.US, "%,.4f", v).trimEnd('0').trimEnd('.')

    fun newId(): String = java.lang.Long.toString(System.currentTimeMillis(), 36) + java.lang.Long.toString((Math.random() * 1e6).toLong(), 36)
}
