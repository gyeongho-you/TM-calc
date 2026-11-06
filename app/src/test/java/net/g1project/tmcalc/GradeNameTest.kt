package net.g1project.tmcalc

import org.junit.Assert.assertEquals
import org.junit.Test

/** 같은 이름에 등급만 다른 소환수 ("갓독 성요한(전설)" / "(영웅)") */
class GradeNameTest {

    private fun pet(n: String, g: String) = Pet(n, g, "빛", "마법", intArrayOf(13, 8, 15, 100), doubleArrayOf(3.3, 2.0, 3.8, 25.0), intArrayOf(505, 306, 581, 3825))
    private val db = PetDb(listOf(pet("갓독 성요한(전설)", "전설"), pet("갓독 성요한(영웅)", "영웅"), pet("라르곤", "전설")))

    @Test
    fun keyKeepsGradeButDropsBreaks() {
        assertEquals("갓독성요한전설", PetDb.key("갓독 성요한(전설)"))
        assertEquals("갓독성요한전설", PetDb.key("갓독 성요한 (전설) (+4)"))
        assertEquals("라르곤", PetDb.key("라르곤 (+3)"))
        assertEquals("갓독 성요한(전설)", PetDb.displayName("갓독 성요한 (전설) (+4)"))
        assertEquals("1세대의 전설", PetDb.displayName("1세대의 전설 (+2)"))
        assertEquals("갓독 성요한(전설)", db.find("갓독 성요한(전설)")?.name)
        assertEquals("갓독 성요한(영웅)", db.find("갓독성요한(영웅)")?.name)
    }

    /** 화면 이름은 "갓독 성요한" 뿐이고 이름 위 태그가 "전설" → (전설) 을 찾는다 */
    @Test
    fun screenUsesGradeTag() {
        fun lines(grade: String) = listOf(
            OcrLine(grade, 330, 380, 390, 415), OcrLine("빛", 470, 380, 510, 415), OcrLine("마법", 590, 380, 650, 415),
            OcrLine("갓독 성요한 (+2)", 340, 425, 620, 475), OcrLine("Lv.150", 340, 485, 470, 530),
            OcrLine("공격력", 170, 880, 250, 910), OcrLine("520 (15)", 330, 880, 450, 910),
            OcrLine("방어력", 170, 930, 250, 960), OcrLine("310 (4)", 330, 930, 450, 960),
            OcrLine("속도", 170, 980, 230, 1010), OcrLine("590 (9)", 330, 980, 450, 1010),
            OcrLine("생명력", 170, 1030, 250, 1060), OcrLine("3,900 (75)", 300, 1030, 450, 1060))
        assertEquals("갓독 성요한(전설)", OcrParser.parse(lines("전설"), db).pet?.name)
        assertEquals("갓독 성요한(영웅)", OcrParser.parse(lines("영웅"), db).pet?.name)
        assertEquals(2, OcrParser.parse(lines("전설"), db).breaks)
    }
}
