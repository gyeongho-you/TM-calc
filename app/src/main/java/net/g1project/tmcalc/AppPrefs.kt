package net.g1project.tmcalc

import android.content.Context

/** 앱 설정 (폰 안에만 저장) */
object AppPrefs {
    private const val FILE = "settings"
    private const val GROWTH_AMP = "growthAmpPct"

    /** 내 캐릭터의 성장증폭 (0.08 = 8%). 입력 안 했으면 [Calculator.DEFAULT_GROWTH_AMP] */
    fun growthAmp(c: Context): Double =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getFloat(GROWTH_AMP, (Calculator.DEFAULT_GROWTH_AMP * 100).toFloat()).toDouble() / 100

    /** [pct] 는 % 단위 (8.5 = 8.5%). null 이면 기본값으로 */
    fun setGrowthAmp(c: Context, pct: Double?) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (pct == null) remove(GROWTH_AMP) else putFloat(GROWTH_AMP, pct.toFloat())
        }.apply()
    }

    private const val SHOW_COMBAT = "showCombat"

    /** 종 대비 전투 능력치를 보여 줄지 (기본 켜짐) */
    fun showCombat(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(SHOW_COMBAT, true)

    fun setShowCombat(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(SHOW_COMBAT, on).apply()
    }

    private const val WORST_BREAKS = "worstBreaks"

    /** 강화별 표를 최저치(소수점을 모두 낮게 잡은 경우)로 볼지 (기본 꺼짐) */
    fun worstBreaks(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(WORST_BREAKS, false)

    fun setWorstBreaks(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(WORST_BREAKS, on).apply()
    }

    private const val SHOW_LOWER = "showLowerBreaks"

    /** 강화별 표에서 현재 강보다 아래(0강~) 줄을 펼쳐 둘지 (기본 접힘) */
    fun showLowerBreaks(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(SHOW_LOWER, false)

    fun setShowLowerBreaks(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(SHOW_LOWER, on).apply()
    }

    /** 화면에 보일 % 글자 (8.0 → "8", 10.4 → "10.4") */
    fun pctText(amp: Double): String {
        val p = Math.round(amp * 1000) / 10.0
        return if (p == Math.floor(p)) p.toLong().toString() else p.toString()
    }
}
