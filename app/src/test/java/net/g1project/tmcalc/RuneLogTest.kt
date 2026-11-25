package net.g1project.tmcalc

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuneLogTest {
    private fun rune(level: Int, atk: String) = RuneInfo("전설", "테스트 룬", level, RuneParser.num(atk), emptyList(),
        listOf("피해 증가" to RuneParser.num("4.38%")!!))

    @Test fun exactValuesMakeTableAndEstimateMan() {
        val a = JSONArray()
        a.put(RuneLog.entry(rune(100, "5000"), 1)); a.put(RuneLog.entry(rune(200, "9000"), 2))
        a.put(RuneLog.entry(rune(300, "1.3만+"), 3))
        val tb = RuneLog.table(a)
        // 정확한 값만 표에 (1.3만은 빠짐)
        assertEquals(setOf("100", "200"), tb.getJSONObject("전설|공격력").keys().asSequence().toSet())
        // 직선 5000 → 9000 (100레벨당 4000) → Lv.300 = 13000, 화면 범위 13000~13999 안
        assertEquals(13000.0, RuneLog.estimate(tb, "전설", "공격력", 300, 13000.0, 13999.0)!!, 0.0)
        val s = RuneLog.summary(a)
        assertTrue(s, s.contains("[전설] 공격력") && s.contains("Lv.300  1.3만+  ≈ 13,000 (추정)"))
    }
}
