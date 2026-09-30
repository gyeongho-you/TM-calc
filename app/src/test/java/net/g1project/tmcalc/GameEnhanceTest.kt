package net.g1project.tmcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 게임 강화 화면(강화 전 → 강화 후)을 옮겨 적은 자료로 강화 식과 등급 식을 검증한다.
 * 자료: 스크린샷 폴더의 예상.jpg, 결과.jpg 와 예상결과치 폴더의 캡처 8장 (2026-09-30)
 */
class GameEnhanceTest {

    private val db = PetDb.parse(File("src/main/assets/pets.json").readText())

    private class Case(
        val name: String, val from: Int, val to: Int,
        val before: IntArray, val after: IntArray,
        val gradesBefore: List<String>, val gradesAfter: List<String>,
    )

    private val cases = listOf(
        Case("차르가", 0, 1, intArrayOf(615, 345, 339, 4396), intArrayOf(621, 348, 343, 4440),
            listOf("S++", "S+", "S", "A++"), listOf("S++", "S++", "S+", "S")),
        Case("화혼랑", 3, 4, intArrayOf(605, 301, 511, 4037), intArrayOf(610, 304, 516, 4076),
            listOf("SS", "S+", "SS", "S++"), listOf("SS", "S++", "SS", "S++")),
        Case("이시스", 1, 2, intArrayOf(522, 276, 609, 3661), intArrayOf(527, 278, 615, 3698),
            listOf("S++", "S+", "S+", "S"), listOf("S++", "S++", "S+", "S+")),
        Case("조세르", 1, 2, intArrayOf(523, 266, 620, 3710), intArrayOf(528, 269, 626, 3746),
            listOf("S++", "A++", "S+", "S+"), listOf("S++", "S", "S++", "S++")),
        Case("로얄 가드 유니", 4, 5, intArrayOf(373, 679, 369, 4363), intArrayOf(376, 686, 372, 4405),
            listOf("SS", "SS+", "S++", "SS"), listOf("SS", "SS+", "SS", "SS+")),
    )

    /** 1% 식: 모든 칸이 게임과 1 이내, 20칸 중 13칸은 정확 (시트 0.95% 식은 10칸, 최대 3 차이) */
    @Test
    fun breaksMatchGameWithinOne() {
        var exact = 0
        for (c in cases) {
            val s = Calculator.statsAtBreaks(c.before, c.from, c.to)
            for (i in 0..3) {
                assertTrue("${c.name} ${c.from}→${c.to} stat$i: 예상 ${s[i]} 게임 ${c.after[i]}", Math.abs(s[i] - c.after[i]) <= 1)
                if (s[i] == c.after[i]) exact++
            }
        }
        assertEquals(13, exact)
    }

    /** 등급 = 능력치 ÷ 도감값: 강화 전·후 40칸 모두 게임 표시와 같다 */
    @Test
    fun gradesMatchGame() {
        for (c in cases) {
            val m = db.find(c.name)!!.maxS
            assertEquals("${c.name} 강화 전", c.gradesBefore, Calculator.statGrades(c.before, m))
            assertEquals("${c.name} 강화 후", c.gradesAfter, Calculator.statGrades(c.after, m))
        }
    }
}
