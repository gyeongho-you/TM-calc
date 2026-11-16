package net.g1project.tmcalc

/**
 * 연구실: 아무 화면에서나 "이름 … 숫자" 줄을 뽑아 둔다 (공식을 맞추기 위한 자료 모으기).
 * 화면마다 따로 맞춘 해석은 하지 않고, 같은 줄의 왼쪽 글자 + 오른쪽 숫자를 짝지은 뒤
 * 그 위의 소제목(기본능력치, 전투능력치 …)을 앞에 붙여 구분한다.
 */
object LabParser {

    class Value(val section: String?, val label: String, val raw: String, val value: Double?) {
        val key: String get() = if (section == null) label else "$section/$label"
    }

    /** 상태창 소제목 (같은 '공격력'이 기본/전투/룬에 다 있어서 구분용) */
    private val HEADERS = listOf("기본능력치", "전투능력치", "속성강화", "특수버프", "룬옵션")

    /** 숫자 값: 3억5952만, 191만9961, 16.6만, +8.5만, +44.38%, 11.70%, 2618, 1,234 */
    private val VALUE = Regex("""^[+\-]?\d[\d,]*(\.\d+)?(억(\d[\d,]*)?)?(만(\d[\d,]*)?)?\s*%?$""")

    fun kind(lines: List<OcrLine>): String {
        val all = lines.joinToString(" ") { it.text.replace(" ", "") }
        return when {
            "기본능력치" in all && "전투능력치" in all -> "상태창"
            "전투능력치" in all -> "전투능력치"
            "총성장점수" in all || "성장평균" in all -> "소환수"
            else -> "기타"
        }
    }

    fun values(lines: List<OcrLine>): List<Value> {
        val headers = lines.filter { l -> HEADERS.any { l.text.replace(" ", "").startsWith(it) } }
        val out = ArrayList<Value>()
        val used = HashSet<OcrLine>()
        for (l in lines.sortedWith(compareBy({ it.top }, { it.left }))) {
            if (l in headers || l in used) continue
            val t = l.text.trim()
            // OCR 이 한 줄로 붙여 읽은 경우: "공격력 261"
            val m = Regex("""^(.*[가-힣A-Za-z%)])\s+([+\-]?\d[\d,.억만]*\s*%?)$""").find(t)
            if (m != null && VALUE.matches(m.groupValues[2].replace(" ", ""))) {
                out += make(section(l, headers), m.groupValues[1], m.groupValues[2]); continue
            }
            if (!t.any { it in '가'..'힣' } || VALUE.matches(t.replace(" ", ""))) continue
            // 같은 높이, 오른쪽에서 가장 가까운 숫자 칸
            val v = lines.filter {
                it !== l && it !in used && it.left > l.right - l.height / 2 &&
                    Math.abs(it.cy - l.cy) < maxOf(l.height, it.height) * 0.6 && VALUE.matches(it.text.replace(" ", ""))
            }.minByOrNull { it.left } ?: continue
            // 그 사이에 다른 글자(다른 칸의 이름)가 끼어 있으면 짝이 아니다
            val between = lines.any { it !== l && it !== v && Math.abs(it.cy - l.cy) < l.height * 0.6 && it.left > l.right && it.right < v.left }
            if (between) continue
            used += v
            out += make(section(l, headers), t, v.text)
        }
        return out
    }

    private fun section(l: OcrLine, headers: List<OcrLine>): String? {
        val tol = maxOf(l.height * 3, 40)
        return headers.filter { it.top < l.top && Math.abs(it.left - l.left) < tol }
            .maxByOrNull { it.top }?.text?.replace(" ", "")?.replace(Regex("[^가-힣]"), "")
    }

    private fun make(section: String?, label: String, raw: String) =
        Value(section, label.replace(" ", ""), raw.trim(), number(raw))

    /** "3억5952만" → 359520000, "191만9961" → 1919961, "16.6만" → 166000, "+44.38%" → 44.38 */
    fun number(raw: String): Double? {
        var s = raw.replace(" ", "").replace(",", "").removeSuffix("%")
        val neg = s.startsWith("-")
        s = s.removePrefix("+").removePrefix("-")
        var total = 0.0
        for ((unit, mul) in listOf("억" to 1e8, "만" to 1e4)) {
            val i = s.indexOf(unit)
            if (i >= 0) {
                total += (s.substring(0, i).toDoubleOrNull() ?: return null) * mul
                s = s.substring(i + 1)
            }
        }
        if (s.isNotEmpty()) total += s.toDoubleOrNull() ?: return null
        return if (neg) -total else total
    }
}
