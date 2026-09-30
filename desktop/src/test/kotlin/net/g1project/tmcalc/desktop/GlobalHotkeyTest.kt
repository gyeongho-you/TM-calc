package net.g1project.tmcalc.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.JLabel

/** 설정 창에서 누른 키 → Windows 단축키 변환 */
class GlobalHotkeyTest {

    private fun press(code: Int, mods: Int = 0) =
        GlobalHotkey.fromKeyEvent(KeyEvent(JLabel(), KeyEvent.KEY_PRESSED, 0, mods, code, KeyEvent.CHAR_UNDEFINED))

    @Test
    fun functionKeysAlone() {
        assertEquals(GlobalHotkey.Key(0, 0x78), press(KeyEvent.VK_F9).first)
        assertEquals("F9", press(KeyEvent.VK_F9).first.toString())
        assertEquals(GlobalHotkey.Key(0, 0x87), press(KeyEvent.VK_F24).first)
        assertEquals("F24", press(KeyEvent.VK_F24).first.toString())
    }

    @Test
    fun combos() {
        val k = press(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK).first!!
        assertEquals(GlobalHotkey.Key(GlobalHotkey.MOD_CONTROL or GlobalHotkey.MOD_SHIFT, 'C'.code), k)
        assertEquals("Ctrl+Shift+C", k.toString())
        assertEquals("Alt+Num5", press(KeyEvent.VK_NUMPAD5, InputEvent.ALT_DOWN_MASK).first.toString())
        assertEquals("Ctrl+F9", press(KeyEvent.VK_F9, InputEvent.CTRL_DOWN_MASK).first.toString())
    }

    /** 문자/숫자 단독은 평소 입력을 막으니 거부, 조합 키만 누른 상태는 아직 기다림(에러 없음) */
    @Test
    fun rejectsPlainLettersAndWaitsOnModifiers() {
        val (k, err) = press(KeyEvent.VK_A)
        assertNull(k); assertNotNull(err)
        assertEquals(null to null, press(KeyEvent.VK_CONTROL, InputEvent.CTRL_DOWN_MASK))
        assertNull(press(KeyEvent.VK_ENTER).first)
        assertEquals("사용 안 함", GlobalHotkey.Key(0, 0).toString())
    }
}
