package net.g1project.tmcalc.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.GraphicsEnvironment
import java.awt.Robot
import java.awt.event.KeyEvent
import java.util.concurrent.atomic.AtomicInteger

/** 단축키를 바꾸면 윈도우에 새 조합이 등록되고, 이전 키는 풀리는지 (실제 키 입력으로 확인) */
class HotkeySwitchTest {

    private fun press(vararg keys: Int) {
        val r = Robot()
        keys.forEach { r.keyPress(it) }
        keys.reversed().forEach { r.keyRelease(it) }
        Thread.sleep(400)
    }

    @Test
    fun switchAndRelease() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val hits = AtomicInteger()
        val hk = GlobalHotkey()
        val combo = GlobalHotkey.Key(GlobalHotkey.MOD_CONTROL or GlobalHotkey.MOD_SHIFT, 0x78) // Ctrl+Shift+F9
        assumeTrue("Ctrl+Shift+F9 를 다른 프로그램이 쓰고 있음", hk.set(combo) { hits.incrementAndGet() })

        press(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_F9)
        assertEquals("Ctrl+Shift+F9 로 계산", 1, hits.get())
        press(KeyEvent.VK_F9)
        assertEquals("F9 만 누르면 반응하지 않아야 함", 1, hits.get())

        // 끄면 윈도우 등록이 풀려서 다른 곳(여기선 새 인스턴스)에서 같은 조합을 다시 등록할 수 있다
        hk.set(GlobalHotkey.Key(0, 0)) {}
        press(KeyEvent.VK_CONTROL, KeyEvent.VK_SHIFT, KeyEvent.VK_F9)
        assertEquals("사용 안 함이면 반응 없음", 1, hits.get())
        val other = GlobalHotkey()
        assertTrue("풀린 조합은 다시 등록 가능", other.set(combo) {})
        other.stop()
    }
}
