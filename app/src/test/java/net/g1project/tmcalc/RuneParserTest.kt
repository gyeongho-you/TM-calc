package net.g1project.tmcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuneParserTest {
    private fun L(t: String, l: Int, top: Int, r: Int, b: Int) = OcrLine(t, l, top, r, b)

    @Test fun numbers() {
        RuneParser.num("5737+")!!.let { assertEquals(5737.0, it.lo, 0.0); assertTrue(it.exact) }
        RuneParser.num("1.7만+")!!.let { assertEquals(17000.0, it.lo, 0.0); assertEquals(17999.0, it.hi, 0.0) }
        RuneParser.num("1.4만")!!.let { assertEquals(14000.0, it.lo, 0.0); assertEquals(14999.0, it.hi, 0.0) }
        RuneParser.num("4.38%")!!.let { assertEquals(4.38, it.lo, 1e-9); assertTrue(it.pct && it.exact) }
        RuneParser.num("440만8917")!!.let { assertEquals(4408917.0, it.lo, 0.0); assertTrue(it.exact) }
        RuneParser.num("1억210만")!!.let { assertEquals(102100000.0, it.lo, 0.0); assertEquals(102109999.0, it.hi, 0.0) }
        RuneParser.num("3조4521억")!!.let { assertEquals(3.4521e12, it.lo, 1.0); assertEquals(3.4521e12 + 1e8 - 1, it.hi, 1.0) }
        RuneParser.num("^ 5796+")!!.let { assertEquals(5796.0, it.lo, 0.0) }
        assertNull(RuneParser.num("공격력"))
    }

    /** 스크린샷: 찰나의 룬 [신화] Lv.287 → Lv.289 (따로 읽힌 줄) */
    @Test fun compareSeparateLines() {
        val lines = listOf(
            L("룬", 140, 125, 175, 160), L("2.7만", 560, 140, 625, 175),
            L("장착중", 150, 272, 215, 305),
            L("룬", 380, 335, 410, 365), L("신화", 500, 335, 540, 365),
            L("찰나의 룬 [신화]", 335, 390, 550, 425), L("Lv.287", 335, 435, 442, 470), L("287", 212, 455, 255, 482),
            L("주 옵션", 165, 510, 238, 538),
            L("공격력", 165, 566, 240, 598), L("5737+", 337, 566, 428, 598), L("방어력", 480, 566, 556, 598), L("5734+", 650, 566, 743, 598),
            L("속도", 165, 615, 213, 647), L("5732+", 337, 615, 428, 647), L("생명력", 480, 615, 556, 647), L("5732+", 650, 615, 743, 647),
            L("부 옵션", 165, 690, 238, 718),
            L("피해증가", 165, 745, 268, 776), L("4.38%", 668, 737, 758, 767),
            L("239", 272, 963, 320, 990), L("284", 603, 963, 650, 990),
            L("룬", 380, 1055, 410, 1085), L("신화", 500, 1055, 540, 1085),
            L("찰나의 룬 [신화]", 335, 1110, 550, 1145), L("Lv.289", 335, 1155, 435, 1188), L("118만401", 648, 1155, 758, 1188),
            L("주 옵션", 165, 1230, 238, 1258),
            L("공격력", 165, 1287, 240, 1320), L("^ 5796+", 290, 1287, 428, 1320), L("방어력", 480, 1287, 556, 1320), L("^ 5786+", 605, 1287, 743, 1320),
            L("속도", 165, 1335, 213, 1367), L("^ 5780+", 290, 1335, 428, 1367), L("생명력", 480, 1335, 556, 1367), L("^ 5780+", 605, 1335, 743, 1367),
            L("부 옵션", 165, 1410, 238, 1438),
            L("장비 공격", 165, 1462, 273, 1495), L("3595+", 665, 1455, 758, 1488),
            L("레벨업", 143, 1710, 205, 1738), L("자동 제작", 703, 1710, 793, 1738),
            L("분해", 305, 1765, 375, 1805), L("장착", 545, 1765, 615, 1805), L("탭하여 닫기", 390, 1885, 533, 1920),
        )
        val r = RuneParser.parse(lines)
        assertEquals(2, r.size)
        assertEquals("신화", r[0].grade); assertEquals("찰나의 룬", r[0].name); assertEquals(287, r[0].level)
        assertEquals(5737.0, r[0].atk!!.lo, 0.0)
        assertEquals(listOf("방어력", "속도", "생명력"), r[0].others.map { it.first })
        assertEquals(1, r[0].subs.size); assertEquals("피해증가", r[0].subs[0].first); assertEquals(4.38, r[0].subs[0].second.lo, 1e-9)
        assertEquals(289, r[1].level); assertEquals(5796.0, r[1].atk!!.lo, 0.0)
        assertEquals(listOf("장비 공격" to 3595.0), r[1].subs.map { it.first to it.second.lo })
    }

    /** 스크린샷: 근원의 룬 [에픽] Lv.552 (1.7만+) → [신화] Lv.566, 줄이 합쳐져 읽힌 경우 */
    @Test fun compareMergedLinesAndMan() {
        val lines = listOf(
            L("장착중", 150, 272, 215, 305),
            L("근원의 룬 [에픽]", 335, 390, 550, 425), L("Lv.552", 335, 435, 442, 470),
            L("주 옵션", 165, 510, 238, 538),
            L("공격력 1.7만+ 방어력 1.7만+", 165, 566, 743, 598),
            L("속도 1.7만+ 생명력 1.7만+", 165, 615, 743, 647),
            L("부 옵션", 165, 690, 238, 718),
            L("화염속성 강화 5.87%", 165, 740, 758, 776),
            L("장비 공격 1.4만+", 165, 785, 758, 820),
            L("근원의 룬 [신화]", 335, 1110, 550, 1145), L("Lv.566", 335, 1155, 435, 1188), L("8억532만", 648, 1155, 758, 1188),
            L("주 옵션", 165, 1230, 238, 1258),
            L("공격력 1.4만+ 방어력 1.4만+", 165, 1287, 743, 1320),
            L("부 옵션", 165, 1410, 238, 1438),
            L("치명타 확률", 165, 1462, 300, 1495), L("1.19%", 665, 1455, 758, 1488),
            L("분해", 305, 1765, 375, 1805), L("장착", 545, 1765, 615, 1805),
        )
        val r = RuneParser.parse(lines)
        assertEquals(2, r.size)
        assertEquals("에픽", r[0].grade); assertEquals(552, r[0].level)
        assertEquals(17000.0, r[0].atk!!.lo, 0.0); assertEquals(17999.0, r[0].atk!!.hi, 0.0)
        assertEquals(listOf("화염속성 강화", "장비 공격"), r[0].subs.map { it.first })
        assertEquals(14000.0, r[0].subs[1].second.lo, 0.0)
        assertEquals("신화", r[1].grade); assertEquals(14000.0, r[1].atk!!.lo, 0.0)
        assertEquals(listOf("치명타 확률" to 1.19), r[1].subs.map { it.first to it.second.lo })
    }

    /** OCR이 이름과 [등급]을 나눠 읽고, '주옵션'·'부옵션'을 붙여 읽은 경우 */
    @Test fun splitNameAndJoinedHeads() {
        val lines = listOf(
            L("장착중", 150, 272, 215, 305),
            L("룬", 380, 335, 410, 365), L("신화", 500, 335, 540, 365),
            L("찰나의 룬", 335, 390, 470, 425), L("[신화]", 478, 390, 550, 425), L("Lv. 287", 335, 435, 442, 470),
            L("주옵션", 165, 510, 238, 538),
            L("공격력", 165, 566, 240, 598), L("5737+", 337, 566, 428, 598),
            L("부옵션", 165, 690, 238, 718), L("피해증가", 165, 745, 268, 776), L("4.38%", 668, 737, 758, 767),
            L("룬", 380, 1055, 410, 1085), L("전설", 500, 1055, 540, 1085),
            L("불굴의 룬", 335, 1110, 470, 1145), L("(전설]", 478, 1110, 550, 1145), L("Lv.568", 335, 1155, 435, 1188),
            L("주옵션", 165, 1230, 238, 1258),
            L("공격력", 165, 1287, 240, 1320), L("1.4만+", 290, 1287, 428, 1320),
            L("부옵션", 165, 1410, 238, 1438), L("장비 속도", 165, 1462, 273, 1495), L("4880+", 665, 1455, 758, 1488),
            L("분해", 305, 1765, 375, 1805),
        )
        val r = RuneParser.parse(lines)
        assertEquals(2, r.size)
        assertEquals("신화", r[0].grade); assertEquals("찰나의 룬", r[0].name); assertEquals(287, r[0].level); assertEquals(5737.0, r[0].atk!!.lo, 0.0)
        assertEquals("전설", r[1].grade); assertEquals(568, r[1].level); assertEquals(14000.0, r[1].atk!!.lo, 0.0)
        assertEquals(listOf("장비 속도"), r[1].subs.map { it.first })
    }
}
