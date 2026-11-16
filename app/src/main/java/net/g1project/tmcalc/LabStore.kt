package net.g1project.tmcalc

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 연구실에서 고를 수 있는 실험 (무엇을 바꾸고 뭘 찍을지 안내) */
class LabExperiment(val id: String, val title: String, val short: String, val guide: String)

/**
 * 연구실 기록: 폰 안(앱 전용 폴더)에만 저장하고, 내보내기를 눌러야 zip 파일로 나간다.
 * lab/<세션>/ 안에 001_상태창.jpg + 001_상태창.json (OCR 줄, 뽑은 값, 메모) 식으로 쌓인다.
 */
object LabStore {
    val EXPERIMENTS = listOf(
        LabExperiment("stat", "1. 전투능력치 공식", "실험1",
            "상태창(소환술사 > 정보)을 찍습니다.\n" +
                "그다음 딱 하나만 바꾸고 다시 찍기를 반복합니다: 룬 1개 교체, 장비 1개 교체, 레벨업 1번, 속성 강화 1번 등.\n" +
                "찍을 때마다 메모 칸에 무엇을 바꿨는지 적어 주세요 (예: 룬 공격 +8.5만 → 빼기).\n" +
                "룬 옵션이 화면 아래로 넘치면 스크롤해서 한 장 더 찍습니다."),
        LabExperiment("pet", "2. 소환수가 전투능력치에 주는 몫", "실험2",
            "1) 소환수 정보 화면을 찍습니다 (능력치·도감 대비가 보이는 화면).\n" +
                "2) 그 소환수를 파티(배치)에서 빼고 상태창을 찍습니다.\n" +
                "3) 다시 넣고 상태창을 찍습니다.\n" +
                "여러 소환수로 반복할수록 좋습니다. 메모에 '뺌' / '넣음'을 적어 주세요."),
        LabExperiment("power", "3. 전투력 공식", "실험3",
            "상태창을 여러 상태에서 찍습니다 (실험 1·2를 하면서 찍은 것도 그대로 쓰입니다).\n" +
                "전투력과 전투능력치 4개, 특수 버프가 한 화면에 다 보이면 됩니다."),
        LabExperiment("free", "자유 기록", "자유",
            "아무 화면이나 찍습니다. 메모에 무엇을 찍었는지 적어 주세요."),
    )

    fun experiment(id: String?) = EXPERIMENTS.firstOrNull { it.id == id } ?: EXPERIMENTS.last()

    private fun root(c: Context) = File(c.filesDir, "lab").apply { mkdirs() }

    private val stamp get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun sessions(c: Context): List<File> =
        root(c).listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()

    /** 지금 세션. 없으면 null */
    fun currentOrNull(c: Context): File? =
        AppPrefs.labSession(c)?.let { File(root(c), it) }?.takeIf { it.isDirectory }

    /** 지금 세션 (없으면 자유 기록으로 새로 만든다) */
    fun current(c: Context): File = currentOrNull(c) ?: newSession(c, "free", "")

    fun newSession(c: Context, expId: String, memo: String): File {
        val exp = experiment(expId)
        val safe = (exp.short + " " + memo.trim()).trim().replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(40)
        val dir = File(root(c), "${stamp}_$safe").apply { mkdirs() }
        File(dir, "session.json").writeText(JSONObject()
            .put("experiment", exp.id).put("title", exp.title)
            .put("memo", memo.trim()).put("start", System.currentTimeMillis()).toString(1))
        AppPrefs.setLabSession(c, dir.name)
        return dir
    }

    private fun meta(dir: File) = runCatching { JSONObject(File(dir, "session.json").readText()) }.getOrDefault(JSONObject())

    fun memo(dir: File): String = meta(dir).optString("memo")

    fun experimentOf(dir: File): LabExperiment = experiment(meta(dir).optString("experiment", "free"))

    /** "실험1 허수아비" 처럼 짧은 이름 */
    fun label(dir: File): String = (experimentOf(dir).short + " " + memo(dir)).trim()

    private fun records(dir: File) = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "session.json" }.orEmpty()

    fun count(dir: File): Int = records(dir).size

    /** 한 장 기록. 반환: 기록 번호 */
    fun save(c: Context, bmp: Bitmap, kind: String, lines: List<OcrLine>, values: List<LabParser.Value>): Int {
        val dir = current(c)
        // 지운 번호는 다시 쓰지 않는다 (가장 큰 번호 + 1)
        val n = (records(dir).mapNotNull { it.name.substringBefore("_").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val base = String.format(Locale.US, "%03d_%s", n, kind)
        File(dir, "$base.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val json = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("kind", kind)
            .put("note", "")
            .put("width", bmp.width).put("height", bmp.height)
            .put("values", JSONArray().apply {
                values.forEach { v ->
                    put(JSONObject().put("key", v.key).put("raw", v.raw).apply { v.value?.let { put("value", it) } })
                }
            })
            .put("lines", JSONArray().apply {
                lines.forEach { put(JSONArray().put(it.text).put(it.left).put(it.top).put(it.right).put(it.bottom)) }
            })
        File(dir, "$base.json").writeText(json.toString(1))
        return n
    }

    private fun recordFiles(dir: File, n: Int): List<File> {
        val p = String.format(Locale.US, "%03d_", n)
        return dir.listFiles { f -> f.name.startsWith(p) }.orEmpty().toList()
    }

    /** 기록에 "무엇을 바꿨는지" 메모 */
    fun setNote(c: Context, n: Int, note: String) {
        val dir = currentOrNull(c) ?: return
        val f = recordFiles(dir, n).firstOrNull { it.name.endsWith(".json") } ?: return
        val j = JSONObject(f.readText()).put("note", note.trim())
        f.writeText(j.toString(1))
    }

    /** 방금 기록 취소 */
    fun delete(c: Context, n: Int) {
        val dir = currentOrNull(c) ?: return
        recordFiles(dir, n).forEach { it.delete() }
    }

    /** 세션들을 zip 하나로 (values.csv 로 값만 모은 표도 같이) */
    fun exportZip(dirs: List<File>, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            for (dir in dirs) {
                val csv = StringBuilder("﻿no,time,kind,note,key,raw,value\n")
                dir.listFiles()?.sortedBy { it.name }?.forEach { f ->
                    zip.putNextEntry(ZipEntry("${dir.name}/${f.name}"))
                    f.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    if (f.name.endsWith(".json") && f.name != "session.json") runCatching {
                        val j = JSONObject(f.readText())
                        val no = f.name.substringBefore("_")
                        val vs = j.getJSONArray("values")
                        for (i in 0 until vs.length()) {
                            val v = vs.getJSONObject(i)
                            csv.append(listOf(no, j.getLong("time").toString(), j.getString("kind"), j.optString("note"),
                                v.getString("key"), v.getString("raw"), if (v.has("value")) v.getDouble("value").toString() else "")
                                .joinToString(",") { "\"" + it.replace("\"", "\"\"") + "\"" }).append('\n')
                        }
                    }
                }
                zip.putNextEntry(ZipEntry("${dir.name}/values.csv"))
                zip.write(csv.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    fun deleteAll(c: Context) {
        root(c).deleteRecursively()
        AppPrefs.setLabSession(c, null)
    }
}
