package net.g1project.tmcalc

/** OCR이 돌려준 텍스트 한 줄과 화면상 위치(px). */
class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cy: Int get() = (top + bottom) / 2
    val height: Int get() = (bottom - top).coerceAtLeast(1)
}

class ParsedScreen(
    val pet: Pet?,
    val nameRaw: String?,
    val nameScore: Double,
    val level: Int?,
    val breaks: Int?,
    /** 기본 능력 (공, 방, 순, 체) */
    val stats: Array<Int?>,
    /** 괄호 안 "도감 대비" 값. "5 -" 처럼 대시만 있으면 0 */
    val deltas: Array<Int?>,
    /** 괄호 부분의 대략적인 화면 영역 (left, top, right, bottom) — 글자색으로 +/- 확인용 */
    val deltaBoxes: Array<IntArray?> = arrayOfNulls(4),
    /** "성장 평균" 숫자 (예: 4.11). 거래소 화면처럼 숫자가 없으면 null */
    val growthAvgs: Array<Double?> = arrayOfNulls(4),
    /** "성장 평균" 등급 글자 (예: SS++). 확실히 읽힌 경우만, 아니면 null */
    val screenGrades: Array<String?> = arrayOfNulls(4),
    /** 화면의 "총 성장 점수" (예: 96%). 게임이 직접 계산한 인게임 값이라 계산보다 정확하다 */
    val gameTotal: Int? = null,
) {
    val complete: Boolean get() = pet != null && level != null && stats.all { it != null }
}

/**
 * 소환수 정보 화면을 라벨 기준으로 해석한다.
 * 좌표를 고정으로 쓰지 않고 "Lv.", "공격력" 같은 글자를 기준점으로 삼기 때문에
 * 해상도나 창 위치가 달라도 동작한다.
 */
object OcrParser {

    private val STAT_LABELS = listOf("공격", "방어", "속도", "생명")
    private val LEVEL = Regex("""[Ll][Vv]\s*[.,·]?\s*(\d{1,3})""")
    private val BREAKS = Regex("""\(\s*\+\s*(\d{1,2})\s*\)?|\+\s*(\d{1,2})\s*\)""")
    private val PAREN = Regex("""\(\s*([+-]?\d+)\s*\)?""")
    private val DASH_ZERO = Regex("""^\s*\d+\s*[-–—]\s*(?!\d)""")
    private val INT = Regex("""(?<![\d.])\d+(?![\d.])""")
    /** "4,168" / OCR이 쉼표를 점으로 읽은 "4.168" 을 4168 로 */
    private val THOUSANDS = Regex("""(\d)[,.](\d{3})(?!\d)""")
    /** 성장 평균 숫자 (소수 둘째 자리) */
    private val GROWTH = Regex("""(?<![\d.])(\d{1,3}\.\d{2})(?!\d)""")
    /** 성장 평균 등급: 글자 그대로 읽혔을 때만 인정 ("S+t" 처럼 애매하면 버림) */
    private val GRADE = Regex("""^\s*(SS|S|A|B|C|D)(\+{0,2})\s*$""")
    /** 총 성장 점수 "96%" */
    private val PERCENT = Regex("""^\s*(\d{2,3})\s*%\s*$""")

    /** 정보 카드에 있지만 이름이 아닌 문구 (등급/속성/타입 태그, 항목 제목) */
    private val NOT_NAME = setOf(
        "전설", "유일", "영웅", "희귀", "고급", "일반", "신화",
        "불", "물", "바람", "땅", "빛", "어둠", "화염", "대지", "자연",
        "야수", "마법", "수중", "던전", "비행",
        "잠재력", "성장등급", "총성장점수", "총능력치점수", "기본능력치", "전투능력치", "거래소", "소환수",
    )

