package net.g1project.tmcalc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.io.File

class CalculatorTest {

    private val db = PetDb.parse(File("src/main/assets/pets.json").readText())

    /** 원본 시트에 저장돼 있던 예시: 플루스타 / 150 / 18, 13, 10, -2 / 돌파 0 */
    @Test
    fun matchesSheetExample() {
        val pet = db.find("플루스타")!!
        val stats = IntArray(4) { pet.maxS[it] + intArrayOf(18, 13, 10, -2)[it] }
        val r = Calculator.compute(Calculator.Input(pet, 150, stats, 0))

        assertEquals(listOf("S++", "S++", "S++", "S"), r.statGrades)
        assertArrayEquals(doubleArrayOf(103.0, 103.3, 103.1, 99.9), r.percentile, 1e-9)
        assertEquals(102.325, r.realTotal, 1e-9)
        assertEquals("S+", r.realTotalGrade)
        assertEquals(102.0, r.gameTotal, 1e-9)
        assertEquals("S+", r.gameTotalGrade)
        assertEquals(1780.8, r.myTotal, 1e-9)
        assertEquals("S+", r.curGrade)
        assertEquals(1757.4, r.curGradeTotal!!, 1e-9)
        assertEquals(103.0, r.nextGrowthPct, 1e-9)
        assertEquals(1775.0, r.nextGrowthTotal!!, 1e-9)
        assertEquals("S++", r.nextGrade)
        assertEquals(1792.2, r.nextGradeTotal!!, 1e-9)
        assertEquals(1.329545455, r.toNextGrowth!!, 1e-8)
        assertEquals(0.6724137931, r.toNextGrade!!, 1e-8)

        val b = r.breakRows
        assertArrayEquals(intArrayOf(610, 615, 621, 627, 633), b.map { it.stats[0] }.toIntArray())
        assertArrayEquals(intArrayOf(4521, 4563, 4606, 4648, 4691), b.map { it.stats[3] }.toIntArray())
        assertArrayEquals(doubleArrayOf(2.67, 2.7, 2.72, 2.74, 2.77), b.map { it.growth[1] }.toDoubleArray(), 1e-9)
        assertEquals(listOf("S++", "SS", "SS", "SS+", "SS+"), b.map { it.grades[0] })
        assertEquals(listOf("S", "S+", "S+", "S++", "S++"), b.map { it.grades[3] })
        assertArrayEquals(doubleArrayOf(1798.1, 1814.3, 1831.6, 1847.8, 1865.1), b.map { it.total }.toDoubleArray(), 1e-9)
    }

    /** 시트에 첨부된 게임 캡처: 화혼랑(+4) Lv.150, 게임 표기 105% / 1,860.8 / SS++ S++ SS SS */
    @Test
    fun matchesInGameScreenshot() {
        val pet = db.find("화혼랑")!!
        val r = Calculator.compute(Calculator.Input(pet, 150, intArrayOf(629, 305, 510, 4168), 4))
        assertEquals(105.0, r.gameTotal, 1e-9)
        assertEquals(1860.8, r.myTotal, 1e-9)
        assertEquals(listOf("SS++", "S++", "SS", "SS"), r.statGrades)
        assertArrayEquals(intArrayOf(55, 11, 25, 264), Calculator.Input(pet, 150, intArrayOf(629, 305, 510, 4168), 4).deltas)
    }

