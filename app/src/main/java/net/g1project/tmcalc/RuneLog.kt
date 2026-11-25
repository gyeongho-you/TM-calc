package net.g1project.tmcalc

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 읽은 룬 기록 (임시): 룬 읽기로 읽은 룬마다 등급·이름·레벨·옵션을 모아 둔다.
 * 정확히 보인 값(만 단위 전)으로 등급|옵션별 레벨→수치를 맞춰, 나중에 '1.2만' 처럼 줄여 보인 값이 실제로 얼마쯤인지 추정한다.
 * 화면에서 가려진 옵션은 못 읽는 대로 둔다.
 * 데이터 모으기 전용 — 계산에는 안 씀. 모은 걸 분석해 수치 공식을 넣은 뒤, 정식 배포 전에 이 파일·메뉴·호출 2곳을 지운다.
 */
object RuneLog {
    private const val FILE = "rune_log.json"
    private const val MAX = 2000

    private fun file(c: Context) = File(c.filesDir, FILE)

    fun load(c: Context): JSONArray = runCatching { JSONArray(file(c).readText()) }.getOrDefault(JSONArray())

    private fun save(c: Context, a: JSONArray) = runCatching { file(c).writeText(a.toString()) }

    fun clear(c: Context) { file(c).delete() }

    /** 기록 한 줄: {t, grade, name, level, opts:[[이름, 화면 글자, lo, hi, pct, 주/부]]} */
    fun entry(r: RuneInfo, time: Long): JSONObject {
        val opts = JSONArray()
        fun put(label: String, n: RuneNum, kind: String) = opts.put(JSONArray().put(label).put(n.text).put(n.lo).put(n.hi).put(n.pct).put(kind))
        r.atk?.let { put("공격력", it, "주") }
        for ((l, n) in r.others) put(l, n, "주")
        for ((l, n) in r.subs) put(l, n, "부")
        return JSONObject().put("t", time).put("grade", r.grade ?: "").put("name", r.name ?: "").put("level", r.level ?: JSONObject.NULL).put("opts", opts)
    }

    /** 같은 룬(등급·이름·레벨·옵션 글자 같음)은 한 번만 */
    private fun key(o: JSONObject): String {
        val a = o.optJSONArray("opts") ?: JSONArray()
        return o.optString("grade") + "|" + o.optString("name") + "|" + o.opt("level") + "|" +
            (0 until a.length()).joinToString(",") { a.getJSONArray(it).let { x -> x.optString(0) + "=" + x.optString(1) } }
    }

    /** 읽은 룬들을 더한다 (등급이나 레벨을 못 읽은 상자는 뺌). 새로 더한 개수 */
    fun add(c: Context, runes: List<RuneInfo>): Int {
        val a = load(c)
        val have = (0 until a.length()).map { key(a.getJSONObject(it)) }.toMutableSet()
        var n = 0
        val now = System.currentTimeMillis()
        for (r in runes) {
            if (r.grade == null || r.level == null) continue
            if (r.atk == null && r.others.isEmpty() && r.subs.isEmpty()) continue
            val e = entry(r, now)
            if (have.add(key(e))) { a.put(e); n++ }
        }
        if (n > 0) {
            val out = if (a.length() > MAX) JSONArray((a.length() - MAX until a.length()).map { a.get(it) }) else a
            save(c, out)
        }
        return n
    }

    /** 정확히 보인 값으로 만든 수치 표: "등급|옵션" → {레벨: 값} (같은 레벨 여러 번이면 마지막 값) */
    fun table(a: JSONArray): JSONObject {
        val tb = JSONObject()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val lv = o.opt("level") as? Int ?: continue
            val opts = o.optJSONArray("opts") ?: continue
            for (j in 0 until opts.length()) {
                val x = opts.optJSONArray(j) ?: continue
                val lo = x.optDouble(2); val hi = x.optDouble(3)
                if (lo.isNaN() || lo != hi) continue
                val k = o.optString("grade") + "|" + x.optString(0)
                (tb.optJSONObject(k) ?: JSONObject().also { tb.put(k, it) }).put(lv.toString(), lo)
            }
        }
        return tb
    }

    /** 레벨 → 값 직선 (정확한 값 2레벨 이상일 때). 줄여 보인 범위 [lo, hi] 안으로 자름 */
    fun estimate(tb: JSONObject, grade: String, label: String, level: Int, lo: Double, hi: Double): Double? {
        val o = tb.optJSONObject("$grade|$label") ?: return null
        val pts = o.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it.toDouble() to o.optDouble(k) } }.filter { !it.second.isNaN() }.toList()
        if (pts.map { it.first }.distinct().size < 2) return null
        val mx = pts.map { it.first }.average(); val my = pts.map { it.second }.average()
        val sxx = pts.sumOf { (it.first - mx) * (it.first - mx) }
        if (sxx <= 0) return null
        val b = pts.sumOf { (it.first - mx) * (it.second - my) } / sxx
        return Math.round(my + b * (level - mx)).toDouble().coerceIn(lo, hi)
    }

    /** 보기용 글: 등급|옵션마다 레벨 순으로, 줄여 보인 값은 추정값 같이 */
    fun summary(a: JSONArray): String {
        val tb = table(a)
        data class Row(val level: Int, val text: String, val lo: Double, val hi: Double)
        val groups = sortedMapOf<String, MutableMap<String, Row>>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val lv = o.opt("level") as? Int ?: continue
            val opts = o.optJSONArray("opts") ?: continue
            for (j in 0 until opts.length()) {
                val x = opts.optJSONArray(j) ?: continue
                val k = o.optString("grade") + "|" + x.optString(0)
                groups.getOrPut(k) { linkedMapOf() }["$lv|${x.optString(1)}"] = Row(lv, x.optString(1), x.optDouble(2), x.optDouble(3))
            }
        }
        val sb = StringBuilder()
        for ((k, rows) in groups) {
            val (grade, label) = k.split("|", limit = 2)
            sb.append("[$grade] $label\n")
            for (r in rows.values.sortedWith(compareBy({ it.level }, { it.lo }))) {
                sb.append("  Lv.${r.level}  ${r.text}")
                if (r.lo != r.hi) {
                    val e = estimate(tb, grade, label, r.level, r.lo, r.hi)
                    sb.append(if (e != null) "  ≈ ${LabData.fmt(e)} (추정)" else "  (정확한 값이 2레벨 이상 쌓이면 추정)")
                }
                sb.append("\n")
            }
            sb.append("\n")
        }
        return if (sb.isEmpty()) "아직 읽은 룬이 없어요. 룬 비교 모드나 [연구] 창의 룬 읽기로 룬 화면을 읽으면 쌓여요." else sb.toString().trimEnd()
    }
}
