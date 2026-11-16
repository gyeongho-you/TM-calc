package net.g1project.tmcalc

import org.junit.Assert.assertEquals
import org.junit.Test

class LabParserTest {
    private fun l(t: String, x: Int, y: Int, w: Int = t.length * 20) = OcrLine(t, x, y, x + w, y + 30)

    @Test fun numbers() {
        assertEquals(359520000.0, LabParser.number("3억5952만")!!, 0.0)
        assertEquals(1919961.0, LabParser.number("191만9961")!!, 0.0)
        assertEquals(166000.0, LabParser.number("16.6만")!!, 1e-6)
        assertEquals(85000.0, LabParser.number("+8.5만")!!, 1e-6)
        assertEquals(44.38, LabParser.number("+44.38%")!!, 1e-9)
        assertEquals(2618.0, LabParser.number("2618")!!, 0.0)
    }

    /** 상태창 (2026-11 스크린샷 배치): 같은 '공격력'이 기본/전투로 갈린다 */
    @Test fun statusScreen() {
        val lines = listOf(
            l("기본능력치", 88, 595), l("전투능력치 ?", 342, 595),
            l("공격력", 88, 628), l("261", 262, 628, 36), l("공격력", 342, 628), l("191만9961", 451, 628, 100),
            l("생명력", 88, 715), l("2618", 249, 715, 48), l("생명력", 342, 715), l("1671만8281", 430, 715, 120),
            l("속성 강화 ?", 88, 757), l("특수 버프", 342, 757),
            l("모든능력치", 88, 920), l("16.6만", 247, 920, 52), l("성장 증폭%", 342, 895), l("11.70%", 491, 895, 64),
            l("룬 옵션", 88, 962),
            l("치명타피해", 88, 1112), l("+44.38%", 470, 1112, 86),
        )
        assertEquals("상태창", LabParser.kind(lines))
        val m = LabParser.values(lines).associate { it.key to it.value }
        assertEquals(261.0, m["기본능력치/공격력"])
        assertEquals(1919961.0, m["전투능력치/공격력"])
        assertEquals(16718281.0, m["전투능력치/생명력"])
        assertEquals(166000.0, m["속성강화/모든능력치"]!!, 1e-6)
        assertEquals(11.70, m["특수버프/성장증폭%"]!!, 1e-9)
        assertEquals(44.38, m["룬옵션/치명타피해"]!!, 1e-9)
    }
}
