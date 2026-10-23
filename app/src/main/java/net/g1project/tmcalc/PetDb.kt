package net.g1project.tmcalc

import org.json.JSONArray

/** 소환수S도감 시트 한 줄. 스탯 배열 순서는 항상 공, 방, 순, 체. */
class Pet(
    val name: String,
    val grade: String,
    val element: String,
    val type: String,
    /** 초기치(1렙) */
    val init: IntArray,
    /** 성장S등급 */
    val sGrowth: DoubleArray,
    /** 만렙S 능력치 (게임의 "도감" 기준값) */
    val maxS: IntArray,
)

/**
 * 도감 편집 창에 미리 채울 값 (계산 화면에서 읽은 것). 게임 도감 화면이면 초기치·성장률·태그까지 있다.
 * 화면 값이 있으면 도감에 있던 값보다 먼저 쓴다. [note] 는 성장률과 안 맞아 고친 칸.
 */
class PetDraft(val maxS: IntArray?, val init: IntArray?, val growth: DoubleArray?,
               val grade: String?, val element: String?, val type: String?, val note: String?)

class PetDb(val pets: List<Pet>) {

    private val byKey = pets.associateBy { key(it.name) }

    fun find(name: String): Pet? = byKey[key(name)]

    /** OCR로 읽은 문자열과 가장 비슷한 소환수와 유사도(0~1). */
    fun bestMatch(raw: String): Pair<Pet, Double>? {
        val k = key(raw)
        if (k.isEmpty()) return null
        byKey[k]?.let { return it to 1.0 }
        var best: Pet? = null
        var bestScore = 0.0
        for (p in pets) {
            val pk = key(p.name)
            val score = similarity(k, pk)
            if (score > bestScore) {
                bestScore = score
                best = p
            }
        }
        return best?.let { it to bestScore }
    }

