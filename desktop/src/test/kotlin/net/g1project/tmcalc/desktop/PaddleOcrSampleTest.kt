package net.g1project.tmcalc.desktop

import net.g1project.tmcalc.OcrParser
import net.g1project.tmcalc.PetDb
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * 실제 게임 캡처로 PC OCR + 앱 해석기를 돌려 본다. 캡처는 개인 화면이라 레포에 넣지 않으므로
 * 환경 변수 TMCALC_SAMPLES (캡처 폴더) 가 있을 때만 돌고, 결과를 build/ocr-samples.txt 에 남긴다.
 */
class PaddleOcrSampleTest {

    @Test
    fun samples() {
        val dir = System.getenv("TMCALC_SAMPLES")?.let(::File)
        assumeTrue("TMCALC_SAMPLES 없음 — 건너뜀", dir != null && dir.isDirectory)
        val db = PetDb.parse(javaClass.getResource("/pets.json")!!.readText())
        val out = StringBuilder()
        PaddleOcr(File(System.getProperty("tmcalc.models"))).use { ocr ->
            dir!!.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }!!.sortedBy { it.name }.forEach { f ->
                val t0 = System.currentTimeMillis()
                val lines = ocr.recognize(ImageIO.read(f))
                val ms = System.currentTimeMillis() - t0
                val p = OcrParser.parse(lines, db)
                out.append("${f.nameWithoutExtension}\t이름=${p.pet?.name ?: "(${p.nameRaw})"}\tLv=${p.level}\t능력치=${p.stats.toList()}" +
                    "\t대비=${p.deltas.toList()}\t총성장=${p.gameTotal}\t${ms}ms\n")
                File("build/ocr-lines").apply { mkdirs() }.resolve(f.nameWithoutExtension + ".txt")
                    .writeText(lines.joinToString("\n") { "${it.text}|${it.left}|${it.top}|${it.right}|${it.bottom}" })
            }
        }
        File("build/ocr-samples.txt").writeText(out.toString())
    }
}
