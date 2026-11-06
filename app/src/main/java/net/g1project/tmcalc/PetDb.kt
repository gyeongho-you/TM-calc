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

/** 도감 목록 정렬: 도감 만렙S(S 100%, 150레벨) 값 기준. [label] 은 고르기 목록에 보일 글자 */
enum class PetSort(val label: String) {
    DEFAULT("기본 순서"),
    SCORE_DESC("총능 높은순"), SCORE_ASC("총능 낮은순"),
    ATK_DESC("공격 높은순"), ATK_ASC("공격 낮은순"),
    DEF_DESC("방어 높은순"), DEF_ASC("방어 낮은순"),
    SPD_DESC("속도 높은순"), SPD_ASC("속도 낮은순"),
    HP_DESC("체력 높은순"), HP_ASC("체력 낮은순");

    /** 능력치 정렬이면 그 능력치 번호 (공 0 · 방 1 · 순 2 · 체 3), 아니면 null */
    val stat: Int? get() = when (this) {
        ATK_DESC, ATK_ASC -> 0; DEF_DESC, DEF_ASC -> 1; SPD_DESC, SPD_ASC -> 2; HP_DESC, HP_ASC -> 3
        else -> null
    }

    /**
     * 정렬에 쓰는 값 (기본 순서면 null). [amp] 를 주면 능력치를 실전투로:
     * 도감 능력치 × (1 + 성장증폭 × u(그 종 도감 총능)). 캐릭터 배율은 모든 종에 같아서 순서에는 영향이 없다.
     */
    fun key(p: Pet, amp: Double? = null): Double? = when (this) {
        DEFAULT -> null
        SCORE_DESC, SCORE_ASC -> Calculator.abilityScore(p.maxS)
        else -> p.maxS[stat!!] * (amp?.let { realBonus(p, it) } ?: 1.0)
    }

    /** 같은 값이면 원래 순서를 지킨다 (stable sort) */
    fun sort(list: List<Pet>, amp: Double? = null): List<Pet> = when {
        this == DEFAULT -> list
        name.endsWith("DESC") -> list.sortedByDescending { key(it, amp)!! }
        else -> list.sortedBy { key(it, amp)!! }
    }

    companion object {
        /** 그 종 도감(S 100%) 개체의 총능 보너스 배율: 1 + 성장증폭 × u(총능) */
        fun realBonus(p: Pet, amp: Double): Double =
            1 + amp.coerceIn(0.0, 1.0) * Calculator.scoreFactor(Calculator.abilityScore(p.maxS).coerceIn(1480.0, 1920.0))
    }
}

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

        /**
         * 내 도감 중 기본 도감과 값이 똑같아진 것(기본 도감이 업데이트돼서 내가 고친 값과 같아짐)을 뺀다.
         * 숫자(초기치·성장률·만렙S)가 같고, 등급/속성/타입은 같거나 내 쪽이 비어 있으면 같다고 본다.
         * 그래야 [수정함] 표시가 사라지고, 다음에 기본 도감이 또 바뀌면 그 값을 그대로 받는다.
         */
        fun pruneSameAsBase(user: List<Pet>, base: List<Pet>): List<Pet> {
            val byKey = base.associateBy { key(it.name) }
            fun tag(mine: String, b: String) = mine.isBlank() || mine == b
            return user.filterNot { u ->
                val b = byKey[key(u.name)] ?: return@filterNot false
                u.init.contentEquals(b.init) && u.sGrowth.contentEquals(b.sGrowth) && u.maxS.contentEquals(b.maxS) &&
                    tag(u.grade, b.grade) && tag(u.element, b.element) && tag(u.type, b.type)
            }
        }

        /** 화면에서 읽은 이름을 도감에 넣을 이름으로: 돌파 "(+4)" 만 빼고 숫자·띄어쓰기는 둔다 ("1세대의 전설") */
        fun displayName(raw: String): String =
            raw.replace(STRIP, "").filter { it in '가'..'힣' || it.isLetterOrDigit() || it == ' ' || it == '(' || it == ')' }
                .replace(Regex(" +"), " ").replace(" (", "(").trim()

        /**
         * 지울 괄호: 돌파 "(+4)" 등은 지우고, 등급 괄호 "(전설)" 은 남긴다.
         * 같은 이름에 등급만 다른 소환수("갓독 성요한(전설)")가 있어서 등급까지 이름으로 본다.
         */
        private val STRIP = Regex("""\((?!\s*(?:전설|유일|영웅|희귀|고급|일반|신화)\s*\))[^)]*\)|\+\d+""")

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
            s.replace(STRIP, "")
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

