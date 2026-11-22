package com.firas.tv

import android.content.Context
import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

data class StreamConfig(
    val id: String, val name: String, val mode: String, val url: String,
    val headers: Map<String, String> = emptyMap(), val urls: List<String> = emptyList(),
    val cookie: String = ""
)

object UniversalStreamEngine {
    private const val TAG = "FIRASStream"
    private const val CONFIG = "channels_stream_config.json"
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

    fun load(context: Context): List<StreamConfig> {
        val file = File(context.filesDir, CONFIG)
        val raw = try {
            if (file.exists()) file.readText() else context.assets.open(CONFIG).bufferedReader().use { it.readText() }
        } catch (e: Exception) { Log.w(TAG, "Config unavailable", e); return emptyList() }
        return try {
            val arr = JSONObject(raw).optJSONArray("channels") ?: JSONArray()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val h = mutableMapOf<String, String>()
                val ho = o.optJSONObject("headers") ?: JSONObject()
                ho.keys().forEach { k -> h[k] = ho.optString(k) }
                val urls = mutableListOf<String>()
                val ua = o.optJSONArray("urls")
                if (ua != null) for (j in 0 until ua.length()) urls += ua.optString(j)
                StreamConfig(o.optString("id"), o.optString("name"), o.optString("mode", "direct"),
                    o.optString("url"), h, urls, o.optString("cookie"))
            }
        } catch (e: Exception) { Log.e(TAG, "Invalid stream config", e); emptyList() }
    }

    fun find(context: Context, idOrName: String): StreamConfig? =
        load(context).firstOrNull { it.id.equals(idOrName, true) || it.name.equals(idOrName, true) }

    fun mergedHeaders(config: StreamConfig): Map<String, String> =
        config.headers.toMutableMap().apply {
            if (config.cookie.isNotBlank()) put("Cookie", config.cookie)
        }

    fun candidates(config: StreamConfig): List<String> = when (config.mode.lowercase()) {
        "sequential", "resolver" -> (config.urls + config.url).filter { it.isNotBlank() }.distinct()
        else -> listOf(config.url).filter { it.isNotBlank() }
    }

    fun probe(url: String, headers: Map<String, String> = emptyMap()): Boolean = try {
        val request = Request.Builder().url(url).head().apply { headers.forEach { (k,v) -> header(k,v) } }.build()
        client.newCall(request).execute().use { it.isSuccessful || it.code == 405 }
    } catch (e: Exception) { Log.w(TAG, "Probe failed: $url", e); false }

    fun requestText(url: String, headers: Map<String, String> = emptyMap()): String? = try {
        val request = Request.Builder().url(url).get().apply { headers.forEach { (k,v) -> header(k,v) } }.build()
        client.newCall(request).execute().use { response -> response.body?.string() }
    } catch (e: Exception) { Log.w(TAG, "Request failed", e); null }

    fun rememberSetCookie(url: HttpUrl, response: Response) {
        val parsed = Cookie.parseAll(url, response.headers)
        if (parsed.isNotEmpty()) cookieJar.saveFromResponse(url, parsed)
    }
}
