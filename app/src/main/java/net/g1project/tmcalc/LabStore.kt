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

/**
 * 연구실 기록: 폰 안(앱 전용 폴더)에만 저장하고, 내보내기를 눌러야 zip 파일로 나간다.
 * lab/<세션>/ 안에 001_상태창.jpg + 001_상태창.json (OCR 줄, 뽑은 값) 식으로 쌓인다.
 */
object LabStore {
    private fun root(c: Context) = File(c.filesDir, "lab").apply { mkdirs() }

    private val stamp get() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    fun sessions(c: Context): List<File> =
        root(c).listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()

    /** 지금 세션 (없으면 새로 만든다) */
    fun current(c: Context): File {
        val name = AppPrefs.labSession(c)
        val f = name?.let { File(root(c), it) }
        return if (f != null && f.isDirectory) f else newSession(c, "")
    }

    fun newSession(c: Context, memo: String): File {
        val safe = memo.trim().replace(Regex("""[\\/:*?"<>|\s]+"""), "_").take(30)
        val dir = File(root(c), stamp + if (safe.isEmpty()) "" else "_$safe").apply { mkdirs() }
        File(dir, "session.json").writeText(JSONObject().put("memo", memo.trim()).put("start", System.currentTimeMillis()).toString(1))
        AppPrefs.setLabSession(c, dir.name)
        return dir
    }

    fun memo(dir: File): String =
        runCatching { JSONObject(File(dir, "session.json").readText()).optString("memo") }.getOrDefault("")

    fun count(dir: File): Int = dir.listFiles { f -> f.name.endsWith(".json") && f.name != "session.json" }?.size ?: 0

    /** 한 장 기록. 반환: 몇 번째 기록인지 */
    fun save(c: Context, bmp: Bitmap, kind: String, lines: List<OcrLine>, values: List<LabParser.Value>): Int {
        val dir = current(c)
        val n = count(dir) + 1
        val base = String.format(Locale.US, "%03d_%s", n, kind)
        File(dir, "$base.jpg").outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        val json = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("kind", kind)
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

    /** 세션들을 zip 하나로 (values.csv 로 값만 모은 표도 같이) */
    fun exportZip(c: Context, dirs: List<File>, out: OutputStream) {
        ZipOutputStream(out.buffered()).use { zip ->
            for (dir in dirs) {
                val csv = StringBuilder("﻿no,time,kind,key,raw,value\n")
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
                            csv.append(listOf(no, j.getLong("time").toString(), j.getString("kind"), v.getString("key"),
                                v.getString("raw"), if (v.has("value")) v.getDouble("value").toString() else "")
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
