package net.g1project.tmcalc.desktop

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.platform.win32.GDI32
import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinGDI
import com.sun.jna.platform.win32.WinNT
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import com.sun.jna.win32.StdCallLibrary
import com.sun.jna.win32.W32APIOptions
import java.awt.Rectangle
import java.awt.Robot
import java.awt.image.BufferedImage
import java.io.File

/**
 * 앱플레이어 창 찾기 + 그 창만 캡처. 게임/앱플레이어에 입력을 보내거나 메모리를 읽지 않는다 (화면 캡처 프로그램과 같은 방식).
 */
object WindowCapture {

    class Target(val hwnd: WinDef.HWND, val title: String, val process: String) {
        val isEmulator: Boolean get() = EMULATORS.any { process.equals(it, ignoreCase = true) } ||
            TITLES.any { title.contains(it, ignoreCase = true) }
        override fun toString() = if (isEmulator) "★ $title" else title
    }

    /** 앱플레이어 실행 파일 이름 (블루스택, LD플레이어, MuMu, 녹스, 미뮤, scrcpy) */
    private val EMULATORS = listOf("HD-Player.exe", "dnplayer.exe", "MuMuPlayer.exe", "MuMuNxDevice.exe", "Nox.exe", "MEmu.exe", "scrcpy.exe")
    private val TITLES = listOf("BlueStacks", "LDPlayer", "MuMu", "NoxPlayer", "MEmu")

    /**
     * 화면에 보이는 창 목록 (Alt+Tab 과 비슷한 기준). 앱플레이어로 보이는 창을 맨 앞에.
     * 작업 표시줄에 안 나오는 보조 창(툴 창), 시스템이 숨긴 창, 크기가 거의 없는 창은 뺀다
     * (예: 모니터 프로그램의 투명한 전체 화면 창을 고르면 엉뚱한 화면을 읽는다).
     */
    fun list(selfTitle: String): List<Target> {
        val out = ArrayList<Target>()
        User32.INSTANCE.EnumWindows({ hwnd, _ ->
            if (User32.INSTANCE.IsWindowVisible(hwnd) && isUserWindow(hwnd)) {
                val len = User32.INSTANCE.GetWindowTextLength(hwnd)
                if (len > 0) {
                    val buf = CharArray(len + 1)
                    User32.INSTANCE.GetWindowText(hwnd, buf, buf.size)
                    val title = Native.toString(buf)
                    if (title.isNotBlank() && title != selfTitle && title != "Program Manager") out += Target(hwnd, title, processName(hwnd))
                }
            }
            true
        }, null)
        return out.sortedByDescending { it.isEmulator }
    }

    private fun isUserWindow(hwnd: WinDef.HWND): Boolean {
        val ex = User32.INSTANCE.GetWindowLong(hwnd, WinUser.GWL_EXSTYLE)
        if (ex and WS_EX_TOOLWINDOW != 0 && ex and WS_EX_APPWINDOW == 0) return false
        val cloaked = IntByReference()
        if (Dwm.I.DwmGetWindowAttribute(hwnd, DWMWA_CLOAKED, cloaked, 4) == 0 && cloaked.value != 0) return false
        if (User32Ex.I.IsIconic(hwnd)) return true // 최소화된 앱플레이어도 목록에는 둔다 (캡처할 땐 복원 필요)
        val rc = WinDef.RECT()
        User32.INSTANCE.GetClientRect(hwnd, rc)
        return rc.right - rc.left >= 150 && rc.bottom - rc.top >= 150
    }

