package net.g1project.tmcalc.desktop

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** 설정 > 계산 단축키: 키 조합을 누르고 저장하면 버튼 이름과 저장된 설정이 바뀐다 */
class HotkeyDialogUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun pickAndSave() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = Preferences.userRoot().node("tmcalc-test-hotkey").apply { clear() }
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait { win = MainWindow(DesktopPets(tmp.newFolder()), prefs); win.isVisible = true }
        SwingUtilities.invokeLater {
            MainWindow::class.java.getDeclaredMethod("showHotkeyDialog").apply { isAccessible = true }.invoke(win)
        }
        Thread.sleep(1500)
        val dlg = Window.getWindows().first { it.isShowing && it is JDialog && it.title == "계산 단축키" } as JDialog
        val catcher = find(dlg, JTextField::class.java).first()
        // Ctrl+Alt+K 를 누른 것처럼
        SwingUtilities.invokeAndWait {
            catcher.dispatchEvent(KeyEvent(catcher, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK, KeyEvent.VK_K, KeyEvent.CHAR_UNDEFINED))
        }
        snap(dlg, "단축키")
        SwingUtilities.invokeAndWait { find(dlg, JButton::class.java).first { it.text == "저장" }.doClick() }
        Thread.sleep(500)
        val scanBtn = MainWindow::class.java.getDeclaredField("scanBtn").apply { isAccessible = true }.get(win) as JButton
        assertEquals("계산 (Ctrl+Alt+K)", scanBtn.text)
        assertEquals(GlobalHotkey.MOD_CONTROL or GlobalHotkey.MOD_ALT, prefs.getInt("hotkeyMods", -1))
        assertEquals('K'.code, prefs.getInt("hotkeyVk", -1))
        SwingUtilities.invokeAndWait { Window.getWindows().forEach { it.dispose() } }
        prefs.removeNode()
    }

    private fun <T> find(c: java.awt.Container, type: Class<T>): List<T> =
        c.components.flatMap { if (type.isInstance(it)) listOf(type.cast(it)) else if (it is java.awt.Container) find(it, type) else emptyList() }

    private fun snap(w: Window, name: String) {
        val img = BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { w.paint(img.graphics) }
        File("build/ui").mkdirs()
        ImageIO.write(img, "png", File("build/ui/$name.png"))
    }
}