    /** 거래소 화면: 하프 드래곤 주디 Lv.150, 587(-20) 398(+7) 294(+6) 4,356(-33), 게임 표기 99% / 1,714.6 */
    @Test
    fun parsesMarketScreen() {
        val lines = listOf(
            OcrLine("거래소", 110, 140, 215, 180), OcrLine("312.9만", 525, 140, 628, 175),
            OcrLine("전설", 105, 320, 150, 348), OcrLine("물", 240, 320, 265, 348), OcrLine("던전", 360, 320, 400, 348),
            OcrLine("77위", 478, 318, 532, 350),
            OcrLine("하프 드래곤 주디", 68, 392, 288, 428),
            OcrLine("총 성장 점수", 92, 528, 218, 555), OcrLine("총 능력치 점수", 256, 528, 402, 555),
            OcrLine("99%", 100, 560, 166, 594), OcrLine("1,714.6", 260, 560, 372, 594),
            OcrLine("Lv. 150", 136, 762, 250, 804), OcrLine("환생 회복 가능", 688, 772, 835, 802),
            OcrLine("능력치", 138, 842, 204, 868), OcrLine("기본 능력 (도감 대비)", 440, 842, 642, 868), OcrLine("성장평균", 695, 842, 784, 868),
            OcrLine("공격력", 145, 892, 220, 924), OcrLine("587 (-20)", 460, 890, 597, 926), OcrLine("A+", 702, 892, 740, 924),
            OcrLine("방어력", 145, 937, 220, 969), OcrLine("398 (+7)", 466, 935, 590, 971), OcrLine("S+", 702, 937, 738, 969),
            OcrLine("속도", 145, 982, 196, 1014), OcrLine("294 (+6)", 466, 980, 590, 1016), OcrLine("S+", 702, 982, 738, 1014),
            OcrLine("생명력", 145, 1026, 220, 1058), OcrLine("4,356 (-33)", 445, 1024, 612, 1060), OcrLine("S", 702, 1026, 720, 1058),
            OcrLine("현재 시세", 114, 1160, 222, 1192), OcrLine("1,181,827", 370, 1158, 490, 1190),
            OcrLine("[하프 드래곤 주디]을 구매하시겠습니까?", 226, 1296, 698, 1330),
            OcrLine("구매 금액", 114, 1400, 222, 1434), OcrLine("1,999,999", 640, 1398, 770, 1430),
        )
        val p = OcrParser.parse(lines, db)
        assertEquals("하프 드래곤 주디", p.pet!!.name)
        assertEquals(150, p.level)
        assertEquals(0, p.breaks ?: 0)
        assertArrayEquals(arrayOf<Int?>(587, 398, 294, 4356), p.stats)
        assertArrayEquals(arrayOf<Int?>(-20, 7, 6, -33), p.deltas)

        val r = Calculator.compute(Calculator.Input(p.pet!!, 150, intArrayOf(587, 398, 294, 4356), 0))
        assertEquals(99.0, r.gameTotal, 1e-9)
        assertEquals(1714.6, r.myTotal, 1e-9)
        assertEquals(listOf("A+", "S+", "S+", "S"), r.statGrades)
    }

    /** 소환수 목록 팝업: 뒤에 깔린 카드의 "Lv.100" 이 아니라 팝업의 Lv.1 을 읽어야 한다 */
    @Test
    fun picksLevelNearName() {
        val lines = listOf(
            OcrLine("화혼랑", 336, 372, 400, 404), OcrLine("Lv. 1", 336, 420, 392, 454),
            OcrLine("공격력", 172, 870, 248, 900), OcrLine("8 (-1)", 370, 868, 450, 902),
            OcrLine("Lv.100", 116, 1550, 190, 1580), OcrLine("Lv.60", 328, 1550, 392, 1580),
        )
        val p = OcrParser.parse(listOf(lines[4], lines[5]) + lines.take(4), db)
        assertEquals(1, p.level)
        assertEquals(8, p.stats[0])
    }

    /** 도감에 없는 유일 등급 티거 Lv.1: 8(-1) 5 - 5 - 68(-2), 게임 표기 96% */
    @Test
    fun totalWithoutDbFromScreenOnly() {
        val lines = listOf(
            OcrLine("유일", 336, 326, 450, 356), OcrLine("바람", 462, 326, 576, 356), OcrLine("야수", 590, 326, 702, 356),
            OcrLine("티거", 336, 370, 392, 404), OcrLine("Lv. 1", 336, 420, 392, 454),
            OcrLine("총 성장 점수", 166, 618, 290, 644), OcrLine("96%", 172, 668, 244, 704),
            OcrLine("공격력", 172, 870, 248, 900), OcrLine("8 (-1)", 370, 868, 450, 902), OcrLine("?", 680, 870, 696, 900),
            OcrLine("방어력", 172, 916, 248, 946), OcrLine("5 -", 370, 916, 412, 946), OcrLine("?", 680, 916, 696, 946),
            OcrLine("속도", 172, 962, 222, 992), OcrLine("5 -", 370, 962, 412, 992), OcrLine("?", 680, 962, 696, 992),
            OcrLine("생명력", 172, 1008, 248, 1038), OcrLine("68 (-2)", 350, 1006, 454, 1040), OcrLine("?", 680, 1008, 696, 1038),
            OcrLine("Lv.100", 116, 1550, 190, 1580),
        )
        val p = OcrParser.parse(lines, db)
        assertEquals(null, p.pet)
        assertArrayEquals(arrayOf<Int?>(8, 5, 5, 68), p.stats)
        assertArrayEquals(arrayOf<Int?>(-1, 0, 0, -2), p.deltas)
        assertNotNull(p.deltaBoxes[0])

        val stats = IntArray(4) { p.stats[it]!! }
        val t = Calculator.total(stats, IntArray(4) { stats[it] - p.deltas[it]!! })
        assertEquals(96.475, t.realTotal, 1e-9)
        assertEquals(96.0, t.gameTotal, 1e-9)
    }

