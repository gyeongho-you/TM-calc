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

    /** 메모·이름 바꾸기, 파일 올리기 합치기 (똑같은 건 건너뛰고 이름만 겹치면 (2)) */
    @Test
    fun presetMemoAndMerge() {
        val a = Preset("화혼랑 3강", "화혼랑", 150, 3, hwa, hwaBase, 103, "PVP용")
        val back = Preset.listFromJson(Preset.listToJson(listOf(a)))[0]
        assertEquals("PVP용", back.memo)
        assertEquals("새 이름", a.renamed("  새 이름 ", "메모").label)
        val b = Preset("화혼랑 3강", "라르곤", 150, 0, lar, larBase)
        val (merged, added) = Preset.merge(listOf(a), listOf(a, b))
        assertEquals(1, added)
        assertEquals(listOf("화혼랑 3강", "화혼랑 3강 (2)"), merged.map { it.label })
        assertEquals("화혼랑 3강 (2)", Preset.uniqueLabel("화혼랑 3강", listOf(a)))
    }

    /** 도감 정렬: 총능·공격 등 높은순/낮은순, 기본은 그대로 */
    @Test
    fun petSort() {
        val db = PetDb.parse(java.io.File("src/main/assets/pets.json").readText())
        val atk = PetSort.ATK_DESC.sort(db.pets).map { it.maxS[0] }
        assertEquals(atk.sortedDescending(), atk)
        val hp = PetSort.HP_ASC.sort(db.pets).map { it.maxS[3] }
        assertEquals(hp.sorted(), hp)
        val sc = PetSort.SCORE_DESC.sort(db.pets).map { Calculator.abilityScore(it.maxS) }
        assertEquals(sc.sortedDescending(), sc)
        assertEquals(db.pets, PetSort.DEFAULT.sort(db.pets))
    }

    /** 실전투 기준: 능력치 × (1 + 증폭 × u(그 종 총능)) 순서. 총능이 높은 종이 같은 공격력이어도 앞에 온다 */
    @Test
    fun petSortRealCombat() {
        val db = PetDb.parse(java.io.File("src/main/assets/pets.json").readText())
        val sorted = PetSort.ATK_DESC.sort(db.pets, 0.08)
        val keys = sorted.map { it.maxS[0] * PetSort.realBonus(it, 0.08) }
        assertEquals(keys.sortedDescending(), keys)
        // 순서가 실제로 바뀌는 경우가 있어야 의미가 있다
        assert(sorted.map { it.name } != PetSort.ATK_DESC.sort(db.pets).map { it.name })
        // 총능 정렬은 실전투와 상관없이 같다
        assertEquals(PetSort.SCORE_DESC.sort(db.pets).map { it.name }, PetSort.SCORE_DESC.sort(db.pets, 0.08).map { it.name })
    }

    /** 기본 도감이 업데이트돼서 내가 고친 값과 같아지면 내 도감에서 뺀다 (등급 등이 비어 있어도 같다고 봄) */
    @Test
    fun pruneSameAsBase() {
        val base = listOf(Pet("라르곤", "전설", "불", "야수", intArrayOf(14, 9, 10, 114), doubleArrayOf(3.54, 2.32, 2.57, 27.67), intArrayOf(541, 356, 394, 4237)))
        val same = Pet("라르곤", "", "", "", intArrayOf(14, 9, 10, 114), doubleArrayOf(3.54, 2.32, 2.57, 27.67), intArrayOf(541, 356, 394, 4237))
        val diff = Pet("라르곤", "전설", "불", "야수", intArrayOf(14, 9, 10, 114), doubleArrayOf(3.54, 2.32, 2.57, 27.67), intArrayOf(541, 356, 395, 4237))
        val extra = Pet("새것", "", "", "", intArrayOf(1, 1, 1, 1), doubleArrayOf(1.0, 1.0, 1.0, 1.0), intArrayOf(150, 150, 150, 150))
        assertEquals(listOf("새것"), PetDb.pruneSameAsBase(listOf(same, extra), base).map { it.name })
        assertEquals(2, PetDb.pruneSameAsBase(listOf(diff, extra), base).size)
    }
}
