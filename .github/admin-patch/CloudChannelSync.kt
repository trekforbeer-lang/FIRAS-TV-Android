package com.firas.tv

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Shared channel catalog sync. Public clients download active non-private entries.
 * Only a Firebase-authenticated administrator can publish/delete documents.
 * Local JSON and stream credentials remain on-device as an offline cache.
 */
object CloudChannelSync {
    private val db: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }

    private const val PREFS = "rahmani_cloud_sync"
    private const val PENDING_DELETES = "pending_deletes"
    private const val CLOUD_IDS = "cloud_catalog_ids"

    fun publishAll(context: Context, root: JSONObject, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            onComplete?.invoke(false, "لا توجد جلسة إدارة")
            return
        }
        val writes = mutableListOf<Pair<String, Map<String, Any>>>()
        val sections = root.optJSONArray("sections") ?: JSONArray()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val sectionTitle = section.optString("title").ifBlank { "قنوات" }
            val items = section.optJSONArray("items") ?: continue
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val name = item.optString("name").trim()
                val url = item.optString("url").trim()
                if (name.isBlank() || name.length > 120 ||
                    !(url.startsWith("https://", true) || url.startsWith("http://", true)) ||
                    url.length > 2048) continue
                val data = hashMapOf<String, Any>(
                    "name" to name,
                    "url" to url,
                    "active" to item.optBoolean("active", true),
                    "priv" to item.optBoolean("priv", false),
                    "group" to sectionTitle,
                    "region" to section.optString("region"),
                    "sortOrder" to item.optInt("sortOrder", j),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                putString(data, "logo", item.optString("logo"))
                putString(data, "referer", item.optString("referer"))
                putString(data, "userAgent", item.optString("userAgent"))
                putString(data, "countryCode", item.optString("countryCode", item.optString("cc")))
                putString(data, "countryName", item.optString("countryName"))
                putString(data, "category", item.optString("category", sectionTitle))
                val alts = item.optJSONArray("alts")
                if (alts != null) {
                    val values = mutableListOf<String>()
                    for (k in 0 until alts.length()) alts.optString(k).takeIf { it.isNotBlank() }?.let(values::add)
                    data["alts"] = values
                }
                writes += docId(item) to data
            }
        }
        val chunks = writes.distinctBy { it.first }.chunked(400)
        flushPendingDeletes(context) { deleted, deleteError ->
            if (!deleted) {
                onComplete?.invoke(false, deleteError ?: "تعذّر تطبيق عمليات الحذف المؤجلة")
                return@flushPendingDeletes
            }
            commitChunks(chunks, 0, onComplete)
        }
    }

    private fun commitChunks(
        chunks: List<List<Pair<String, Map<String, Any>>>>,
        startIndex: Int,
        onComplete: ((Boolean, String?) -> Unit)?
    ) {
        fun commitChunk(index: Int) {
            if (index >= chunks.size) {
                onComplete?.invoke(true, null)
                return
            }
            val batch = db.batch()
            chunks[index].forEach { (id, data) -> batch.set(db.collection("channels").document(id), data) }
            batch.commit()
                .addOnSuccessListener { commitChunk(index + 1) }
                .addOnFailureListener { error -> onComplete?.invoke(false, error.localizedMessage) }
        }
        commitChunk(startIndex)
    }

    private fun flushPendingDeletes(context: Context, onComplete: (Boolean, String?) -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(PENDING_DELETES, emptySet()).orEmpty().toList()
        fun remove(id: String) {
            val updated = prefs.getStringSet(PENDING_DELETES, emptySet()).orEmpty().toMutableSet()
            updated.remove(id)
            prefs.edit().putStringSet(PENDING_DELETES, updated).apply()
        }
        fun deleteAt(index: Int) {
            if (index >= pending.size) {
                onComplete(true, null)
                return
            }
            val id = pending[index]
            db.collection("channels").document(id).delete()
                .addOnSuccessListener { remove(id); deleteAt(index + 1) }
                .addOnFailureListener { onComplete(false, it.localizedMessage) }
        }
        deleteAt(0)
    }

    fun delete(context: Context, channel: JSONObject, onComplete: ((Boolean, String?) -> Unit)? = null) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            onComplete?.invoke(false, "لا توجد جلسة إدارة")
            return
        }
        val id = docId(channel)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = prefs.getStringSet(PENDING_DELETES, emptySet()).orEmpty().toMutableSet()
        pending.add(id)
        prefs.edit().putStringSet(PENDING_DELETES, pending).apply()
        db.collection("channels").document(id).delete()
            .addOnSuccessListener {
                val updated = prefs.getStringSet(PENDING_DELETES, emptySet()).orEmpty().toMutableSet()
                updated.remove(id)
                prefs.edit().putStringSet(PENDING_DELETES, updated).apply()
                onComplete?.invoke(true, null)
            }
            .addOnFailureListener { onComplete?.invoke(false, it.localizedMessage) }
    }

    fun refreshPublic(context: Context, onComplete: ((Int) -> Unit)? = null) {
        db.collection("channels")
            .whereEqualTo("active", true)
            .whereEqualTo("priv", false)
            .get()
            .addOnSuccessListener { snapshot ->
                val rows = snapshot.documents.map { document ->
                    document.id to (document.data ?: emptyMap<String, Any>())
                }
                Thread {
                    val target = File(context.filesDir, "channels.json")
                    val root = try {
                        if (target.isFile) JSONObject(target.readText(Charsets.UTF_8))
                        else JSONObject(context.assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
                    } catch (_: Exception) {
                        JSONObject(context.assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
                    }
                    val groups = root.optJSONArray("sections") ?: JSONArray().also { root.put("sections", it) }
                    var changed = 0
                    val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    val previousCloudIds = prefs.getStringSet(CLOUD_IDS, emptySet()).orEmpty().toSet()
                    val currentCloudIds = rows.map { it.first }.toSet()
                    rows.forEach { (id, data) ->
                        val name = data["name"] as? String ?: return@forEach
                        val url = data["url"] as? String ?: return@forEach
                        if (name.isBlank() || url.isBlank()) return@forEach
                        val groupName = (data["group"] as? String)?.takeIf { it.isNotBlank() }
                            ?: (data["countryName"] as? String)?.takeIf { it.isNotBlank() }
                            ?: (data["category"] as? String)?.takeIf { it.isNotBlank() }
                            ?: "قنوات سحابية"
                        var section: JSONObject? = null
                        for (i in 0 until groups.length()) {
                            val candidate = groups.optJSONObject(i)
                            if (candidate?.optString("title") == groupName) { section = candidate; break }
                        }
                        if (section == null) {
                            section = JSONObject().put("title", groupName).put("region", data["region"] as? String ?: "").put("countryCode", data["countryCode"] as? String ?: "").put("items", JSONArray())
                            groups.put(section)
                        }
                        section?.put("region", data["region"] as? String ?: section.optString("region"))
                        section?.put("countryCode", data["countryCode"] as? String ?: section.optString("countryCode"))
                        val items = section?.optJSONArray("items")
                            ?: JSONArray().also { section?.put("items", it) }
                        var existing: JSONObject? = null
                        for (i in 0 until items.length()) {
                            val candidate = items.optJSONObject(i) ?: continue
                            if (candidate.optString("id") == id || candidate.optString("url") == url) {
                                existing = candidate
                                break
                            }
                        }
                        val item = existing ?: JSONObject().also { items.put(it) }
                        item.put("id", id).put("name", name).put("url", url).put("priv", false)
                        putJsonString(item, "region", data["region"])
                        putJsonString(item, "logo", data["logo"])
                        putJsonString(item, "referer", data["referer"])
                        putJsonString(item, "userAgent", data["userAgent"])
                        putJsonString(item, "cc", data["countryCode"])
                        putJsonString(item, "countryName", data["countryName"])
                        putJsonString(item, "category", data["category"])
                        putJsonString(item, "group", data["group"])
                        val remoteAlts = data["alts"] as? List<*>
                        if (remoteAlts != null) item.put("alts", JSONArray(remoteAlts.filterIsInstance<String>()))
                        changed++
                    }
                    val staleCloudIds = previousCloudIds - currentCloudIds
                    if (staleCloudIds.isNotEmpty()) {
                        for (i in 0 until groups.length()) {
                            val items = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                            for (j in items.length() - 1 downTo 0) {
                                val item = items.optJSONObject(j) ?: continue
                                if (staleCloudIds.contains(item.optString("id"))) {
                                    items.remove(j)
                                    changed++
                                }
                            }
                        }
                    }
                    if (changed > 0) {
                        val temp = File(context.filesDir, "channels.json.tmp")
                        temp.writeText(root.toString(2), Charsets.UTF_8)
                        if (!temp.renameTo(target)) {
                            temp.copyTo(target, overwrite = true)
                            temp.delete()
                        }
                    }
                    prefs.edit().putStringSet(CLOUD_IDS, currentCloudIds).apply()
                    onComplete?.invoke(changed)
                }.start()
            }
            .addOnFailureListener { onComplete?.invoke(0) }
    }

    private fun docId(item: JSONObject): String {
        val identity = item.optString("id").ifBlank { item.optString("url") }
        if (identity.matches(Regex("[0-9a-f]{64}"))) return identity
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun putString(target: MutableMap<String, Any>, key: String, value: String) {
        if (value.isNotBlank()) target[key] = value
    }

    private fun putJsonString(target: JSONObject, key: String, value: Any?) {
        if (value is String && value.isNotBlank()) target.put(key, value)
    }
}