    /** 도감 없이 화면값만으로 계산해도 시트(도감 사용) 결과와 같아야 한다 */
    @Test
    fun screenOnlyTotalEqualsSheet() {
        val pet = db.find("하프 드래곤 주디")!!
        val stats = intArrayOf(587, 398, 294, 4356)
        val fromScreen = Calculator.total(stats, IntArray(4) { stats[it] - intArrayOf(-20, 7, 6, -33)[it] })
        val sheet = Calculator.compute(Calculator.Input(pet, 150, stats, 0))
        assertEquals(sheet.realTotal, fromScreen.realTotal, 1e-12)
        assertEquals(99.9, fromScreen.realTotal, 1e-9)
    }

    /** 기본(도감) 총 능력치 점수 = 시트 E10 / 도감 Q열, 목표 강 = 시트 추가돌파 표 */
    @Test
    fun abilityScoreAndTargetBreaks() {
        val pet = db.find("플루스타")!!
        assertEquals(1740.0, Calculator.abilityScore(pet.maxS), 1e-9)
        assertEquals(1743.4, Calculator.abilityScore(db.find("화혼랑")!!.maxS), 1e-9)

        val cur = IntArray(4) { pet.maxS[it] + intArrayOf(18, 13, 10, -2)[it] }
        assertArrayEquals(intArrayOf(621, 416, 334, 4606), Calculator.statsAtBreaks(cur, 0, 3)) // 시트 D35:D38
        assertArrayEquals(cur, Calculator.statsAtBreaks(cur, 2, 2))

        // 강화별 등급 = 시트 추가돌파 표(B43:F46)와 현재 등급(B20:B23)
        assertEquals(listOf("S++", "S++", "S++", "S"), Calculator.statGrades(pet, 150, cur, roundGrowth = false))
        assertEquals(listOf("SS", "SS", "SS", "S+"),
            Calculator.statGrades(pet, 150, Calculator.statsAtBreaks(cur, 0, 3), roundGrowth = true)) // 시트 D43:D46
    }

    @Test
    fun parsesScreenshotLikeOcr() {
        // ML Kit이 저 화면에서 돌려줄 법한 줄들 (라벨과 숫자가 따로 잡히고, 쉼표가 점으로 읽히는 경우 포함)
        val lines = listOf(
            OcrLine("전설", 200, 40, 280, 70), OcrLine("화염", 320, 40, 400, 70), OcrLine("마법", 440, 40, 540, 70),
            OcrLine("화흔랑 (+4)", 198, 85, 352, 118),
            OcrLine("Lv. 150", 198, 128, 292, 162), OcrLine("4위", 520, 140, 570, 168),
            OcrLine("잠재력", 38, 205, 110, 232), OcrLine("성장 등급", 326, 205, 420, 232),
            OcrLine("총 성장 점수", 38, 316, 160, 342), OcrLine("105%", 44, 366, 128, 396),
            OcrLine("1,860.8", 330, 366, 450, 396),
            OcrLine("능력치 기본 능력 (도감 대비) 성장 평균", 38, 512, 520, 538),
            OcrLine("공격력", 42, 555, 118, 585), OcrLine("629 (55)", 194, 553, 318, 587), OcrLine("4.11 SS++", 436, 553, 596, 587),
            OcrLine("방어력", 42, 598, 118, 628), OcrLine("305 (11)", 194, 596, 314, 630), OcrLine("2.00 S++", 450, 596, 576, 630),
            OcrLine("속도", 42, 643, 90, 673), OcrLine("510 (25)", 194, 641, 318, 675), OcrLine("3.34 SS", 450, 641, 562, 675),
            OcrLine("생명력", 42, 686, 118, 716), OcrLine("4.168 (264)", 172, 684, 336, 718), OcrLine("27.29 SS", 436, 684, 562, 718),
        )
        val p = OcrParser.parse(lines, db)
        assertNotNull(p.pet)
        assertEquals("화혼랑", p.pet!!.name)
        assertEquals(150, p.level)
        assertEquals(4, p.breaks)
        assertArrayEquals(arrayOf<Int?>(629, 305, 510, 4168), p.stats)
        assertArrayEquals(arrayOf<Int?>(55, 11, 25, 264), p.deltas)
    }
}
