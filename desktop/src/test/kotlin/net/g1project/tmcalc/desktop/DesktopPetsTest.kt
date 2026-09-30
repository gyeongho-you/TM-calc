package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.Pet
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** PC 도감: 폰 앱 PetStore 와 같은 규칙 (내 도감 우선, 기본값으로 되돌리기, 바뀐 것만 올리기, 폰 형식 호환) */
class DesktopPetsTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun pet(name: String, m: IntArray) = Pet(name, "", "", "", intArrayOf(10, 10, 10, 100), doubleArrayOf(1.0, 1.0, 1.0, 10.0), m)

    @Test
    fun putOverridesAndRemoveReverts() {
        val pets = DesktopPets(tmp.newFolder())
        val base = pets.db.find("플루스타")!!.maxS.clone()
        pets.put(pet("플루스타", intArrayOf(1, 2, 3, 4)))
        assertArrayEquals(intArrayOf(1, 2, 3, 4), pets.db.find("플루스타")!!.maxS)
        assertTrue(pets.isMine("플루스타") && pets.inBase("플루스타"))
        pets.remove("플루스타")
        assertArrayEquals(base, pets.db.find("플루스타")!!.maxS)
        assertFalse(pets.isMine("플루스타"))
    }

    @Test
    fun exportAllThenImportOnlyStoresChanges() {
        val dir = tmp.newFolder()
        val pets = DesktopPets(dir)
        pets.put(pet("티거", intArrayOf(5, 6, 7, 8)))
        val all = tmp.newFile("도감.json")
        assertEquals(pets.db.pets.size, pets.export(all, all = true))
        // 전체 도감을 그대로 다시 올리면: 바뀐 것 없음, 내 도감에는 티거만 남는다
        val r = pets.import(all)
        assertEquals(0, r.added); assertEquals(0, r.changed); assertEquals(pets.db.pets.size, r.same)
        assertEquals(1, pets.userCount)
        // 다른 PC(새 폴더)에 내 도감만 옮기기
        val mine = tmp.newFile("내도감.json")
        pets.export(mine, all = false)
        val other = DesktopPets(tmp.newFolder())
        assertEquals(1, other.import(mine).added)
        assertArrayEquals(intArrayOf(5, 6, 7, 8), other.db.find("티거")!!.maxS)
    }

    /** 폰 앱이 내보낸 파일(한글 키)과 앱 내장 형식(짧은 키) 둘 다 읽는다 */
    @Test
    fun readsPhoneFormats() {
        val pets = DesktopPets(tmp.newFolder())
        val f = tmp.newFile("phone.json")
        f.writeText("""[{"이름":"블루 데비라","등급":"희귀","속성":"물","타입":"","초기치":[6,8,10,60],"S성장률":[1.6,1.1,1.2,14.0],"만렙S":[244,172,189,2146]},""" +
            """{"n":"새소환수","g":"","e":"","t":"","i":[1,1,1,10],"s":[1.0,1.0,1.0,10.0],"m":[150,150,150,1500]}]""")
        assertEquals(2, pets.import(f).added)
        assertEquals("희귀", pets.db.find("블루데비라")!!.grade)
        assertTrue(pets.db.find("새소환수") != null)
    }
}
