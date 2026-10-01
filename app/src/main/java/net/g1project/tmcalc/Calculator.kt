package net.g1project.tmcalc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * "골로스 성장률 계산기 Ver 1.3" 시트(성장률계산기 + 숨김 시트 '등급')의 수식을 그대로 옮긴 것.
 * 주석의 셀 주소는 원본 시트 기준.
 */
object Calculator {

    /** '등급'!B2:B14 배율, 열 G..T 순서. index 8 은 S(100) = 도감값 그대로. */
    val MULTS = doubleArrayOf(0.85, 0.87, 0.89, 0.91, 0.93, 0.95, 0.97, 0.99, 1.0, 1.01, 1.03, 1.05, 1.07, 1.09)
    val LABELS = listOf("C++", "B", "B+", "B++", "A", "A+", "A++", "S", "S(100)", "S+", "S++", "SS", "SS+", "SS++")

    /** 성장률 등급 IF 체인 (성장률계산기!B20). 시트의 라벨을 그대로 따른다. */
    private val STAT_CHAIN = listOf(13 to "SS++", 12 to "SS+", 11 to "SS", 10 to "S++", 9 to "S+", 7 to "S",
        6 to "A++", 5 to "A+", 4 to "A", 3 to "B+", 2 to "B", 1 to "C")

    /** 총성 등급 판정에 쓰이는 열 (성장률계산기!C26). */
    private val TOTAL_CHAIN = listOf(13, 12, 11, 10, 9, 7, 6)

    class Input(
        val pet: Pet,
        val level: Int,
        /** 현재 능력치 (공, 방, 순, 체) = 만렙S + 증감율 */
        val stats: IntArray,
        val breaks: Int,
        /** 등급·총 성장률의 기준 도감값. 화면에서 읽은 값(현재 - 도감 대비)이 있으면 그걸, 없으면 시트 만렙S */
        val base: IntArray = pet.maxS,
    ) {
        /** 시트 입력칸 (3)~(6) 증감율 */
        val deltas: IntArray get() = IntArray(4) { stats[it] - pet.maxS[it] }
    }

    class BreakRow(val n: Int, val stats: IntArray, val growth: DoubleArray, val grades: List<String>, val total: Double)

    class Result(
        val growth: DoubleArray,        // F13:F16
        val statGrades: List<String>,   // B20:B23
        val percentile: DoubleArray,    // C20:C23
        val realTotal: Double,          // F20
        val realTotalGrade: String,     // F21
        val gameTotal: Double,          // F22
        val gameTotalGrade: String,     // F23
        val myTotal: Double,            // D25
        val curGrade: String,           // C26
        val curGradeTotal: Double?,     // D26
        val nextGrowthPct: Double,      // C27
        val nextGrowthTotal: Double?,   // D27
        val nextGrade: String,          // C28
        val nextGradeTotal: Double?,    // D28
        val toNextGrowth: Double?,      // D29
        val toNextGrade: Double?,       // D30
        val breakRows: List<BreakRow>,  // B34:F47
    )

    /** 성장률 부분만: 도감 값만 있으면 되므로 도감에 없는 소환수도 계산 가능 */
    class Total(val percentile: DoubleArray, val realTotal: Double, val realTotalGrade: String,
                val gameTotal: Double, val gameTotalGrade: String)

    /** 성장률계산기!C20:C23, F20:F23. base = 도감 값(= 현재 능력치 - 도감 대비). */
    fun total(stats: IntArray, base: IntArray): Total {
        val ratio3 = DoubleArray(4) { roundDown(stats[it].toDouble() / base[it], 3) } // '등급'!B19:B22
        val ratio2 = DoubleArray(4) { roundDown(stats[it].toDouble() / base[it], 2) } // '등급'!C19:C22
        val real = fix(ratio3.average() * 100)
        val game = fix(roundDown(ratio2.average(), 2) * 100)
        return Total(DoubleArray(4) { fix(ratio3[it] * 100) }, real, totalGrade(real), game, totalGrade(game))
    }

    /** 실제 총 성장률(%)이 들어갈 수 있는 범위 */
    class TotalRange(val min: Double, val max: Double)

