package net.g1project.tmcalc

/**
 * 게임 화면의 숫자 하나 (내림 표시 범위 포함).
 * "5737+" → 5737 (정확), "1.7만+" → 17000~17999, "1억210만" → 1억210만~1억210만9999, "4.38%" → 4.38
 */
class RuneNum(val text: String, val lo: Double, val hi: Double, val pct: Boolean) {
    val exact: Boolean get() = hi == lo
    /** 범위의 가운데 (정확하면 그 값) */
    val mid: Double get() = (lo + hi) / 2
}

/** 룬 상자 하나: 등급·이름·레벨·주 옵션 공격력·부 옵션들 */
class RuneInfo(
    val grade: String?,
    val name: String?,
    val level: Int?,
    val atk: RuneNum?,
    /** 주 옵션 중 계산에 안 쓰는 것 (방어력·속도·생명력) — 참고 표시용 */
    val others: List<Pair<String, RuneNum>>,
    val subs: List<Pair<String, RuneNum>>,
)

/**
 * 룬 비교 화면 (위: 장착중, 아래: 바꿀 룬) 해석.
 * 좌표를 고정하지 않고 "[등급]" 이름 줄, "Lv.", "공격력", "부 옵션" 글자를 기준점으로 삼아 해상도가 달라도 동작한다.
 */
object RuneParser {
    private val UNITS = listOf('경' to 1e16, '조' to 1e12, '억' to 1e8, '만' to 1e4)
    private val NUM_RE = Regex("[0-9][0-9.,]*(?:[만억조경][0-9.,]*)*\\+?%?")
    val GRADES = listOf("일반", "고급", "희귀", "레어", "영웅", "에픽", "유니크", "전설", "신화", "초월")
    private val MAIN = listOf("공격력", "방어력", "속도", "생명력")

    /** 화면 숫자 → 값 + 내림 범위. 숫자가 없으면 null */
    fun num(raw: String): RuneNum? {
        val m = NUM_RE.find(raw.replace(" ", "")) ?: return null
        val t = m.value
        val pct = t.endsWith("%")
        val s = t.removeSuffix("%").removeSuffix("+").replace(",", "")
        if (s.isEmpty()) return null
        var rest = s
        var total = 0.0
        var lastUnit = 1.0
        var lastDec = 0
        for ((u, mul) in UNITS) {
            val i = rest.indexOf(u)
            if (i < 0) continue
            val a = rest.substring(0, i)
            val v = if (a.isEmpty()) 1.0 else a.toDoubleOrNull() ?: return null
            total += v * mul
            lastUnit = mul
            lastDec = a.substringAfter('.', "").length
            rest = rest.substring(i + 1)
        }
        var res = 1.0
        if (rest.isNotEmpty()) total += rest.toDoubleOrNull() ?: return null
        else if (lastUnit > 1) res = lastUnit / Math.pow(10.0, lastDec.toDouble())   // 단위로 끝남: 그 아래는 숨김 (내림)
        val lo = Math.round(total * 1e6) / 1e6
        val hi = if (res > 1) lo + res - 1 else lo
        return RuneNum(t, lo, hi, pct)
    }

    private fun gradeIn(text: String): String? {
        Regex("\\[([^\\]]+)]").find(text)?.groupValues?.get(1)?.trim()?.let { g -> GRADES.firstOrNull { g.contains(it) }?.let { return it } }
        return null
    }

    /**
     * 룬 비교 화면인지 엄격하게 판단 ([계산] 버튼이 소환수 화면을 룬으로 잘못 보지 않게).
     * '주 옵션' 머리글 2개 + '부 옵션' 머리글 + ('장착중' 또는 '○○의 룬') 이 모두 있어야 하고, 상자 2개 모두 공격력을 읽어야 한다.
     */
    fun isRuneCompare(lines: List<OcrLine>): Boolean {
        val t = lines.map { it.text.replace(" ", "") }
        val heads = t.count { it.startsWith("주") && it.contains("옵") && it.length <= 5 }
        val subs = t.count { it.startsWith("부") && it.contains("옵") && it.length <= 5 }
        val mark = t.any { it.contains("장착중") } || t.count { it.contains("룬") && it.length > 2 } >= 2
        if (heads < 2 || subs < 1 || !mark) return false
        val r = parse(lines)
        return r.size >= 2 && r.take(2).all { it.atk != null }
    }

