package com.firas.tv

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ChannelStore {
    private const val FILE_NAME = "channels.json"

    fun readRoot(context: Context): JSONObject {
        val override = File(context.filesDir, FILE_NAME)
        val raw = if (override.isFile) override.readText()
            else context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
        return JSONObject(raw)
    }

    fun saveRoot(context: Context, root: JSONObject) {
        val target = File(context.filesDir, FILE_NAME)
        val temp = File(context.filesDir, "$FILE_NAME.tmp")
        temp.writeText(root.toString(2))
        if (target.exists() && !target.delete()) throw IllegalStateException("تعذر استبدال ملف القنوات")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    fun loadLegacy(context: Context): List<LegacyChannel> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return UniversalStreamEngine.loadLegacy(context)
        return try {
            val root = JSONObject(file.readText())
            val sections = root.optJSONArray("sections") ?: JSONArray()
            buildList {
                for (i in 0 until sections.length()) {
                    val section = sections.optJSONObject(i) ?: continue
                    val category = section.optString("title")
                    val items = section.optJSONArray("items") ?: continue
                    for (j in 0 until items.length()) {
                        val item = items.optJSONObject(j) ?: continue
                        val stream = item.optString("url", item.optString("stream"))
                        if (stream.isBlank()) continue
                        val headers = linkedMapOf<String, String>()
                        item.optString("referer").takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
                        item.optString("userAgent").takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
                        item.optString("cookie").takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                        add(LegacyChannel(
                            item.optString("id", "local-$i-$j"),
                            item.optString("name", "Channel $j"),
                            category,
                            item.optString("logo"),
                            stream,
                            headers
                        ))
                    }
                }
            }
        } catch (_: Exception) {
            UniversalStreamEngine.loadLegacy(context)
        }
    }
}