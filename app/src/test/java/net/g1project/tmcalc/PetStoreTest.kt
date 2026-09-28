package net.g1project.tmcalc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 내 도감이 기본 도감을 이름 기준으로 덮어쓰고, 새 소환수는 뒤에 붙는지 */
class PetStoreTest {

    private val base = PetDb.parse(File("src/main/assets/pets.json").readText()).pets

    private fun pet(name: String, m: IntArray) =
        Pet(name, "", "", "", intArrayOf(10, 10, 10, 100), doubleArrayOf(1.0, 1.0, 1.0, 10.0), m)

    @Test
    fun userOverridesAndAdds() {
        val fixed = pet("플루스타", intArrayOf(1, 2, 3, 4))
        val added = pet("티거", intArrayOf(5, 6, 7, 8))
        val merged = PetStore.merge(base, listOf(fixed, added))
        assertEquals(base.size + 1, merged.size)
        val db = PetDb(merged)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), db.find("플루스타")!!.maxS)
        assertArrayEquals(intArrayOf(5, 6, 7, 8), db.find("티거")!!.maxS)
        // 이름 비교는 공백/괄호를 무시한다
        assertEquals(base.size, PetStore.merge(base, listOf(pet("하프드래곤 주디", intArrayOf(1, 1, 1, 1)))).size)
    }

    @Test
    fun jsonRoundTrip() {
        val p = Pet("블루 데비라", "", "물", "", intArrayOf(8, 5, 5, 68), doubleArrayOf(3.21, 2.1, 2.3, 28.05), intArrayOf(486, 318, 348, 4247))
        val back = PetDb.parse(PetStore.exportJson(listOf(p))).pets.single()
        assertEquals(p.name, back.name)
        assertEquals("", back.grade)
        assertArrayEquals(p.init, back.init)
        assertArrayEquals(p.sGrowth, back.sGrowth, 1e-9)
        assertArrayEquals(p.maxS, back.maxS)
    }
}
