package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.OcrParser
import net.g1project.tmcalc.PetDb
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Image
import java.io.File
import javax.imageio.ImageIO
import javax.swing.ImageIcon
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.SwingUtilities

/**
 * 앱플레이어 대신 게임 캡처를 띄운 창을 만들어 "창 찾기 → 캡처 → OCR → 해석" 전체를 돌려 본다.
 * 앱플레이어 창은 보통 폰 화면보다 작아서 몇 가지 크기로 확인. TMCALC_SAMPLES 가 있을 때만 돈다.
 */
class CaptureFlowTest {

    @Test
    fun captureFakeEmulatorWindow() {
        val dir = System.getenv("TMCALC_SAMPLES")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory && !GraphicsEnvironment.isHeadless())
        val db = PetDb.parse(javaClass.getResource("/pets.json")!!.readText())
        val out = StringBuilder()
        PaddleOcr(File(System.getProperty("tmcalc.models"))).use { ocr ->
            for (f in dir!!.listFiles { f -> f.extension == "jpg" }!!.sortedBy { it.name }) {
                val src = ImageIO.read(f)
                for (width in listOf(src.width, 540, 450)) {
                    val h = src.height * width / src.width
                    lateinit var frame: JFrame
                    SwingUtilities.invokeAndWait {
                        frame = JFrame("FakePlayer-${f.nameWithoutExtension}-$width").apply {
                            contentPane = JLabel(ImageIcon(src.getScaledInstance(width, h, Image.SCALE_SMOOTH)))
                            contentPane.preferredSize = Dimension(width, h)
                            pack(); setLocation(0, 0); isVisible = true
                        }
                    }
                    Thread.sleep(500)
                    val target = WindowCapture.list("x").first { it.title == frame.title }
                    val img = WindowCapture.capture(target)!!
                    val t0 = System.currentTimeMillis()
                    val p = OcrParser.parse(ocr.recognize(img), db)
                    out.append("${f.nameWithoutExtension}\t창 ${img.width}x${img.height}\t이름=${p.pet?.name ?: "(${p.nameRaw})"}\tLv=${p.level}" +
                        "\t능력치=${p.stats.toList()}\t대비=${p.deltas.toList()}\t총성장=${p.gameTotal}\t${System.currentTimeMillis() - t0}ms\n")
                    SwingUtilities.invokeAndWait { frame.dispose() }
                }
            }
        }
        File("build/capture-flow.txt").writeText(out.toString())
    }
}
