package net.g1project.tmcalc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * 에뮬레이터에서 실제 ML Kit 이 게임 캡처를 읽은 결과(src/test/resources/ocr 폴더의 txt, "글자|l|t|r|b")로
 * 해석기를 검증한다. 잘못 읽히는 화면이 생기면 여기에 캡처를 추가한다.
 */
class RealOcrTest {

    private val db = PetDb.parse(File("src/main/assets/pets.json").readText())

    private fun load(name: String): List<OcrLine> =
        File("src/test/resources/ocr/$name.txt").readLines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('|')
            val n = p.size
            OcrLine(p.subList(0, n - 4).joinToString("|"), p[n - 4].toInt(), p[n - 3].toInt(), p[n - 2].toInt(), p[n - 1].toInt())
        }

    private fun total(p: ParsedScreen): Double {
        val s = IntArray(4) { p.stats[it]!! }
        return Calculator.total(s, IntArray(4) { s[it] - p.deltas[it]!! }).realTotal
    }

    @Test
    fun market_halfDragonJudy() {
        val p = OcrParser.parse(load("judy"), db)
        assertEquals("하프 드래곤 주디", p.pet?.name)
        assertEquals(150, p.level)
        assertEquals(0, p.breaks ?: 0)
        assertArrayEquals(arrayOf<Int?>(587, 398, 294, 4356), p.stats)
        assertArrayEquals(arrayOf<Int?>(-20, 7, 6, -33), p.deltas)
        // 거래소 화면엔 성장 평균 숫자가 없다. 등급 글자는 읽힌 것만 (생명력 "S" 는 인식이 안 됨)
        assertArrayEquals(arrayOf<Double?>(null, null, null, null), p.growthAvgs)
        assertArrayEquals(arrayOf<String?>("A+", "S+", "S+", null), p.screenGrades)
        assertEquals(99.9, total(p), 1e-9)
        assertEquals(99, p.gameTotal)
    }

    /**
     * 거래소 "청랑": 짧은 이름이 Lv. 보다 왼쪽에 있어 세로줄이 안 겹치고, 같은 세로줄엔
     * "총 성장 점수 총 능력치 점수" 라벨만 있어 그걸 이름으로 넣던 문제.
     * (사용자 캡처 인식못함.jpg 의 글자 위치를 옮겨 적은 자료 — ML Kit 원본 출력은 아님)
     */
    @Test
    fun market_cheongrang_shortNameLeftOfLevel() {
        val p = OcrParser.parse(load("cheongrang"), db)
        assertEquals("청랑", p.pet?.name)
        assertEquals(150, p.level)
        assertArrayEquals(arrayOf<Int?>(566, 289, 483, 4094), p.stats)
        assertArrayEquals(arrayOf<Int?>(0, 3, 6, 23), p.deltas)
        assertEquals(100, p.gameTotal)
        // "[청랑]을 구매하시겠습니까?" 줄이 없어도 (다른 화면) 왼쪽의 짧은 이름을 찾는다
        val noBuy = OcrParser.parse(load("cheongrang").filterNot { it.text.contains("구매하시") }, db)
        assertEquals("청랑", noBuy.pet?.name)
    }

    @Test
    fun infoCard_hwahonrang_4breaks() {
        val p = OcrParser.parse(load("hwa"), db)
        assertEquals("화혼랑", p.pet?.name)
        assertEquals(150, p.level)
        assertEquals(4, p.breaks)
        assertArrayEquals(arrayOf<Int?>(629, 305, 510, 4168), p.stats)
        assertArrayEquals(arrayOf<Int?>(55, 11, 25, 264), p.deltas)
        assertArrayEquals(arrayOf<Double?>(4.11, 2.00, 3.34, 27.29), p.growthAvgs)
        assertArrayEquals(arrayOf<String?>("SS++", "S++", "SS", "SS"), p.screenGrades)
        assertEquals(105, p.gameTotal)
    }

    /** 소환수 목록 위 팝업: 뒤에 깔린 카드의 Lv.100 이 아니라 Lv.1 */
    @Test
    fun listPopup_tiger_notInDb() {
        val p = OcrParser.parse(load("tiger"), db)
        assertNull(p.pet)
        assertEquals("티거", p.nameRaw)
        assertEquals(1, p.level)
        assertArrayEquals(arrayOf<Int?>(8, 5, 5, 68), p.stats)
        assertArrayEquals(arrayOf<Int?>(-1, 0, 0, -2), p.deltas)
        assertEquals(96.475, total(p), 1e-9)
        assertEquals(96, p.gameTotal)
    }

    /** 채팅창 위에 띄운 경우: 왼쪽 메뉴 "시스(템)"를 전설 "이시스"로 잘못 읽던 문제 */
    @Test
    fun chatScreen_blueDevira_notConfusedByMenuOrChat() {
        val p = OcrParser.parse(load("chat"), db)
        assertNull(p.pet)
        assertEquals("블루 데비라", p.nameRaw)
        assertEquals(30, p.level)
        assertEquals(0, p.breaks ?: 0)
        assertArrayEquals(arrayOf<Int?>(53, 37, 39, 468), p.stats)
        assertArrayEquals(arrayOf<Int?>(1, 1, 1, 20), p.deltas)
        assertArrayEquals(arrayOf<Double?>(1.62, 1.14, 1.21, 14.17), p.growthAvgs)
        // "S+t" 처럼 애매하게 읽힌 등급은 버린다
        assertArrayEquals(arrayOf<String?>(null, null, "S++", "S++"), p.screenGrades)
        // 계산기 방식 102.90%. (게임 표기는 103% — 저레벨에서는 게임 내부 도감값이 소수라 1% 차이가 날 수 있다)
        assertEquals(102.9, total(p), 1e-9)
        assertEquals(103, p.gameTotal)
        // 계산만 하면 인게임 102%가 나오지만 화면은 103%. 화면 값에 맞는 실제 범위는 103% 이상
        val s = IntArray(4) { p.stats[it]!! }
        val b = IntArray(4) { s[it] - p.deltas[it]!! }
        assertEquals(102.0, Calculator.total(s, b).gameTotal, 1e-9)
        val r = Calculator.realRangeForGame(s, b, 103)!!
        assertEquals(103.075, r.min, 1e-9)
        assertEquals(103.9, r.max, 1e-9)
    }

    /** 화면의 인게임 값과 맞는 실제 범위는 항상 계산값 근처이고, 말이 안 되는 인게임 값이면 null */
    @Test
    fun realRangeForGame_consistency() {
        val s = intArrayOf(629, 305, 510, 4168)
        val b = intArrayOf(574, 294, 485, 3904)
        val r = Calculator.realRangeForGame(s, b, 105)!!
        assertEquals(106.15, r.min, 1e-9)
        assertEquals(106.35, r.max, 1e-9)
        assertNull(Calculator.realRangeForGame(s, b, 110))
    }
}
