package com.firas.tv

import android.content.Context
import android.util.Log
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class StreamConfig(
    val id: String,
    val name: String,
    val mode: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val urls: List<String> = emptyList(),
    val cookie: String = "",
    val aliases: List<String> = emptyList(),
    val resolverUrl: String = "",
    val resolverParam: String = "",
    val resolverJsonPath: String = "",
    val sequenceRegex: String = "",
    val sequenceStart: Long = 0L,
    val sequenceCount: Int = 8,
    val fallbackUrl: String = ""
)

data class LegacyChannel(val id: String, val name: String, val category: String, val logo: String, val stream: String, val headers: Map<String, String> = emptyMap())

object UniversalStreamEngine {
    private const val TAG = "FIRASStream"
    private const val CONFIG = "channels_stream_config.json"
    private const val LEGACY = "channels.json"
    private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
    private val cookieJar = object : CookieJar {
        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            synchronized(cookieStore) {
                val current = cookieStore[url.host]?.toMutableList() ?: mutableListOf()
                cookies.forEach { c -> current.removeAll { it.name == c.name }; current.add(c) }
                cookieStore[url.host] = current
            }
        }
        override fun loadForRequest(url: HttpUrl): List<Cookie> =
            synchronized(cookieStore) { cookieStore[url.host]?.filter { it.matches(url) } ?: emptyList() }
    }
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }

    private fun readConfig(context: Context): String? {
        val file = File(context.filesDir, CONFIG)
        return try {
            if (file.isFile) file.readText()
            else context.assets.open(CONFIG).bufferedReader().use { it.readText() }
        } catch (e: Exception) { Log.w(TAG, "Stream config unavailable", e); null }
    }

    fun load(context: Context): List<StreamConfig> {
        val raw = readConfig(context) ?: return emptyList()
        return try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("channels") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val headers = linkedMapOf<String, String>()
                val global = root.optJSONObject("default_headers") ?: JSONObject()
                val own = o.optJSONObject("headers") ?: JSONObject()
                global.keys().forEach { headers[it] = global.optString(it) }
                own.keys().forEach { headers[it] = own.optString(it) }
                val urls = mutableListOf<String>()
                o.optJSONArray("urls")?.let { a -> for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let(urls::add) }
                val aliases = mutableListOf<String>()
                o.optJSONArray("aliases")?.let { a -> for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let(aliases::add) }
                StreamConfig(
                    id = o.optString("id"), name = o.optString("name"),
                    mode = o.optString("mode", "direct"), url = o.optString("url"),
                    headers = headers, urls = urls, cookie = o.optString("cookie"),
                    aliases = aliases, resolverUrl = o.optString("resolver_url"),
                    resolverParam = o.optString("resolver_param", "url"),
                    resolverJsonPath = o.optString("resolver_json_path"),
                    sequenceRegex = o.optString("sequence_regex"),
                    sequenceStart = o.optLong("sequence_start", 0L),
                    sequenceCount = o.optInt("sequence_count", 8).coerceIn(1, 30),
                    fallbackUrl = o.optString("fallback_url")
                )
            }
        } catch (e: Exception) { Log.e(TAG, "Invalid stream config", e); emptyList() }
    }

    fun loadLegacy(context: Context): List<LegacyChannel> = try {
        val raw = context.assets.open(LEGACY).bufferedReader().use { it.readText() }
        val root = JSONObject(raw)
        val sections = root.optJSONArray("sections")
        if (sections != null) {
            val result = mutableListOf<LegacyChannel>()
            for (i in 0 until sections.length()) {
                val section = sections.optJSONObject(i) ?: continue
                val category = section.optString("title")
                val items = section.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val item = items.optJSONObject(j) ?: continue
                    val stream = item.optString("url")
                    if (stream.isBlank()) continue
                    val headers = linkedMapOf<String, String>()
                    item.optString("referer").takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
                    item.optString("userAgent").takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
                    result += LegacyChannel(item.optString("id", "legacy-$i-$j"), item.optString("name", "Channel $j"),
                        category, item.optString("logo"), stream, headers)
                }
            }
            result
        } else {
            val arr = root.optJSONArray("channels") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNull null
                val stream = item.optString("stream", item.optString("url"))
                if (stream.isBlank()) null else LegacyChannel(
                    item.optString("id", "legacy-$i"), item.optString("name", "Channel $i"),
                    item.optString("category"), item.optString("logo"), stream
                )
            }
        }
    } catch (e: Exception) { Log.w(TAG, "Legacy channel list unavailable", e); emptyList() }

    fun match(configs: List<StreamConfig>, id: String, name: String): StreamConfig? {
        fun norm(s: String) = s.trim().lowercase()
        return configs.firstOrNull { c ->
            listOf(c.id, c.name).plus(c.aliases).filter { it.isNotBlank() }
                .any { norm(it) == norm(id) || norm(it) == norm(name) }
        }
    }

    fun mergedHeaders(config: StreamConfig): Map<String, String> =
        config.headers.toMutableMap().apply {
            if (config.cookie.isNotBlank()) {
                val old = entries.firstOrNull { it.key.equals("Cookie", true) }?.value
                put("Cookie", listOfNotNull(old, config.cookie).filter { it.isNotBlank() }.joinToString("; "))
            }
        }

    fun resolve(config: StreamConfig): List<String> {
        val headers = mergedHeaders(config)
        return when (config.mode.lowercase()) {
            "resolver" -> {
                val endpoint = config.resolverUrl.ifBlank { config.url }
                val body = requestText(endpoint, headers) ?: return listOf(config.fallbackUrl).filter { it.isNotBlank() }
                val resolved = extractResolverValue(body, config)
                listOf(resolved, config.fallbackUrl).filter { it.isNotBlank() }.distinct()
            }
            "sequential" -> sequentialCandidates(config)
            else -> listOf(config.url, config.fallbackUrl).filter { it.isNotBlank() }.distinct()
        }
    }

    private fun extractResolverValue(body: String, config: StreamConfig): String {
        val jsonPath = config.resolverJsonPath
        if (jsonPath.isNotBlank()) {
            try {
                var value: Any? = JSONObject(body)
                jsonPath.split(".").forEach { key ->
                    value = when (value) {
                        is JSONObject -> (value as JSONObject).opt(key)
                        is JSONArray -> (value as JSONArray).opt(key.toIntOrNull() ?: -1)
                        else -> null
                    }
                }
                if (value is String && (value as String).isNotBlank()) return value as String
            } catch (_: Exception) { }
        }
        val key = Regex.escape(config.resolverParam.ifBlank { "url" })
        val jsonMatch = Regex("[\"']?$key[\"']?\\s*[:=]\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(body)
        if (jsonMatch != null) return jsonMatch.groupValues[1]
        val urlMatch = Regex("https?://[^\\s\"'<>]+").find(body)
        return urlMatch?.value?.trimEnd(',', ';', ')') ?: ""
    }

    private fun sequentialCandidates(config: StreamConfig): List<String> {
        val pattern = config.sequenceRegex
        if (pattern.isBlank()) return (config.urls + config.url + config.fallbackUrl).filter { it.isNotBlank() }.distinct()
        val regex = try { Regex(pattern) } catch (_: Exception) { return emptyList() }
        val template = config.url
        val match = regex.find(template) ?: return listOf(template, config.fallbackUrl).filter { it.isNotBlank() }
        val current = match.groupValues.getOrNull(1)?.toLongOrNull() ?: config.sequenceStart
        val start = maxOf(config.sequenceStart, current - config.sequenceCount + 1)
        val result = (current downTo start).map { n ->
            template.replaceRange(match.range, match.value.replace(match.groupValues.getOrNull(1) ?: "", n.toString()))
        }.toMutableList()
        result.addAll(config.urls)
        if (config.fallbackUrl.isNotBlank()) result.add(config.fallbackUrl)
        return result.distinct()
    }

    fun okhttpFactory(config: StreamConfig): OkHttpDataSource.Factory =
        OkHttpDataSource.Factory(client).setDefaultRequestProperties(mergedHeaders(config))

    fun requestText(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val request = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    } catch (e: Exception) { Log.w(TAG, "Resolver request failed", e); null }

    fun probe(url: String, headers: Map<String, String> = emptyMap()): Boolean = try {
        val request = Request.Builder().url(url).head().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(request).execute().use { it.isSuccessful || it.code == 405 }
    } catch (e: Exception) { false }
}
