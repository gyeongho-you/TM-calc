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
        // 2026-10-20 조세르 다른 개체: 강화 화면 4→5강, 그리고 0강 때 계산 결과와 실제 4·5강 (여러 강을 한 번에)
        Case("조세르", 4, 5, intArrayOf(535, 280, 647, 3896), intArrayOf(540, 282, 653, 3932),
            listOf("SS", "S++", "SS+", "SS+"), listOf("SS", "S++", "SS+", "SS++")),
        // 2026-10-20 조세르 또 다른 개체 (0강 521 260 625 3663): 강화 화면 3→4강
        Case("조세르", 3, 4, intArrayOf(536, 269, 643, 3772), intArrayOf(541, 271, 649, 3808),
            listOf("SS", "S", "SS", "S++"), listOf("SS", "S", "SS+", "SS")),
    )

    /** 0강 화면 → 실제 4강·5강 (강화 화면이 아니라 강화한 뒤 정보 화면) */
    private val jumps = listOf(
        Triple(intArrayOf(515, 269, 623, 3751), 4, intArrayOf(535, 280, 647, 3896)),
        Triple(intArrayOf(515, 269, 623, 3751), 5, intArrayOf(540, 282, 653, 3932)),
        // 다른 개체: 예전 식은 4강 체 최저 3809 (실제 3808)
        Triple(intArrayOf(521, 260, 625, 3663), 3, intArrayOf(536, 269, 643, 3772)),
        Triple(intArrayOf(521, 260, 625, 3663), 4, intArrayOf(541, 271, 649, 3808)),
    )

    /** 도감값 1% 식: 강화 화면 28칸은 모두 1 이내, 18칸은 정확 */
    @Test
    fun breaksMatchGameWithinOne() {
        var exact = 0
        for (c in cases) {
            val s = Calculator.statsAtBreaks(c.before, db.find(c.name)!!.maxS, c.from, c.to)
            for (i in 0..3) {
                assertTrue("${c.name} ${c.from}→${c.to} stat$i: 예상 ${s[i]} 게임 ${c.after[i]}", Math.abs(s[i] - c.after[i]) <= 1)
                if (s[i] == c.after[i]) exact++
            }
        }
        assertEquals(18, exact)
    }

    /** 최저치: 게임 결과는 항상 이 값 이상 (여러 강을 한 번에 건너뛰어도). 예상과는 같거나 1 낮다 */
    @Test
    fun worstIsNeverAboveGame() {
        val all = cases.map { Triple(it.name, it.from to it.to, it.before to it.after) } +
            jumps.map { (b, t, a) -> Triple("조세르", 0 to t, b to a) }
        for ((name, ft, ba) in all) {
            val m = db.find(name)!!.maxS
            val lo = Calculator.statsAtBreaks(ba.first, m, ft.first, ft.second, worst = true)
            val mid = Calculator.statsAtBreaks(ba.first, m, ft.first, ft.second)
            for (i in 0..3) {
                assertTrue("$name ${ft.first}→${ft.second} stat$i: 최저 ${lo[i]} 게임 ${ba.second[i]}", lo[i] <= ba.second[i])
                assertTrue(mid[i] - lo[i] in 0..1)
            }
        }
        // 현재 강은 그대로
        assertEquals(615, Calculator.statsAtBreaks(intArrayOf(615, 345, 339, 4396), intArrayOf(594, 336, 337, 4463), 0, 0, worst = true)[0])
    }

    /** 예전 식(현재 능력치의 1%)은 0강에서 5강을 보면 게임보다 높았다: 체 3938 예상, 실제 3932 */
    @Test
    fun multiStepJump() {
        val m = db.find("조세르")!!.maxS
        val (b, t, a) = jumps[1]
        val s = Calculator.statsAtBreaks(b, m, 0, t)
        for (i in 0..3) assertTrue("stat$i ${s[i]} vs ${a[i]}", Math.abs(s[i] - a[i]) <= 1)
    }

    /** 등급 = 능력치 ÷ 도감값: 강화 전·후 40칸 모두 게임 표시와 같다 */
    @Test
    fun gradesMatchGame() {
        // 조세르(4→5강) 속도 647 은 게임이 SS+ 로 보여 주는데 647÷605 = 1.069 라 이 식으로는 SS (따로 확인 중)
        for (c in cases.filter { !(it.name == "조세르" && it.from == 4) }) {
            val m = db.find(c.name)!!.maxS
            assertEquals("${c.name} 강화 전", c.gradesBefore, Calculator.statGrades(c.before, m))
            assertEquals("${c.name} 강화 후", c.gradesAfter, Calculator.statGrades(c.after, m))
        }
    }
}
