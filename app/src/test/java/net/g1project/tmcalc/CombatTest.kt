package net.g1project.tmcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 종 대비 전투 (게임 전투 능력치 화면 실측 기준) */
class CombatTest {

    /** 게임 화면: (총능, 공격력 전투 ÷ 기본) — 2026-10-01 같은 캐릭터 상태 */
    private val measured = listOf(
        1500.9 to 381.3e4 / 341, 1554.6 to 412.0e4 / 349, 1608.6 to 604.3e4 / 471, 1692.7 to 794.5e4 / 500,
        1718.4 to 931.2e4 / 535, 1747.1 to 1180.8e4 / 603, 1794.4 to 1274.4e4 / 516, 1851.1 to 1880.4e4 / 543,
        1881.2 to 2325.1e4 / 548,
    )

    @Test
    fun curveMatchesGameRatios() {
        // 캐릭터 배율은 모르니 비율만 본다: 곡선 비 = 게임 배율 비 (0.1% 이내)
        val (s0, r0) = measured.first { it.first == 1718.4 }
        for ((s, r) in measured) {
            val want = r / r0
            val got = Calculator.combatCurve(s) / Calculator.combatCurve(s0)
            assertEquals("총능 $s", want, got, want * 0.001)
        }
    }

    @Test
    fun snakeVsSpecies() {
        // 뱀뱀이 603 410 296 4381, 도감 601 395 291 4336 → 공 +12, 방 +16, 속 +14, 생 +13, 평균 +13.5
        val c = Calculator.combat(intArrayOf(603, 410, 296, 4381), intArrayOf(601, 395, 291, 4336))!!
        assertEquals(11.9, c.pct[0], 0.1)
        assertEquals(15.8, c.pct[1], 0.1)
        assertEquals(13.5, c.pct[2], 0.1)
        assertEquals(12.7, c.pct[3], 0.1)
        assertEquals(13.5, c.avg, 0.1)
        assertFalse(c.approx)
    }

    @Test
    fun standardIsZero() {
        val base = intArrayOf(601, 395, 291, 4336)
        assertEquals(0.0, Calculator.combat(base, base)!!.avg, 1e-9)
    }

    @Test
    fun outsideCheckedRange() {
        // +5강처럼 도감과 100점 넘게 차이 나면 대략값
        val c = Calculator.combat(intArrayOf(629, 297, 513, 4189), intArrayOf(574, 294, 485, 3904))!!
        assertTrue(c.approx)
        // 곡선을 믿기 어려운 점수는 계산하지 않음
        assertNull(Calculator.combat(intArrayOf(200, 100, 100, 1000), intArrayOf(601, 395, 291, 4336)))
    }
}
