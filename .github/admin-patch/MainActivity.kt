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
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sections = ChannelRepository.load(this)
        val appCtx = applicationContext
        Thread { StreamConfigStore.ensureLoaded(appCtx) }.start()
        adapter = ChannelAdapter { c ->
            val now = SystemClock.elapsedRealtime()
            if (now - lastLaunchAt >= 800L) {
                lastLaunchAt = now
                startActivity(Intent(this, PlayerActivity::class.java)
                    .putExtra(PlayerActivity.EXTRA_NAME, c.name)
                    .putExtra(PlayerActivity.EXTRA_URL, c.url)
                    .putExtra(PlayerActivity.EXTRA_REFERER, c.referer)
                    .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, c.id))
            }
        }
        val rv = findViewById<RecyclerView>(R.id.list)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        empty = findViewById(R.id.empty)
        chips = findViewById(R.id.chips)
        restoreButton = findViewById(R.id.restoreChannelsButton)
        rebuildChips(chips)
        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { query = s?.toString()?.trim().orEmpty(); refresh() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        findViewById<Button>(R.id.moviesButton).setOnClickListener { startActivity(Intent(this, MoviesActivity::class.java)) }
        restoreButton.setOnClickListener { restoreOriginalLists() }
        findViewById<Button>(R.id.adminButton).setOnClickListener {
            returningFromAdmin = true
            startActivity(Intent(this, AdminActivity::class.java))
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (returningFromAdmin) { returningFromAdmin = false; recreate() }
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
                    connection.setRequestProperty("User-Agent", "FIRAS-TV/4.0")
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