/**
 * 저장해 둔 소환수 (프리셋). 계산 화면의 값을 그대로 남겨서 누르면 다시 볼 수 있고, 비교 기준으로도 쓴다.
 * [base] 는 그 레벨의 도감값(현재 − 도감 대비). 강을 바꿔 볼 때(강화 예상)와 종 대비 계산에 쓴다.
 */
class Preset(
    val label: String,
    val name: String,
    val level: Int,
    val breaks: Int,
    val stats: IntArray,
    val base: IntArray,
    val gameTotal: Int? = null,
    /** 사용자가 단 메모 ("PVP용", "재료 후보" 등) */
    val memo: String = "",
) {
    /** 도감 대비 (현재 − 도감값) */
    val deltas: IntArray get() = IntArray(4) { stats[it] - base[it] }

    fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
        put("label", label); put("name", name); put("level", level); put("breaks", breaks)
        put("stats", org.json.JSONArray(stats.toList())); put("base", org.json.JSONArray(base.toList()))
        gameTotal?.let { put("game", it) }
        if (memo.isNotEmpty()) put("memo", memo)
    }

    /** 이름·메모만 바꾼 복사본 */
    fun renamed(label: String, memo: String) = Preset(label.trim().take(MAX_LABEL), name, level, breaks, stats, base, gameTotal, memo.trim().take(MAX_MEMO))

    companion object {
        const val MAX_COUNT = 200
        const val MAX_LABEL = 40
        const val MAX_MEMO = 60

        fun listToJson(list: List<Preset>): String = org.json.JSONArray(list.map { it.toJson() }).toString()

        /** 잘못된 항목은 건너뛴다 (손으로 고친 파일이어도 앱이 죽지 않게) */
        fun listFromJson(json: String): List<Preset> {
            val arr = runCatching { org.json.JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until minOf(arr.length(), MAX_COUNT)).mapNotNull { i ->
                runCatching {
                    val o = arr.getJSONObject(i)
                    val s = o.getJSONArray("stats"); val b = o.getJSONArray("base")
                    Preset(o.getString("label").trim().take(MAX_LABEL), o.getString("name").take(40), o.getInt("level"), o.getInt("breaks"),
                        IntArray(4) { s.getInt(it) }, IntArray(4) { b.getInt(it) }, if (o.has("game")) o.getInt("game") else null,
                        o.optString("memo").trim().take(MAX_MEMO))
                }.getOrNull()?.takeIf { p ->
                    p.label.isNotEmpty() && p.level in 1..300 && p.breaks in 0..Calculator.MAX_BREAKS && p.stats.all { it in 1..1_000_000 } && p.base.all { it in 1..1_000_000 }
                }
            }
        }

        /** 이름이 겹치지 않게 (목록에서 이름으로 구분하므로). 겹치면 뒤에 (2), (3) */
        fun uniqueLabel(label: String, existing: List<Preset>): String {
            val base = label.trim().ifEmpty { "이름 없음" }.take(MAX_LABEL - 5)
            var l = base; var n = 2
            while (existing.any { it.label == l }) l = "$base (${n++})"
            return l
        }

        /**
         * 파일에서 올린 목록을 지금 목록 뒤에 붙인다. 똑같은 소환수(이름·능력치·도감값·강이 같음)는 건너뛰고,
         * 이름만 겹치면 (2) 를 붙인다. 돌려주는 값: (합친 목록, 새로 넣은 수)
         */
        fun merge(current: List<Preset>, incoming: List<Preset>): Pair<List<Preset>, Int> {
            val out = current.toMutableList(); var added = 0
            for (p in incoming) {
                if (out.size >= MAX_COUNT) break
                if (out.any { it.name == p.name && it.breaks == p.breaks && it.level == p.level && it.stats.contentEquals(p.stats) && it.base.contentEquals(p.base) }) continue
                out += p.renamed(uniqueLabel(p.label, out), p.memo); added++
            }
            return out to added
        }

        /** 기본 이름: "화혼랑 3강 103%" (같은 이름이 있으면 뒤에 (2), (3)) */
        fun defaultLabel(name: String, breaks: Int, gameTotal: Int?, existing: List<Preset>): String {
            val base = (name.ifBlank { "이름 없음" } + " ${breaks}강" + (gameTotal?.let { " $it%" } ?: "")).take(36)
            var label = base; var n = 2
            while (existing.any { it.label == label }) label = "$base (${n++})"
            return label
        }
    }
}
