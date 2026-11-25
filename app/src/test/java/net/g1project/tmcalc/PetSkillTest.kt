package net.g1project.tmcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 도감의 스킬 칸: 내보내기 → 다시 읽기, 한글 이름 키, 기본 도감과 같은지 판단 */
class PetSkillTest {
    private fun pet(skill: Map<String, Double>) =
        Pet("라르곤", "전설", "불", "야수", intArrayOf(14, 9, 10, 114), doubleArrayOf(3.54, 2.32, 2.57, 27.67), intArrayOf(541, 356, 394, 4237), skill)

    @Test fun roundTrip() {
        val p = pet(mapOf("mana" to 5.0, "pct" to 300.0, "hits" to 1.5))
        val json = PetStore.exportJson(listOf(p))
        assertTrue(json.contains("\"스킬\":{\"mana\":5,\"pct\":300,\"hits\":1.5}"))
        assertEquals(p.skill, PetDb.parse(json).pets[0].skill)
        // 스킬 없는 소환수는 예전 형식 그대로
        assertTrue(!PetStore.exportJson(listOf(pet(emptyMap()))).contains("스킬"))
    }

    @Test fun koreanKeysAndBadValues() {
        val j = """[{"이름":"x","초기치":[1,1,1,1],"S성장률":[1,1,1,1],"만렙S":[150,150,150,150],"스킬":{"최대 마나":4,"pct":"abc"}}]"""
        assertEquals(mapOf("mana" to 4.0), PetDb.parse(j).pets[0].skill)
        assertNotNull(PetDb.check(pet(mapOf("mana" to -1.0))))
    }

    @Test fun skillKeepsUserCopy() {
        // 숫자가 기본 도감과 같아도 스킬을 적었으면 내 도감에 남는다
        val base = listOf(pet(emptyMap()))
        assertEquals(1, PetDb.pruneSameAsBase(listOf(pet(mapOf("mana" to 5.0))), base).size)
        assertEquals(0, PetDb.pruneSameAsBase(listOf(pet(emptyMap())), base).size)
    }

    @Test fun userCopyKeepsBaseSkill() {
        // 내 도감에서 숫자만 고친 소환수(스킬 없음)는 기본 도감 스킬을 이어받는다
        val base = listOf(pet(mapOf("mana" to 20.0, "pct" to 300.0)))
        val mine = Pet("라르곤", "전설", "화염", "야수", intArrayOf(14, 9, 10, 114), doubleArrayOf(3.54, 2.32, 2.57, 27.67), intArrayOf(541, 356, 395, 4237))
        assertEquals(300.0, PetStore.merge(base, listOf(mine))[0].skill["pct"])
        assertEquals(395, PetStore.merge(base, listOf(mine))[0].maxS[2])
    }

    @Test fun builtInDexHasSkills() {
        val db = PetDb.parse(java.io.File("src/main/assets/pets.json").readText())
        assertTrue(db.pets.all { it.skill["mana"] != null && it.skill["pct"] != null })
        assertTrue(db.pets.none { it.element == "불" })
    }
}
