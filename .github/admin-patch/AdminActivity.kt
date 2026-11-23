package com.firas.tv

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Retains the supplied app's original administration UI and mirrors its public channels to Media3. */
class AdminActivity : Activity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = true
        web.settings.javaScriptCanOpenWindowsAutomatically = false
        web.settings.loadsImagesAutomatically = true
        web.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        web.addJavascriptInterface(AdminBridge(filesDir), "FirasBridge")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                return !(uri.scheme == "file" && uri.path == "/android_asset/admin.html")
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/admin.html#admin")
    }

    private class AdminBridge(private val directory: File) {
        @JavascriptInterface
        fun saveAdminState(raw: String) {
            try {
                val state = JSONObject(raw)
                writeAtomic(File(directory, "admin_state.json"), state.toString(2))
                val channels = state.optJSONArray("ch") ?: JSONArray()
                val categories = state.optJSONArray("cats") ?: JSONArray()
                val categoryNames = LinkedHashMap<String, String>()
                for (i in 0 until categories.length()) {
                    val category = categories.optJSONObject(i) ?: continue
                    val id = category.optString("id")
                    if (id.isNotBlank()) categoryNames[id] = category.optString("n", id)
                }
                val grouped = LinkedHashMap<String, JSONArray>()
                categoryNames.keys.forEach { grouped[it] = JSONArray() }
                for (i in 0 until channels.length()) {
                    val channel = channels.optJSONObject(i) ?: continue
                    if (channel.optBoolean("priv", false)) continue
                    val stream = channel.optString("url").trim()
                    if (!(stream.startsWith("http://", true) || stream.startsWith("https://", true))) continue
                    val categoryId = channel.optString("cat").ifBlank { "misc" }
                    val items = grouped.getOrPut(categoryId) { JSONArray() }
                    val item = JSONObject()
                        .put("id", channel.optString("id"))
                        .put("name", channel.optString("name", "قناة"))
                        .put("url", stream)
                        .put("logo", channel.optString("logo"))
                        .put("referer", channel.optString("referer"))
                        .put("userAgent", channel.optString("userAgent"))
                        .put("cookie", channel.optString("cookie"))
                    channel.optJSONArray("alts")?.let { item.put("aliases", it) }
                    items.put(item)
                }
                val sections = JSONArray()
                for ((id, items) in grouped) {
                    val title = categoryNames[id] ?: if (id == "misc") "قنوات أخرى" else id
                    sections.put(JSONObject().put("title", title).put("items", items))
                }
                val root = JSONObject().put("appName", "FIRAS TV").put("sections", sections)
                writeAtomic(File(directory, "channels.json"), root.toString(2))
            } catch (_: Exception) {
                // Keep the last valid channel list if a WebView save is incomplete.
            }
        }

        private fun writeAtomic(target: File, text: String) {
            val temp = File(directory, target.name + ".tmp")
            temp.writeText(text, Charsets.UTF_8)
            if (temp.renameTo(target)) return
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }
}