    /** 입력 중인 이름에 대한 후보 목록. */
    fun suggest(raw: String, limit: Int = 6): List<Pet> {
        val k = key(raw)
        if (k.isEmpty()) return emptyList()
        return pets
            .map { p -> p to (if (key(p.name).contains(k)) 2.0 else similarity(k, key(p.name))) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    companion object {
        fun parse(json: String): PetDb {
            val arr = JSONArray(json)
            val list = ArrayList<Pet>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                // 앱 내장 형식(n, i, s, m)과 내보내기 형식(이름, 초기치, S성장률, 만렙S) 둘 다 읽는다
                fun arr(short: String, long: String) = o.optJSONArray(short) ?: o.getJSONArray(long)
                fun str(short: String, long: String) = o.optString(short).ifEmpty { o.optString(long) }
                val ini = arr("i", "초기치")
                val s = arr("s", "S성장률")
                val m = arr("m", "만렙S")
                list += Pet(
                    name = str("n", "이름").trim().ifEmpty { throw IllegalArgumentException("${i + 1}번째 소환수에 이름이 없습니다") },
                    // 앱에서 추가한 소환수는 등급/속성/타입을 비워 둘 수 있다
                    grade = str("g", "등급"),
                    element = str("e", "속성"),
                    type = str("t", "타입"),
                    init = IntArray(4) { ini.getInt(it) },
                    sGrowth = DoubleArray(4) { s.getDouble(it) },
                    maxS = IntArray(4) { m.getInt(it) },
                )
                check(list.last())?.let { throw IllegalArgumentException("${i + 1}번째 소환수: $it") }
            }
            return PetDb(list)
        }

        /**
         * 계산이 깨지지 않는 값인지 검사한다 (JSON 올리기로 들어온 파일은 믿을 수 없으므로).
         * 0/음수/NaN/엄청 큰 값이 들어가면 나눗셈이 무한대가 되어 앱이 죽을 수 있다. 문제가 없으면 null.
         */
        fun check(p: Pet): String? = when {
            PetDb.key(p.name).isEmpty() -> "이름이 비어 있습니다"
            p.name.length > MAX_TEXT -> "이름이 너무 깁니다"
            listOf(p.grade, p.element, p.type).any { it.length > MAX_TEXT } -> "등급/속성/타입이 너무 깁니다"
            // PC 화면(Swing)은 "<html>" 로 시작하는 글자를 HTML 로 그려서 <img src=http://...> 로 외부 접속을 시킬 수 있다
            listOf(p.name, p.grade, p.element, p.type).any { t -> t.any { it == '<' || it == '>' } } -> "이름/등급/속성/타입에 < > 는 쓸 수 없습니다"
            p.init.any { it !in 0..MAX_STAT } -> "초기치는 0 ~ $MAX_STAT 사이여야 합니다"
            p.maxS.any { it !in 1..MAX_STAT } -> "만렙S는 1 ~ $MAX_STAT 사이여야 합니다"
            p.sGrowth.any { !it.isFinite() || it <= 0 || it > MAX_GROWTH } -> "S성장률은 0 ~ $MAX_GROWTH 사이여야 합니다"
            else -> null
        }

        /**
         * 도감 값끼리 안 맞는 칸 (계산은 되지만 오타일 가능성이 큰 값). 게임 도감은 만렙S = 초기치 + S성장률 × 149 (±1) 라서
         * 2 넘게 다르면 알려 준다 (예: 하용이 방어 만렙S 586, 식대로면 485).
         */
        fun oddValues(p: Pet): List<String> = (0..3).mapNotNull { i ->
            val want = p.init[i] + p.sGrowth[i] * (Calculator.MAX_LEVEL - 1)
            if (Math.abs(p.maxS[i] - want) <= ODD_LIMIT) null
            else "${STAT_NAMES[i]} 만렙S ${p.maxS[i]} (초기치+성장률×149 = ${Math.round(want)})"
        }

        /** 화면에서 읽은 이름을 도감에 넣을 이름으로: 돌파 "(+4)" 만 빼고 숫자·띄어쓰기는 둔다 ("1세대의 전설") */
        fun displayName(raw: String): String =
            raw.replace(Regex("\\(.*?\\)|\\+\\d+"), "").filter { it in '가'..'힣' || it.isLetterOrDigit() || it == ' ' }
                .replace(Regex(" +"), " ").trim()

        /**
         * 도감에 추가할 값 (화면에서 바로 얻은 것). [notes] 는 읽은 값이 이상해서 성장률로 고친 칸.
         * 못 구한 값은 null.
         */
        class DexValues(val init: IntArray?, val growth: DoubleArray?, val maxS: IntArray?, val notes: List<String>)

        /**
         * 게임 도감 화면(또는 도감 대비가 모두 0 인 S 100% 개체)에서 초기치·S성장률·만렙S 를 구한다.
         * - 도감 대비가 모두 0("-") 이고 성장 평균 4개가 읽혔으면 그 성장 평균이 곧 S성장률
         * - 능력치가 초기치 추정(성장률 × 약 4) 근처면 LV.1 탭(초기치), 아니면 150레벨 값(만렙S)
         * - 만렙S 와 성장률이 있으면 초기치 = 만렙S − 성장률 × 149 (반올림 차이 0.5 안이라 정확)
         * - 그 초기치가 성장률로 본 값과 3 넘게 다르면 능력치를 잘못 읽은 것(예: 3849 → 3349)이라 성장률로 다시 계산
         * 성장률을 못 읽은 150레벨 화면은 만렙S(현재 − 도감 대비)만 준다.
         */
        fun dexFromScreen(level: Int?, stats: List<Int?>, deltas: List<Int?>, growth: List<Double?>): DexValues {
            val lv1 = Calculator.MAX_LEVEL - 1
            val g = growth.takeIf { gs -> gs.size == 4 && gs.all { it != null && it > 0 } && deltas.all { it == 0 } }
                ?.map { it!! }?.toDoubleArray()
            val st = stats.takeIf { s -> s.size == 4 && s.all { it != null && it > 0 } }?.map { it!! }
            if (g == null) {
                val m = if (level == Calculator.MAX_LEVEL && st != null && deltas.all { it != null })
                    IntArray(4) { st[it] - deltas[it]!! }.takeIf { a -> a.all { it > 0 } } else null
                return DexValues(null, null, m, emptyList())
            }
            val est = Calculator.estimate(g) ?: return DexValues(null, null, null, emptyList())
            val notes = ArrayList<String>()
            // LV.1 탭: 모든 능력치가 초기치 추정 근처
            val isLv1 = st != null && (0..3).all { st[it] in (est.initLo[it] - 3)..(est.initHi[it] + 3) }
            val init = IntArray(4); val maxS = IntArray(4)
            for (i in 0..3) {
                val guessInit = est.initLo[i]
                when {
                    st == null -> { init[i] = guessInit; maxS[i] = Math.round(init[i] + g[i] * lv1).toInt() }
                    isLv1 -> { init[i] = st[i]; maxS[i] = Math.round(st[i] + g[i] * lv1).toInt() }
                    else -> {
                        val fromMax = Math.round(st[i] - g[i] * lv1).toInt()
                        if (Math.abs(fromMax - guessInit) <= 3) { init[i] = fromMax; maxS[i] = st[i] }
                        else {
                            init[i] = guessInit; maxS[i] = Math.round(guessInit + g[i] * lv1).toInt()
                            notes += "${STAT_NAMES[i]} ${st[i]} → ${maxS[i]}"
                        }
                    }
                }
            }
            return DexValues(init, g, maxS, notes)
        }

        private const val ODD_LIMIT = 2.0
        private val STAT_NAMES = listOf("공격", "방어", "속도", "생명")

        private const val MAX_TEXT = 40
        private const val MAX_STAT = 1_000_000
        private const val MAX_GROWTH = 10_000.0

        /** 한글/영문/숫자만 남겨 비교용 키로 만든다 (공백, 괄호, 돌파 표기 제거). */
        fun key(s: String): String =
            s.replace(Regex("\\(.*?\\)|\\+\\d+"), "")
                .filter { it in '가'..'힣' || it.isLetter() }

        fun similarity(a: String, b: String): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val d = levenshtein(a, b)
            return 1.0 - d.toDouble() / maxOf(a.length, b.length)
        }

        /** 편집 거리. 한글 두 글자가 다르면 초성·중성·종성 중 다른 만큼만 센다 (화"흔"량 ↔ 화"혼"랑: 모음만 달라 1/3씩) */
        private fun levenshtein(a: String, b: String): Double {
            var prev = DoubleArray(b.length + 1) { it.toDouble() }
            var cur = DoubleArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i.toDouble()
                for (j in 1..b.length) {
                    val cost = charCost(a[i - 1], b[j - 1])
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        private fun charCost(x: Char, y: Char): Double {
            if (x == y) return 0.0
            if (x !in '가'..'힣' || y !in '가'..'힣') return 1.0
            val p = x - '가'; val q = y - '가'
            var diff = 0
            if (p / 588 != q / 588) diff++              // 초성
            if (p % 588 / 28 != q % 588 / 28) diff++    // 중성
            if (p % 28 != q % 28) diff++                // 종성
            return diff / 3.0
        }
    }
}
