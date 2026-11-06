package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.Preset
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JDialog
import javax.swing.SwingUtilities

/** 저장 목록 창: 보기/기준으로/비교/이름·메모/삭제, 내보내기/올리기 버튼과 메모 표시 */
class PresetDialogUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun buttonsAndMemo() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val dir = tmp.newFolder()
        File(dir, "presets.json").writeText(Preset.listToJson(listOf(
            Preset("화혼랑 3강 103%", "화혼랑", 150, 3, intArrayOf(593, 309, 481, 4231), intArrayOf(574, 294, 485, 3904), 103, "PVP용"),
            Preset("라르곤 0강", "라르곤", 150, 0, intArrayOf(559, 360, 405, 3981), intArrayOf(541, 356, 394, 4237)))))
        val prefs = java.util.prefs.Preferences.userRoot().node("tmcalc-test-presetdlg").apply { clear() }
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait { win = MainWindow(DesktopPets(dir), prefs); win.isVisible = true }
        SwingUtilities.invokeLater { MainWindow::class.java.getDeclaredMethod("showPresets").apply { isAccessible = true }.invoke(win) }
        Thread.sleep(1200)
        val dlg = Window.getWindows().first { it.isShowing && it is JDialog && it.title == "저장 목록" } as JDialog
        val buttons = mutableListOf<String>()
        fun walk(c: Container) { for (x in c.components) { if (x is JButton) buttons += x.text; if (x is Container) walk(x) } }
        SwingUtilities.invokeAndWait { walk(dlg.contentPane as Container) }
        assertTrue(buttons.toString(), buttons.containsAll(listOf("보기", "기준으로", "비교", "이름/메모", "삭제", "내보내기", "올리기")))
        val img = BufferedImage(dlg.width, dlg.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { dlg.paint(img.graphics) }
        File("build/ui").mkdirs(); ImageIO.write(img, "png", File("build/ui/preset-dialog.png"))
        SwingUtilities.invokeAndWait { Window.getWindows().forEach { it.dispose() } }
        prefs.removeNode()
    }
}
