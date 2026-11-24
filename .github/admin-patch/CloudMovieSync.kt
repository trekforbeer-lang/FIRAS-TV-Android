package com.firas.tv

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Cloud catalog for owner-managed public movies. */
object CloudMovieSync {
    private val db by lazy { FirebaseFirestore.getInstance() }

    fun publishAll(context: Context, rows: JSONArray, done: ((Boolean, String?) -> Unit)? = null) {
        if (FirebaseAuth.getInstance().currentUser == null) {
            done?.invoke(false, "لا توجد جلسة مدير")
            return
        }
        val local = linkedMapOf<String, Map<String, Any>>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONObject(i) ?: continue
            val title = row.optString("title").trim()
            val url = row.optString("url").trim()
            if (title.isBlank() || title.length > 180 ||
                !(url.startsWith("https://", true) || url.startsWith("http://", true)) || url.length > 2048) continue
            val identity = row.optString("id").ifBlank { title + "|" + url }
            val id = if (identity.matches(Regex("[0-9a-f]{64}"))) identity else documentId(identity)
            val data = hashMapOf<String, Any>(
                "title" to title, "url" to url, "active" to true,
                "updatedAt" to FieldValue.serverTimestamp()
            )
            listOf("poster", "country").forEach { key ->
                row.optString(key).takeIf { it.isNotBlank() }?.let { data[key] = it.take(2048) }
            }
            local[id] = data
        }
        db.collection("movies").get()
            .addOnSuccessListener { snapshot ->
                val stale = snapshot.documents.map { it.id }.filter { it !in local.keys }
                        val refs = local.entries.toList()
                val tasks = (stale.map { id -> Triple(id, null as Map<String, Any>?, true) } +
                    refs.map { Triple(it.key, it.value, false) })
                fun commitAt(start: Int) {
                    if (start >= tasks.size) { done?.invoke(true, null); return }
                    val batch = db.batch()
                    tasks.drop(start).take(400).forEach { (id, data, remove) ->
                        val ref = db.collection("movies").document(id)
                        if (remove) batch.delete(ref) else batch.set(ref, data!!)
                    }
                    batch.commit().addOnSuccessListener { commitAt(start + 400) }
                        .addOnFailureListener { done?.invoke(false, it.localizedMessage) }
                }
                commitAt(0)
            }
            .addOnFailureListener { done?.invoke(false, it.localizedMessage) }
    }

    fun refreshPublic(context: Context, done: (() -> Unit)? = null) {
        db.collection("movies").whereEqualTo("active", true).get()
            .addOnSuccessListener { snapshot ->
                Thread {
                    val target = File(context.filesDir, "movies.json")
                    val merged = linkedMapOf<String, JSONObject>()
                    try {
                        val old = if (target.isFile) JSONObject(target.readText(Charsets.UTF_8)).optJSONArray("movies") else null
                        if (old != null) for (i in 0 until old.length()) {
                            val row = old.optJSONObject(i) ?: continue
                            row.optString("id").takeIf { it.isNotBlank() }?.let { merged[it] = row }
                        }
                    } catch (_: Exception) { }
                    snapshot.documents.forEach { doc ->
                        val data = doc.data ?: return@forEach
                        val row = JSONObject().put("id", doc.id)
                            .put("title", data["title"] as? String ?: "")
                            .put("url", data["url"] as? String ?: "")
                            .put("active", true)
                        (data["poster"] as? String)?.let { row.put("poster", it) }
                        (data["country"] as? String)?.let { row.put("country", it) }
                        merged[doc.id] = row
                    }
                    val rows = JSONArray()
                    merged.values.forEach { rows.put(it) }
                    val temp = File(context.filesDir, "movies.json.tmp")
                    temp.writeText(JSONObject().put("movies", rows).toString(2), Charsets.UTF_8)
                    if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
                    done?.let { android.os.Handler(context.mainLooper).post(it) }
                }.start()
            }
            .addOnFailureListener { done?.let { android.os.Handler(context.mainLooper).post(it) } }
    }

    private fun documentId(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
