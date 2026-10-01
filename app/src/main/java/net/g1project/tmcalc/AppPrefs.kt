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

    /** 화면에 보일 % 글자 (8.0 → "8", 10.4 → "10.4") */
    fun pctText(amp: Double): String {
        val p = Math.round(amp * 1000) / 10.0
        return if (p == Math.floor(p)) p.toLong().toString() else p.toString()
    }
}
