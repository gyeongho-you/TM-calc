package net.g1project.tmcalc.desktop

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import java.util.prefs.Preferences
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** 도감 > 성장률로 추정: 성장률 4개를 넣으면 초기치·만렙S 표와 총능이 나온다 */
class EstimateDialogUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun fillAndShow() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = Preferences.userRoot().node("tmcalc-test-estimate").apply { clear() }
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait { win = MainWindow(DesktopPets(tmp.newFolder()), prefs); win.isVisible = true }
        SwingUtilities.invokeLater {
            MainWindow::class.java.getDeclaredMethod("showEstimateDialog").apply { isAccessible = true }.invoke(win)
        }
        Thread.sleep(1500)
        val dlg = Window.getWindows().first { it.isShowing && it is JDialog && it.title == "성장률로 추정" } as JDialog
        val fields = find(dlg, JTextField::class.java)
        SwingUtilities.invokeAndWait { listOf("3.18", "1.93", "3.75", "25.14").forEachIndexed { i, t -> fields[i].text = t } }
        Thread.sleep(300)
        val texts = find(dlg, JLabel::class.java).map { it.text }
        assertTrue(texts.toString(), "12~13" in texts && "295~296" in texts && "3849" in texts)
        assertTrue(texts.toString(), texts.any { it.startsWith("만렙S 총능 약 1741") })
        snap(dlg, "추정")
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
