package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.ParsedScreen
import net.g1project.tmcalc.PetDb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.awt.image.BufferedImage
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import java.util.prefs.Preferences
import javax.swing.JComboBox
import javax.swing.JFrame
import javax.swing.JTextField
import javax.swing.SwingUtilities

/**
 * Swing 은 "<html>" 로 시작하는 글자를 HTML 로 그리고 <img src=http://…> 를 받으러 인터넷에 접속한다.
 * 바깥에서 들어오는 글자(다른 프로그램 창 제목, 화면에서 읽은 글자, 도감 JSON)로 이 접속이 일어나지 않는지
 * 로컬 테스트 서버로 실제 확인한다.
 */
class HtmlSafetyTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun outsideTextNeverTriggersNetwork() {
        assumeTrue(!GraphicsEnvironment.isHeadless())
        val hits = AtomicInteger()
        val server = ServerSocket(0)
        Thread { runCatching { while (true) server.accept().use { hits.incrementAndGet() } } }.apply { isDaemon = true; start() }
        val evil = "<html><img src='http://127.0.0.1:${server.localPort}/x.png'>"

        // 1) 악성 창 제목 (다른 프로그램이 정함)
        lateinit var bad: JFrame
        SwingUtilities.invokeAndWait { bad = JFrame(evil + "악성창").apply { setSize(400, 400); isVisible = true } }
        val prefs = Preferences.userRoot().node("tmcalc-test-html").apply { clear() }
        val pets = DesktopPets(tmp.newFolder())
        lateinit var win: MainWindow
        SwingUtilities.invokeAndWait { win = MainWindow(pets, prefs); win.isVisible = true }
        fun field(n: String) = MainWindow::class.java.getDeclaredField(n).apply { isAccessible = true }
        SwingUtilities.invokeAndWait {
            @Suppress("UNCHECKED_CAST")
            val combo = field("targets").get(win) as JComboBox<WindowCapture.Target>
            val item = (0 until combo.itemCount).map { combo.getItemAt(it) }.first { it.title.contains("악성창") }
            combo.selectedItem = item
            combo.showPopup()
            // 2) 화면에서 읽은 글자에 HTML 이 섞인 경우 → 경고 문구로 나간다
            val pet = pets.db.find("청랑")
            field("lastParsed").set(win, ParsedScreen(pet, evil + "청랑", 0.5, 150, 0,
                arrayOf(566, 289, 483, 4094), arrayOf(0, 3, 6, 23)))
            (field("nameF").get(win) as JTextField).text = "청랑"
        }
        // 화면을 실제로 그려 본다
        Thread.sleep(800)
        SwingUtilities.invokeAndWait {
            Window.getWindows().filter { it.isShowing }.forEach { w ->
                w.paint(BufferedImage(maxOf(1, w.width), maxOf(1, w.height), BufferedImage.TYPE_INT_RGB).graphics)
            }
        }
        // 3) 악성 도감 JSON 은 들어오지 않는다
        val json = tmp.newFile("evil.json").apply {
            writeText("""[{"이름":"${evil.replace("'", "\\u0027")}","초기치":[1,1,1,1],"S성장률":[1,1,1,1],"만렙S":[1,1,1,1]}]""")
        }
        val rejected = runCatching { pets.import(json) }.isFailure
        Thread.sleep(1500)
        SwingUtilities.invokeAndWait { Window.getWindows().forEach { it.dispose() } }
        prefs.removeNode()
        server.close()

        assertTrue("악성 도감 JSON 은 거부돼야 함", rejected)
        assertEquals("바깥 글자 때문에 인터넷 접속이 일어나면 안 됨", 0, hits.get())
        assertTrue(PetDb.check(net.g1project.tmcalc.Pet("<b>x", "", "", "", intArrayOf(1, 1, 1, 1), doubleArrayOf(1.0, 1.0, 1.0, 1.0), intArrayOf(1, 1, 1, 1))) != null)
    }
}
