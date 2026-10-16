package net.g1project.tmcalc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 성장률로 초기치·만렙S 추정 (참고용) */
class EstimateTest {

    private val db = PetDb.parse(File("src/main/assets/pets.json").readText())

    /** 도감 전체: 초기치는 거의 맞고, 만렙S 는 추정 범위에서 1 넘게 벗어나지 않는다 */
    @Test
    fun matchesPetDb() {
        var initHit = 0; var cells = 0
        val far = mutableListOf<String>()
        for (p in db.pets) {
            val e = Calculator.estimate(p.sGrowth)!!
            for (i in 0..3) {
                cells++
                if (p.init[i] in e.initLo[i]..e.initHi[i]) initHit++
                if (p.maxS[i] < e.maxLo[i] - 1 || p.maxS[i] > e.maxHi[i] + 1) far += "${p.name}[$i] ${p.maxS[i]} vs ${e.maxLo[i]}"
            }
        }
        assertTrue("초기치 $initHit/$cells", initHit >= cells * 0.9)
        // 하용이 방어 586 은 도감 쪽 오타로 보임 (규칙대로면 485 근처)
        assertTrue(far.joinToString(), far.size <= 2)
    }

    @Test
    fun example() {
        val e = Calculator.estimate(doubleArrayOf(3.18, 1.93, 3.75, 25.14))!!
        assertArrayEquals(intArrayOf(12, 7, 15, 103), e.initLo)
        assertArrayEquals(intArrayOf(13, 8, 15, 103), e.initHi)
        assertArrayEquals(intArrayOf(486, 295, 574, 3849), e.maxLo)
        assertEquals(1741.0, e.score, 1.0)
    }

    @Test
    fun rejectsBadInput() {
        assertNull(Calculator.estimate(doubleArrayOf(3.0, 0.0, 3.0, 25.0)))
        assertNull(Calculator.estimate(doubleArrayOf(3.0, Double.NaN, 3.0, 25.0)))
    }
}
