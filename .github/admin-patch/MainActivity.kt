package com.firas.tv

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import android.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.firas.tv.stream.StreamConfigStore
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {
    private var sections: List<Section> = emptyList()
    private var selected = ""
    private var query = ""
    private var lastLaunchAt = 0L
    private var returningFromAdmin = false
    private lateinit var adapter: ChannelAdapter
    private lateinit var empty: TextView
    private lateinit var chips: ChipGroup
    private lateinit var restoreButton: Button
    private val homePrefs by lazy { getSharedPreferences("rahmani_home_preferences", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sections = ChannelRepository.load(this)
        val appCtx = applicationContext
        Thread { StreamConfigStore.ensureLoaded(appCtx) }.start()
        adapter = ChannelAdapter { c ->
            if (c.url.isBlank()) {
                Toast.makeText(this, "رابط البث غير مضاف لهذه القناة بعد. أضفه من لوحة الإدارة.", Toast.LENGTH_LONG).show()
                return@ChannelAdapter
            }
            val now = SystemClock.elapsedRealtime()
            if (now - lastLaunchAt >= 800L) {
                lastLaunchAt = now
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_NAME, c.name)
                    .putExtra(PlayerActivity.EXTRA_URL, c.url)
                    .putExtra(PlayerActivity.EXTRA_REFERER, c.referer)
                    .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, c.id)
                    .putExtra(PlayerActivity.EXTRA_LOGO, c.logo))
            }
        }
        val rv = findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = GridLayoutManager(this, if (resources.configuration.screenWidthDp >= 600) 4 else 2)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        empty = findViewById(R.id.empty)
        chips = findViewById(R.id.chips)
        restoreButton = findViewById(R.id.restoreChannelsButton)
        applyHomePreferences()
        findViewById<TextView>(R.id.customizeButton).setOnClickListener { showHomePreferences() }
        findViewById<TextView>(R.id.homeModeButton).setOnClickListener { showHomeModePicker() }
        rebuildChips(chips)
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<Button>(R.id.moviesButton).setOnClickListener { startActivity(Intent(this, MoviesActivity::class.java)) }
        findViewById<Button>(R.id.featuredMoviesButton).setOnClickListener { startActivity(Intent(this, MoviesActivity::class.java)) }
        findViewById<Button>(R.id.adminButton).setOnClickListener { returningFromAdmin = true; startActivity(Intent(this, AdminActivity::class.java)) }
        findViewById<TextView>(R.id.homeTab).setOnClickListener { selected = ""; rebuildChips(chips); refresh(); rv.smoothScrollToPosition(0) }
        findViewById<TextView>(R.id.channelsTab).setOnClickListener { rv.smoothScrollToPosition(0) }
        findViewById<TextView>(R.id.sportsTab).setOnClickListener { startActivity(Intent(this, SportsActivity::class.java)) }
        findViewById<Button>(R.id.sportsMatchesButton).setOnClickListener { startActivity(Intent(this, SportsActivity::class.java)) }
        findViewById<TextView>(R.id.navHome).setOnClickListener { selected = ""; rebuildChips(chips); refresh(); rv.smoothScrollToPosition(0) }
        findViewById<TextView>(R.id.navChannels).setOnClickListener { rv.smoothScrollToPosition(0) }
        findViewById<TextView>(R.id.navMovies).setOnClickListener { startActivity(Intent(this, MoviesActivity::class.java)) }
        findViewById<TextView>(R.id.navSports).setOnClickListener { startActivity(Intent(this, SportsActivity::class.java)) }
        findViewById<TextView>(R.id.navAdmin).setOnClickListener { returningFromAdmin = true; startActivity(Intent(this, AdminActivity::class.java)) }
        restoreButton.setOnClickListener { restoreOriginalLists() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (returningFromAdmin) { returningFromAdmin = false; recreate() }
    }


    private fun showHomeModePicker() {
        val modes = arrayOf("مختلط — كل الأقسام", "القنوات المباشرة", "الرياضة والمباريات", "الأفلام")
        val values = arrayOf("mixed", "live", "sports", "movies")
        val current = homePrefs.getString("home_mode", "mixed") ?: "mixed"
        val selectedIndex = values.indexOf(current).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("اختر شكل الصفحة الرئيسية")
            .setSingleChoiceItems(modes, selectedIndex) { dialog, which ->
                homePrefs.edit().putString("home_mode", values[which]).apply()
                applyHomePreferences()
                dialog.dismiss()
                Toast.makeText(this, "تم تغيير نمط الصفحة الرئيسية", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إغلاق", null)
            .show()
    }

    private fun showHomePreferences() {
        val options = arrayOf("عرض بانر الأعمال المميزة", "عرض قسم المباريات الرياضية", "عرض القنوات المباشرة", "عرض شريط البحث")
        val keys = arrayOf("hero", "sports", "channels", "search")
        val checked = BooleanArray(keys.size) { i -> homePrefs.getBoolean(keys[i], true) }
        AlertDialog.Builder(this)
            .setTitle("تخصيص واجهة RAHMANI")
            .setMessage("اختر الأقسام التي تريد ظهورها في الصفحة الرئيسية. سيتم حفظ اختياراتك على هذا الجهاز.")
            .setMultiChoiceItems(options, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("حفظ") { _, _ ->
                keys.forEachIndexed { i, key -> homePrefs.edit().putBoolean(key, checked[i]).apply() }
                applyHomePreferences()
                Toast.makeText(this, "تم حفظ تفضيلات الصفحة الرئيسية", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("إلغاء", null)
            .setNeutralButton("إظهار الكل") { _, _ ->
                keys.forEach { homePrefs.edit().putBoolean(it, true).apply() }
                applyHomePreferences()
            }
            .show()
    }

    private fun applyHomePreferences() {
        val mode = homePrefs.getString("home_mode", "mixed") ?: "mixed"
        val showHero = homePrefs.getBoolean("hero", true) && mode != "live"
        val showSports = homePrefs.getBoolean("sports", true) && mode != "live" && mode != "movies"
        val showChannels = homePrefs.getBoolean("channels", true) && mode != "sports" && mode != "movies"
        val showSearch = homePrefs.getBoolean("search", true)
        findViewById<View>(R.id.homeHeroSection)?.visibility = if (showHero) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homeSportsSection)?.visibility = if (showSports) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homeChannelsSection)?.visibility = if (showChannels) View.VISIBLE else View.GONE
        (findViewById<View>(R.id.chips)?.parent as? View)?.visibility = if (showChannels) View.VISIBLE else View.GONE
        findViewById<View>(R.id.list)?.visibility = if (showChannels) View.VISIBLE else View.GONE
        findViewById<View>(R.id.restoreChannelsButton)?.visibility = if (showChannels) View.VISIBLE else View.GONE
        findViewById<View>(R.id.search)?.visibility = if (showSearch) View.VISIBLE else View.GONE
        findViewById<View>(R.id.empty)?.visibility =
            if (showChannels && adapter.currentList.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun selectCategory(keyword: String) {
        val section = sections.firstOrNull { it.title.contains(keyword, ignoreCase = true) || (keyword == "رياضة" && it.title.contains("sport", ignoreCase = true)) }
        if (section == null) { Toast.makeText(this, "لا يوجد تصنيف رياضي حالياً. أضف قنوات رياضية من لوحة الإدارة.", Toast.LENGTH_LONG).show(); return }
        selected = section.title
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i) as? Chip ?: continue
            chip.isChecked = (chip.tag as? String) == selected
        }
        refresh()
    }

    private fun rebuildChips(group: ChipGroup) {
        group.removeAllViews()
        fun addChip(label: String, tag: String, checked: Boolean) {
            val chip = layoutInflater.inflate(R.layout.item_chip, group, false) as Chip
            chip.id = View.generateViewId(); chip.text = label; chip.tag = tag; chip.isChecked = checked
            group.addView(chip)
        }
        addChip("الكل", "", true)
        sections.forEach { addChip(it.title, it.title, false) }
        group.setOnCheckedStateChangeListener { g, ids ->
            selected = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? String } ?: ""
            refresh()
        }
    }

    private fun refresh() {
        val list = sections.filter { selected.isEmpty() || it.title == selected }.flatMap { it.items }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
        adapter.submitList(list)
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun restoreOriginalLists() {
        restoreButton.isEnabled = false
        restoreButton.text = "جارٍ استعادة القنوات..."
        Thread {
            var added = 0
            val sources = listOf(
                Pair("قنوات عربية", "https://iptv-org.github.io/iptv/languages/ara.m3u"),
                Pair("رياضة", "https://iptv-org.github.io/iptv/categories/sports.m3u"),
                Pair("أخبار", "https://iptv-org.github.io/iptv/categories/news.m3u"),
                Pair("أطفال", "https://iptv-org.github.io/iptv/categories/kids.m3u")
            )
            try {
                val target = File(filesDir, "channels.json")
                val raw = if (target.isFile) target.readText(Charsets.UTF_8)
                    else assets.open("channels.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
                val root = JSONObject(raw)
                val groups = root.optJSONArray("sections") ?: JSONArray().also { root.put("sections", it) }
                for ((title, endpoint) in sources) {
                    val connection = URL(endpoint).openConnection() as HttpURLConnection
                    connection.connectTimeout = 15000; connection.readTimeout = 25000
                    connection.setRequestProperty("User-Agent", "RAHMANI/1.0")
                    val body = try { connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
                        finally { connection.disconnect() }
                    var section: JSONObject? = null
                    for (i in 0 until groups.length()) {
                        val item = groups.optJSONObject(i)
                        if (item?.optString("title") == title) { section = item; break }
                    }
                    if (section == null) {
                        val created = JSONObject().put("title", title).put("items", JSONArray())
                        groups.put(created)
                        section = created
                    }
                    val actualSection = section ?: continue
                    val items = actualSection.optJSONArray("items") ?: JSONArray().also { actualSection.put("items", it) }
                    val known = HashSet<String>()
                    for (i in 0 until items.length()) known.add(items.optJSONObject(i)?.optString("url").orEmpty())
                    var name = ""; var logo = ""
                    for (line in body.lineSequence()) {
                        val value = line.trim()
                        if (value.startsWith("#EXTINF", true)) {
                            name = value.substringAfterLast(',').trim()
                            logo = Regex("""tvg-logo="([^"]*)"""").find(value)?.groupValues?.getOrNull(1).orEmpty()
                        } else if (value.startsWith("http", true) && name.isNotBlank() && value !in known) {
                            items.put(JSONObject().put("id", "iptv-" + value.hashCode()).put("name", name)
                                .put("logo", logo).put("url", value).put("referer", ""))
                            known.add(value); added++; name = ""; logo = ""
                        }
                    }
                }
                val temp = File(filesDir, "channels.json.tmp")
                temp.writeText(root.toString(2), Charsets.UTF_8)
                if (!temp.renameTo(target)) { temp.copyTo(target, overwrite = true); temp.delete() }
                runOnUiThread {
                    sections = ChannelRepository.load(this); selected = ""
                    rebuildChips(chips); refresh()
                    restoreButton.isEnabled = true; restoreButton.text = "استعادة القنوات القديمة"
                    Toast.makeText(this, "أضيفت " + added + " قناة. افحص الروابط من لوحة الإدارة لأن توفر البث يتغير.", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                runOnUiThread {
                    restoreButton.isEnabled = true; restoreButton.text = "إعادة محاولة استعادة القنوات"
                    Toast.makeText(this, "تعذّر تحميل القوائم؛ تحقق من اتصال الإنترنت", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }
}
