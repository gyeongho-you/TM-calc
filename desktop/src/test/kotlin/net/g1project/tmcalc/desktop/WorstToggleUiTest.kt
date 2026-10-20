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
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** 강화별 표의 '최저치로 보기' 를 켜면 예상 능력치가 같거나 1 낮아지고, 설정에 기억된다 */
class WorstToggleUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun toggleLowersPrediction() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = java.util.prefs.Preferences.userRoot().node("tmcalc-test-worst").apply { clear() }
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait {
            win = MainWindow(DesktopPets(tmp.newFolder()), prefs)
            win.setSize(420, 1100)
            win.isVisible = true
            fun f(name: String) = MainWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(win)
            // 라르곤 150레벨 (도감 541 356 394 4237)
            (f("nameF") as JTextField).text = "라르곤"
            (f("levelF") as JTextField).text = "150"
            (f("breakF") as JTextField).text = "0"
            val st = f("statF") as Array<*>; val de = f("deltaF") as Array<*>
            listOf(559, 360, 405, 3981).forEachIndexed { i, v -> (st[i] as JTextField).text = "$v" }
            listOf(18, 4, 11, -256).forEachIndexed { i, v -> (de[i] as JTextField).text = "$v" }
            (f("detail") as Component).isVisible = true
            win.revalidate()
        }
        Thread.sleep(800)
        fun labels(): List<String> {
            val out = mutableListOf<String>()
            fun walk(c: Container) { for (x in c.components) { if (x is JLabel) out += x.text.orEmpty(); if (x is Container) walk(x) } }
            SwingUtilities.invokeAndWait { walk(win.contentPane as Container) }
            return out
        }
        fun snap(name: String) {
            val img = BufferedImage(win.width, win.height, BufferedImage.TYPE_INT_RGB)
            SwingUtilities.invokeAndWait { win.paint(img.graphics) }
            File("build/ui").mkdirs(); ImageIO.write(img, "png", File("build/ui/$name.png"))
        }
        val before = labels(); snap("worst-off")
        var box: javax.swing.JCheckBox? = null
        fun find(c: Container) { for (x in c.components) { if (x is javax.swing.JCheckBox && x.text == "최저치로 보기") box = x; if (x is Container) find(x) } }
        SwingUtilities.invokeAndWait { find(win.contentPane as Container); box!!.doClick() }
        Thread.sleep(500)
        val after = labels(); snap("worst-on")
        assertTrue(prefs.getBoolean("worstBreaks", false))
        assertTrue(after.any { it.contains("최저치)") })
        // 5강 줄의 공격: 예상 559/1.00*1.05=587 → 최저 (558.5*1.05=586.4) 586
        assertTrue(before.toString(), before.any { it.startsWith("<html>587") })
        assertTrue(after.toString(), after.any { it.startsWith("<html>586") })
        SwingUtilities.invokeAndWait { win.dispose() }
        prefs.removeNode()
    }
}
