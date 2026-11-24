package com.firas.tv

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
            FirebaseAuth.getInstance().signOut()
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
    private fun authenticate() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            verifyAdminRole()
            return
        }

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 12, 28, 8)
        }
        val email = EditText(this).apply {
            hint = "البريد الإلكتروني"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            setSingleLine(true)
        }
        val password = EditText(this).apply {
            hint = "كلمة المرور"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        box.addView(email)
        box.addView(password)
        val dialog = AlertDialog.Builder(this)
            .setTitle("دخول الإدارة الآمن")
            .setMessage("أنشئ حساب Firebase أولاً، ثم يضيف مالك المشروع UID الخاص بك إلى قائمة المديرين.")
            .setView(box)
            .setPositiveButton("دخول", null)
            .setNeutralButton("إنشاء حساب", null)
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val mail = email.text.toString().trim()
                val pass = password.text.toString()
                if (mail.isBlank() || pass.isBlank()) {
                    Toast.makeText(this, "أدخل البريد الإلكتروني وكلمة المرور", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                auth.signInWithEmailAndPassword(mail, pass)
                    .addOnSuccessListener { dialog.dismiss(); verifyAdminRole() }
                    .addOnFailureListener {
                        Toast.makeText(this, "تعذّر تسجيل الدخول. تحقق من الحساب أو إعداد Firebase.", Toast.LENGTH_LONG).show()
                    }
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val mail = email.text.toString().trim()
                val pass = password.text.toString()
                if (mail.isBlank() || pass.length < 10) {
                    Toast.makeText(this, "أدخل بريداً صالحاً وكلمة مرور لا تقل عن 10 أحرف", Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                auth.createUserWithEmailAndPassword(mail, pass)
                    .addOnSuccessListener { result ->
                        val uid = result.user?.uid
                        auth.signOut()
                        dialog.dismiss()
                        if (uid.isNullOrBlank()) {
                            Toast.makeText(this, "أُنشئ الحساب لكن تعذّر قراءة UID", Toast.LENGTH_LONG).show()
                        } else {
                            showAdminUid(uid)
                        }
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "تعذّر إنشاء الحساب. قد يكون البريد مستخدماً بالفعل.", Toast.LENGTH_LONG).show()
                    }
            }
        }
        dialog.show()
    }

    private fun verifyAdminRole() {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            authenticate()
            return
        }
        FirebaseFirestore.getInstance()
            .collection("admins")
            .document(user.uid)
            .get()
            .addOnSuccessListener { document ->
                if (document.getString("role") == "admin" && document.getBoolean("active") == true) {
                    enterPanel()
                } else {
                    val uid = user.uid
                    FirebaseAuth.getInstance().signOut()
                    showAdminUid(uid)
                }
            }
            .addOnFailureListener {
                FirebaseAuth.getInstance().signOut()
                Toast.makeText(this, "تعذّر التحقق من صلاحيات الإدارة. تحقق من اتصال Firebase وقواعد Firestore.", Toast.LENGTH_LONG).show()
                finish()
            }
    }

    private fun showAdminUid(uid: String) {
        val value = TextView(this).apply {
            text = uid
            setTextIsSelectable(true)
            textSize = 13f
            setTextColor(0xFF111827.toInt())
            setPadding(12, 12, 12, 12)
        }
        AlertDialog.Builder(this)
            .setTitle("الحساب لا يملك صلاحية الإدارة بعد")
            .setMessage("انسخ UID وأضف في Firebase Console وثيقة داخل collection باسم admins، واجعل معرّف الوثيقة هو UID، ثم أضف الحقول role = admin و active = true. لا يمكن للتطبيق إنشاء هذه الصلاحية.")
            .setView(value)
            .setPositiveButton("نسخ UID") { _, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Admin UID", uid))
                Toast.makeText(this, "تم نسخ UID", Toast.LENGTH_SHORT).show()
                finish()
            }
            .setNegativeButton("إغلاق") { _, _ -> finish() }
            .show()
    }

    private fun enterPanel() {
        authenticated = true
        handler.postDelayed(autoLogout, 10 * 60 * 1000L)
        root = readRoot()
        CloudChannelSync.publishAll(this, root) { success, error ->
            if (!success && authenticated) {
                Toast.makeText(this, "تعذرت مزامنة النسخة المحلية عند فتح لوحة الإدارة: ${error ?: "تحقق من الاتصال"}", Toast.LENGTH_LONG).show()
            }
        }
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
            text = "  يعمل محلياً على هذا الجهاز  ·  البيانات محفوظة محلياً"
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
        panel.addView(action("＋  إضافة تصنيف") { addCategory() })
        panel.addView(action("استيراد قائمة M3U / روابط") { importChannels() })
        panel.addView(action("فحص روابط القنوات") { checkChannels() })
        panel.addView(action("نسخ نسخة احتياطية آمنة") { backup() })
        panel.addView(action("استعادة نسخة احتياطية") { restore() })
        panel.addView(action("حذف القنوات المضافة التي فشل فحصها") { deleteBrokenAdded() })
        panel.addView(action(if (showOnlyMissingUrls) "عرض جميع القنوات" else "عرض القنوات التي بلا رابط") {
            showOnlyMissingUrls = !showOnlyMissingUrls
            render()
        })
        panel.addView(TextView(this).apply {
            text = "القنوات والتصنيفات"; textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFF4F4F5.toInt()); setPadding(2, 24, 2, 7)
        })
        for (i in 0 until groups.length()) {
            val section = groups.optJSONObject(i) ?: continue
            val list = items(i)
            val visibleItems = (0 until list.length()).filter { index ->
                val item = list.optJSONObject(index) ?: return@filter false
                !showOnlyMissingUrls || item.optString("url").isBlank()
            }
            if (visibleItems.isEmpty()) continue
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
                text = section.optString("title"); textSize = 14f
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
            panel.addView(sectionBox, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        }
        panel.addView(action("إرسال رابط إعادة تعيين كلمة المرور") { changePassword() })
        panel.addView(action("تسجيل الخروج") { authenticated = false; handler.removeCallbacks(autoLogout); FirebaseAuth.getInstance().signOut(); finish() })
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
                if (channelName.isBlank() || (stream.isNotBlank() && !safe(stream)) || (logoUrl.startsWith("http", true) && !safe(logoUrl))) {
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
                item.put("name", channelName).put("url", stream).put("logo", logoUrl)
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
                Toast.makeText(this, "جارٍ تحليل القائمة في الخلفية، يمكنك الانتظار دون تجمّد الواجهة", Toast.LENGTH_LONG).show()

                val snapshot = root.toString()
                Thread {
                    val result = try {
                        val parsed = parseM3u(raw).take(15000)
                        val existingRoot = JSONObject(snapshot)
                        val groups = existingRoot.optJSONArray("sections") ?: JSONArray()
                        val existing = HashSet<String>()
                        for (i in 0 until groups.length()) {
                            val array = groups.optJSONObject(i)?.optJSONArray("items") ?: continue
                            for (j in 0 until array.length()) {
                                val url = array.optJSONObject(j)?.optString("url").orEmpty()
                                if (url.isNotBlank()) existing.add(url)
                            }
                        }
                        parsed.filter { channel ->
                            val url = channel.optString("url")
                            url.isNotBlank() && existing.add(url)
                        }
                    } catch (_: Exception) {
                        null
                    }

                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (result == null) {
                            importButton.isEnabled = true
                            importButton.text = "إعادة المحاولة"
                            Toast.makeText(this, "تعذّر تحليل القائمة. تحقق من تنسيق M3U.", Toast.LENGTH_LONG).show()
                            return@runOnUiThread
                        }

                        val groups = sections()
                        val sectionName = "قنوات مستوردة"
                        var idx = (0 until groups.length()).firstOrNull {
                            groups.optJSONObject(it)?.optString("title") == sectionName
                        }
                        if (idx == null) {
                            groups.put(JSONObject().put("title", sectionName).put("items", JSONArray()))
                            idx = groups.length() - 1
                        }
                        val dest = items(idx)
                        result.forEach { dest.put(it) }
                        if (result.isNotEmpty()) save()
                        Toast.makeText(
                            this,
                            "تمت إضافة ${result.size} قناة جديدة" +
                                if (result.size == 15000) " (تم إيقاف الاستيراد عند الحد الآمن 15000)" else "",
                            Toast.LENGTH_LONG
                        ).show()
                        dialog.dismiss()
                        render()
                    }
                }.start()
            }
        }
        dialog.show()
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