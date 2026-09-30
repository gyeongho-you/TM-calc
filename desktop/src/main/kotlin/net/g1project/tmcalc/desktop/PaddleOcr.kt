package net.g1project.tmcalc.desktop

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import net.g1project.tmcalc.OcrLine
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PaddleOCR(PP-OCRv5 글자 위치 찾기 + 한국어 인식) 를 ONNX Runtime 으로 돌린다. 인터넷 없이 PC 안에서만 동작.
 * 전처리/후처리 값은 RapidOCR(파이썬) 기본값과 같게 맞췄다. 게임 글자는 가로라서 글자 상자는 축에 맞춘 직사각형으로 단순화.
 *
 * 이 게임은 어두운 배경에 테두리 있는 흰 글씨라, 흑백을 뒤집어(흰 배경 검은 글씨) 넣으면 훨씬 잘 읽는다
 * (게임 화면 4장 44칸: 원본 35칸 → 반전 40칸).
 */
class PaddleOcr(modelDir: File) : AutoCloseable {

    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(max(1, Runtime.getRuntime().availableProcessors() / 2)) }
    private val det = env.createSession(File(modelDir, DET_MODEL).path, opts)
    private val rec = env.createSession(File(modelDir, REC_MODEL).path, opts)

    /** 인식 결과 번호 → 글자. 0 은 CTC 빈칸, 마지막은 공백. 글자표는 모델 파일 안(metadata "character")에 들어 있다 */
    private val chars: List<String> =
        listOf("") + rec.metadata.customMetadata.getValue("character").lines() + " "

    fun recognize(src: BufferedImage, invert: Boolean = true): List<OcrLine> {
        val img = toRgb(src, invert)
        return detect(img).mapNotNull { box ->
            val (text, score) = read(crop(img, box)) ?: return@mapNotNull null
            if (text.isBlank() || score < TEXT_SCORE) null else OcrLine(text, box[0], box[1], box[2], box[3])
        }
    }

    // ------------------------------------------------------------------ 글자 위치 찾기 (DBNet)

    /** 글자 줄 상자들 (left, top, right, bottom), 원본 이미지 좌표 */
    private fun detect(img: BufferedImage): List<IntArray> {
        val w = img.width; val h = img.height
        // 짧은 변이 736 보다 작으면 키우고, 가로세로를 32의 배수로 (RapidOCR limit_type=min)
        val ratio = if (min(w, h) < LIMIT_SIDE) LIMIT_SIDE.toDouble() / min(w, h) else 1.0
        val rw = max(32, (w * ratio / 32).roundToInt() * 32)
        val rh = max(32, (h * ratio / 32).roundToInt() * 32)
        val input = resize(img, rw, rh)
        val prob = OnnxTensor.createTensor(env, toTensor(input, rw), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { out ->
                val arr = out[0].value as Array<Array<Array<FloatArray>>>
                arr[0][0]
            }
        }
        // 0.3 넘는 곳 → 2x2 팽창 → 이어진 덩어리마다 상자
        val mask = BooleanArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            mask[y * rw + x] = prob[y][x] > DET_THRESH ||
                (x > 0 && prob[y][x - 1] > DET_THRESH) || (y > 0 && prob[y - 1][x] > DET_THRESH) ||
                (x > 0 && y > 0 && prob[y - 1][x - 1] > DET_THRESH)
        }
        val boxes = ArrayList<IntArray>()
        val seen = BooleanArray(rw * rh)
        val stack = IntArray(rw * rh)
        for (start in 0 until rw * rh) {
            if (!mask[start] || seen[start]) continue
            var sp = 0; stack[sp++] = start; seen[start] = true
            var x0 = rw; var y0 = rh; var x1 = -1; var y1 = -1
            while (sp > 0) {
                val p = stack[--sp]; val px = p % rw; val py = p / rw
                if (px < x0) x0 = px; if (px > x1) x1 = px; if (py < y0) y0 = py; if (py > y1) y1 = py
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = px + dx; val ny = py + dy
                    if (nx < 0 || ny < 0 || nx >= rw || ny >= rh) continue
                    val q = ny * rw + nx
                    if (mask[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
                }
            }
            val bw = (x1 - x0).toDouble(); val bh = (y1 - y0).toDouble()
            if (min(bw, bh) < MIN_SIZE) continue
            // 상자 안 평균 확률 (score_mode=fast)
            var sum = 0.0
            for (y in y0..y1) for (x in x0..x1) sum += prob[y][x]
            if (sum / ((x1 - x0 + 1) * (y1 - y0 + 1)) < BOX_THRESH) continue
            // 상자 넓히기 (unclip): 거리 = 넓이 × 1.6 ÷ 둘레
            val d = bw * bh * UNCLIP_RATIO / (2 * (bw + bh))
            if (min(bw, bh) + 2 * d < MIN_SIZE + 2) continue
            val sx = w.toDouble() / rw; val sy = h.toDouble() / rh
            boxes += intArrayOf(
                ((x0 - d) * sx).roundToInt().coerceIn(0, w - 1), ((y0 - d) * sy).roundToInt().coerceIn(0, h - 1),
                ((x1 + d) * sx).roundToInt().coerceIn(0, w), ((y1 + d) * sy).roundToInt().coerceIn(0, h),
            )
        }
        return boxes.filter { it[2] - it[0] > 1 && it[3] - it[1] > 1 }.sortedWith(compareBy({ it[1] }, { it[0] }))
    }

    // ------------------------------------------------------------------ 글자 읽기 (CTC)

    private fun crop(img: BufferedImage, b: IntArray): BufferedImage {
        val c = img.getSubimage(b[0], b[1], b[2] - b[0], b[3] - b[1])
        // RapidOCR 처럼 세로로 긴 조각(높이 ≥ 가로 × 1.5)은 90° 돌려서 읽는다
        if (c.height.toDouble() / c.width < 1.5) return c
        val r = BufferedImage(c.height, c.width, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until c.height) for (x in 0 until c.width) r.setRGB(y, c.width - 1 - x, c.getRGB(x, y))
        return r
    }

    private fun read(c: BufferedImage): Pair<String, Double>? {
        val ratio = c.width.toDouble() / c.height
        val imgW = (REC_H * max(REC_MIN_RATIO, ratio)).toInt()
        val rw = min(imgW, ceil(REC_H * ratio).toInt()).coerceAtLeast(1)
        val resized = resize(c, rw, REC_H)
        // 오른쪽은 0(정규화 값)으로 채운다
        val buf = FloatBuffer.allocate(3 * REC_H * imgW)
        val px = resized.getRGB(0, 0, rw, REC_H, null, 0, rw)
        for (ch in 0..2) for (y in 0 until REC_H) for (x in 0 until imgW) {
            buf.put(if (x < rw) norm(px[y * rw + x], ch) else 0f)
        }
        buf.rewind()
        val probs = OnnxTensor.createTensor(env, buf, longArrayOf(1, 3, REC_H.toLong(), imgW.toLong())).use { t ->
            rec.run(mapOf(rec.inputNames.first() to t)).use { out -> (out[0].value as Array<Array<FloatArray>>)[0] }
        }
        val sb = StringBuilder(); var prev = -1; var scoreSum = 0.0; var n = 0
        for (step in probs) {
            var best = 0
            for (k in step.indices) if (step[k] > step[best]) best = k
            if (best != 0 && best != prev && best < chars.size) { sb.append(chars[best]); scoreSum += step[best]; n++ }
            prev = best
        }
        return if (n == 0) null else sb.toString() to scoreSum / n
    }

    // ------------------------------------------------------------------ 이미지 도구

    private fun toRgb(src: BufferedImage, invert: Boolean): BufferedImage {
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        val px = src.getRGB(0, 0, src.width, src.height, null, 0, src.width)
        if (invert) for (i in px.indices) px[i] = px[i] xor 0xFFFFFF
        out.setRGB(0, 0, src.width, src.height, px, 0, src.width)
        return out
    }

    private fun resize(src: BufferedImage, w: Int, h: Int): BufferedImage {
        if (src.width == w && src.height == h) return src
        val out = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        return out
    }

    /** (값/255 - 0.5) / 0.5, 채널 순서는 BGR (OpenCV 와 같게) */
    private fun norm(rgb: Int, ch: Int): Float {
        val v = when (ch) { 0 -> rgb and 0xFF; 1 -> (rgb shr 8) and 0xFF; else -> (rgb shr 16) and 0xFF }
        return (v / 255f - 0.5f) / 0.5f
    }

    private fun toTensor(img: BufferedImage, w: Int): FloatBuffer {
        val h = img.height
        val px = img.getRGB(0, 0, w, h, null, 0, w)
        val buf = FloatBuffer.allocate(3 * w * h)
        for (ch in 0..2) for (i in 0 until w * h) buf.put(norm(px[i], ch))
        buf.rewind()
        return buf
    }

    override fun close() {
        det.close(); rec.close(); opts.close()
    }

    companion object {
        const val DET_MODEL = "ch_PP-OCRv5_det_mobile.onnx"
        const val REC_MODEL = "korean_PP-OCRv5_rec_mobile.onnx"
        private const val LIMIT_SIDE = 736
        private const val DET_THRESH = 0.3f
        private const val BOX_THRESH = 0.5
        private const val UNCLIP_RATIO = 1.6
        private const val MIN_SIZE = 3.0
        private const val REC_H = 48
        private const val REC_MIN_RATIO = 320.0 / 48
        private const val TEXT_SCORE = 0.5
    }
}
