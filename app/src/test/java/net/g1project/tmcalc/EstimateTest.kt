package net.g1project.tmcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 도감 없는 소환수용 등급 추정이, 도감이 있는 119종에서 실제(시트) 등급을 항상 범위 안에 담는지 검증 */
class EstimateTest {

    private val db = PetDb.parse(File("src/main/assets/pets.json").readText())
    private val order = listOf("D", "C", "B", "B+", "A", "A+", "A++", "S", "S+", "S++", "SS", "SS+", "SS++")

    private fun contains(range: String, g: String): Boolean {
        val parts = range.split('~')
        val lo = order.indexOf(parts.first()); val hi = order.indexOf(parts.last())
        return order.indexOf(g) in lo..hi
    }

    @Test
    fun estimateAlwaysContainsSheetGrade() {
        var cells = 0; var exact = 0
        val deltaSets = listOf(intArrayOf(0, 0, 0, 0), intArrayOf(18, 13, 10, -2), intArrayOf(-20, 7, 6, -33),
            intArrayOf(55, 11, 25, 264), intArrayOf(-40, -25, 30, 150), intArrayOf(9, -9, 3, -90))
        // 시트 S성장률이 (만렙S-초기치)/149 와 크게 어긋나는(시트 입력 오류로 보이는) 소환수는 제외
        val odd = db.pets.filter { p -> (0..3).any { Math.abs(p.sGrowth[it] - (p.maxS[it] - p.init[it]) / 149.0) > 0.05 } }
        println("시트 값이 어긋나 제외: " + odd.joinToString { it.name })
        for (p in db.pets - odd.toSet()) for (d in deltaSets) for (b in 0..4) {
            val cur = IntArray(4) { p.maxS[it] + d[it] }
            if (cur.any { it <= 0 }) continue
            val cg = Calculator.statGrades(p, 150, cur, roundGrowth = false)
            val screen = List(4) {
                Calculator.ScreenStat(cur[it], d[it], Calculator.round((cur[it] - p.init[it]) / 149.0, 2), cg[it])
            }
            for (t in b..5) {
                val truth = Calculator.statGrades(p, 150, Calculator.statsAtBreaks(cur, b, t), roundGrowth = t != b)
                val est = Calculator.estimateGrades(screen, b, t)
                for (i in 0..3) {
                    cells++
                    assertTrue("${p.name} ${d.toList()} ${b}->${t} stat$i: truth=${truth[i]} est=${est[i]}", contains(est[i], truth[i]))
                    if (!est[i].contains('~')) exact++
                }
            }
        }
        println("추정 등급: $cells 칸 중 한 등급으로 확정 $exact 칸 (${"%.1f".format(exact * 100.0 / cells)}%), 나머지는 두 등급 범위")
    }

    /** 화혼랑 캡처 값만으로 추정 (도감 미사용) → 시트와 같은 결과 */
    @Test
    fun hwahonrangFromScreenOnly() {
        val screen = listOf(
            Calculator.ScreenStat(629, 55, 4.11, "SS++"), Calculator.ScreenStat(305, 11, 2.00, "S++"),
            Calculator.ScreenStat(510, 25, 3.34, "SS"), Calculator.ScreenStat(4168, 264, 27.29, "SS"),
        )
        assertEquals(listOf("SS++", "S++", "SS", "SS"), Calculator.estimateGrades(screen, 4, 4))
        val five = Calculator.estimateGrades(screen, 4, 5)
        val truth = listOf("SS++", "SS", "SS", "SS+")
        for (i in 0..3) assertTrue("stat$i ${five[i]}", contains(five[i], truth[i]))
    }
}
