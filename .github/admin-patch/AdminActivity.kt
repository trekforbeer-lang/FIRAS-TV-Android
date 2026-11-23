package com.firas.tv

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class AdminActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("firas_admin", MODE_PRIVATE) }
    private lateinit var content: LinearLayout
    private var rootJson = JSONObject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authenticate()
    }

    private fun authenticate() {
        val saved = prefs.getString("pin_hash", null)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 24, 40, 8)
        }
        val pin = EditText(this).apply {
            hint = if (saved == null) "رمز جديد (4 أرقام على الأقل)" else "رمز الأدمن"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        box.addView(pin)
        val confirm = if (saved == null) EditText(this).apply {
            hint = "أعد كتابة الرمز"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        } else null
        confirm?.let(box::addView)
        AlertDialog.Builder(this)
            .setTitle(if (saved == null) "إنشاء رمز الأدمن" else "دخول لوحة الإدارة")
            .setMessage(if (saved == null) "أنشئ رمزاً خاصاً لحماية إدارة القنوات." else "أدخل الرمز السري للمتابعة.")
            .setView(box)
            .setCancelable(false)
            .setPositiveButton(if (saved == null) "حفظ الرمز" else "دخول") { _, _ ->
                val value = pin.text.toString()
                if (saved == null) {
                    if (value.length < 4 || value != confirm?.text?.toString()) {
                        Toast.makeText(this, "الرمز غير صحيح أو غير متطابق", Toast.LENGTH_LONG).show()
                        finish()
                    } else {
                        prefs.edit().putString("pin_hash", hash(value)).apply()
                        openPanel()
                    }
                } else if (hash(value) == saved) openPanel()
                else {
                    Toast.makeText(this, "رمز خاطئ", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
            .setNegativeButton("إلغاء") { _, _ -> finish() }
            .show()
    }

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun openPanel() {
        try {
            rootJson = ChannelStore.readRoot(this)
        } catch (e: Exception) {
            rootJson = JSONObject().put("appName", "FIRAS TV").put("sections", JSONArray())
            Toast.makeText(this, "تم إنشاء قائمة قنوات جديدة", Toast.LENGTH_SHORT).show()
        }
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 32)
            setBackgroundColor(0xFF101010.toInt())
        }
        scroll.addView(content)
        setContentView(scroll)
        render()
    }

    private fun render() {
        content.removeAllViews()
        content.addView(TextView(this).apply {
            text = "إدارة قنوات FIRAS TV"
            textSize = 24f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        })
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(Button(this).apply {
            text = "＋ إضافة قناة"
            setOnClickListener { editChannel(null, null) }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(Button(this).apply {
            text = "＋ قسم جديد"
            setOnClickListener { addSection() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(actions)
        val sections = rootJson.optJSONArray("sections") ?: JSONArray()
        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val category = section.optString("title")
            content.addView(TextView(this).apply {
                text = category
                textSize = 18f
                setTextColor(0xFFFFC107.toInt())
                setPadding(4, 24, 4, 8)
            })
            val items = section.optJSONArray("items") ?: JSONArray()
            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(12, 10, 12, 10)
                    setBackgroundColor(0xFF202020.toInt())
                }
                row.addView(TextView(this).apply {
                    text = item.optString("name")
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                })
                row.addView(TextView(this).apply {
                    text = item.optString("url")
                    textSize = 11f
                    setTextColor(0xFFBBBBBB.toInt())
                    maxLines = 2
                })
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(Button(this@AdminActivity).apply {
                    text = "تعديل"
                    setOnClickListener { editChannel(i, j) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                buttons.addView(Button(this@AdminActivity).apply {
                    text = "حذف"
                    setOnClickListener { deleteChannel(i, j, item.optString("name")) }
                }, LinearLayout.LayoutParams(0, -2, 1f))
                row.addView(buttons)
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply {
                    bottomMargin = 8
                })
            }
        }
        content.addView(Button(this).apply {
            text = "حفظ التغييرات"
            setOnClickListener {
                try {
                    ChannelStore.saveRoot(this@AdminActivity, rootJson)
                    Toast.makeText(this@AdminActivity, "تم حفظ القنوات على الهاتف", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    Toast.makeText(this@AdminActivity, "فشل الحفظ: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
                }
            }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 20 })
    }

    private fun addSection() {
        val input = EditText(this).apply { hint = "اسم القسم" }
        AlertDialog.Builder(this).setTitle("إضافة قسم")
            .setView(input)
            .setPositiveButton("إضافة") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    val sections = rootJson.optJSONArray("sections") ?: JSONArray().also { rootJson.put("sections", it) }
                    if ((0 until sections.length()).none { sections.optJSONObject(it)?.optString("title") == name }) {
                        sections.put(JSONObject().put("title", name).put("items", JSONArray()))
                        render()
                    } else Toast.makeText(this, "القسم موجود مسبقاً", Toast.LENGTH_SHORT).show()
                }
            }.setNegativeButton("إلغاء", null).show()
    }

    private fun editChannel(sectionIndex: Int?, itemIndex: Int?) {
        val sections = rootJson.optJSONArray("sections") ?: JSONArray().also { rootJson.put("sections", it) }
        val old = if (sectionIndex != null && itemIndex != null)
            sections.optJSONObject(sectionIndex)?.optJSONArray("items")?.optJSONObject(itemIndex) else null
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 8, 28, 8)
        }
        fun field(label: String, key: String, uri: Boolean = false): EditText {
            val edit = EditText(this).apply {
                hint = label
                setText(old?.optString(key).orEmpty())
                if (uri) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            }
            form.addView(edit)
            return edit
        }
        val name = field("اسم القناة", "name")
        val category = field("القسم", "category")
        val url = field("رابط البث M3U8 / MP4", "url", true)
        val logo = field("رابط الشعار (اختياري)", "logo", true)
        val userAgent = field("User-Agent (اختياري)", "userAgent")
        val referer = field("Referer (اختياري)", "referer", true)
        val cookie = field("Cookies (اختياري)", "cookie")
        val wrapper = ScrollView(this).apply { addView(form) }
        AlertDialog.Builder(this)
            .setTitle(if (old == null) "إضافة قناة" else "تعديل القناة")
            .setView(wrapper)
            .setPositiveButton("حفظ") { _, _ ->
                val channelName = name.text.toString().trim()
                val stream = url.text.toString().trim()
                val group = category.text.toString().trim().ifBlank { "قنوات عامة" }
                if (channelName.isBlank() || !(stream.startsWith("http://") || stream.startsWith("https://"))) {
                    Toast.makeText(this, "أدخل اسم القناة ورابطاً صحيحاً يبدأ بـ http أو https", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val section = (0 until sections.length()).mapNotNull { sections.optJSONObject(it) }
                    .firstOrNull { it.optString("title").equals(group, true) }
                    ?: JSONObject().put("title", group).put("items", JSONArray()).also { sections.put(it) }
                val items = section.optJSONArray("items") ?: JSONArray().also { section.put("items", it) }
                val item = old ?: JSONObject().put("id", "admin-${System.currentTimeMillis()}")
                item.put("name", channelName).put("url", stream).put("logo", logo.text.toString().trim())
                    .put("userAgent", userAgent.text.toString().trim())
                    .put("referer", referer.text.toString().trim())
                    .put("cookie", cookie.text.toString().trim())
                if (old == null) items.put(item)
                else if (sectionIndex != null && itemIndex != null) {
                    val oldItems = sections.optJSONObject(sectionIndex)?.optJSONArray("items")
                    if (oldItems != null && group.equals(sections.optJSONObject(sectionIndex)?.optString("title"), true)) {
                        oldItems.put(itemIndex, item)
                    } else {
                        oldItems?.remove(itemIndex)
                        items.put(item)
                    }
                }
                render()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun deleteChannel(sectionIndex: Int, itemIndex: Int, name: String) {
        AlertDialog.Builder(this).setTitle("حذف القناة")
            .setMessage("هل تريد حذف $name؟")
            .setPositiveButton("حذف") { _, _ ->
                rootJson.optJSONArray("sections")?.optJSONObject(sectionIndex)?.optJSONArray("items")?.remove(itemIndex)
                render()
            }.setNegativeButton("إلغاء", null).show()
    }
}