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
            p.init.any { it !in 0..MAX_STAT } -> "초기치는 0 ~ $MAX_STAT 사이여야 합니다"
            p.maxS.any { it !in 1..MAX_STAT } -> "만렙S는 1 ~ $MAX_STAT 사이여야 합니다"
            p.sGrowth.any { !it.isFinite() || it <= 0 || it > MAX_GROWTH } -> "S성장률은 0 ~ $MAX_GROWTH 사이여야 합니다"
            else -> null
        }

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

        private fun levenshtein(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                val t = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }
    }
}
