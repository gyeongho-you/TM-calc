package net.g1project.tmcalc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 게임 도감 화면 (2026-10-23 "1세대의 전설"): 150레벨 능력치 486 295 574 3849, 성장 평균(S) 3.18 1.93 3.75 25.14.
 * [도감에 추가/수정] 을 누르면 이름·태그·초기치·성장률·만렙S 가 모두 채워져야 한다.
 */
class DexScreenTest {

    private val db = PetDb.parse(File("src/main/assets/pets.json").readText())

    private fun load(name: String) = File("src/test/resources/ocr/$name").readLines().filter { it.isNotBlank() }.map { line ->
        val p = line.split('|'); val n = p.size
        OcrLine(p.subList(0, n - 4).joinToString("|"), p[n - 4].toInt(), p[n - 3].toInt(), p[n - 2].toInt(), p[n - 1].toInt())
    }

    private fun dex(p: ParsedScreen) = PetDb.dexFromScreen(p.level, p.stats.toList(), p.deltas.toList(), p.growthAvgs.toList())

    /** PC OCR 그대로: [LV. 150] [LV. 1] 탭을 레벨로 읽지 않고, "486" 뒤 대시를 놓쳐도 도감 대비 0 */
    @Test
    fun pcCapture() {
        val p = OcrParser.parse(load("dex_pc.txt"), db)
        assertTrue(p.dexScreen)
        assertEquals(150, p.level)
        // 이름의 숫자를 지우지 않는다 (예전엔 "세대의전설")
        assertEquals("1세대의전설", PetDb.displayName(p.nameRaw!!))
        assertEquals(listOf("전설", "어둠", "비행"), listOf(p.grade, p.element, p.type))
        assertEquals(listOf(0, 0, 0, 0), p.deltas.toList())
        val d = dex(p)
        assertArrayEquals(intArrayOf(12, 7, 15, 103), d.init)
        assertArrayEquals(doubleArrayOf(3.18, 1.93, 3.75, 25.14), d.growth, 1e-9)
        assertArrayEquals(intArrayOf(486, 295, 574, 3849), d.maxS)
        assertTrue(d.notes.isEmpty())
        // 그대로 저장해도 값끼리 맞는다
        assertTrue(PetDb.oddValues(Pet("1세대의 전설", "전설", "어둠", "비행", d.init!!, d.growth!!, d.maxS!!)).isEmpty())
    }

    /** 폰처럼 탭이 한 줄로 읽히고 생명력을 3349 로 잘못 읽은 경우: 레벨은 150, 생명력은 성장률로 3849 로 고친다 */
    @Test
    fun phoneLikeMisread() {
        val p = OcrParser.parse(load("dex_phone_like.txt"), db)
        assertEquals(150, p.level)
        val d = dex(p)
        assertArrayEquals(intArrayOf(486, 295, 574, 3849), d.maxS)
        assertEquals(103, d.init!![3])
        assertEquals(listOf("생명 3349 → 3849"), d.notes)
    }

    /** LV.1 탭을 눌러 찍은 경우: 능력치가 초기치 */
    @Test
    fun levelOneTab() {
        val d = PetDb.dexFromScreen(150, listOf(12, 7, 15, 103), listOf(0, 0, 0, 0), listOf(3.18, 1.93, 3.75, 25.14))
        assertArrayEquals(intArrayOf(12, 7, 15, 103), d.init)
        assertArrayEquals(intArrayOf(486, 295, 574, 3849), d.maxS)
    }

    /** 보통 150레벨 소환수 화면: 도감 대비가 있으면 성장 평균은 개체 값이라 쓰지 않고 만렙S(현재 − 대비)만 */
    @Test
    fun normalScreenGivesOnlyMaxS() {
        val d = PetDb.dexFromScreen(150, listOf(540, 282, 653, 3932), listOf(33, 11, 48, 325), listOf(3.54, 1.85, 4.28, 25.72))
        assertArrayEquals(intArrayOf(507, 271, 605, 3607), d.maxS)
        assertEquals(null, d.init); assertEquals(null, d.growth)
    }
}
