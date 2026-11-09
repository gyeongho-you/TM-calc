package net.g1project.tmcalc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** SS++ 를 넘는 계산기 전용 등급 SSS (×1.11) · SSS+ (×1.13) · SSS++ (×1.15) */
class BeyondGradeTest {

    @After fun reset() { Calculator.showBeyondGame = true }

    @Test
    fun statGrades() {
        val base = intArrayOf(1000, 1000, 1000, 1000)
        assertEquals(listOf("SS++", "SSS", "SSS+", "SSS++"), Calculator.statGrades(intArrayOf(1100, 1110, 1130, 1160), base))
        assertEquals("SSS", Calculator.totalGrade(111.0))
        assertEquals("SS++", Calculator.totalGrade(110.9))
        // 끄면 게임처럼 SS++ 까지만
        Calculator.showBeyondGame = false
        assertEquals(listOf("SS++", "SS++", "SS++", "SS++"), Calculator.statGrades(intArrayOf(1100, 1110, 1130, 1160), base))
        assertEquals("SS++", Calculator.totalGrade(116.0))
    }
}
