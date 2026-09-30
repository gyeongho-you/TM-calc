package net.g1project.tmcalc.desktop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JDialog
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * 계산 창 → [도감에 추가/수정] → 도감 관리 + 편집 창이 뜨고, 150레벨이면 만렙S 칸에 화면 도감값이 채워지는지.
 * 화면이 있는 PC 에서만 돌고, 창 모습을 build/ui 폴더에 png 로 남긴다.
 */
class EditorUiTest {

    /** 실제 사용자 설정을 건드리지 않도록 테스트 전용 저장소 */
    private fun testPrefs() = java.util.prefs.Preferences.userRoot().node("tmcalc-test").apply { clear() }

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun openEditorFromScanPrefillsMaxS() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val pets = DesktopPets(tmp.newFolder())
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait {
            win = MainWindow(pets, testPrefs())
            win.isVisible = true
            // 도감에 없는 소환수를 150레벨 화면에서 읽었다고 가정: 이름, 레벨, 능력치, 도감 대비
            fun f(name: String) = MainWindow::class.java.getDeclaredField(name).apply { isAccessible = true }.get(win)
            (f("nameF") as JTextField).text = "새소환수"
            (f("levelF") as JTextField).text = "150"
            val st = f("statF") as Array<*>; val de = f("deltaF") as Array<*>
            listOf(566, 289, 483, 4094).forEachIndexed { i, v -> (st[i] as JTextField).text = "$v" }
            listOf(0, 3, 6, 23).forEachIndexed { i, v -> (de[i] as JTextField).text = "$v" }
        }
        snap(win, "main")
        // 모달 편집 창이 EDT 를 붙잡으므로 invokeLater 로 연다
        SwingUtilities.invokeLater {
            MainWindow::class.java.getDeclaredMethod("openPetEditor", Boolean::class.java).apply { isAccessible = true }.invoke(win, true)
        }
        // 창이 뜰 때까지 최대 10초 기다린다 (PC 가 바쁘면 늦게 뜸)
        var shown = emptyList<JDialog>()
        for (i in 0 until 50) {
            shown = Window.getWindows().filter { it.isShowing && it is JDialog }.map { it as JDialog }
            if (shown.any { it.title == "소환수 추가" }) break
            Thread.sleep(200)
        }
        val titles = shown.map { it.title }
        assertTrue("도감 관리 창: $titles", "도감 관리" in titles)
        val edit = shown.first { it.title == "소환수 추가" }
        shown.forEach { snap(it, it.title) }
        // 만렙S 칸 = 현재 - 도감 대비 = 566, 286, 477, 4071
        val boxes = allFields(edit)
        val fields = boxes.map { it.text }
        assertEquals("새소환수", fields[0])
        assertEquals(listOf("566", "286", "477", "4071"), fields.takeLast(4))
        // 초기치만 넣고 저장 → S성장률은 (만렙S - 초기치) / 149 로 자동 계산되어 도감에 들어간다
        SwingUtilities.invokeAndWait {
            listOf("15", "7", "12", "109").forEachIndexed { i, v -> boxes[4 + i].text = v } // 이름, 등급, 속성, 타입 다음이 초기치
            allButtons(edit).first { it.text == "저장" }.doClick()
        }
        Thread.sleep(500)
        val saved = pets.db.find("새소환수")!!
        // (만렙S - 초기치) / 149 → 청랑의 시트 S성장률과 같다
        assertEquals(listOf(3.70, 1.87, 3.12, 26.59), saved.sGrowth.toList())
        assertTrue("내 도감에 [추가함]으로 들어가야 함", pets.isMine("새소환수") && !pets.inBase("새소환수"))
        val manager = shown.first { it.title == "도감 관리" }
        snap(manager, "도감 관리-저장후")
        SwingUtilities.invokeAndWait { Window.getWindows().forEach { it.dispose() } }
        java.util.prefs.Preferences.userRoot().node("tmcalc-test").removeNode()
    }

    private fun allFields(c: java.awt.Container): List<JTextField> =
        c.components.flatMap { if (it is JTextField) listOf(it) else if (it is java.awt.Container) allFields(it) else emptyList() }

    private fun allButtons(c: java.awt.Container): List<javax.swing.JButton> =
        c.components.flatMap { if (it is javax.swing.JButton) listOf(it) else if (it is java.awt.Container) allButtons(it) else emptyList() }

    private fun snap(w: Window, name: String) {
        val img = BufferedImage(w.width, w.height, BufferedImage.TYPE_INT_RGB)
        SwingUtilities.invokeAndWait { w.paint(img.graphics) }
        File("build/ui").mkdirs()
        ImageIO.write(img, "png", File("build/ui/$name.png"))
    }
}
