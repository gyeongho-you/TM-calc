package net.g1project.tmcalc.desktop

import org.junit.Assert.assertFalse
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

/**
 * 자세히 화면(능력치 표, 강화 표)이 제대로 그려지는지.
 * 표 칸은 "능력치<br>등급" HTML 로 그리므로, HTML 글자(<html>, <br>)가 그대로 보이면 안 된다.
 */
class DetailUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun detailTablesRender() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = java.util.prefs.Preferences.userRoot().node("tmcalc-test-detail").apply { clear() }
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
        val img = BufferedImage(win.width, win.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { win.paint(img.graphics) }
        File("build/ui").mkdirs()
        ImageIO.write(img, "png", File("build/ui/detail.png"))
        val labels = mutableListOf<JLabel>()
        fun walk(c: Container) { for (x in c.components) { if (x is JLabel) labels += x; if (x is Container) walk(x) } }
        SwingUtilities.invokeAndWait { walk(win.contentPane as Container) }
        // 강화 표의 "2강" 줄을 누르면 표 아래에 2강의 능력치별 전투가 나온다
        SwingUtilities.invokeAndWait {
            val cell = labels.first { it.text == "2강" }
            cell.mouseListeners.forEach { it.mouseClicked(java.awt.event.MouseEvent(cell, java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false)) }
        }
        Thread.sleep(300)
        // 2강 줄 바로 아래에 "전투" 줄이 끼어 들어가 공·방·순·체 칸에 숫자만 나온다
        val after = mutableListOf<JLabel>()
        fun walk3(c: Container) { for (x in c.components) { if (x is JLabel) after += x; if (x is Container) walk3(x) } }
        SwingUtilities.invokeAndWait { walk3(win.contentPane as Container) }
        val i = after.indexOfLast { it.text == "전투" } // 능력치 표 머리줄에도 "전투" 가 있어 마지막 것
        org.junit.Assert.assertTrue("전투 줄", i > 0 && after[i + 1].text.endsWith("%") && after[i - 7].text.startsWith("2강"))
        SwingUtilities.invokeAndWait { win.paint(img.graphics) }
        ImageIO.write(img, "png", File("build/ui/detail-open.png"))
        SwingUtilities.invokeAndWait { win.dispose() }
        prefs.removeNode()
        // HTML 로 그려지는 칸은 html.disable 이 꺼져 있어야 한다 (켜져 있으면 "<html>" 글자가 그대로 보임)
        val raw = mutableListOf<JLabel>()
        fun walk2(c: Container) { for (x in c.components) { if (x is JLabel && (x.text ?: "").startsWith("<html>") && x.getClientProperty("html.disable") == true) raw += x; if (x is Container) walk2(x) } }
        walk2(win.contentPane as Container)
        assertFalse("HTML 이 글자로 보이는 칸: ${raw.map { it.text }.take(3)}", raw.isNotEmpty())
    }
}
