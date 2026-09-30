package net.g1project.tmcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** 화면의 "총 성장 점수 N%" 읽기: OCR 이 옆 글자와 붙여 읽어도 찾는다 (PC OCR 에서 자주 생김) */
class GameTotalParseTest {

    private val db = PetDb.parse(File("src/main/assets/pets.json").readText())

    private fun total(vararg lines: OcrLine) = OcrParser.parse(lines.toList(), db).gameTotal

    @Test
    fun separateLine() = assertEquals(100, total(OcrLine("총 성장 점수", 109, 617, 250, 649), OcrLine("100%", 117, 658, 214, 693)))

    /** "100%" 와 옆의 총 능력치 점수 "1,747.4" 가 한 줄로 읽힌 경우 */
    @Test
    fun mergedWithAbilityScore() = assertEquals(100, total(
        OcrLine("총 성장 점수 총 능력치 점수", 109, 618, 471, 645), OcrLine("100% 1,747.4", 117, 658, 438, 694)))

    /** 라벨과 값이 같은 줄로 읽힌 경우 */
    @Test
    fun sameLineAsLabel() = assertEquals(99, total(OcrLine("총 성장 점수 99%", 90, 610, 300, 650)))

    /**
     * 실제 사례: PC 사진 보기(28% 확대)로 띄운 주디 거래소 화면. 작은 "총 성장 점수" 라벨은 못 읽고 "99%" 만 읽혔다.
     * 라벨이 없으면 이름과 Lv. 사이의 % 를 쓴다
     */
    @Test
    fun smallCaptureWithoutLabel() {
        val lines = File("src/test/resources/ocr/judy_pc_small.txt").readLines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('|'); val n = p.size
            OcrLine(p.subList(0, n - 4).joinToString("|"), p[n - 4].toInt(), p[n - 3].toInt(), p[n - 2].toInt(), p[n - 1].toInt())
        }
        val p = OcrParser.parse(lines, db)
        assertEquals("하프 드래곤 주디", p.pet?.name)
        assertEquals(150, p.level)
        assertEquals(99, p.gameTotal)
    }

    /** 강화 성공률 "35.00%", 채팅의 "성장점수가108" 은 총 성장 점수로 보지 않는다 */
    @Test
    fun ignoresOtherPercents() {
        assertNull(total(OcrLine("요놈이 처음에 성장점수가108로 나와서", 297, 1738, 970, 1767), OcrLine("35.00%", 300, 1800, 400, 1830)))
        assertEquals(105, total(OcrLine("총 성장 점수", 60, 600, 180, 630), OcrLine("105%", 70, 640, 160, 670),
            OcrLine("성공", 300, 1520, 360, 1550), OcrLine("35.00%", 260, 1570, 400, 1610)))
    }
}
