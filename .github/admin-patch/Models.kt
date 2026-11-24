package com.firas.tv

import android.content.Context
import org.json.JSONObject
import java.io.File

data class Channel(val name: String, val logo: String, val url: String, val referer: String, val id: String = "", val isPrivate: Boolean = false, val countryCode: String = "")
data class Section(val title: String, val items: List<Channel>, val region: String = "", val countryCode: String = "")

object ChannelRepository {
    fun load(context: Context): List<Section> {
        val override = File(context.filesDir, "channels.json")
        val raw = if (override.isFile) {
            try { override.readText(Charsets.UTF_8) } catch (_: Exception) { readAsset(context) }
        } else readAsset(context)
        return try { parse(raw) }
        catch (_: Exception) {
            if (override.isFile) try { parse(readAsset(context)) } catch (_: Exception) { emptyList() }
            else emptyList()
        }
    }

    private fun parse(raw: String): List<Section> {
        val sections = JSONObject(raw).getJSONArray("sections")
        return (0 until sections.length()).mapNotNull { i ->
            val section = sections.optJSONObject(i) ?: return@mapNotNull null
            val items = section.optJSONArray("items") ?: return@mapNotNull null
            val channels = (0 until items.length()).mapNotNull { j ->
                val item = items.optJSONObject(j) ?: return@mapNotNull null
                if (item.optBoolean("priv", false)) return@mapNotNull null
                val url = item.optString("url", item.optString("stream")).trim()
                if (url.isNotBlank() && !(url.startsWith("http://", true) || url.startsWith("https://", true))) return@mapNotNull null
                Channel(item.optString("name", "قناة"), item.optString("logo"), url,
                    item.optString("referer"), item.optString("id"), item.optBoolean("priv", false), item.optString("countryCode", item.optString("cc", section.optString("countryCode"))))
            }
            if (channels.isEmpty() && section.optString("region").isBlank() && section.optString("countryCode").isBlank()) null else {
                val code = section.optString("countryCode").ifBlank { channels.firstOrNull()?.countryCode.orEmpty() }.uppercase()
                val region = section.optString("region").ifBlank {
                    if (code in setOf("DZ","SA","EG","MA","TN","LY","SD","IQ","SY","JO","LB","PS","YE","OM","AE","QA","BH","KW","MR","SO","DJ","KM")) "arab" else ""
                }
                Section(section.optString("title"), channels, region, code)
            }
        }
    }

    private fun readAsset(context: Context): String =
        context.assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
}