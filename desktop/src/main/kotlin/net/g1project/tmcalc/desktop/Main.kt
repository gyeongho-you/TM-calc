package net.g1project.tmcalc.desktop

import java.io.File
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

fun main() {
    runCatching { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()) }
    val pets = DesktopPets()
    SwingUtilities.invokeLater {
        val win = MainWindow(pets)
        win.isVisible = true
        // OCR 모델은 1~2초 걸리므로 창을 먼저 띄우고 뒤에서 읽는다
        Thread {
            val r = runCatching { PaddleOcr(modelDir()) }
            SwingUtilities.invokeLater {
                r.onSuccess { win.ocr = it; win.setStatus("준비 완료 · F9 또는 [계산]") }
                    .onFailure {
                        win.setStatus("OCR 모델을 불러오지 못했습니다")
                        JOptionPane.showMessageDialog(win, "OCR 모델을 불러오지 못했습니다.\n${it.message}", "오류", JOptionPane.ERROR_MESSAGE)
                    }
            }
        }.start()
    }
}

/** 개발 중에는 -Dtmcalc.models, 배포본에서는 프로그램 폴더의 models */
private fun modelDir(): File {
    System.getProperty("tmcalc.models")?.let { return File(it) }
    val jar = File(MainWindow::class.java.protectionDomain.codeSource.location.toURI())
    return File(jar.parentFile, "models")
}
