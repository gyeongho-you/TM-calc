package net.g1project.tmcalc

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** SS++ 를 넘는 능력치(×1.11 이상): 켜면 계산기 전용 등급 EX · EX+ · EX++, 끄면(기본) 게임처럼 SS++ */
class BeyondGradeTest {

    @org.junit.Before fun on() { Calculator.showBeyondGame = true }
    @After fun reset() { Calculator.showBeyondGame = false }

    @Test
    fun statGrades() {
        val base = intArrayOf(1000, 1000, 1000, 1000)
        assertEquals(listOf("SS++", "EX", "EX+", "EX++"), Calculator.statGrades(intArrayOf(1100, 1110, 1130, 1160), base))
        assertEquals("EX", Calculator.totalGrade(111.0))
        assertEquals("SS++", Calculator.totalGrade(110.9))
        assertTrue(Calculator.isBeyondGame("EX"))
        Calculator.showBeyondGame = false
        assertEquals(listOf("SS++", "SS++", "SS++", "SS++"), Calculator.statGrades(intArrayOf(1100, 1110, 1130, 1160), base))
        assertEquals("SS++", Calculator.totalGrade(116.0))
    }
}