    /**
     * 화면의 인게임 총 성장 점수 [game]% 와 맞는 실제 총 성장률 범위.
     *
     * 게임은 도감값을 소수로 들고 있고 화면에는 반올림한 정수만 보여 준다("도감 대비"도 그 정수 기준).
     * 그래서 [base] 로 계산한 인게임 값이 게임 표시와 1% 어긋날 수 있다 (능력치마다 1% 단위로 버리기 때문).
     * 진짜 도감값을 base-0.5 ~ base+0.5 로 보고, 능력치별로 나올 수 있는 (ROUNDDOWN 3자리, 2자리) 값을 모두 따져
     * 인게임 값이 [game] 이 되는 경우만 남긴다. 맞는 경우가 없으면 null (숫자를 잘못 읽었거나 입력이 틀림).
     */
    fun realRangeForGame(stats: IntArray, base: IntArray, game: Int): TotalRange? {
        // 성장률(현재/도감)은 실제로 0.8~1.2 안팎. 채팅 글자 등을 잘못 읽어 말도 안 되는 숫자가 들어오면
        // 경우의 수가 폭발해 앱이 멈출 수 있으므로 계산하지 않는다
        if ((0..3).any { base[it] < 1 || stats[it].toDouble() / base[it] !in 0.3..3.0 }) return null
        // 인게임 값 합(% 정수) → 실제 값 합(‰ 정수)의 (최소, 최대)
        var dp = mapOf(0 to (0 to 0))
        for (i in 0..3) {
            val opts = HashMap<Int, Pair<Int, Int>>() // 인게임 %(2자리) → 실제 ‰(3자리) 최소, 최대
            for (k in 0 until RANGE_STEPS) {
                val x = base[i] - 0.5 + k / RANGE_STEPS.toDouble()
                if (x <= 0) continue
                val q = stats[i] / x
                val r3 = Math.round(roundDown(q, 3) * 1000).toInt()
                val r2 = Math.round(roundDown(q, 2) * 100).toInt()
                val o = opts[r2]
                opts[r2] = if (o == null) r3 to r3 else minOf(o.first, r3) to maxOf(o.second, r3)
            }
            val next = HashMap<Int, Pair<Int, Int>>()
            for ((sum, mm) in dp) for ((r2, rr) in opts) {
                val key = sum + r2
                val lo = mm.first + rr.first; val hi = mm.second + rr.second
                val o = next[key]
                next[key] = if (o == null) lo to hi else minOf(o.first, lo) to maxOf(o.second, hi)
            }
            dp = next
        }
        // 인게임 = ROUNDDOWN(평균, 2) × 100 = 합 ÷ 4 버림
        val ok = dp.filterKeys { Math.floorDiv(it, 4) == game }.values
        if (ok.isEmpty()) return null
        return TotalRange(fix(ok.minOf { it.first } / 40.0), fix(ok.maxOf { it.second } / 40.0))
    }

    fun compute(input: Input): Result {
        val p = input.pet
        val lv1 = (input.level - 1).coerceAtLeast(1).toDouble()
        val cur = input.stats

        val growth = DoubleArray(4) { (cur[it] - p.init[it]) / lv1 }
        val statGrades = statGrades(cur, input.base)

        val t = total(cur, input.base)

        val myTotal = totalOf(cur)
        val baseTotal = totalOf(p.maxS) // '등급'!O15
        val tot = DoubleArray(MULTS.size) { k -> if (k == 8) baseTotal else round(baseTotal * MULTS[k], 1) }

        val curIdx = TOTAL_CHAIN.firstOrNull { myTotal >= tot[it] }
        val curGradeTotal = curIdx?.let { tot[it] }
        val nextIdx: Int? = when {
            curIdx == null -> null
            curIdx == 13 -> -1 // MAX
            else -> TOTAL_CHAIN[TOTAL_CHAIN.indexOf(curIdx) - 1]
        }
        val nextGradeTotal = nextIdx?.takeIf { it >= 0 }?.let { tot[it] }
        val nextGrowthTotal = curGradeTotal?.let { round(it * 1.01, 1) }

        val breakRows = (1..5).map { n ->
            val s = statsAtBreaks(cur, input.breaks, input.breaks + n)
            val g = DoubleArray(4) { i -> round((s[i] - p.init[i]) / lv1, 2) }
            BreakRow(n, s, g, statGrades(s, input.base), totalOf(s))
        }

        return Result(
            growth = growth,
            statGrades = statGrades,
            percentile = t.percentile,
            realTotal = t.realTotal,
            realTotalGrade = t.realTotalGrade,
            gameTotal = t.gameTotal,
            gameTotalGrade = t.gameTotalGrade,
            myTotal = myTotal,
            curGrade = curIdx?.let { LABELS[it] } ?: "X",
            curGradeTotal = curGradeTotal,
            nextGrowthPct = t.gameTotal + 1,
            nextGrowthTotal = nextGrowthTotal,
            nextGrade = when (nextIdx) { null -> "X"; -1 -> "MAX"; else -> LABELS[nextIdx] },
            nextGradeTotal = nextGradeTotal,
            toNextGrowth = if (curGradeTotal != null && nextGrowthTotal != null && nextGrowthTotal != curGradeTotal)
                (myTotal - curGradeTotal) / (nextGrowthTotal - curGradeTotal) else null,
            toNextGrade = if (curGradeTotal != null && nextGradeTotal != null)
                (myTotal - curGradeTotal) / (nextGradeTotal - curGradeTotal) else null,
            breakRows = breakRows,
        )
    }

