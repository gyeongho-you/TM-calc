package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.Pet
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
import javax.swing.JLabel
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** 도감 값끼리 안 맞는 소환수를 계산하면 결과에 경고가 나오고, 창 오른쪽 아래에 버전이 보인다 */
class OddValueUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun warnsAndShowsVersion() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = java.util.prefs.Preferences.userRoot().node("tmcalc-test-odd").apply { clear() }
        val pets = DesktopPets(tmp.newFolder())
        // 예전 도감의 하용이 (방어 만렙S 586, 식대로면 485)
        val p = pets.db.find("하용이")!!
        pets.put(Pet(p.name, p.grade, p.element, p.type, p.init, p.sGrowth, intArrayOf(497, 586, 299, 4409)))
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait {
            win = MainWindow(pets, prefs)
            win.isVisible = true
            fun f(name: String) = MainWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(win)
            (f("nameF") as JTextField).text = "하용이"
            (f("levelF") as JTextField).text = "150"
            (f("breakF") as JTextField).text = "0"
            val st = f("statF") as Array<*>
            listOf(500, 590, 300, 4400).forEachIndexed { i, v -> (st[i] as JTextField).text = "$v" }
        }
        Thread.sleep(800)
        val img = BufferedImage(win.width, win.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { win.paint(img.graphics) }
        File("build/ui").mkdirs()
        ImageIO.write(img, "png", File("build/ui/odd.png"))
        val texts = mutableListOf<String>()
        fun walk(c: Container) { for (x in c.components) { if (x is JLabel) texts += x.text.orEmpty(); if (x is Container) walk(x) } }
        SwingUtilities.invokeAndWait { walk(win.contentPane as Container) }
        assertTrue(texts.toString(), texts.any { "방어 만렙S 586" in it && "485" in it })
        assertTrue(texts.toString(), texts.any { it.matches(Regex("v\\d+\\.\\d+\\.\\d+")) })
        SwingUtilities.invokeAndWait { win.dispose() }
        prefs.removeNode()
    }
}