    /** 위에서 아래 순서로 룬 상자들 (보통 [장착중, 바꿀 룬]) */
    fun parse(lines: List<OcrLine>): List<RuneInfo> {
        val ls = lines.filter { it.text.isNotBlank() }.sortedBy { it.top }
        val lvRe = Regex("[Ll][Vv]\\s*[.,]?\\s*(\\d{1,4})")
        // 상자 기준점: "주 옵션" 글자 (OCR이 '주옵션'·'주 옵선'으로 읽어도). 없으면 "Lv.숫자" 줄
        val heads = ls.filter { val t = it.text.replace(" ", ""); t.startsWith("주") && t.contains("옵") && t.length <= 5 }
            .ifEmpty { ls.filter { lvRe.containsMatchIn(it.text) && !it.text.contains("던전") } }
        if (heads.isEmpty()) return emptyList()
        // 이름 줄: 기준점 위쪽에서 "룬"이 든 가장 긴 줄 ("찰나의 룬 [신화]" 이 둘로 나뉘어 읽혀도)
        val names = heads.map { hd -> ls.filter { it.bottom <= hd.top + hd.height && it.top >= hd.top - hd.height * 8 && it.text.contains("룬") && it.text.replace(" ", "").length > 1 }
            .maxByOrNull { it.text.length } ?: hd }
        val stop = ls.firstOrNull { l -> heads.last().top < l.top && listOf("분해", "장착", "탭하여", "레벨업", "자동제작").any { l.text.replace(" ", "").contains(it) } && !l.text.contains("장착중") }
        return names.mapIndexed { k, nl ->
            val h = heads[k].height
            val y0 = minOf(nl.top, heads[k].top) - 3 * h
            val y1 = names.getOrNull(k + 1)?.let { minOf(it.top, heads[k + 1].top) - 3 * h } ?: (stop?.top ?: Int.MAX_VALUE)
            val card = ls.filter { it.cy in y0 until y1 }
            val name = nl.text.substringBefore("[").substringBefore("(").trim().takeIf { it.contains("룬") }
            val level = card.firstNotNullOfOrNull { lvRe.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
            // 등급: 이름 줄의 [신화] 또는 위쪽 등급 표시 ("신화")
            val grade = gradeIn(nl.text) ?: card.filter { it.top <= heads[k].top }.firstNotNullOfOrNull { l -> GRADES.firstOrNull { l.text.replace(" ", "").contains(it) } }
            val subHead = card.firstOrNull { val t = it.text.replace(" ", ""); t.startsWith("부") && t.contains("옵") && t.length <= 5 }
            val mainEnd = subHead?.top ?: Int.MAX_VALUE
            // 같은 줄(높이 비슷)에서 라벨 오른쪽의 첫 숫자
            fun valueRight(label: OcrLine, after: Int): RuneNum? {
                num(label.text.substring(after))?.let { return it }
                return card.filter { it !== label && Math.abs(it.cy - label.cy) < label.height * 0.7 && it.left >= label.right - label.height }
                    .sortedBy { it.left }.firstNotNullOfOrNull { l -> if (MAIN.any { l.text.startsWith(it) }) null else num(l.text) }
            }
            var atk: RuneNum? = null
            val others = ArrayList<Pair<String, RuneNum>>()
            for (l in card) {
                if (l.top >= mainEnd) break
                for (lab in MAIN) {
                    var i = l.text.indexOf(lab)
                    while (i >= 0) {
                        // "장비 공격력" 같은 부 옵션 글자는 주 옵션이 아님
                        if (!(lab == "공격력" && i > 0 && l.text.substring(0, i).trimEnd().endsWith("장비"))) {
                            val rest = l.text.substring(i + lab.length)
                            val v = num(rest.substringBefore(MAIN.filter { it != lab }.firstOrNull { rest.contains(it) } ?: "\u0000")) ?: valueRight(l, i + lab.length)
                            if (v != null) { if (lab == "공격력") { if (atk == null) atk = v } else if (others.none { it.first == lab }) others += lab to v }
                        }
                        i = l.text.indexOf(lab, i + lab.length)
                    }
                }
            }
            // 부 옵션: "부 옵션" 아래, 왼쪽 글자 = 이름, 같은 줄 오른쪽 숫자 = 값
            val subs = ArrayList<Pair<String, RuneNum>>()
            if (subHead != null) {
                val rows = card.filter { it.top > subHead.bottom - 2 }
                val used = HashSet<OcrLine>()
                for (l in rows) {
                    if (l in used) continue
                    val label = l.text.replace(Regex("[0-9][0-9.,]*(?:[만억조경][0-9.,]*)*\\+?%?"), "").trim()
                    if (label.length < 2 || !label.any { it in '가'..'힣' }) continue
                    used += l
                    val inLine = num(l.text.substring(l.text.indexOf(label.take(1)).coerceAtLeast(0)))
                    val v = inLine ?: rows.filter { it !in used && Math.abs(it.cy - l.cy) < l.height * 0.7 && it.left > l.left }
                        .sortedBy { it.left }.firstOrNull { num(it.text) != null }?.also { used += it }?.let { num(it.text) }
                    if (v != null) subs += label to v
                }
            }
            RuneInfo(grade, name, level, atk, others, subs)
        }
    }
}
