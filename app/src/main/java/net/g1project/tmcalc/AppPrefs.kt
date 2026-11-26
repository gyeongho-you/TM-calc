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

    private const val RUNE_MODE = "runeMode"

    /** 게임 위 버튼 최적화 모드: 룬 비교 화면이면 바꾸면 몇 %, 그 밖의 화면이면 내 스펙 입력 (끄면 성장률 계산) */
    fun runeMode(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(RUNE_MODE, false)

    fun setRuneMode(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(RUNE_MODE, on).apply()
    }

    private const val SHOW_COMBAT = "showCombat"

    /** 종 대비 전투 능력치를 보여 줄지 (기본 켜짐) */
    fun showCombat(c: Context): Boolean =
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(SHOW_COMBAT, true)

    fun setShowCombat(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(SHOW_COMBAT, on).apply()
    }

    private const val SHOW_SSS = "showSss"

    /** SS++ 를 넘는 능력치를 계산기 전용 등급 SSS(무지개)로 보여 줄지 (기본 꺼짐, 아는 사람만 켠다) */
    fun showSss(c: Context): Boolean = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(SHOW_SSS, false)

    fun setShowSss(c: Context, on: Boolean) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean(SHOW_SSS, on).apply()
    }

    private const val LAB_ACTIVE = "labActive"

    /** 진행 중인 연구실 세션 id. 있으면 레이어 버튼이 '연구'가 되어 누르면 기록 입력 창이 열린다 */
    fun labActive(c: Context): String? = c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(LAB_ACTIVE, null)

    fun setLabActive(c: Context, id: String?) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().apply {
            if (id.isNullOrBlank()) remove(LAB_ACTIVE) else putString(LAB_ACTIVE, id)
        }.apply()
    }

    private const val PRESETS = "presets"

    /** 저장한 소환수 (프리셋). 폰 안에만 저장 */
    fun presets(c: Context): List<Preset> =
        Preset.listFromJson(c.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(PRESETS, null) ?: "[]")

    fun setPresets(c: Context, list: List<Preset>) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(PRESETS, Preset.listToJson(list.take(Preset.MAX_COUNT))).apply()
    }

    /** 화면에 보일 % 글자 (8.0 → "8", 10.4 → "10.4") */
    fun pctText(amp: Double): String {
        val p = Math.round(amp * 1000) / 10.0
        return if (p == Math.floor(p)) p.toLong().toString() else p.toString()
    }
}
