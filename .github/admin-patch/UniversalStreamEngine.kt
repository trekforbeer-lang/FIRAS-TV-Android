package com.firas.tv

import android.content.Context
import android.util.Log
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
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
    val resolverValue: String = "",
    val resolverJsonPath: String = "",
    val sequenceRegex: String = "",
    val sequenceStart: Long = 0L,
    val sequenceCount: Int = 8,
    val fallbackUrl: String = ""
)

data class LegacyChannel(val id: String, val name: String, val category: String, val logo: String, val stream: String, val headers: Map<String, String> = emptyMap(), val qualityUrls: Map<String, String> = emptyMap())

object UniversalStreamEngine {
    private const val TAG = "FIRASStream"
    private const val CONFIG = "channels_stream_config.json"
    private const val LEGACY = "channels.json"
    private val channelCookieStores = mutableMapOf<String, MutableMap<String, MutableList<Cookie>>>()
    private val channelCookieJars = mutableMapOf<String, CookieJar>()
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
            .connectTimeout(15, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true).build()
    }

    private fun cookieJarFor(channelId: String): CookieJar = synchronized(channelCookieJars) {
        channelCookieJars.getOrPut(channelId) {
            object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    synchronized(channelCookieStores) {
                        val store = channelCookieStores.getOrPut(channelId) { mutableMapOf() }
                        val current = store[url.host]?.toMutableList() ?: mutableListOf()
                        cookies.forEach { cookie ->
                            current.removeAll { it.name == cookie.name && it.path == cookie.path }
                            current.add(cookie)
                        }
                        store[url.host] = current
                    }
                }
                override fun loadForRequest(url: HttpUrl): List<Cookie> =
                    synchronized(channelCookieStores) {
                        channelCookieStores[channelId]?.get(url.host)?.filter { it.matches(url) } ?: emptyList()
                    }
            }
        }
    }

    private fun readConfig(context: Context): String? {
        val file = File(context.filesDir, CONFIG)
        return try {
            if (file.isFile) file.readText()
            else context.assets.open(CONFIG).bufferedReader().use { it.readText() }
        } catch (e: Exception) { Log.w(TAG, "Stream config unavailable", e); null }
    }

    fun load(context: Context): List<StreamConfig> {
        val overrideFile = File(context.filesDir, CONFIG)
        val assetRaw = try {
            context.assets.open(CONFIG).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Bundled stream config unavailable", e)
            null
        }
        fun parse(raw: String?): List<StreamConfig> {
            if (raw.isNullOrBlank()) return emptyList()
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
                    o.optJSONArray("urls")?.let { a ->
                        for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let(urls::add)
                    }
                    val aliases = mutableListOf<String>()
                    o.optJSONArray("aliases")?.let { a ->
                        for (j in 0 until a.length()) a.optString(j).takeIf { it.isNotBlank() }?.let(aliases::add)
                    }
                    StreamConfig(
                        id = o.optString("id"), name = o.optString("name"),
                        mode = o.optString("mode", "direct"), url = o.optString("url"),
                        headers = headers, urls = urls, cookie = o.optString("cookie"),
                        aliases = aliases, resolverUrl = o.optString("resolver_url"),
                        resolverParam = o.optString("resolver_param", "id"),
                        resolverValue = o.optString("resolver_value"),
                        resolverJsonPath = o.optString("resolver_json_path"),
                        sequenceRegex = o.optString("sequence_regex"),
                        sequenceStart = o.optLong("sequence_start", 0L),
                        sequenceCount = o.optInt("sequence_count", 8).coerceIn(1, 30),
                        fallbackUrl = o.optString("fallback_url")
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Invalid stream config; trying bundled defaults", e)
                emptyList()
            }
        }
        val overrideConfigs = if (overrideFile.isFile) parse(try { overrideFile.readText() } catch (_: Exception) { null }) else emptyList()
        return if (overrideConfigs.isNotEmpty()) overrideConfigs else parse(assetRaw)
    }

    fun loadLegacy(context: Context): List<LegacyChannel> = try {
        val override = File(context.filesDir, LEGACY)
        val raw = if (override.isFile) override.readText(Charsets.UTF_8)
            else context.assets.open(LEGACY).bufferedReader().use { it.readText() }
        val parsed = org.json.JSONTokener(raw).nextValue()
        val result = mutableListOf<LegacyChannel>()
        when (parsed) {
            is JSONArray -> {
                for (i in 0 until parsed.length()) {
                    val item = parsed.optJSONObject(i) ?: continue
                    val stream = item.optString("stream", item.optString("url"))
                    if (stream.isBlank()) continue
                    val headers = linkedMapOf<String, String>()
                    item.optString("referer").takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
                    item.optString("userAgent").takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
                    item.optString("cookie").takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                    (item.optJSONObject("headers") ?: JSONObject()).let { extra ->
                        extra.keys().forEach { key -> headers[key] = extra.optString(key) }
                    }
                    val qualityUrls = linkedMapOf<String, String>()
                    item.optJSONObject("qualityUrls")?.let { q -> listOf("SD", "HD", "FHD", "4K").forEach { label -> q.optString(label).takeIf { it.isNotBlank() }?.let { qualityUrls[label] = it } } }
                    result += LegacyChannel(item.optString("id", "legacy-$i"), item.optString("name", "Channel $i"),
                        item.optString("category"), item.optString("logo"), stream, headers, qualityUrls)
                }
            }
            is JSONObject -> {
                val sections = parsed.optJSONArray("sections")
                if (sections != null) {
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
                            val qualityUrls = linkedMapOf<String, String>()
                            item.optJSONObject("qualityUrls")?.let { q -> listOf("SD", "HD", "FHD", "4K").forEach { label -> q.optString(label).takeIf { it.isNotBlank() }?.let { qualityUrls[label] = it } } }
                            result += LegacyChannel(item.optString("id", "legacy-$i-$j"), item.optString("name", "Channel $j"),
                                category, item.optString("logo"), stream, headers, qualityUrls)
                        }
                    }
                } else {
                    val arr = parsed.optJSONArray("channels") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val stream = item.optString("stream", item.optString("url"))
                        if (stream.isBlank()) continue
                        val headers = linkedMapOf<String, String>()
                        item.optString("referer").takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
                        item.optString("userAgent").takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
                        item.optString("cookie").takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                        (item.optJSONObject("headers") ?: JSONObject()).let { extra ->
                            extra.keys().forEach { key -> headers[key] = extra.optString(key) }
                        }
                        result += LegacyChannel(item.optString("id", "legacy-$i"), item.optString("name", "Channel $i"),
                            item.optString("category"), item.optString("logo"), stream, headers)
                    }
                }
            }
        }
        result
    } catch (e: Exception) { Log.w(TAG, "Legacy channel list unavailable", e); emptyList() }

    fun match(configs: List<StreamConfig>, id: String, name: String): StreamConfig? {
        fun norm(s: String) = s.trim().lowercase().replace(Regex("[^\\p{L}\\p{N}]"), "")
        val keys = setOf(norm(id), norm(name)).filter { it.isNotBlank() }.toSet()
        return configs.firstOrNull { c ->
            (listOf(c.id, c.name) + c.aliases).map(::norm).any { it.isNotBlank() && it in keys }
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
                val requestUrl = try {
                    val parsed = endpoint.toHttpUrl()
                    val parameter = config.resolverParam.ifBlank { "id" }
                    val existingValue = parsed.queryParameter(parameter).orEmpty()
                    val value = config.resolverValue.ifBlank {
                        existingValue.ifBlank {
                            when (parameter.lowercase()) {
                                "name", "channel", "channel_name" -> config.name
                                "slug" -> config.id.ifBlank { config.name.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-') }
                                "url", "stream", "source" -> config.url
                                else -> config.id.ifBlank { config.name }
                            }
                        }
                    }
                    if (value.isBlank() || value == existingValue) parsed
                    else parsed.newBuilder().setQueryParameter(parameter, value).build()
                } catch (e: Exception) {
                    Log.w(TAG, "Invalid resolver endpoint", e)
                    return listOf(config.fallbackUrl).filter { it.isNotBlank() }
                }
                var resolved = ""
                repeat(2) { attempt ->
                    if (resolved.isBlank()) {
                        resolved = resolveHttpEntry(requestUrl.toString(), headers, config.id, config)
                        if (resolved.isBlank() && attempt == 0) Thread.sleep(350)
                    }
                }
                listOf(resolved, config.fallbackUrl).filter { it.isNotBlank() }.distinct()
            }
            "sequential" -> {
                val candidates = sequentialCandidates(config)
                val liveEdge = candidates.firstOrNull { probe(it, headers, config.id) }
                if (liveEdge != null) listOf(liveEdge) + candidates.filter { it != liveEdge }
                else candidates
            }
            else -> (config.urls + config.url + config.fallbackUrl).filter { it.isNotBlank() }.distinct()
        }
    }

    private fun resolveHttpEntry(
        url: String,
        headers: Map<String, String>,
        channelId: String,
        config: StreamConfig
    ): String = try {
        val request = Request.Builder().url(url).get()
            .apply { headers.forEach { (key, value) -> header(key, value) } }.build()
        val requestClient = client.newBuilder().cookieJar(cookieJarFor(channelId)).build()
        requestClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use ""
            val finalUrl = response.request.url
            val body = response.body?.string().orEmpty()
            val trimmed = body.trim()
            // PHP endpoints may return an HLS manifest directly or redirect to its final URL.
            if (trimmed.startsWith("#EXTM3U") ||
                (finalUrl.encodedPath.endsWith(".m3u8", true) &&
                    (trimmed.isBlank() || response.header("Content-Type").orEmpty().contains("mpegurl", true)))) {
                return@use finalUrl.toString()
            }
            val quotedM3u8 = Regex("""["']([^"'\s<>]+\.m3u8(?:\?[^"'\s<>]*)?)["']""", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.getOrNull(1).orEmpty()
            val absoluteM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""", RegexOption.IGNORE_CASE)
                .find(body)?.value?.trimEnd(',', ';', ')').orEmpty()
            val extracted = when {
                trimmed.startsWith("http://") || trimmed.startsWith("https://") ->
                    trimmed.lineSequence().firstOrNull()?.trim()?.trim('"')
                quotedM3u8.isNotBlank() -> quotedM3u8
                absoluteM3u8.isNotBlank() -> absoluteM3u8
                trimmed.endsWith(".m3u8", true) -> trimmed.trim('"')
                else -> extractResolverValue(body, config)
            }
            extracted?.takeIf { it.isNotBlank() }?.let { finalUrl.resolve(it)?.toString() }.orEmpty()
        }
    } catch (e: Exception) {
        Log.w(TAG, "Resolver entry failed", e)
        ""
    }

    private fun extractResolverValue(body: String, config: StreamConfig): String {
        val jsonPath = config.resolverJsonPath
        if (jsonPath.isNotBlank()) {
            try {
                var value: Any? = org.json.JSONTokener(body).nextValue()
                jsonPath.split(".").forEach { key ->
                    value = when (value) {
                        is JSONObject -> (value as JSONObject).opt(key)
                        is JSONArray -> (value as JSONArray).opt(key.toIntOrNull() ?: -1)
                        else -> null
                    }
                }
                if (value is String && (value as String).isNotBlank()) return (value as String).trim()
            } catch (_: Exception) { }
        }
        val key = Regex.escape(config.resolverParam.ifBlank { "url" })
        val jsonMatch = Regex("""["']?$key["']?\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(body)
        if (jsonMatch != null) return jsonMatch.groupValues[1]
        val urlMatch = Regex("""https?://[^\s"'<>]+""").find(body)
        return urlMatch?.value?.trimEnd(',', ';', ')') ?: ""
    }

    private fun sequentialCandidates(config: StreamConfig): List<String> {
        val pattern = config.sequenceRegex
        if (pattern.isBlank()) return (config.urls + config.url + config.fallbackUrl).filter { it.isNotBlank() }.distinct()
        val regex = try { Regex(pattern) } catch (_: Exception) { return emptyList() }
        val template = config.url
        val match = regex.find(template) ?: return (config.urls + template + config.fallbackUrl).filter { it.isNotBlank() }.distinct()
        // Kotlin's MatchGroupCollection supports indexed access; getOrNull is unavailable here.
        val numberGroup = if (match.groups.size > 1) match.groups[1] else null
        val current = numberGroup?.value?.toLongOrNull() ?: config.sequenceStart
        val start = maxOf(config.sequenceStart, current - config.sequenceCount + 1)
        val result = (current downTo start).map { n ->
            if (numberGroup != null) template.replaceRange(numberGroup.range, n.toString())
            else template.replaceRange(match.range, n.toString())
        }.toMutableList()
        result.addAll(config.urls)
        if (config.fallbackUrl.isNotBlank()) result.add(config.fallbackUrl)
        return result.distinct()
    }

    fun okhttpFactory(config: StreamConfig): OkHttpDataSource.Factory {
        val configured = mergedHeaders(config)
        val staticCookie = configured.entries.firstOrNull { it.key.equals("Cookie", true) }?.value.orEmpty()
        val requestHeaders = configured.filterKeys { !it.equals("Cookie", true) }
        val channelCookieJar = cookieJarFor(config.id)
        val cookieHost = try {
            (config.url.ifBlank { config.resolverUrl }).toHttpUrl().host
        } catch (_: Exception) { "" }
        val channelClient = client.newBuilder().cookieJar(channelCookieJar).addNetworkInterceptor { chain ->
            val original = chain.request()
            val jarCookies = channelCookieJar.loadForRequest(original.url).map { it.name + "=" + it.value }
            val supplied = original.header("Cookie").orEmpty()
            // Never forward a static channel cookie to a different host after redirects.
            val hostCookie = if (cookieHost.isNotBlank() && original.url.host.equals(cookieHost, true)) staticCookie else ""
            val merged = linkedMapOf<String, String>()
            (listOf(supplied, hostCookie) + jarCookies).forEach { header ->
                header.split(";").forEach { part ->
                    val pair = part.trim().split("=", limit = 2)
                    if (pair.size == 2 && pair[0].isNotBlank()) merged[pair[0].trim()] = pair[1].trim()
                }
            }
            val request = original.newBuilder().apply {
                removeHeader("Cookie")
                if (merged.isNotEmpty()) header("Cookie", merged.entries.joinToString("; ") { "${it.key}=${it.value}" })
            }.build()
            chain.proceed(request)
        }.build()
        return OkHttpDataSource.Factory(channelClient).setDefaultRequestProperties(requestHeaders)
    }

    fun requestText(url: String, headers: Map<String, String> = emptyMap(), channelId: String? = null): String? = try {
        val request = Request.Builder().url(url).get().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val requestClient = channelId?.let { client.newBuilder().cookieJar(cookieJarFor(it)).build() } ?: client
        requestClient.newCall(request).execute().use { response -> if (response.isSuccessful) response.body?.string() else null }
    } catch (e: Exception) { Log.w(TAG, "Resolver request failed", e); null }

    fun probe(url: String, headers: Map<String, String> = emptyMap(), channelId: String? = null): Boolean = try {
        val request = Request.Builder().url(url).head().apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        val requestClient = channelId?.let { client.newBuilder().cookieJar(cookieJarFor(it)).build() } ?: client
        requestClient.newCall(request).execute().use { it.isSuccessful || it.code == 405 }
    } catch (e: Exception) { false }
}
