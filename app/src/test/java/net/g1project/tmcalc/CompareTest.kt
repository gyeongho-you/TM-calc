package net.g1project.tmcalc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 두 소환수 실전투 비교, 프리셋 저장 형식 */
class CompareTest {

    private val hwa = intArrayOf(593, 309, 481, 4231); private val hwaBase = intArrayOf(574, 294, 485, 3904)
    private val lar = intArrayOf(559, 360, 405, 3981); private val larBase = intArrayOf(541, 356, 394, 4237)

    /** 기준 A = 화혼랑(총능 1806.1), B = 라르곤(1722.1), 성장증폭 8%: 라르곤은 공 −34% 방 −18% 순 −41% 체 −34% */
    @Test
    fun realCombatVsBase() {
        val c = Calculator.compare(hwa, hwaBase, 0, 0, lar, larBase, 0, 0, 0.08)
        assertNull(c.reason)
        assertArrayEquals(doubleArrayOf(-34.0, -18.0, -41.0, -34.0), c.pct!!.map { Math.round(it).toDouble() }.toDoubleArray(), 0.0)
        // 같은 개체끼리는 0%
        val same = Calculator.compare(hwa, hwaBase, 3, 3, hwa, hwaBase, 3, 3, 0.08)
        same.pct!!.forEach { assertEquals(0.0, it, 1e-9) }
    }

    /** 강을 바꿔 비교: 고른 강의 예상 능력치(강마다 도감값 1%)로 */
    @Test
    fun chosenBreaks() {
        val c = Calculator.compare(hwa, hwaBase, 3, 5, lar, larBase, 0, 5, 0.08)
        assertArrayEquals(Calculator.statsAtBreaks(hwa, hwaBase, 3, 5), c.a)
        assertArrayEquals(Calculator.statsAtBreaks(lar, larBase, 0, 5), c.b)
        assertArrayEquals(intArrayOf(586, 378, 425, 4193), c.b)
        assertNotNull(c.pct)
    }

    /** 총능이 범위 밖이면 숫자 대신 이유 */
    @Test
    fun outOfRange() {
        val low = intArrayOf(300, 200, 250, 2500)
        val c = Calculator.compare(hwa, hwaBase, 0, 0, low, low, 0, 0, 0.08)
        assertNull(c.pct)
        assertNotNull(c.reason)
        assertNotNull(Calculator.combatUnavailableReason(low, low))
        assertNull(Calculator.combatUnavailableReason(hwa, hwaBase))
    }

    @Test
    fun presetJsonRoundTrip() {
        val p = Preset("화혼랑 3강 103%", "화혼랑", 150, 3, hwa, hwaBase, 103)
        val back = Preset.listFromJson(Preset.listToJson(listOf(p)))
        assertEquals(1, back.size)
        assertEquals("화혼랑 3강 103%", back[0].label)
        assertArrayEquals(hwa, back[0].stats); assertArrayEquals(hwaBase, back[0].base)
        assertArrayEquals(intArrayOf(19, 15, -4, 327), back[0].deltas)
        assertEquals(103, back[0].gameTotal)
        // 깨진 파일, 이상한 값은 건너뛴다
        assertEquals(0, Preset.listFromJson("{oops").size)
        assertEquals(0, Preset.listFromJson("""[{"label":"x","name":"x","level":150,"breaks":9,"stats":[1,1,1,1],"base":[1,1,1,1]}]""").size)
        assertEquals("화혼랑 3강 103% (2)", Preset.defaultLabel("화혼랑", 3, 103, back))
    }
}
