package com.firas.tv

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Manually-started, persistent channel restore.
 * Imports at most 100 new channels per run, then queues the next batch 30 minutes later.
 */
class RestoreChannelsWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    companion object {
        const val TAG = "rahmani_manual_channel_restore"
        const val PREFS = "rahmani_channel_restore"
        private const val KEY_RUNNING = "running"
        private const val KEY_TOTAL = "total_added"
        private const val KEY_BATCH = "batch"
        private const val KEY_LAST_ERROR = "last_error"

        fun start(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_RUNNING, false)) return false
            prefs.edit().putBoolean(KEY_RUNNING, true).putInt(KEY_TOTAL, 0).putInt(KEY_BATCH, 0).remove(KEY_LAST_ERROR).apply()
            enqueueBatch(context, 0, 0)
            return true
        }

        private fun enqueueBatch(context: Context, batch: Int, delayMinutes: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<RestoreChannelsWorker>()
                .setInputData(workDataOf(KEY_BATCH to batch))
                .setConstraints(constraints)
                .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "restore_channels_batch_$batch", ExistingWorkPolicy.KEEP, request
            )
        }
    }

    private fun extractM3uAttribute(line: String, name: String): String {
        val pattern = Regex("""(?i)(?:^|[\s,])${Regex.escape(name)}\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,]+))""")
        val match = pattern.find(line) ?: return ""
        return (1..3).map { match.groupValues[it] }
            .firstOrNull { it.isNotBlank() }
            ?.replace("&amp;", "&", ignoreCase = true)
            ?.trim()
            .orEmpty()
    }

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val batch = inputData.getInt(KEY_BATCH, 0)
        var added = 0
        return try {
            val target = File(applicationContext.filesDir, "channels.json")
            val raw = if (target.isFile) target.readText(Charsets.UTF_8)
                else applicationContext.assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(raw)
            val groups = root.optJSONArray("sections") ?: JSONArray().also { root.put("sections", it) }
            val known = HashSet<String>()
            for (i in 0 until groups.length()) {
                val items = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                for (j in 0 until items.length()) {
                    val url = items.optJSONObject(j)?.optString("url").orEmpty()
                    if (url.isNotBlank()) known.add(url)
                }
            }

            val sources = listOf(
                "قنوات عربية" to "https://iptv-org.github.io/iptv/languages/ara.m3u",
                "رياضة" to "https://iptv-org.github.io/iptv/categories/sports.m3u",
                "أخبار" to "https://iptv-org.github.io/iptv/categories/news.m3u",
                "أطفال" to "https://iptv-org.github.io/iptv/categories/kids.m3u"
            )

            for ((title, endpoint) in sources) {
                if (added >= 100) break
                var section: JSONObject? = null
                for (i in 0 until groups.length()) {
                    val item = groups.optJSONObject(i)
                    if (item?.optString("title") == title) { section = item; break }
                }
                if (section == null) {
                    section = JSONObject().put("title", title).put("items", JSONArray())
                    groups.put(section)
                }
                val actualSection = section ?: continue
                val items = actualSection.optJSONArray("items") ?: JSONArray().also { actualSection.put("items", it) }
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 30000
                    instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "RAHMANI-TV/1.0")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IOException("Playlist HTTP $code")
                    var consumedBytes = 0L
                    var name = ""
                    var logo = ""
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                        while (true) {
                            val line = reader.readLine() ?: break
                            consumedBytes += line.toByteArray(Charsets.UTF_8).size + 1
                            if (consumedBytes > 8L * 1024L * 1024L) throw IOException("Playlist exceeds 8 MB")
                            val value = line.trim()
                            if (value.startsWith("#EXTINF", true)) {
                                name = value.substringAfterLast(',').trim()
                                logo = extractM3uAttribute(value, "tvg-logo").take(2048)
                            } else if (value.startsWith("http", true) && name.isNotBlank()) {
                                if (value !in known && added < 100) {
                                    items.put(JSONObject()
                                        .put("id", "iptv-" + value.hashCode())
                                        .put("name", name.take(120))
                                        .put("logo", logo.take(2048))
                                        .put("url", value.take(2048))
                                        .put("referer", ""))
                                    known.add(value)
                                    added++
                                }
                                name = ""
                                logo = ""
                                if (added >= 100) break
                            }
                        }
                    }
                } finally {
                    connection.disconnect()
                }
            }

            if (added > 0) {
                val temp = File(applicationContext.filesDir, "channels.json.tmp")
                temp.writeText(root.toString(2), Charsets.UTF_8)
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
            }

            val total = prefs.getInt(KEY_TOTAL, 0) + added
            prefs.edit().putInt(KEY_TOTAL, total).putInt(KEY_BATCH, batch).apply()
            setProgressAsync(workDataOf("added" to added, "total" to total, "batch" to batch))

            if (added == 100) {
                // A fresh request is queued so Android can resume the import after the app is closed.
                enqueueBatch(applicationContext, batch + 1, 30)
            } else {
                prefs.edit().putBoolean(KEY_RUNNING, false).apply()
            }
            Result.success()
        } catch (error: Exception) {
            // Retry transient failures, but never leave the restore UI permanently locked.
            if (runAttemptCount < 4) {
                Result.retry()
            } else {
                prefs.edit()
                    .putBoolean(KEY_RUNNING, false)
                    .putString(KEY_LAST_ERROR, error.message?.take(240) ?: "تعذر تحميل قوائم القنوات")
                    .apply()
                Result.failure(workDataOf("error" to (error.message ?: "restore_failed")))
            }
        }
    }
}
