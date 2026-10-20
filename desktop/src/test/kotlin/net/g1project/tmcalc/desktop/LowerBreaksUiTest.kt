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

/** 3강짜리에서 '이전 강화 보기' 를 누르면 0~2강 줄이 펼쳐지고, 설정에 기억된다 */
class LowerBreaksUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun foldOutLowerBreaks() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = java.util.prefs.Preferences.userRoot().node("tmcalc-test-lower").apply { clear() }
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait {
            win = MainWindow(DesktopPets(tmp.newFolder()), prefs)
            win.setSize(420, 1100)
            win.isVisible = true
            fun f(name: String) = MainWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(win)
            // 라르곤 150레벨 (도감 541 356 394 4237)
            (f("nameF") as JTextField).text = "라르곤"
            (f("levelF") as JTextField).text = "150"
            (f("breakF") as JTextField).text = "3"
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
        val before = labels()
        assertTrue(before.toString(), before.contains("▶ 이전 강화 보기") && before.none { it.startsWith("<html>0강") })
        SwingUtilities.invokeAndWait {
            fun find(c: Container): JLabel? { for (x in c.components) { if (x is JLabel && x.text == "▶ 이전 강화 보기") return x; if (x is Container) find(x)?.let { return it } }; return null }
            val l = find(win.contentPane as Container)!!
            l.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(l, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false)) }
        }
        Thread.sleep(500)
        val after = labels(); snap("lower-open")
        assertTrue(prefs.getBoolean("showLowerBreaks", false))
        assertTrue(after.toString(), after.contains("▼ 이전 강화 접기") && after.any { it.startsWith("<html>0강") } && after.any { it.startsWith("<html>2강") })
        SwingUtilities.invokeAndWait { win.dispose() }
        prefs.removeNode()
    }
}