    /**
     * 지금 [breaks]강인 소환수를 [target]강까지 했을 때 능력치.
     * 게임 강화 화면(5마리 20칸)과 맞춘 식: 강화 1번마다 강화 안 한 능력치의 1%.
     *   목표 = ROUND(현재 ÷ (1 + 1%×현재강) × (1 + 1%×목표강))
     * 원래 시트는 0.95% 에 뺄셈 근사(현재 - 현재×0.95%×현재강)라 1~3 씩 낮게 나왔다.
     * 게임은 소수까지 들고 있고 화면엔 정수만 보여서, 이 식도 게임과 1 차이 날 수 있다 (20칸 중 13칸 정확).
     */
    fun statsAtBreaks(cur: IntArray, breaks: Int, target: Int): IntArray = IntArray(4) { i ->
        if (target == breaks) cur[i]
        else round(cur[i] / (1 + BREAK_RATE * breaks) * (1 + BREAK_RATE * target), 0).toInt()
    }

    /** 게임의 "총 능력치 점수" = 공 + 방 + 순 + 체/10 */
    fun abilityScore(s: IntArray): Double = totalOf(s)

    /**
     * 종 대비 전투: 이 개체의 전투 능력치가 같은 종 도감(S 100%) 개체보다 몇 % 높은지.
     * [pct] 는 공·방·순·체, [avg] 는 그 평균. [approx] 면 곡선을 확인한 범위 밖이라 대략값.
     */
    class Combat(val pct: DoubleArray, val avg: Double, val approx: Boolean)

    /**
     * 게임의 전투 능력치 = 기본 능력치 × 캐릭터 배율 × (1 + 성장증폭 × [scoreFactor](총 능력치 점수)).
     * 성장증폭은 캐릭터 능력치라 사람마다 다르다 ([amp], 0.08 = 8%). 캐릭터 배율은 나눗셈에서 지워진다:
     *   능력치별 = (능력치 ÷ 도감값) × (1 + 증폭·u(총능)) ÷ (1 + 증폭·u(도감 총능)) − 1
     * 총능이 곡선을 믿기 어려운 범위 밖이면 null.
     */
    fun combat(stats: IntArray, base: IntArray, amp: Double = DEFAULT_GROWTH_AMP): Combat? {
        if (base.any { it < 1 }) return null
        val s = totalOf(stats); val s0 = totalOf(base)
        if (s !in COMBAT_LIMIT || s0 !in COMBAT_LIMIT) return null
        val a = amp.coerceIn(0.0, 1.0)
        val bonus = (1 + a * scoreFactor(s)) / (1 + a * scoreFactor(s0))
        val pct = DoubleArray(4) { (stats[it].toDouble() / base[it] * bonus - 1) * 100 }
        // 캐릭터마다 다르던 부분은 성장증폭으로 반영되므로, 실측한 총능 범위 밖일 때만 대략값
        val approx = s !in COMBAT_CHECKED || s0 !in COMBAT_CHECKED
        return Combat(pct, pct.average(), approx)
    }

    /** 성장증폭을 모를 때 쓰는 값 (설정에서 바꿀 수 있음) */
    const val DEFAULT_GROWTH_AMP = 0.08

    /**
     * 성장증폭에 곱해지는 총능 값 u(총능). 1500 → 2.0, 1700 → 7.3, 1880 → 34.2.
     * 성장증폭 9.4% 캐릭터 2개(15마리)와 10.4% 캐릭터(31마리)의 곡선 차이에서 구했고,
     * 같은 캐릭터를 11.6% 로 올렸을 때 변화(+2.0%, +5.4%, +9.0%)를 0.02% 안으로 맞혔다.
     */
    fun scoreFactor(score: Double): Double = (combatCurve(score) / AMP_BASE - 1) / AMP_REF

