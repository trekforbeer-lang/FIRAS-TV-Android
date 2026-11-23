package com.firas.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Native port of the original FIRAS TV admin workflow.
 * Bundled channels are protected from edit/delete; user-added channels remain manageable.
 */
class AdminActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("firas_admin_auth", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var panel: LinearLayout
    private var root = JSONObject()
    private var selectedSection = -1
    private var authenticated = false
    private val autoLogout = Runnable {
        if (authenticated) {
            authenticated = false
            Toast.makeText(this, "انتهت جلسة الإدارة", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authenticate()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        if (authenticated) {
            handler.removeCallbacks(autoLogout)
            handler.postDelayed(autoLogout, 10 * 60 * 1000L)
        }
    }

    override fun onPause() {
        if (authenticated) {
            authenticated = false
            handler.removeCallbacks(autoLogout)
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoLogout)
        super.onDestroy()
    }

    private fun authenticate() {
        val lockUntil = prefs.getLong("lock_until", 0L)
        if (System.currentTimeMillis() < lockUntil) {
            Toast.makeText(this, "محاولات كثيرة. حاول لاحقاً.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        val salt = prefs.getString("salt", null)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 20, 32, 8)
        }
        val first = EditText(this).apply {
            hint = if (salt == null) "كلمة مرور الإدارة (10 أحرف على الأقل)" else "كلمة مرور الإدارة"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        box.addView(first)
        val second = if (salt == null) EditText(this).apply {
            hint = "أعد كتابة كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        } else null
        second?.let(box::addView)
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (salt == null) "إنشاء كلمة مرور الإدارة" else "دخول الإدارة")
            .setMessage(if (salt == null) "استخدم 10 أحرف على الأقل مع حرف ورقم." else "أدخل كلمة المرور للمتابعة.")
            .setView(box)
            .setPositiveButton(if (salt == null) "إنشاء" else "دخول", null)
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val password = first.text.toString()
                if (salt == null) {
                    if (!strong(password) || password != second?.text?.toString()) {
                        Toast.makeText(this, "كلمة المرور ضعيفة أو غير متطابقة", Toast.LENGTH_LONG).show()
                        return@setOnClickListener
                    }
                    val newSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                    prefs.edit().putString("salt", Base64.encodeToString(newSalt, Base64.NO_WRAP))
                        .putString("hash", hashPassword(password, newSalt)).putInt("fails", 0).apply()
                    dialog.dismiss()
                    enterPanel()
                } else {
                    val decoded = try { Base64.decode(salt, Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
                    val expected = prefs.getString("hash", "").orEmpty()
                    if (decoded.isNotEmpty() && constantTimeEquals(hashPassword(password, decoded), expected)) {
                        prefs.edit().putInt("fails", 0).putLong("lock_until", 0L).apply()
                        dialog.dismiss()
                        enterPanel()
                    } else {
                        val fails = prefs.getInt("fails", 0) + 1
                        val edit = prefs.edit().putInt("fails", fails)
                        if (fails >= 5) {
                            val exponent = (fails - 5).coerceAtMost(8)
                            edit.putLong("lock_until", System.currentTimeMillis() + minOf(24L * 60 * 60 * 1000, 5L * 60 * 1000 * (1L shl exponent)))
                        }
                        edit.apply()
                        Toast.makeText(this, "كلمة المرور غير صحيحة", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun strong(value: String) =
        value.length >= 10 && value.any { it.isLetter() } && value.any { it.isDigit() }

    private fun hashPassword(password: String, salt: ByteArray): String {
        val spec = PBEKeySpec(password.toCharArray(), salt, 120_000, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
                .joinToString("") { "%02x".format(it) }
        } finally { spec.clearPassword() }
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun enterPanel() {
        authenticated = true
        handler.postDelayed(autoLogout, 10 * 60 * 1000L)
        root = readRoot()
        val scroll = ScrollView(this)
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 18, 18, 36)
            setBackgroundColor(0xFF090A0E.toInt())
        }
        scroll.addView(panel)
        setContentView(scroll)
        render()
    }

    private fun readRoot(): JSONObject {
        val file = File(filesDir, "channels.json")
        return try {
            if (file.isFile) JSONObject(file.readText(Charsets.UTF_8))
            else JSONObject(assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) {
            JSONObject(assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }

    private fun sections(): JSONArray =
        root.optJSONArray("sections") ?: JSONArray().also { root.put("sections", it) }

    private fun items(sectionIndex: Int): JSONArray =
        sections().optJSONObject(sectionIndex)?.optJSONArray("items") ?: JSONArray()

    private fun save() {
        val target = File(filesDir, "channels.json")
        val temp = File(filesDir, "channels.json.tmp")
        temp.writeText(root.toString(2), Charsets.UTF_8)
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        Toast.makeText(this, "تم حفظ القنوات", Toast.LENGTH_SHORT).show()
    }

    private fun isOriginal(channel: JSONObject): Boolean {
        val name = channel.optString("name")
        val url = channel.optString("url")
        return try {
            val raw = JSONObject(assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val groups = raw.optJSONArray("sections") ?: return false
            for (i in 0 until groups.length()) {
                val list = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                for (j in 0 until list.length()) {
                    val item = list.optJSONObject(j) ?: continue
                    if (item.optString("name") == name && item.optString("url") == url) return true
                }
            }
            false
        } catch (_: Exception) { false }
    }

    private fun label(text: String, size: Float = 16f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(4, 12, 4, 8)
    }

    private fun action(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        minHeight = 44
        setOnClickListener { click() }
    }

    private fun render() {
        panel.removeAllViews()
        panel.addView(label("لوحة إدارة FIRAS TV", 24f))
        var count = 0
        sections().let { groups ->
            for (i in 0 until groups.length()) count += groups.optJSONObject(i)?.optJSONArray("items")?.length() ?: 0
        }
        panel.addView(label("عدد القنوات: $count", 13f))
        panel.addView(action("＋ إضافة قناة") { editChannel(null, null) })
        panel.addView(action("＋ إضافة تصنيف") { addCategory() })
        panel.addView(action("استيراد قائمة M3U / روابط") { importChannels() })
        panel.addView(action("نسخ نسخة احتياطية") { backup() })
        panel.addView(action("استعادة نسخة احتياطية") { restore() })
        panel.addView(action("فحص روابط القنوات") { checkChannels() })
        panel.addView(action("حذف القنوات المعطلة المضافة فقط") { deleteBrokenAdded() })

        val groups = sections()
        for (i in 0 until groups.length()) {
            val section = groups.optJSONObject(i) ?: continue
            panel.addView(label(section.optString("title"), 19f))
            val categoryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            categoryActions.addView(action("تغيير الاسم") { renameCategory(i) }, LinearLayout.LayoutParams(0, -2, 1f))
            val protected = (0 until items(i).length()).any { j -> items(i).optJSONObject(j)?.let(::isOriginal) == true }
            categoryActions.addView(action(if (protected) "تصنيف محمي" else "حذف التصنيف") {
                if (!protected) deleteCategory(i)
                else Toast.makeText(this, "لا يمكن حذف تصنيف يحتوي قنوات أصلية", Toast.LENGTH_LONG).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            panel.addView(categoryActions)
            val list = items(i)
            for (j in 0 until list.length()) {
                val channel = list.optJSONObject(j) ?: continue
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(10, 8, 10, 8)
                    setBackgroundColor(0xFF191B23.toInt())
                }
                row.addView(label(channel.optString("name"), 15f))
                val url = channel.optString("url")
                row.addView(TextView(this).apply {
                    text = if (url.isBlank()) "بدون رابط بث" else url
                    textSize = 10f
                    setTextColor(0xFFB7BAC5.toInt())
                })
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                val original = isOriginal(channel)
                buttons.addView(action("تعديل") { editChannel(i, j) }, LinearLayout.LayoutParams(0, -2, 1f))
                buttons.addView(action("حذف") { deleteChannel(i, j, channel.optString("name")) }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(buttons)
                panel.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 7 })
            }
        }
        panel.addView(action("تغيير كلمة مرور الإدارة") { changePassword() })
        panel.addView(action("تسجيل الخروج") { authenticated = false; handler.removeCallbacks(autoLogout); finish() })
    }

    private fun addCategory() {
        val input = EditText(this).apply { hint = "اسم التصنيف" }
        AlertDialog.Builder(this).setTitle("إضافة تصنيف").setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank() && (0 until sections().length()).none { sections().optJSONObject(it)?.optString("title") == name }) {
                    sections().put(JSONObject().put("title", name).put("items", JSONArray()))
                    save(); render()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun renameCategory(index: Int) {
        val section = sections().optJSONObject(index) ?: return
        val input = EditText(this).apply { setText(section.optString("title")) }
        AlertDialog.Builder(this).setTitle("تغيير اسم التصنيف").setView(input)
            .setPositiveButton("حفظ") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) { section.put("title", name); save(); render() }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun deleteCategory(index: Int) {
        val list = items(index)
        AlertDialog.Builder(this).setTitle("حذف التصنيف")
            .setMessage("سيُنقل محتوى التصنيف إلى «بدون تصنيف»، ولن تُحذف القنوات.")
            .setPositiveButton("متابعة") { _, _ ->
                val groups = sections()
                var target = (0 until groups.length()).firstOrNull {
                    it != index && groups.optJSONObject(it)?.optString("title") == "بدون تصنيف"
                }
                if (target == null) {
                    groups.put(JSONObject().put("title", "بدون تصنيف").put("items", JSONArray()))
                    target = groups.length() - 1
                }
                val destinationIndex = target ?: return@setPositiveButton
                val dest = items(destinationIndex)
                for (i in 0 until list.length()) dest.put(list.optJSONObject(i))
                groups.remove(index)
                save(); render()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun editChannel(sectionIndex: Int?, itemIndex: Int?) {
        val old = if (sectionIndex != null && itemIndex != null) items(sectionIndex).optJSONObject(itemIndex) else null
        if (old != null && isOriginal(old)) { /* originals can now be managed by the owner */ }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        fun field(hintText: String, value: String = "", multiline: Boolean = false): EditText {
            val e = EditText(this).apply {
                hint = hintText
                setText(value)
                if (multiline) { minLines = 3; gravity = Gravity.TOP; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
            }
            form.addView(e)
            return e
        }
        val name = field("اسم القناة", old?.optString("name").orEmpty())
        val url = field("رابط البث (الصق رابط M3U8 أو MP4)", old?.optString("url").orEmpty())
        form.addView(action("لصق الرابط من الحافظة") { pasteInto(url) })
        val logo = field("رابط الشعار", old?.optString("logo").orEmpty())
        form.addView(action("لصق رابط الشعار من الحافظة") { pasteInto(logo) })
        val referer = field("Referer", old?.optString("referer").orEmpty())
        val country = field("رمز الدولة مثل DZ أو SA", old?.optString("cc").orEmpty())
        val category = field("التصنيف", sectionIndex?.let { sections().optJSONObject(it)?.optString("title") }.orEmpty())
        val alts = field("روابط بديلة - رابط في كل سطر", old?.optJSONArray("alts")?.let { a -> (0 until a.length()).joinToString("\n") { a.optString(it) } }.orEmpty(), true)
        val privateBox = CheckBox(this).apply { text = "قناة خاصة (لا تظهر في القائمة العامة)"; isChecked = old?.optBoolean("priv", false) ?: false }
        form.addView(privateBox)
        val scroller = ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this).setTitle(if (old == null) "إضافة قناة" else "تعديل القناة").setView(scroller)
            .setPositiveButton("حفظ") { _, _ ->
                val channelName = name.text.toString().trim()
                val stream = url.text.toString().trim()
                val logoUrl = logo.text.toString().trim()
                if (channelName.isBlank() || (stream.isNotBlank() && !safe(stream)) || (logoUrl.isNotBlank() && !safe(logoUrl))) {
                    Toast.makeText(this, "تحقق من الاسم والروابط", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val altValues = alts.text.toString().lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
                if (altValues.any { !safe(it) }) { Toast.makeText(this, "يوجد رابط بديل غير صالح", Toast.LENGTH_LONG).show(); return@setPositiveButton }
                val targetName = category.text.toString().trim().ifBlank { "بدون تصنيف" }
                val groups = sections()
                var targetIndex = (0 until groups.length()).firstOrNull { groups.optJSONObject(it)?.optString("title") == targetName }
                if (targetIndex == null) {
                    groups.put(JSONObject().put("title", targetName).put("items", JSONArray()))
                    targetIndex = groups.length() - 1
                }
                val destinationIndex = targetIndex ?: return@setPositiveButton
                val item = old ?: JSONObject().put("id", "admin-${System.currentTimeMillis()}")
                item.put("name", channelName).put("url", stream).put("logo", logoUrl)
                    .put("referer", referer.text.toString().trim())
                    .put("cc", country.text.toString().trim().uppercase())
                    .put("priv", privateBox.isChecked)
                    .put("alts", JSONArray(altValues))
                if (old == null) items(destinationIndex).put(item)
                else {
                    items(sectionIndex!!).remove(itemIndex!!)
                    items(destinationIndex).put(item)
                }
                save(); render()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun pasteInto(target: EditText) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val pasted = clipboard.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString()?.trim().orEmpty()
        if (safe(pasted)) target.setText(pasted)
        else Toast.makeText(this, "الحافظة لا تحتوي رابط http أو https صالحاً", Toast.LENGTH_LONG).show()
    }

    private fun deleteChannel(sectionIndex: Int, itemIndex: Int, name: String) {
        AlertDialog.Builder(this).setTitle("حذف القناة").setMessage("هل تريد حذف القناة المضافة «$name»؟")
            .setPositiveButton("حذف") { _, _ ->
                val channel = items(sectionIndex).optJSONObject(itemIndex)
                if (channel != null) {
                    items(sectionIndex).remove(itemIndex); save(); render()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun importChannels() {
        val input = EditText(this).apply {
            hint = "#EXTM3U أو اسم القناة | https://..."
            minLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        AlertDialog.Builder(this).setTitle("استيراد القنوات").setView(ScrollView(this).apply { addView(input) })
            .setPositiveButton("استيراد") { _, _ ->
                val parsed = parseM3u(input.text.toString())
                var added = 0
                val groups = sections()
                val sectionName = "قنوات مستوردة"
                var idx = (0 until groups.length()).firstOrNull { groups.optJSONObject(it)?.optString("title") == sectionName }
                if (idx == null) { groups.put(JSONObject().put("title", sectionName).put("items", JSONArray())); idx = groups.length() - 1 }
                val dest = items(idx)
                val existing = mutableSetOf<String>()
                for (i in 0 until groups.length()) {
                    val arr = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                    for (j in 0 until arr.length()) arr.optJSONObject(j)?.optString("url")?.let(existing::add)
                }
                parsed.forEach { c ->
                    val stream = c.optString("url")
                    if (stream !in existing) { dest.put(c); existing.add(stream); added++ }
                }
                if (added > 0) save()
                Toast.makeText(this, "تمت إضافة $added قناة جديدة", Toast.LENGTH_LONG).show()
                render()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun parseM3u(raw: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var name = ""
        var logo = ""
        var group = ""
        for (line0 in raw.lines()) {
            val line = line0.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(',').trim().ifBlank { "قناة مستوردة" }
                logo = Regex("tvg-logo=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1).orEmpty()
                group = Regex("group-title=\"([^\"]*)\"").find(line)?.groupValues?.getOrNull(1).orEmpty()
            } else if (!line.startsWith("#") && safe(line)) {
                val parts = if (line.contains('|')) line.split('|', limit = 2) else emptyList()
                val channelName = if (parts.isNotEmpty()) parts[0].trim() else name.ifBlank { "قناة مستوردة" }
                val stream = if (parts.isNotEmpty()) parts[1].trim() else line
                if (safe(stream)) out += JSONObject().put("id", "import-${System.currentTimeMillis()}-${out.size}")
                    .put("name", channelName).put("url", stream).put("logo", logo).put("referer", "")
                    .put("cc", "").put("priv", false).put("group", group)
                name = ""; logo = ""; group = ""
            }
        }
        return out
    }

    private fun backup() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("FIRAS TV backup", root.toString(2)))
        Toast.makeText(this, "تم نسخ النسخة الاحتياطية إلى الحافظة", Toast.LENGTH_LONG).show()
    }

    private fun restore() {
        val input = EditText(this).apply { hint = "الصق النسخة الاحتياطية JSON"; minLines = 8; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("استعادة النسخة الاحتياطية").setView(ScrollView(this).apply { addView(input) })
            .setPositiveButton("استعادة") { _, _ ->
                try {
                    val restored = JSONObject(input.text.toString())
                    if (restored.optJSONArray("sections") == null) throw IllegalArgumentException()
                    root = preserveOriginalChannels(restored)
                    save(); render()
                } catch (_: Exception) {
                    Toast.makeText(this, "النسخة الاحتياطية غير صالحة", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun preserveOriginalChannels(restored: JSONObject): JSONObject {
        val original = readAssetRoot()
        val incoming = restored.optJSONArray("sections") ?: JSONArray()
        val originals = original.optJSONArray("sections") ?: JSONArray()
        for (i in 0 until originals.length()) {
            val source = originals.optJSONObject(i) ?: continue
            val sourceItems = source.optJSONArray("items") ?: continue
            var target = (0 until incoming.length()).firstOrNull { incoming.optJSONObject(it)?.optString("title") == source.optString("title") }
            if (target == null) { incoming.put(JSONObject().put("title", source.optString("title")).put("items", JSONArray())); target = incoming.length() - 1 }
            val targetIndex = target ?: continue
            val targetSection = incoming.optJSONObject(targetIndex) ?: continue
            val dest = targetSection.optJSONArray("items") ?: JSONArray().also { targetSection.put("items", it) }
            for (j in 0 until sourceItems.length()) {
                val item = sourceItems.optJSONObject(j) ?: continue
                val exists = (0 until incoming.length()).any { k ->
                    val arr = incoming.optJSONObject(k)?.optJSONArray("items") ?: return@any false
                    (0 until arr.length()).any { n ->
                        val current = arr.optJSONObject(n) ?: return@any false
                        current.optString("name") == item.optString("name") && current.optString("url") == item.optString("url")
                    }
                }
                if (!exists) dest.put(JSONObject(item.toString()))
            }
        }
        return JSONObject().put("appName", "FIRAS TV").put("sections", incoming)
    }

    private fun readAssetRoot() = JSONObject(assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() })

    private fun checkChannels() {
        Toast.makeText(this, "جارٍ فحص روابط القنوات...", Toast.LENGTH_SHORT).show()
        Thread {
            var good = 0
            var bad = 0
            val groups = sections()
            for (i in 0 until groups.length()) {
                val arr = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                for (j in 0 until arr.length()) {
                    val item = arr.optJSONObject(j) ?: continue
                    val stream = item.optString("url")
                    if (!safe(stream)) continue
                    val ok = probe(stream)
                    item.put("ok", ok).put("chk", System.currentTimeMillis())
                    if (ok) good++ else bad++
                }
            }
            runOnUiThread { save(); Toast.makeText(this, "الفحص انتهى: تعمل $good • لا تعمل $bad", Toast.LENGTH_LONG).show(); render() }
        }.start()
    }

    private fun probe(url: String): Boolean = try {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 7000
        connection.requestMethod = "HEAD"
        val code = connection.responseCode
        connection.disconnect()
        code in 200..399 || code == 405
    } catch (_: Exception) { false }

    private fun deleteBrokenAdded() {
        AlertDialog.Builder(this).setTitle("حذف القنوات المعطلة")
            .setMessage("سيتم حذف القنوات المضافة فقط التي فشل فحصها. القنوات الأصلية محمية.")
            .setPositiveButton("حذف") { _, _ ->
                val groups = sections()
                for (i in 0 until groups.length()) {
                    val arr = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                    for (j in arr.length() - 1 downTo 0) {
                        val item = arr.optJSONObject(j) ?: continue
                        if (!isOriginal(item) && item.has("ok") && !item.optBoolean("ok")) arr.remove(j)
                    }
                }
                save(); render()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun changePassword() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val current = EditText(this).apply { hint = "كلمة المرور الحالية"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val next = EditText(this).apply { hint = "كلمة المرور الجديدة"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        val confirm = EditText(this).apply { hint = "تأكيد كلمة المرور"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        form.addView(current); form.addView(next); form.addView(confirm)
        AlertDialog.Builder(this).setTitle("تغيير كلمة المرور").setView(form)
            .setPositiveButton("حفظ") { _, _ ->
                val salt = try { Base64.decode(prefs.getString("salt", "").orEmpty(), Base64.NO_WRAP) } catch (_: Exception) { ByteArray(0) }
                if (salt.isEmpty() || !constantTimeEquals(hashPassword(current.text.toString(), salt), prefs.getString("hash", "").orEmpty())) {
                    Toast.makeText(this, "كلمة المرور الحالية خاطئة", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val value = next.text.toString()
                if (!strong(value) || value != confirm.text.toString()) {
                    Toast.makeText(this, "كلمة المرور الجديدة ضعيفة أو غير متطابقة", Toast.LENGTH_LONG).show(); return@setPositiveButton
                }
                val newSalt = ByteArray(16).also { SecureRandom().nextBytes(it) }
                prefs.edit().putString("salt", Base64.encodeToString(newSalt, Base64.NO_WRAP))
                    .putString("hash", hashPassword(value, newSalt)).apply()
                Toast.makeText(this, "تم تغيير كلمة المرور", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun safe(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)
}