package com.firas.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.ClipboardManager
import android.content.ClipData
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Native admin console. Entry is gated by a server-managed Firestore role.
 * Channel catalog edits in this build are stored locally on this device.
 */
class AdminActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var panel: LinearLayout
    private var root = JSONObject()
    private var selectedSection = -1
    private var authenticated = false
    private var pendingLogoField: EditText? = null
    private var showOnlyMissingUrls = false
    private val logoPickerRequest = 7314
    private val autoLogout = Runnable {
        if (authenticated) {
            authenticated = false
            // Keep the Firebase owner session on this device. The admin role is
            // re-checked from Firestore every time the hidden admin entry is opened.
            Toast.makeText(this, "انتهت مهلة لوحة الإدارة", Toast.LENGTH_LONG).show()
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

    override fun onResume() {
        super.onResume()
        if (authenticated) {
            handler.removeCallbacks(autoLogout)
            handler.postDelayed(autoLogout, 10 * 60 * 1000L)
        }
    }

    override fun onPause() {
        if (authenticated) {
            handler.removeCallbacks(autoLogout)
            handler.postDelayed(autoLogout, 10 * 60 * 1000L)
        }
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoLogout)
        super.onDestroy()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != logoPickerRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val targetField = pendingLogoField ?: return
        try {
            val folder = File(filesDir, "rahmani-logos").apply { mkdirs() }
            val target = File(folder, "channel-${System.currentTimeMillis()}.img")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > 5 * 1024 * 1024) throw IllegalStateException("Logo exceeds 5 MB")
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalStateException("Cannot open selected image")
            targetField.setText(target.absolutePath)
            Toast.makeText(this, "تم اختيار الشعار وحفظه داخل التطبيق", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, "تعذر حفظ الصورة. اختر صورة أصغر من 5 MB.", Toast.LENGTH_LONG).show()
        } finally {
            pendingLogoField = null
        }
    }

    /**
     * لا توجد كلمة مرور محلية أو صلاحية قابلة لإعادة الإنشاء بعد مسح البيانات.
     * يتم التحقق من وثيقة admins/{uid} في Firestore، والعميل لا يملك صلاحية
     * إنشاء هذه الوثيقة أو تعديلها؛ يضيفها مالك Firebase من لوحة التحكم فقط.
     */
    // Owner account UID configured for this app. Firebase keeps the signed-in
    // session on the owner's device, so no email/password prompt is shown again.
    private val ownerAdminUid = "fpmTwA03drg2QF04qefubOdfmjf1"

    private fun authenticate() {
        val auth = FirebaseAuth.getInstance()
        val current = auth.currentUser
        if (current != null && current.uid == ownerAdminUid) {
            enterPanel()
            return
        }
        if (current != null) auth.signOut()
        val email = EditText(this).apply {
            hint = "البريد الإلكتروني للمالك"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        }
        val password = EditText(this).apply {
            hint = "كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(email)
            addView(password)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("دخول مالك التطبيق")
            .setMessage("سجّل الدخول مرة واحدة، وسيحتفظ التطبيق بالجلسة.")
            .setView(form)
            .setPositiveButton("دخول", null)
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .setCancelable(false)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mail = email.text.toString().trim()
                val pass = password.text.toString()
                if (mail.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, "أدخل البريد وكلمة المرور", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                auth.signInWithEmailAndPassword(mail, pass).addOnCompleteListener { task ->
                    val signedIn = auth.currentUser
                    if (task.isSuccessful && signedIn != null && signedIn.uid == ownerAdminUid) {
                        dialog.dismiss()
                        enterPanel()
                    } else {
                        auth.signOut()
                        if (!isFinishing && !isDestroyed) {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            Toast.makeText(this, "تعذر الدخول أو أن الحساب ليس حساب المالك", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun verifyAdminRole() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null || user.uid != ownerAdminUid) {
            Toast.makeText(this, "غير مصرح بالدخول إلى لوحة الإدارة", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        // The Firebase UID is the owner allow-list. Avoid a Firestore role-document
        // lookup here because its read rules were blocking the owner from entering.
        enterPanel()
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

        // Pull the current public cloud catalog before any publish operation.
        // Opening the admin panel must never overwrite newer Firestore data with
        // a stale local JSON snapshot.
        CloudChannelSync.refreshPublic(applicationContext) { changed ->
            runOnUiThread {
                if (!authenticated || isFinishing || isDestroyed) return@runOnUiThread
                root = readRoot()
                render()
                Toast.makeText(
                    this,
                    if (changed > 0) "تم تحديث القنوات من السحابة" else "القنوات المحلية محدثة",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        CloudMovieSync.refreshPublic(this) {
            runOnUiThread { if (authenticated && !isFinishing && !isDestroyed) render() }
        }
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
        Toast.makeText(this, "تم الحفظ على هذا الجهاز", Toast.LENGTH_SHORT).show()
        CloudChannelSync.publishAll(this, root) { success, error ->
            if (!success && authenticated) {
                Toast.makeText(this, "حُفظت محلياً، وتعذرت المزامنة السحابية: ${error ?: "تحقق من الاتصال"}", Toast.LENGTH_LONG).show()
            } else if (success && authenticated) {
                Toast.makeText(this, "تمت مزامنة القنوات مع السحابة", Toast.LENGTH_SHORT).show()
            }
        }
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

    private fun metricCard(title: String, value: String, note: String, accent: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 12, 14, 12)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF111114.toInt())
                setCornerRadius(14f)
                setStroke(1, 0xFF29292F.toInt())
            }
            addView(TextView(this@AdminActivity).apply {
                text = title; textSize = 11f; setTextColor(0xFFA1A1AA.toInt())
            })
            addView(TextView(this@AdminActivity).apply {
                text = value; textSize = 25f; typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(accent); setPadding(0, 5, 0, 2)
            })
            addView(TextView(this@AdminActivity).apply {
                text = note; textSize = 10f; setTextColor(0xFF71717A.toInt())
            })
        }
    }

    private fun render() {
        panel.removeAllViews()
        val groups = sections()
        var total = 0
        var ready = 0
        var broken = 0
        var missing = 0
        for (i in 0 until groups.length()) {
            val list = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
            total += list.length()
            for (j in 0 until list.length()) {
                val channel = list.optJSONObject(j) ?: continue
                if (channel.optString("url").isBlank()) missing++
                else if (channel.has("ok")) {
                    if (channel.optBoolean("ok")) ready++ else broken++
                }
            }
        }
        val checked = ready + broken
        val rate = if (checked == 0) "—" else "${(ready * 100 / checked)}%"
        panel.addView(TextView(this).apply {
            text = "RAHMANI"; textSize = 25f; letterSpacing = 0.12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
            setTextColor(0xFFF4F4F5.toInt()); setPadding(2, 8, 2, 0)
        })
        panel.addView(TextView(this).apply {
            text = "CONSOLE  /  إدارة منصة البث"; textSize = 11f
            letterSpacing = 0.06f; setTextColor(0xFF8B8B95.toInt())
            setPadding(2, 3, 2, 18)
        })
        val status = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(11, 9, 11, 9)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF151518.toInt()); setCornerRadius(9f)
                setStroke(1, 0xFF303036.toInt())
            }
        }
        status.addView(TextView(this).apply {
            text = "●"; textSize = 10f; setTextColor(0xFF34D399.toInt())
        })
        status.addView(TextView(this).apply {
            text = "  حماية المدير مفعّلة  ·  القنوات والأفلام تحفظ محلياً وتزامن عند توفر Firebase"
            textSize = 10f; setTextColor(0xFFA1A1AA.toInt())
        })
        panel.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 16 })
        val metricsTop = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metricsTop.addView(metricCard("إجمالي القنوات", total.toString(), "${groups.length()} تصنيف", 0xFFF4F4F5.toInt()),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 6 })
        metricsTop.addView(metricCard("نجاح آخر فحص", rate, "$ready تعمل من $checked مفحوصة", 0xFF34D399.toInt()),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 6 })
        panel.addView(metricsTop)
        val metricsBottom = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        metricsBottom.addView(metricCard("تحتاج رابطاً", missing.toString(), "قنوات بلا رابط بث", 0xFFFBBF24.toInt()),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 6; topMargin = 10 })
        metricsBottom.addView(metricCard("فشل آخر فحص", broken.toString(), "أعد الفحص قبل الحذف", 0xFFF87171.toInt()),
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = 6; topMargin = 10 })
        panel.addView(metricsBottom)
        panel.addView(TextView(this).apply {
            text = "الإجراءات السريعة"; textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFF4F4F5.toInt()); setPadding(2, 22, 2, 7)
        })
        panel.addView(action("＋  إضافة قناة جديدة") { editChannel(null, null) })
        val movieRows = movieCatalog()
        panel.addView(TextView(this).apply {
            text = "مكتبة الأفلام  ·  " + movieRows.length() + " فيلم"
            textSize = 15f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFF4F4F5.toInt()); setPadding(2, 22, 2, 7)
        })
        panel.addView(action("＋  إضافة فيلم") { editMovie(null) })
        for (m in 0 until movieRows.length()) {
            val film = movieRows.optJSONObject(m) ?: continue
            panel.addView(action("🎬  " + film.optString("title") + "  ·  تعديل / حذف") { editMovie(m) })
        }
        panel.addView(action("＋  إضافة دولة (عربية / أجنبية)") { addCountry() })
        panel.addView(action("استيراد قائمة M3U / روابط") { importChannels() })
        panel.addView(action("استعادة قنوات من المصادر") { startCatalogRestore() })
        panel.addView(action("فحص روابط القنوات") { checkChannels() })
        panel.addView(action("نسخ نسخة احتياطية آمنة") { backup() })
        panel.addView(action("استعادة نسخة احتياطية") { restore() })
        panel.addView(action("حذف القنوات المضافة التي فشل فحصها") { deleteBrokenAdded() })
        panel.addView(action(if (showOnlyMissingUrls) "عرض جميع القنوات" else "عرض القنوات التي بلا رابط") {
            showOnlyMissingUrls = !showOnlyMissingUrls
            render()
        })
        panel.addView(TextView(this).apply {
            text = "الدول والقنوات"; textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFF4F4F5.toInt()); setPadding(2, 24, 2, 7)
        })
        for (i in 0 until groups.length()) {
            val section = groups.optJSONObject(i) ?: continue
            val list = items(i)
            val matchingItems = (0 until list.length()).filter { index ->
                val item = list.optJSONObject(index) ?: return@filter false
                !showOnlyMissingUrls || item.optString("url").isBlank()
            }
            val visibleItems = matchingItems.take(adminChannelLimit)
            if (visibleItems.isEmpty() && section.optString("region").isBlank()) continue
            val sectionBox = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(12, 9, 12, 10)
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xFF111114.toInt()); setCornerRadius(12f)
                    setStroke(1, 0xFF29292F.toInt())
                }
            }
            val heading = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            }
            heading.addView(TextView(this@AdminActivity).apply {
                text = section.optString("flag", "") + "  " + section.optString("title") + "  ·  " + if (section.optString("region") == "foreign") "أجنبية" else if (section.optString("region") == "arab") "عربية" else "غير محدد"; textSize = 14f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(0xFFE4E4E7.toInt())
            }, LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(TextView(this@AdminActivity).apply {
                text = if (showOnlyMissingUrls) "${visibleItems.size}/${list.length()}" else list.length().toString().padStart(2, '0')
                textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE
                setTextColor(0xFF8B8B95.toInt())
            })
            sectionBox.addView(heading)
            val categoryActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            categoryActions.addView(action("تعديل الاسم") { renameCategory(i) }, LinearLayout.LayoutParams(0, -2, 1f))
            val protected = (0 until list.length()).any { j -> list.optJSONObject(j)?.let(::isOriginal) == true }
            categoryActions.addView(action(if (protected) "تصنيف محمي" else "حذف التصنيف") {
                if (!protected) deleteCategory(i)
                else Toast.makeText(this@AdminActivity, "التصنيف يحتوي قنوات أصلية؛ لن نحذفه.", Toast.LENGTH_LONG).show()
            }, LinearLayout.LayoutParams(0, -2, 1f))
            categoryActions.addView(action("＋ قناة لهذه الدولة") { editChannel(i, null) }, LinearLayout.LayoutParams(0, -2, 1f))
            sectionBox.addView(categoryActions)
            for (j in visibleItems) {
                val channel = list.optJSONObject(j) ?: continue
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(9, 9, 9, 9)
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(0xFF18181B.toInt()); setCornerRadius(9f)
                        setStroke(1, 0xFF27272A.toInt())
                    }
                }
                val nameLine = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                nameLine.addView(TextView(this@AdminActivity).apply {
                    text = channel.optString("name"); textSize = 13f
                    setTextColor(0xFFF4F4F5.toInt())
                }, LinearLayout.LayoutParams(0, -2, 1f))
                val state = when {
                    channel.optString("url").isBlank() -> "بلا رابط"
                    !channel.has("ok") -> "لم يُفحص"
                    channel.optBoolean("ok") -> "يعمل"
                    else -> "فشل"
                }
                val stateColor = when (state) {
                    "يعمل" -> 0xFF34D399.toInt()
                    "فشل" -> 0xFFF87171.toInt()
                    "بلا رابط" -> 0xFFFBBF24.toInt()
                    else -> 0xFFA1A1AA.toInt()
                }
                nameLine.addView(TextView(this@AdminActivity).apply {
                    text = "● $state"; textSize = 10f; setTextColor(stateColor)
                })
                row.addView(nameLine)
                val url = channel.optString("url")
                row.addView(TextView(this).apply {
                    text = if (url.isBlank()) "لم تتم إضافة رابط بث" else url
                    textSize = 10f; typeface = android.graphics.Typeface.MONOSPACE
                    setTextColor(0xFF8B8B95.toInt()); maxLines = 2
                    setPadding(0, 5, 0, 5)
                })
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(action("تعديل") { editChannel(i, j) }, LinearLayout.LayoutParams(0, -2, 1f))
                buttons.addView(action("حذف") { deleteChannel(i, j, channel.optString("name")) }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(buttons)
                sectionBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 7 })
            }
            if (matchingItems.size > visibleItems.size) {
                sectionBox.addView(action("عرض المزيد من القنوات (" + (matchingItems.size - visibleItems.size) + " متبقية)") {
                    adminChannelLimit += 60
                    render()
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8 })
            }
            panel.addView(sectionBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
        panel.addView(action("إرسال رابط إعادة تعيين كلمة المرور") { changePassword() })
        panel.addView(action("تسجيل الخروج") { authenticated = false; handler.removeCallbacks(autoLogout); FirebaseAuth.getInstance().signOut(); finish() })
    }


    private fun addCountry() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 8, 22, 8) }
        val name = EditText(this).apply { hint = "اسم الدولة (مثال: الجزائر)" }
        val code = EditText(this).apply { hint = "رمز الدولة (DZ / SA / EG)" }
        val region = android.widget.Spinner(this)
        region.adapter = android.widget.ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("دولة عربية", "دولة أجنبية"))
        form.addView(name); form.addView(code); form.addView(region)
        AlertDialog.Builder(this).setTitle("إضافة دولة جديدة").setView(form)
            .setPositiveButton("إضافة الدولة") { _, _ ->
                val title = name.text.toString().trim()
                val cc = code.text.toString().trim().uppercase()
                if (title.isBlank() || cc.length !in 2..3) {
                    Toast.makeText(this, "أدخل اسم الدولة ورمزاً صحيحاً", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if ((0 until sections().length()).any { sections().optJSONObject(it)?.optString("title").equals(title, true) }) {
                    Toast.makeText(this, "هذه الدولة موجودة بالفعل", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                sections().put(JSONObject().put("title", title).put("countryCode", cc)
                    .put("region", if (region.selectedItemPosition == 0) "arab" else "foreign")
                    .put("items", JSONArray()))
                save(); render()
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun movieCatalog(): JSONArray {
        val file = File(filesDir, "movies.json")
        return try { JSONObject(file.readText(Charsets.UTF_8)).optJSONArray("movies") ?: JSONArray() }
        catch (_: Exception) { JSONArray() }
    }

    private fun saveMovieCatalog(rows: JSONArray) {
        val file = File(filesDir, "movies.json")
        val temp = File(filesDir, "movies.json.tmp")
        temp.writeText(JSONObject().put("movies", rows).toString(2), Charsets.UTF_8)
        if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
        CloudMovieSync.publishAll(this, rows) { success, error ->
            if (!success && authenticated) Toast.makeText(this, "حُفظت الأفلام محلياً لكن تعذرت مزامنتها: " + (error ?: "تحقق من الاتصال"), Toast.LENGTH_LONG).show()
            else if (success && authenticated) Toast.makeText(this, "تمت مزامنة مكتبة الأفلام", Toast.LENGTH_SHORT).show()
        }
    }

    private fun editMovie(index: Int?) {
        val rows = movieCatalog()
        val old = index?.let { rows.optJSONObject(it) }
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 8, 22, 8) }
        fun movieField(hint: String, value: String = "") = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine(true); form.addView(this)
        }
        val title = movieField("اسم الفيلم", old?.optString("title").orEmpty())
        val stream = movieField("رابط الفيلم MP4 أو M3U8", old?.optString("url").orEmpty())
        val poster = movieField("رابط صورة الملصق (اختياري)", old?.optString("poster").orEmpty())
        val country = movieField("الدولة (اختياري)", old?.optString("country").orEmpty())
        AlertDialog.Builder(this).setTitle(if (old == null) "إضافة فيلم" else "تعديل الفيلم")
            .setView(ScrollView(this).apply { addView(form) })
            .setPositiveButton("حفظ") { _, _ ->
                val t = title.text.toString().trim()
                val u = stream.text.toString().trim()
                val p = poster.text.toString().trim()
                if (t.isBlank() || !safe(u) || (p.isNotBlank() && !safe(p))) {
                    Toast.makeText(this, "أدخل اسم الفيلم ورابط مشاهدة HTTP/HTTPS صالحاً", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val row = old ?: JSONObject().put("id", "movie-" + System.currentTimeMillis())
                row.put("title", t).put("url", u).put("poster", p).put("country", country.text.toString().trim())
                if (old == null) rows.put(row)
                saveMovieCatalog(rows); Toast.makeText(this, "تم حفظ الفيلم محلياً", Toast.LENGTH_SHORT).show(); render()
            }.setNeutralButton(if (old == null) "إغلاق" else "حذف") { _, _ ->
                if (old != null) {
                    AlertDialog.Builder(this).setTitle("حذف الفيلم")
                        .setMessage("هل تريد حذف الفيلم: " + old.optString("title") + "؟")
                        .setPositiveButton("حذف") { _, _ ->
                            rows.remove(index!!)
                            saveMovieCatalog(rows)
                            render()
                        }
                        .setNegativeButton("إلغاء", null).show()
                }
            }.setNegativeButton("إلغاء", null).show()
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
        val qualityUrls = linkedMapOf(
            "SD" to field("رابط جودة SD", old?.optJSONObject("qualityUrls")?.optString("SD").orEmpty()),
            "HD" to field("رابط جودة HD", old?.optJSONObject("qualityUrls")?.optString("HD").orEmpty()),
            "FHD" to field("رابط جودة FHD", old?.optJSONObject("qualityUrls")?.optString("FHD").orEmpty()),
            "4K" to field("رابط جودة 4K", old?.optJSONObject("qualityUrls")?.optString("4K").orEmpty())
        )
        form.addView(TextView(this).apply { text = "روابط مستقلة لكل جودة — اترك غير المتوفر فارغاً" })
        val logo = field("رابط الشعار أو مسار صورة محفوظة", old?.optString("logo").orEmpty())
        val logoActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        logoActions.addView(action("اختيار شعار من الهاتف") {
            pendingLogoField = logo
            val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
            startActivityForResult(pick, logoPickerRequest)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        logoActions.addView(action("لصق رابط") { pasteInto(logo) }, LinearLayout.LayoutParams(0, -2, 1f))
        form.addView(logoActions)
        val referer = field("Referer", old?.optString("referer").orEmpty())
        val userAgent = field("User-Agent", old?.optString("userAgent").orEmpty())
        val cookie = field("Cookie (بيانات وصول خاصة بالقناة)", old?.let { readStreamCookie(it) }.orEmpty()).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val country = field("رمز الدولة مثل DZ أو SA", old?.optString("cc")?.takeIf { it.isNotBlank() } ?: sectionIndex?.let { sections().optJSONObject(it)?.optString("countryCode") }.orEmpty())
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
                val primaryStream = stream.ifBlank { qualityUrls.values.firstOrNull { it.text.toString().trim().isNotBlank() }?.text?.toString()?.trim().orEmpty() }
                if (channelName.isBlank() || primaryStream.isBlank() || !safe(primaryStream) || qualityUrls.values.any { it.text.toString().trim().let { value -> value.isNotBlank() && !safe(value) } } || (logoUrl.startsWith("http", true) && !safe(logoUrl))) {
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
                if (item.optString("id").isBlank()) item.put("id", "channel-${System.currentTimeMillis()}")
                val qualityObject = JSONObject()
                qualityUrls.forEach { (label, edit) ->
                    edit.text.toString().trim().takeIf { it.isNotBlank() }?.let { qualityObject.put(label, it) }
                }
                item.put("name", channelName).put("url", primaryStream).put("logo", logoUrl)
                    .put("qualityUrls", qualityObject)
                    .put("referer", referer.text.toString().trim())
                    .put("userAgent", userAgent.text.toString().trim())
                    .put("cc", country.text.toString().trim().uppercase())
                    .put("priv", privateBox.isChecked)
                    .put("alts", JSONArray(altValues))
                saveStreamConfig(item, cookie.text.toString().trim())
                if (old == null) items(destinationIndex).put(item)
                else {
                    items(sectionIndex!!).remove(itemIndex!!)
                    items(destinationIndex).put(item)
                }
                save(); render()
            }.setNegativeButton("إلغاء", null).show()
    }

    // Advanced stream settings are stored in app-private storage, not in the APK.
    private fun readStreamCookie(channel: JSONObject): String {
        val file = File(filesDir, "channels_stream_config.json")
        return try {
            val raw = if (file.isFile) JSONObject(file.readText(Charsets.UTF_8))
                else JSONObject(assets.open("channels_stream_config.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
            val arr = raw.optJSONArray("channels") ?: return ""
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                if ((channel.optString("id").isNotBlank() && item.optString("id") == channel.optString("id")) ||
                    item.optString("name").equals(channel.optString("name"), true)) {
                    val saved = item.optString("cookie")
                    if (saved.isNotBlank()) return saved
                    val headers = item.optJSONObject("headers") ?: JSONObject()
                    val cookieKey = headers.keys().asSequence().firstOrNull { it.equals("Cookie", true) }
                    return cookieKey?.let { headers.optString(it) }.orEmpty()
                }
            }
            ""
        } catch (_: Exception) { "" }
    }

    private fun saveStreamConfig(channel: JSONObject, cookieValue: String) {
        val file = File(filesDir, "channels_stream_config.json")
        val rootConfig = try {
            if (file.isFile) JSONObject(file.readText(Charsets.UTF_8))
            else JSONObject(assets.open("channels_stream_config.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { JSONObject() }
        val array = rootConfig.optJSONArray("channels") ?: JSONArray().also { rootConfig.put("channels", it) }
        val id = channel.optString("id")
        val name = channel.optString("name")
        var config: JSONObject? = null
        for (i in 0 until array.length()) {
            val candidate = array.optJSONObject(i) ?: continue
            if ((id.isNotBlank() && candidate.optString("id") == id) ||
                (name.isNotBlank() && candidate.optString("name").equals(name, true))) {
                config = candidate
                break
            }
        }
        val targetConfig = config ?: JSONObject().put("mode", "direct").also { array.put(it) }
        val headers = targetConfig.optJSONObject("headers") ?: JSONObject().also { targetConfig.put("headers", it) }
        val ua = channel.optString("userAgent").trim()
        val refererValue = channel.optString("referer").trim()
        if (ua.isNotBlank()) headers.put("User-Agent", ua) else headers.remove("User-Agent")
        if (refererValue.isNotBlank()) headers.put("Referer", refererValue) else headers.remove("Referer")
        val streamUrl = channel.optString("url").trim()
        val isPhpResolver = streamUrl.substringBefore('?').endsWith(".php", ignoreCase = true)
        targetConfig.put("id", id).put("name", name).put("url", streamUrl)
            .put("cookie", cookieValue)
            .put("mode", if (isPhpResolver) "resolver" else "direct")
            .put("resolver_url", if (isPhpResolver) streamUrl else "")
            .put("resolver_param", "id")
            .put("resolver_value", "")
        targetConfig.put("urls", channel.optJSONArray("alts") ?: JSONArray())
        val temp = File(filesDir, "channels_stream_config.json.tmp")
        temp.writeText(rootConfig.toString(2), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun removeStreamConfig(channel: JSONObject?) {
        if (channel == null) return
        val file = File(filesDir, "channels_stream_config.json")
        val configRoot = try {
            if (file.isFile) JSONObject(file.readText(Charsets.UTF_8))
            else JSONObject(assets.open("channels_stream_config.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { return }
        val array = configRoot.optJSONArray("channels") ?: return
        val id = channel.optString("id")
        val name = channel.optString("name")
        for (i in array.length() - 1 downTo 0) {
            val item = array.optJSONObject(i) ?: continue
            if ((id.isNotBlank() && item.optString("id") == id) ||
                (name.isNotBlank() && item.optString("name").equals(name, true))) array.remove(i)
        }
        file.writeText(configRoot.toString(2), Charsets.UTF_8)
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
                    removeStreamConfig(channel)
                    CloudChannelSync.delete(this, channel) { success, error ->
                        if (!success && authenticated) {
                            Toast.makeText(this, "حُذفت محلياً، لكن تعذّر حذفها من السحابة: ${error ?: "تحقق من الاتصال"}", Toast.LENGTH_LONG).show()
                        }
                    }
                    items(sectionIndex).remove(itemIndex); save(); render()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private var adminChannelLimit = 60
    private var observedRestoreTotal = -1

    private fun startCatalogRestore() {
        val started = RestoreChannelsWorker.start(applicationContext)
        if (started) {
            Toast.makeText(this, "بدأت استعادة القنوات في الخلفية؛ يمكنك استخدام التطبيق أثناء العمل", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "الاستعادة جارية بالفعل أو توجد دفعة مجدولة", Toast.LENGTH_LONG).show()
        }
    }

    private fun importChannels() {
        val input = EditText(this).apply {
            hint = "#EXTM3U أو اسم القناة | https://..."
            minLines = 8
            gravity = Gravity.TOP
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("استيراد القنوات")
            .setView(ScrollView(this).apply { addView(input) })
            .setPositiveButton("استيراد", null)
            .setNegativeButton("إلغاء", null)
            .create()

        dialog.setOnShowListener {
            val importButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            importButton.setOnClickListener {
                val raw = input.text?.toString().orEmpty()
                if (raw.isBlank()) {
                    Toast.makeText(this, "ألصق قائمة القنوات أولاً", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                // Reject oversized input before allocating parser structures.
                if (raw.length > 10 * 1024 * 1024) {
                    Toast.makeText(this, "القائمة كبيرة جداً. الحد الأقصى 10 MB.", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }

                importButton.isEnabled = false
                importButton.text = "جارٍ التحليل..."
                val progress = AlertDialog.Builder(this)
                    .setTitle("استيراد القنوات")
                    .setMessage("يتم تحليل القائمة وحفظها في الخلفية. قد يستغرق ذلك وقتاً حسب حجم الملف.")
                    .setCancelable(false)
                    .create()
                progress.show()

                val snapshot = root.toString()
                Thread {
                    var outcome: Pair<JSONObject, Int>? = null
                    try {
                        val parsed = parseM3u(raw).take(15000)
                        val workingRoot = JSONObject(snapshot)
                        val groups = workingRoot.optJSONArray("sections") ?: JSONArray().also { workingRoot.put("sections", it) }
                        val existing = HashSet<String>()
                        for (i in 0 until groups.length()) {
                            val array = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                            for (j in 0 until array.length()) {
                                val url = array.optJSONObject(j)?.optString("url").orEmpty()
                                if (url.isNotBlank()) existing.add(url)
                            }
                        }
                        val unique = parsed.filter { channel ->
                            val url = channel.optString("url")
                            url.isNotBlank() && existing.add(url)
                        }
                        // Imported channels are stored directly in their country sections.
                        // Country metadata wins; recognizable broadcaster/channel names are a fallback.
                        val grouped = unique.groupBy { channel ->
                            val code = detectImportedCountry(channel)
                            channel.put("cc", code)
                            code
                        }
                        var added = 0
                        var processed = 0
                        grouped.forEach { (code, batch) ->
                            val country = importedCountryInfo(code)
                            var destinationSection: JSONObject? = null
                            for (i in 0 until groups.length()) {
                                val candidate = groups.optJSONObject(i) ?: continue
                                if (candidate.optString("countryCode").equals(code, true) &&
                                    candidate.optString("region") == country.second) {
                                    destinationSection = candidate
                                    break
                                }
                            }
                            val targetSection = destinationSection ?: JSONObject()
                                .put("title", country.first)
                                .put("countryCode", code)
                                .put("region", country.second)
                                .put("flag", if (code.length == 2) countryFlagEmoji(code) else "")
                                .put("items", JSONArray()).also { groups.put(it) }
                            val destination = targetSection.optJSONArray("items")
                                ?: JSONArray().also { targetSection.put("items", it) }
                            batch.chunked(100).forEach { chunk ->
                                chunk.forEach { channel -> destination.put(channel); added++; processed++ }
                                val done = processed
                                val total = unique.size
                                runOnUiThread {
                                    if (!isFinishing && !isDestroyed && progress.isShowing) {
                                        progress.setMessage("جارٍ تصنيف $done من $total قناة حسب الدولة...")
                                    }
                                }
                            }
                        }
                        if (added > 0) {
                            val target = File(filesDir, "channels.json")
                            val temp = File(filesDir, "channels.json.import.tmp")
                            temp.writeText(workingRoot.toString(), Charsets.UTF_8)
                            if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
                        }
                        outcome = workingRoot to added
                    } catch (_: Exception) { }
                    val completed = outcome
                    runOnUiThread {
                        if (isFinishing || isDestroyed) {
                            progress.dismiss()
                            return@runOnUiThread
                        }
                        progress.dismiss()
                        if (completed == null) {
                            importButton.isEnabled = true
                            importButton.text = "إعادة المحاولة"
                            Toast.makeText(this, "تعذّر تحليل أو حفظ القائمة. لم يتم اعتماد الاستيراد.", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }
                        root = completed.first
                        adminChannelLimit = 60
                        CloudChannelSync.publishAll(this, root) { success, error ->
                            if (!success && authenticated) Toast.makeText(this, "حُفظت محلياً، وتعذرت المزامنة: " + (error ?: "تحقق من الاتصال"), Toast.LENGTH_LONG).show()
                            else if (success && authenticated) Toast.makeText(this, "تمت مزامنة القنوات مع السحابة", Toast.LENGTH_SHORT).show()
                        }
                        Toast.makeText(this, "تمت إضافة " + completed.second + " قناة جديدة", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                        render()
                    }
                }.start()
            }
        }
        dialog.show()
    }

    private fun detectImportedCountry(channel: JSONObject): String {
        val explicit = listOf("cc", "countryCode", "tvg-country", "country")
            .asSequence().map { channel.optString(it).trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        val explicitCode = normalizeImportedCountry(explicit)
        if (explicitCode.isNotBlank()) return explicitCode

        val text = (channel.optString("group") + " " + channel.optString("name"))
            .lowercase(Locale.ROOT)
            .replace('_', ' ')
        val aliases = listOf(
            "DZ" to listOf("الجزائر", "جزائر", "algeria", "algerian", "entv", "canal algerie", "canal algérie", "echorouk", "الشروق", "النهار الجزائرية"),
            "EG" to listOf("مصر", "مصري", "egypt", "egyptian", "mbc masr", "cbc", "dmc", "on tv", "الحياة", "النيل"),
            "MA" to listOf("المغرب", "مغربي", "morocco", "moroccan", "2m", "medi1", "الرياضية المغربية"),
            "TN" to listOf("تونس", "تونسي", "tunisia", "tunisian", "wataniya", "الحوار التونسي"),
            "LY" to listOf("ليبيا", "ليبي", "libya", "libyan", "ليبيا الأحرار"),
            "SA" to listOf("السعودية", "سعودي", "saudi", "ksa", "mbc", "روتانا", "العربية", "السعودية الأولى"),
            "QA" to listOf("قطر", "قطري", "qatar", "qatari", "al jazeera", "الجزيرة", "bein", "بي ان"),
            "AE" to listOf("الإمارات", "اماراتي", "uae", "emirati", "abu dhabi", "دبي", "الشارقة"),
            "KW" to listOf("الكويت", "كويتي", "kuwait", "kuwaiti"),
            "BH" to listOf("البحرين", "بحريني", "bahrain", "bahraini"),
            "OM" to listOf("عمان", "عُمان", "oman", "omani"),
            "IQ" to listOf("العراق", "عراقي", "iraq", "iraqi", "mbc iraq", "السومرية", "العراقية"),
            "JO" to listOf("الأردن", "اردني", "jordan", "jordanian", "رؤيا"),
            "LB" to listOf("لبنان", "لبناني", "lebanon", "lebanese", "lbc", "mtv lebanon", "الجديد"),
            "PS" to listOf("فلسطين", "فلسطيني", "palestine", "palestinian", "فلسطين اليوم"),
            "SY" to listOf("سوريا", "سوري", "syria", "syrian", "سما", "الإخبارية السورية"),
            "YE" to listOf("اليمن", "يمني", "yemen", "yemeni"),
            "SD" to listOf("السودان", "سوداني", "sudan", "sudanese"),
            "FR" to listOf("فرنسا", "فرنسي", "france", "french", "france 24", "فرانس 24", "tf1", "m6"),
            "GB" to listOf("بريطانيا", "المملكة المتحدة", "britain", "british", "united kingdom", "bbc", "itv", "sky news", "channel 4"),
            "US" to listOf("أمريكا", "الولايات المتحدة", "american", "usa", "united states", "cnn", "fox news", "abc news", "nbc", "cbs"),
            "DE" to listOf("ألمانيا", "الماني", "germany", "german", "dw ", "deutsche welle", "zdf", "ard"),
            "ES" to listOf("إسبانيا", "اسبانيا", "spain", "spanish", "rtve", "antena 3"),
            "IT" to listOf("إيطاليا", "ايطاليا", "italy", "italian", "rai "),
            "TR" to listOf("تركيا", "تركي", "turkey", "turkish", "trt", "atv", "kanal d"),
            "IN" to listOf("الهند", "هندي", "india", "indian", "star plus", "zee tv", "colors"),
            "CA" to listOf("كندا", "canada", "canadian", "cbc canada"),
            "RU" to listOf("روسيا", "روسي", "russia", "russian", "rt ", "rtr"),
            "CN" to listOf("الصين", "صيني", "china", "chinese", "cctv"),
            "NL" to listOf("هولندا", "netherlands", "dutch", "npo"),
            "BE" to listOf("بلجيكا", "belgium", "belgian", "rtbf", "vtm"),
            "CH" to listOf("سويسرا", "switzerland", "swiss", "srf"),
            "PT" to listOf("البرتغال", "portugal", "portuguese", "rtp"),
            "JP" to listOf("اليابان", "japan", "japanese", "nhk"),
            "KR" to listOf("كوريا", "korea", "korean", "kbs", "sbs korea"),
            "AU" to listOf("أستراليا", "استراليا", "australia", "australian", "abc australia")
        )
        return aliases.firstOrNull { (_, words) -> words.any { text.contains(it) } }?.first.orEmpty()
    }

    private fun normalizeImportedCountry(value: String): String {
        val v = value.trim().uppercase(Locale.ROOT)
        if (v.matches(Regex("[A-Z]{2}"))) return v
        val names = mapOf(
            "ALGERIA" to "DZ", "DZ" to "DZ", "EGYPT" to "EG", "MOROCCO" to "MA",
            "TUNISIA" to "TN", "LIBYA" to "LY", "SAUDI ARABIA" to "SA", "QATAR" to "QA",
            "UNITED ARAB EMIRATES" to "AE", "UAE" to "AE", "KUWAIT" to "KW", "BAHRAIN" to "BH",
            "OMAN" to "OM", "IRAQ" to "IQ", "JORDAN" to "JO", "LEBANON" to "LB",
            "PALESTINE" to "PS", "SYRIA" to "SY", "YEMEN" to "YE", "SUDAN" to "SD",
            "FRANCE" to "FR", "UNITED KINGDOM" to "GB", "UK" to "GB", "GREAT BRITAIN" to "GB",
            "UNITED STATES" to "US", "USA" to "US", "GERMANY" to "DE", "SPAIN" to "ES",
            "ITALY" to "IT", "TURKEY" to "TR", "INDIA" to "IN", "CANADA" to "CA",
            "RUSSIA" to "RU", "CHINA" to "CN", "NETHERLANDS" to "NL", "BELGIUM" to "BE",
            "SWITZERLAND" to "CH", "PORTUGAL" to "PT", "JAPAN" to "JP", "SOUTH KOREA" to "KR",
            "AUSTRALIA" to "AU"
        )
        return names[v].orEmpty()
    }

    private fun importedCountryInfo(code: String): Pair<String, String> {
        val countries = mapOf(
            "DZ" to "الجزائر", "EG" to "مصر", "MA" to "المغرب", "TN" to "تونس",
            "LY" to "ليبيا", "SA" to "السعودية", "QA" to "قطر", "AE" to "الإمارات",
            "KW" to "الكويت", "BH" to "البحرين", "OM" to "عُمان", "IQ" to "العراق",
            "JO" to "الأردن", "LB" to "لبنان", "PS" to "فلسطين", "SY" to "سوريا",
            "YE" to "اليمن", "SD" to "السودان", "FR" to "فرنسا", "GB" to "المملكة المتحدة",
            "US" to "الولايات المتحدة", "DE" to "ألمانيا", "ES" to "إسبانيا", "IT" to "إيطاليا",
            "TR" to "تركيا", "IN" to "الهند", "CA" to "كندا", "RU" to "روسيا", "CN" to "الصين",
            "NL" to "هولندا", "BE" to "بلجيكا", "CH" to "سويسرا", "PT" to "البرتغال",
            "JP" to "اليابان", "KR" to "كوريا الجنوبية", "AU" to "أستراليا"
        )
        if (code.isBlank()) return "غير محدد" to "unknown"
        val arab = setOf("DZ","EG","MA","TN","LY","SA","QA","AE","KW","BH","OM","IQ","JO","LB","PS","SY","YE","SD","MR","SO","DJ","KM")
        return (countries[code] ?: code) to if (code in arab) "arab" else "foreign"
    }

    private fun countryFlagEmoji(code: String): String {
        if (code.length != 2) return ""
        val upper = code.uppercase(Locale.ROOT)
        return String(Character.toChars(0x1F1E6 + upper[0] - 'A')) +
            String(Character.toChars(0x1F1E6 + upper[1] - 'A'))
    }

    private fun parseM3u(raw: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var name = ""
        var logo = ""
        var group = ""
        var country = ""
        val logoPattern = Regex("tvg-logo=\"([^\"]*)\"")
        val groupPattern = Regex("group-title=\"([^\"]*)\"")
        val countryPattern = Regex("(?:tvg-country|country|countryCode)=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        for (line0 in raw.lineSequence()) {
            if (out.size >= 15000) break
            val line = line0.trim()
            if (line.isBlank()) continue
            if (line.startsWith("#EXTINF", true)) {
                name = line.substringAfterLast(',').trim().ifBlank { "قناة مستوردة" }
                logo = logoPattern.find(line)?.groupValues?.getOrNull(1).orEmpty()
                group = groupPattern.find(line)?.groupValues?.getOrNull(1).orEmpty()
                country = countryPattern.find(line)?.groupValues?.getOrNull(1).orEmpty()
            } else if (!line.startsWith("#") && safe(line)) {
                val parts = if (line.contains('|')) line.split('|', limit = 2) else emptyList()
                val channelName = if (parts.isNotEmpty()) parts[0].trim() else name.ifBlank { "قناة مستوردة" }
                val stream = if (parts.isNotEmpty()) parts[1].trim() else line
                if (safe(stream)) out += JSONObject().put("id", "import-${System.currentTimeMillis()}-${out.size}")
                    .put("name", channelName).put("url", stream).put("logo", logo).put("referer", "")
                    .put("cc", country).put("priv", false).put("group", group)
                name = ""; logo = ""; group = ""; country = ""
            }
        }
        return out
    }

    private fun backup() {
        val streamFile = File(filesDir, "channels_stream_config.json")
        val streamConfig = try {
            if (streamFile.isFile) JSONObject(streamFile.readText(Charsets.UTF_8))
            else JSONObject(assets.open("channels_stream_config.json").bufferedReader(Charsets.UTF_8).use { it.readText() })
        } catch (_: Exception) { JSONObject().put("channels", JSONArray()) }
        val bundle = JSONObject().put("channels", root).put("streamConfig", streamConfig)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RAHMANI backup", bundle.toString(2)))
        Toast.makeText(this, "تم نسخ النسخة الاحتياطية. تحتوي بيانات Cookie سرية؛ لا تشاركها مع أحد.", Toast.LENGTH_LONG).show()
    }

    private fun restore() {
        val input = EditText(this).apply { hint = "الصق النسخة الاحتياطية JSON"; minLines = 8; gravity = Gravity.TOP }
        AlertDialog.Builder(this).setTitle("استعادة النسخة الاحتياطية").setView(ScrollView(this).apply { addView(input) })
            .setPositiveButton("استعادة") { _, _ ->
                try {
                    val restored = JSONObject(input.text.toString())
                    val channelData = restored.optJSONObject("channels") ?: restored
                    if (channelData.optJSONArray("sections") == null) throw IllegalArgumentException()
                    root = preserveOriginalChannels(channelData)
                    restored.optJSONObject("streamConfig")?.let { streamConfig ->
                        val target = File(filesDir, "channels_stream_config.json")
                        val temp = File(filesDir, "channels_stream_config.json.tmp")
                        temp.writeText(streamConfig.toString(2), Charsets.UTF_8)
                        if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
                    }
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
        return JSONObject().put("appName", "RAHMANI").put("sections", incoming)
    }

    private fun readAssetRoot() = JSONObject(assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() })

    private fun checkChannels() {
        Toast.makeText(this, "جارٍ فحص استجابة روابط القنوات...", Toast.LENGTH_SHORT).show()
        Thread {
            // Keep network work off the UI thread and cap concurrency to avoid
            // opening dozens of connections at once on a mobile device.
            val executor = Executors.newFixedThreadPool(4)
            try {
                val checks = mutableListOf<Pair<JSONObject, java.util.concurrent.Future<Boolean>>>()
                val groups = sections()
                for (i in 0 until groups.length()) {
                    val arr = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                    for (j in 0 until arr.length()) {
                        val item = arr.optJSONObject(j) ?: continue
                        val stream = item.optString("url")
                        if (!safe(stream)) continue
                        val snapshot = JSONObject(item.toString())
                        checks += item to executor.submit<Boolean> { probe(snapshot) }
                    }
                }

                var good = 0
                var bad = 0
                val results = checks.map { (original, future) ->
                    val ok = try { future.get() } catch (_: Exception) { false }
                    if (ok) good++ else bad++
                    original to ok
                }
                val completedAt = System.currentTimeMillis()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    results.forEach { (original, ok) ->
                        original.put("ok", ok).put("chk", completedAt)
                    }
                    save()
                    Toast.makeText(this, "انتهى فحص استجابة الخوادم: ناجح $good • فاشل $bad. هذا ليس اختبار تشغيل فيديو.", Toast.LENGTH_LONG).show()
                    render()
                }
            } finally {
                executor.shutdownNow()
            }
        }.start()
    }

    // Use GET rather than HEAD: many HLS/CDN endpoints reject HEAD requests.
    // Send the channel's own headers so authenticated streams are not falsely marked offline.
    private fun probe(channel: JSONObject): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(channel.optString("url")).openConnection() as HttpURLConnection
            connection.connectTimeout = 9000
            connection.readTimeout = 9000
            connection.instanceFollowRedirects = true
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent",
                channel.optString("userAgent").ifBlank { "RAHMANI/1.0" })
            connection.setRequestProperty("Accept", "*/*")
            connection.setRequestProperty("Range", "bytes=0-1023")
            channel.optString("referer").takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Referer", it)
            }
            readStreamCookie(channel).takeIf { it.isNotBlank() }?.let {
                connection.setRequestProperty("Cookie", it)
            }
            val code = connection.responseCode
            val body = if (code in 200..399) connection.inputStream else connection.errorStream
            body?.use { input ->
                val buffer = ByteArray(1024)
                input.read(buffer)
            }
            code in 200..399
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

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
        val email = FirebaseAuth.getInstance().currentUser?.email
        if (email.isNullOrBlank()) {
            Toast.makeText(this, "لا يوجد بريد إلكتروني للحساب", Toast.LENGTH_LONG).show()
            return
        }
        FirebaseAuth.getInstance().sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Toast.makeText(this, "أرسلنا رابط إعادة تعيين كلمة المرور إلى بريدك", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "تعذّر إرسال رابط إعادة التعيين", Toast.LENGTH_LONG).show()
            }
    }

    private fun safe(url: String) = url.startsWith("http://", true) || url.startsWith("https://", true)
}