    /** combatCurve 는 성장증폭 [AMP_REF] 캐릭터 기준이고, 그 안의 '1' 부분이 [AMP_BASE] */
    private const val AMP_REF = 0.104
    private const val AMP_BASE = 9230.8

    /**
     * 성장증폭 10.4% 캐릭터의 총능별 전투 배율 (크기는 의미 없고 비율만 쓴다).
     * 2026-10 게임 화면 28마리(총능 1500.9~1881.2, 여러 종·강화·스킬 코어)로 맞춘 6차식, 오차 0.01% 이내.
     * 종·잠재력·총 성장 %·강화 수와는 상관없고 총능만 따른다.
     */
    fun combatCurve(score: Double): Double {
        val x = (score - 1700) / 100
        var y = 0.0
        for (k in COMBAT_COEF.indices.reversed()) y = y * x + COMBAT_COEF[k]
        return y
    }

    private val COMBAT_COEF = doubleArrayOf(16284.006, 5595.4323, 2576.5586, 781.85629, 180.95972, 38.188918, 5.5675793)

    /** 실측으로 확인한 총능 범위 */
    private val COMBAT_CHECKED = 1500.0..1882.0

    /** 이 밖은 6차식이 엉뚱하게 휠 수 있어 계산하지 않는다 */
    private val COMBAT_LIMIT = 1480.0..1920.0

    /**
     * 능력치별 등급 = 현재 능력치 ÷ 도감값 을 배율(S+ 1.01, S++ 1.03, SS 1.05 ...)과 비교.
     * 게임 화면 등급 40칸(강화 전·후)과 모두 일치. 원래 시트는 성장률 (현재-초기치)/149 로 비교해서
     * 경계선 근처에서 한 칸씩 어긋났다 (예: 로얄 가드 유니 방어력 686 → 시트 SS++, 게임 SS+).
     * 도감값은 화면의 "현재 - 도감 대비"로도 알 수 있어서 도감에 없는 소환수도 똑같이 계산된다.
     */
    fun statGrades(stats: IntArray, base: IntArray): List<String> = List(4) { i ->
        val q = fix(stats[i].toDouble() / base[i])
        STAT_CHAIN.firstOrNull { q >= MULTS[it.first] }?.second ?: "D"
    }

    /** 시트 도감(만렙S)과 강화가 기준으로 하는 레벨 */
    const val MAX_LEVEL = 150

    /** 강화 1번당 오르는 비율 (게임 강화 화면 기준) */
    private const val BREAK_RATE = 0.01

    /** realRangeForGame 에서 도감값 ±0.5 구간을 나누는 칸 수 (도감값이 작은 저레벨도 놓치지 않을 만큼) */
    private const val RANGE_STEPS = 1000

    /**
     * 성장률계산기!F21 / F23. 원본은 91% 미만에서 '등급'!I11(성장률 값, 3 안팎)과 비교하므로
     * 사실상 91% 미만은 모두 "B+" 가 된다. 시트와 같은 결과를 내도록 그대로 따른다.
     */
    fun totalGrade(v: Double): String = when {
        v >= 109 -> "SS++"
        v >= 107 -> "SS+"
        v >= 105 -> "SS"
        v >= 103 -> "S++"
        v >= 101 -> "S+"
        v >= 99 -> "S"
        v >= 97 -> "A++"
        v >= 95 -> "A+"
        v >= 93 -> "A"
        v >= 91 -> "B++"
        else -> "B+"
    }

    private fun totalOf(s: IntArray): Double = fix(s[0] + s[1] + s[2] + s[3] / 10.0)

    /** 엑셀처럼 15자리 유효숫자로 정리해 부동소수 오차를 없앤다. */
    private fun bd(x: Double): BigDecimal = BigDecimal(x).round(MathContext(15, RoundingMode.HALF_EVEN))

    private fun fix(x: Double): Double = bd(x).toDouble()

    /** 엑셀 ROUND (0에서 먼 쪽으로 반올림) */
    fun round(x: Double, digits: Int): Double = bd(x).setScale(digits, RoundingMode.HALF_UP).toDouble()

    /** 엑셀 ROUNDDOWN (0 쪽으로 버림) */
    fun roundDown(x: Double, digits: Int): Double = bd(x).setScale(digits, RoundingMode.DOWN).toDouble()
}
