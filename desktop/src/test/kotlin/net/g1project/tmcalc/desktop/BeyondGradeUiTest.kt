package net.g1project.tmcalc.desktop

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Component
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** SS++ 를 넘는 능력치는 무지개 SSS 칸으로 그리고 설명 줄이 나온다 */
class BeyondGradeUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun rainbowCells() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = java.util.prefs.Preferences.userRoot().node("tmcalc-test-sss").apply { clear() }
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait {
            win = MainWindow(DesktopPets(tmp.newFolder()), prefs); win.setSize(420, 1300); win.isVisible = true
            fun f(name: String) = MainWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(win)
            // 라르곤 도감 541 356 394 4237: 공 ×1.13(611) → SSS+, 순 ×1.115(439) → SSS
            (f("nameF") as JTextField).text = "라르곤"; (f("levelF") as JTextField).text = "150"; (f("breakF") as JTextField).text = "0"
            val st = f("statF") as Array<*>; val de = f("deltaF") as Array<*>
            listOf(611, 360, 439, 4300).forEachIndexed { i, v -> (st[i] as JTextField).text = "$v" }
            listOf(70, 4, 45, 63).forEachIndexed { i, v -> (de[i] as JTextField).text = "$v" }
            (f("detail") as Component).isVisible = true
            win.revalidate()
        }
        Thread.sleep(800)
        var cells = 0; val texts = mutableListOf<String>()
        fun walk(c: Container) { for (x in c.components) { if (x is RainbowCell) cells++; if (x is javax.swing.JLabel) texts += x.text.orEmpty(); if (x is Container) walk(x) } }
        SwingUtilities.invokeAndWait { walk(win.contentPane as Container) }
        assertTrue("무지개 칸 $cells", cells >= 2)
        assertTrue(texts.any { it.contains("계산기 전용 등급") })
        val img = BufferedImage(win.width, win.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { win.paint(img.graphics) }
        File("build/ui").mkdirs(); ImageIO.write(img, "png", File("build/ui/sss.png"))
        SwingUtilities.invokeAndWait { win.dispose() }
        prefs.removeNode()
    }
}
