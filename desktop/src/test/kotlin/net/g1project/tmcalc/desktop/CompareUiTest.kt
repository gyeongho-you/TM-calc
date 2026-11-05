package net.g1project.tmcalc.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Container
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** 저장 → 기준으로 고정 → 다른 소환수를 넣으면 비교표가 나오고, 강을 바꾸면 예상 능력치로 다시 비교한다 */
class CompareUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun pinSaveAndCompare() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = java.util.prefs.Preferences.userRoot().node("tmcalc-test-compare").apply { clear() }
        lateinit var win: MainWindow
        fun f(name: String) = MainWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(win)
        fun put(name: String, level: Int, breaks: Int, stats: List<Int>, deltas: List<Int>) = SwingUtilities.invokeAndWait {
            (f("nameF") as JTextField).text = name
            (f("levelF") as JTextField).text = "$level"
            (f("breakF") as JTextField).text = "$breaks"
            val st = f("statF") as Array<*>; val de = f("deltaF") as Array<*>
            stats.forEachIndexed { i, v -> (st[i] as JTextField).text = "$v" }
            deltas.forEachIndexed { i, v -> (de[i] as JTextField).text = "$v" }
        }
        fun all(): List<java.awt.Component> {
            val out = mutableListOf<java.awt.Component>()
            fun walk(c: Container) { for (x in c.components) { out += x; if (x is Container) walk(x) } }
            SwingUtilities.invokeAndWait { walk(win.contentPane as Container) }
            return out
        }
        fun click(text: String) { val b = all().filterIsInstance<JButton>().first { it.text == text }; SwingUtilities.invokeAndWait { b.doClick() } }
        fun texts() = all().filterIsInstance<JLabel>().map { it.text.orEmpty() }

        SwingUtilities.invokeAndWait { win = MainWindow(DesktopPets(tmp.newFolder()), prefs); win.setSize(420, 1200); win.isVisible = true }
        // A: 화혼랑 0강 (도감 574 294 485 3904)
        put("화혼랑", 150, 0, listOf(593, 309, 481, 4231), listOf(19, 15, -4, 327))
        click("저장"); click("기준으로 고정")
        assertEquals(1, win.presets().size)
        // B: 라르곤 0강
        put("라르곤", 150, 0, listOf(559, 360, 405, 3981), listOf(18, 4, 11, -256))
        Thread.sleep(300)
        var t = texts()
        assertTrue(t.toString(), "화혼랑(기준)" in t && "라르곤" in t)
        // 성장증폭 8%: 라르곤은 공 −34% 방 −18% 순 −41% 체 −34%
        assertTrue(t.toString(), listOf("-34%", "-18%", "-41%").all { it in t })
        snap(win, "compare")
        // B 를 5강으로 바꾸면 예상 능력치(586 378 425 4193)로 비교
        val combo = all().filterIsInstance<JComboBox<*>>().last { it.itemCount == 6 }
        SwingUtilities.invokeAndWait { combo.selectedIndex = 5 }
        Thread.sleep(400)
        t = texts()
        assertTrue(t.toString(), listOf("586", "378", "425", "4193").all { it in t })
        snap(win, "compare-5")
        // 저장 목록에서 다시 불러오기
        val p = win.presets().first()
        SwingUtilities.invokeAndWait { MainWindow::class.java.getDeclaredMethod("loadPreset", net.g1project.tmcalc.Preset::class.java).apply { isAccessible = true }.invoke(win, p) }
        assertEquals("화혼랑", (f("nameF") as JTextField).text)
        assertEquals("327", ((f("deltaF") as Array<*>)[3] as JTextField).text)
        SwingUtilities.invokeAndWait { win.dispose() }
        prefs.removeNode()
    }

    private fun snap(w: java.awt.Window, name: String) {
        val img = BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { w.paint(img.graphics) }
        File("build/ui").mkdirs(); ImageIO.write(img, "png", File("build/ui/$name.png"))
    }
}
