package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.OcrLine
import net.g1project.tmcalc.OcrParser
import net.g1project.tmcalc.ParsedScreen
import org.junit.Assert.assertEquals
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
import javax.swing.JDialog
import javax.swing.JTextField
import javax.swing.SwingUtilities

/** 게임 도감 화면을 읽고 [도감에 추가/수정] 을 누르면 이름·태그·초기치·성장률·만렙S 가 모두 채워진다 */
class DexEditorUiTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun dexScreenFillsEditor() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val prefs = java.util.prefs.Preferences.userRoot().node("tmcalc-test-dex").apply { clear() }
        val pets = DesktopPets(tmp.newFolder())
        val lines = File("../app/src/test/resources/ocr/dex_pc.txt").readLines().filter { it.isNotBlank() }.map { line ->
            val p = line.split('|'); val n = p.size
            OcrLine(p.subList(0, n - 4).joinToString("|"), p[n - 4].toInt(), p[n - 3].toInt(), p[n - 2].toInt(), p[n - 1].toInt())
        }
        val parsed = OcrParser.parse(lines, pets.db)
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait {
            win = MainWindow(pets, prefs); win.isVisible = true
            MainWindow::class.java.getDeclaredField("lastParsed").apply { isAccessible = true }.set(win, parsed)
            MainWindow::class.java.getDeclaredMethod("fill", ParsedScreen::class.java, Array<Int?>::class.java)
                .apply { isAccessible = true }.invoke(win, parsed, parsed.deltas)
        }
        SwingUtilities.invokeLater {
            MainWindow::class.java.getDeclaredMethod("openPetEditor", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(win, true)
        }
        var shown = emptyList<JDialog>()
        for (t in 0 until 30) {
            shown = Window.getWindows().filter { it.isShowing && it is JDialog }.map { it as JDialog }
            if (shown.any { it.title == "소환수 추가" }) break
            Thread.sleep(200)
        }
        val dlg = shown.firstOrNull { it.title == "소환수 추가" } ?: error("창: ${shown.map { it.title }}")
        val f = mutableListOf<String>()
        fun walk(c: Container) { for (x in c.components) { if (x is JTextField) f += x.text; if (x is Container) walk(x) } }
        SwingUtilities.invokeAndWait { walk(dlg.contentPane as Container) }
        val img = BufferedImage(dlg.width, dlg.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { dlg.paint(img.graphics) }
        File("build/ui").mkdirs(); ImageIO.write(img, "png", File("build/ui/dex-editor.png"))
        // 이름, 등급, 속성, 타입, 초기치 4, 성장률 4, 만렙S 4
        assertEquals(listOf("1세대의전설", "전설", "어둠", "비행", "12", "7", "15", "103", "3.18", "1.93", "3.75", "25.14",
            "486", "295", "574", "3849"), f)
        SwingUtilities.invokeAndWait { Window.getWindows().forEach { it.dispose() } }
        prefs.removeNode()
    }
}