    /**
     * 이 글자가 들어 있으면 이름이 아니다. OCR이 옆 라벨과 붙여 읽는 경우가 있어서
     * ("총 성장 점수 총 능력치 점수") 통째로 비교하지 않고 포함 여부로 본다.
     */
    private val UI_WORDS = listOf("점수", "능력치", "잠재력", "성장등급", "거래소", "시세", "구매", "환생")

    /** 거래소 화면의 "[청랑]을 구매하시겠습니까?" — 이름이 가장 확실하게 적힌 곳 */
    private val BUY = Regex("""\[\s*([^\]]+?)\s*]\s*[을를]?\s*구매""")

    private fun isNameLike(text: String): Boolean {
        val k = PetDb.key(text)
        return k.any { it in '가'..'힣' } && k !in NOT_NAME && UI_WORDS.none { k.contains(it) } &&
            !LEVEL.containsMatchIn(text)
    }

    fun parse(lines: List<OcrLine>, db: PetDb): ParsedScreen {
        // 1) 능력치 표를 먼저 찾고, 그 위쪽의 정보 카드에서만 레벨/이름을 찾는다.
        //    (채팅창·메뉴·뒤에 깔린 카드 글자를 이름으로 잘못 읽지 않도록)
        val stats = arrayOfNulls<Int>(4)
        val deltas = arrayOfNulls<Int>(4)
        val deltaBoxes = arrayOfNulls<IntArray>(4)
        val growthAvgs = arrayOfNulls<Double>(4)
        val screenGrades = arrayOfNulls<String>(4)
        var anchor: OcrLine? = null // 능력치 표의 첫 라벨 줄 (공격력)
        STAT_LABELS.forEachIndexed { i, label ->
            for (labelLine in lines.filter { it.text.replace(" ", "").contains(label) }) {
                val rowLines = lines
                    .filter { it === labelLine || (Math.abs(it.cy - labelLine.cy) < labelLine.height * 0.6 && it.left >= labelLine.left) }
                    .sortedBy { it.left }
                val row = rowLines.joinToString(" ") { it.text }
                // 라벨(한글)을 지우면 "629 (55) 4.11 SS++" 형태가 남는다
                val rest = THOUSANDS.replace(row.replace(Regex("[가-힣]"), ""), "$1$2")
                val stat = INT.find(rest.substringBefore('('))?.value?.toIntOrNull() ?: continue
                stats[i] = stat
                if (anchor == null || labelLine.cy < anchor!!.cy) anchor = labelLine
                deltas[i] = PAREN.find(rest)?.groupValues?.get(1)?.toIntOrNull()
                    ?: if (DASH_ZERO.containsMatchIn(rest)) 0 else null
                deltaBoxes[i] = rowLines.firstOrNull { it.text.contains('(') }?.let { l ->
                    // 글자 폭이 대략 같다고 보고 "(" ~ ")" 구간만 잘라낸다 (옆의 등급 글자색 제외)
                    val n = l.text.length.coerceAtLeast(1)
                    val a = l.text.indexOf('(')
                    val b = l.text.indexOf(')', a).let { if (it < 0) n else it + 1 }
                    val w = l.right - l.left
                    intArrayOf(l.left + w * a / n, l.top, l.left + w * b / n, l.bottom)
                }
                // 괄호 뒤: "4.11 SS++" (성장 평균 숫자 + 등급)
                val tail = if (rest.contains(')')) rest.substringAfterLast(')') else rest.substringAfter(stat.toString())
                val gm = GROWTH.find(tail)
                growthAvgs[i] = gm?.value?.toDoubleOrNull()
                val gradeText = if (gm != null) tail.substring(gm.range.last + 1) else tail
                screenGrades[i] = GRADE.find(gradeText)?.let { it.groupValues[1] + it.groupValues[2] }
                break
            }
        }

        val above = anchor?.let { a -> lines.filter { it.bottom <= a.top } } ?: lines

        // 2) 레벨: 능력치 표 바로 위쪽에서 가장 가까운 "Lv. 30"
        val levelLine = above.filter { LEVEL.containsMatchIn(it.text) }.maxByOrNull { it.cy }
            ?: lines.firstOrNull { LEVEL.containsMatchIn(it.text) }
        val level = levelLine?.let { LEVEL.find(it.text)!!.groupValues[1].toIntOrNull() }
            ?.takeIf { it in 1..300 }

        // 3) 이름: "Lv." 줄보다 위에 있고 Lv. 이 끝나는 곳보다 왼쪽에서 시작하는 글자 중, UI 문구가 아닌 가장 가까운 줄.
        //    (짧은 이름은 Lv. 보다 왼쪽에 있어 세로줄이 안 겹칠 수 있다 — 거래소 "청랑")
        //    도감에 거의 같은 이름이 있으면(전설) 그걸 우선한다.
        val nameCandidates = above.filter { l ->
            levelLine != null && l !== levelLine && l.bottom <= levelLine.top + levelLine.height / 2 &&
                l.left < levelLine.right && isNameLike(l.text)
        }.sortedByDescending { it.cy }
        var pet: Pet? = null
        var nameScore = 0.0
        var nameLine: OcrLine? = null
        var nameRaw: String? = null
        // 3-0) 거래소 화면이면 "[이름]을 구매하시겠습니까?" 의 이름을 먼저 쓴다
        val bought = lines.firstNotNullOfOrNull { l -> BUY.find(l.text)?.groupValues?.get(1)?.takeIf { isNameLike(it) } }
        if (bought != null) {
            nameRaw = bought
            nameLine = nameCandidates.firstOrNull { PetDb.key(it.text) == PetDb.key(bought) }
            db.bestMatch(bought)?.let { (p, score) -> if (score >= 0.6) { pet = p; nameScore = score } }
        } else {
            for (l in nameCandidates) {
                val (p, score) = db.bestMatch(l.text) ?: continue
                if (score >= 0.8 && score > nameScore) { pet = p; nameScore = score; nameLine = l }
            }
            if (nameLine == null) {
                nameLine = nameCandidates.firstOrNull()
                nameLine?.let { l ->
                    db.bestMatch(l.text)?.let { (p, score) -> if (score >= 0.6) { pet = p; nameScore = score } }
                }
            }
            nameRaw = nameLine?.text
        }

        // 4) 돌파 "(+4)" 는 이름과 같은 줄에만 있다 (능력치의 "(+7)" 과 헷갈리지 않도록)
        val nameRow = nameLine?.let { n ->
            lines.filter { it === n || (Math.abs(it.cy - n.cy) < n.height * 0.6 && it.left >= n.left) }
        }.orEmpty()
        val breaks = nameRow.firstNotNullOfOrNull { l ->
            BREAKS.find(l.text)?.let { m -> (m.groupValues[1].ifEmpty { m.groupValues[2] }).toIntOrNull() }
        }

        return ParsedScreen(pet, nameRaw, nameScore, level, breaks, stats, deltas, deltaBoxes, growthAvgs, screenGrades,
            gameTotal(lines))
    }

    /** "총 성장 점수" 라벨(OCR이 "충/층 성장 점수"로 읽기도 함) 바로 아래, 왼쪽이 겹치는 "96%" */
    private fun gameTotal(lines: List<OcrLine>): Int? {
        // 채팅에 "성장점수" 글자가 있을 수 있으므로, 라벨 후보마다 바로 아래 % 를 찾아 가장 가까운 짝을 고른다
        val percents = lines.mapNotNull { l ->
            PERCENT.find(l.text)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 50..150 }?.let { l to it }
        }
        return lines.filter { it.text.replace(" ", "").contains("성장점수") }.flatMap { label ->
            percents.filter { (l, _) -> l.top >= label.top && l.left <= label.left + (label.right - label.left) / 2 }
                .map { (l, v) -> (l.cy - label.cy) to v }
        }.minByOrNull { it.first }?.second
    }
}
