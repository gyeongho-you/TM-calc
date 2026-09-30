package net.g1project.tmcalc.desktop

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinUser
import java.awt.event.KeyEvent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 계산 단축키. Windows RegisterHotKey 로 고른 키 조합 하나만 등록한다 — 모든 키 입력을 엿보는 키보드 후킹이 아니라
 * 그 조합이 눌렸을 때만 윈도우가 알려주는 방식. 프로그램을 끄면 윈도우가 등록을 자동으로 푼다.
 */
class GlobalHotkey {

    /** [mods] = MOD_ALT/MOD_CONTROL/MOD_SHIFT 조합, [vk] = Windows 가상 키 코드. vk 0 은 "사용 안 함" */
    data class Key(val mods: Int, val vk: Int) {
        val off: Boolean get() = vk == 0
        override fun toString(): String = if (off) "사용 안 함" else
            (listOfNotNull("Ctrl".takeIf { mods and MOD_CONTROL != 0 }, "Alt".takeIf { mods and MOD_ALT != 0 },
                "Shift".takeIf { mods and MOD_SHIFT != 0 }) + keyName(vk)).joinToString("+")
    }

    private var thread: Thread? = null
    @Volatile private var threadId = 0

    /** 단축키를 바꾼다 (이전 것은 풀림). 다른 프로그램이 이미 쓰고 있으면 false */
    fun set(key: Key, onPress: () -> Unit): Boolean {
        stop()
        if (key.off) return true
        var ok = false
        val ready = CountDownLatch(1)
        // 등록한 스레드가 메시지를 받아야 해서 전용 스레드를 둔다
        thread = Thread({
            val u = User32.INSTANCE
            threadId = Kernel32.INSTANCE.GetCurrentThreadId()
            ok = u.RegisterHotKey(null, ID, key.mods or MOD_NOREPEAT, key.vk)
            ready.countDown()
            if (!ok) return@Thread
            val msg = WinUser.MSG()
            while (u.GetMessage(msg, null, 0, 0) > 0) {
                if (msg.message == WinUser.WM_HOTKEY && msg.wParam.toInt() == ID) onPress()
            }
            u.UnregisterHotKey(null, ID)
        }, "hotkey").apply { isDaemon = true; start() }
        ready.await(2, TimeUnit.SECONDS)
        return ok
    }

    fun stop() {
        val t = thread ?: return
        if (t.isAlive) User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, WinDef.WPARAM(0), WinDef.LPARAM(0))
        t.join(1000)
        thread = null
    }

    companion object {
        const val MOD_ALT = 0x1
        const val MOD_CONTROL = 0x2
        const val MOD_SHIFT = 0x4
        private const val MOD_NOREPEAT = 0x4000
        private const val ID = 1
        val DEFAULT = Key(0, 0x78) // F9

        /**
         * 설정 창에서 누른 키(Java KeyEvent) → Windows 키. 쓸 수 있는 키만 (F1~F24, 문자, 숫자, 숫자패드).
         * 문자/숫자/숫자패드는 평소 입력을 막지 않도록 Ctrl·Alt·Shift 와 같이 눌러야 한다. 안 되면 null + 이유.
         */
        fun fromKeyEvent(e: KeyEvent): Pair<Key?, String?> {
            val mods = (if (e.isControlDown) MOD_CONTROL else 0) or (if (e.isAltDown) MOD_ALT else 0) or
                (if (e.isShiftDown) MOD_SHIFT else 0)
            val c = e.keyCode
            val vk = when (c) {
                in KeyEvent.VK_F1..KeyEvent.VK_F12 -> 0x70 + (c - KeyEvent.VK_F1)
                in KeyEvent.VK_F13..KeyEvent.VK_F24 -> 0x7C + (c - KeyEvent.VK_F13)
                in KeyEvent.VK_A..KeyEvent.VK_Z, in KeyEvent.VK_0..KeyEvent.VK_9 -> c // Windows 와 같은 코드
                in KeyEvent.VK_NUMPAD0..KeyEvent.VK_NUMPAD9 -> 0x60 + (c - KeyEvent.VK_NUMPAD0)
                KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_SHIFT -> return null to null // 조합 키만 누른 상태
                else -> return null to "이 키는 쓸 수 없어요 (F1~F24, 문자, 숫자, 숫자패드만)"
            }
            val isFn = vk in 0x70..0x87
            if (!isFn && mods == 0) return null to "문자·숫자 키는 Ctrl, Alt, Shift 중 하나와 같이 눌러 주세요 (평소 입력이 막히지 않게)"
            return Key(mods, vk) to null
        }

        private fun keyName(vk: Int) = when (vk) {
            in 0x70..0x87 -> "F${vk - 0x6F}"
            in 0x60..0x69 -> "Num${vk - 0x60}"
            else -> vk.toChar().toString()
        }
    }
}