    private fun processName(hwnd: WinDef.HWND): String {
        val pid = IntByReference()
        User32.INSTANCE.GetWindowThreadProcessId(hwnd, pid)
        val h = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_QUERY_LIMITED_INFORMATION, false, pid.value) ?: return ""
        return try {
            val buf = CharArray(1024); val size = IntByReference(buf.size)
            if (Kernel32.INSTANCE.QueryFullProcessImageName(h, 0, buf, size)) File(String(buf, 0, size.value)).name else ""
        } finally {
            Kernel32.INSTANCE.CloseHandle(h)
        }
    }

    /** 창 안쪽(제목줄 제외)을 캡처. 최소화돼 있거나 창이 없어졌으면 null */
    fun capture(t: Target): BufferedImage? {
        val u = User32.INSTANCE
        if (!u.IsWindow(t.hwnd) || User32Ex.I.IsIconic(t.hwnd)) return null
        val rc = WinDef.RECT()
        u.GetClientRect(t.hwnd, rc)
        val w = rc.right - rc.left; val h = rc.bottom - rc.top
        if (w < 50 || h < 50) return null
        return printWindow(t.hwnd, w, h)?.takeUnless { isBlank(it) } ?: robot(t.hwnd, w, h)
    }

    /** PrintWindow: 다른 창에 가려져 있어도 그 창 내용만 찍는다 */
    private fun printWindow(hwnd: WinDef.HWND, w: Int, h: Int): BufferedImage? {
        val g = GDI32.INSTANCE
        val hdcWin = User32.INSTANCE.GetDC(hwnd) ?: return null
        val hdcMem = g.CreateCompatibleDC(hdcWin)
        val bmp = g.CreateCompatibleBitmap(hdcWin, w, h)
        val old = g.SelectObject(hdcMem, bmp)
        try {
            if (!User32.INSTANCE.PrintWindow(hwnd, hdcMem, PW_CLIENTONLY or PW_RENDERFULLCONTENT)) return null
            val bmi = WinGDI.BITMAPINFO().apply {
                bmiHeader.biWidth = w; bmiHeader.biHeight = -h // 위에서 아래로
                bmiHeader.biPlanes = 1; bmiHeader.biBitCount = 32; bmiHeader.biCompression = WinGDI.BI_RGB
            }
            val mem = Memory(w.toLong() * h * 4)
            g.GetDIBits(hdcMem, bmp, 0, h, mem, bmi, WinGDI.DIB_RGB_COLORS)
            val px = mem.getIntArray(0, w * h)
            return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, px, 0, w) }
        } finally {
            g.SelectObject(hdcMem, old); g.DeleteObject(bmp); g.DeleteDC(hdcMem)
            User32.INSTANCE.ReleaseDC(hwnd, hdcWin)
        }
    }

    /** PrintWindow 가 검은 화면을 주는 앱플레이어용: 화면에서 그 창 영역을 찍는다 (창이 보이고 있어야 함) */
    private fun robot(hwnd: WinDef.HWND, w: Int, h: Int): BufferedImage? {
        val p = WinDef.POINT()
        User32Ex.I.ClientToScreen(hwnd, p)
        return runCatching { Robot().createScreenCapture(Rectangle(p.x, p.y, w, h)) }.getOrNull()
    }

    /** 거의 한 색이면(검은 화면) 캡처 실패로 본다 */
    private fun isBlank(img: BufferedImage): Boolean {
        val first = img.getRGB(0, 0)
        for (y in 0 until img.height step 17) for (x in 0 until img.width step 17) if (img.getRGB(x, y) != first) return false
        return true
    }

    /** jna-platform 에 없는 user32 함수 */
    @Suppress("FunctionName")
    private interface User32Ex : StdCallLibrary {
        fun IsIconic(hwnd: WinDef.HWND): Boolean
        fun ClientToScreen(hwnd: WinDef.HWND, point: WinDef.POINT): Boolean
        companion object { val I: User32Ex = Native.load("user32", User32Ex::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    }

    private interface Dwm : StdCallLibrary {
        fun DwmGetWindowAttribute(hwnd: WinDef.HWND, attr: Int, value: IntByReference, size: Int): Int
        companion object { val I: Dwm = Native.load("dwmapi", Dwm::class.java, W32APIOptions.DEFAULT_OPTIONS) }
    }

    private const val WS_EX_TOOLWINDOW = 0x80
    private const val WS_EX_APPWINDOW = 0x40000
    private const val DWMWA_CLOAKED = 14
    private const val PW_CLIENTONLY = 1
    private const val PW_RENDERFULLCONTENT = 2
}
