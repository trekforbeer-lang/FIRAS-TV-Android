package com.firas.tv

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

private data class FilmItem(val id: String, val title: String)

class MoviesActivity : AppCompatActivity() {
    private val io = Executors.newFixedThreadPool(4)
    private val films = mutableListOf<FilmItem>()
    private lateinit var adapter: FilmAdapter
    private lateinit var status: TextView
    private var page = 1
    private var busy = false
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(12, 12, 12, 8); setBackgroundColor(0xFF08090D.toInt()) }
        root.addView(TextView(this).apply { text = "الأفلام"; textSize = 27f; setTextColor(-1); typeface = android.graphics.Typeface.DEFAULT_BOLD; setPadding(8, 8, 8, 2) })
        root.addView(TextView(this).apply { text = "أفلام مختارة • اكتشف وشاهد مباشرة"; textSize = 13f; setTextColor(0xFFB8BAC4.toInt()); setPadding(8, 0, 8, 12) })
        val search = EditText(this).apply { hint = "ابحث عن فيلم..."; setSingleLine(true); setTextColor(-1); setHintTextColor(0xFFAAAAAA.toInt()) }
        root.addView(search, LinearLayout.LayoutParams(-1, 52))
        status = TextView(this).apply { text = "جارٍ تحميل الأفلام..."; setTextColor(0xFFCCCCCC.toInt()); setPadding(4, 8, 4, 8) }
        root.addView(status)
        val list = RecyclerView(this)
        list.layoutManager = GridLayoutManager(this, 2)
        adapter = FilmAdapter()
        list.adapter = adapter
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        search.addTextChangedListener(object : TextWatcher {
            private var pending: Runnable? = null
            override fun afterTextChanged(s: Editable?) {
                pending?.let { list.removeCallbacks(it) }
                val task = Runnable { query = s?.toString()?.trim().orEmpty(); load(true) }
                pending = task; list.postDelayed(task, 500)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as GridLayoutManager
                if (dy > 0 && !busy && lm.findLastVisibleItemPosition() >= films.size - 6) load(false)
            }
        })
        load(true)
    }

    private fun request(address: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "FIRAS-TV/4.0")
        return try { c.inputStream.bufferedReader().use { it.readText() } } finally { c.disconnect() }
    }

    private fun load(reset: Boolean) {
        if (busy) return
        if (reset) { page = 1; films.clear(); adapter.notifyDataSetChanged() }
        busy = true; status.text = "جارٍ تحميل الأفلام..."
        io.execute {
            try {
                val base = "collection:feature_films AND mediatype:movies"
                val clean = query.replace(Regex("[^A-Za-z0-9 ]"), " ").take(70)
                val term = if (clean.isBlank()) base else base + " AND title:(" + clean + ")"
                val address = "https://archive.org/advancedsearch.php?q=" + URLEncoder.encode(term, "UTF-8") + "&fl[]=identifier&fl[]=title&sort[]=downloads+desc&rows=30&page=" + page + "&output=json"
                val docs = JSONObject(request(address)).optJSONObject("response")?.optJSONArray("docs")
                val found = mutableListOf<FilmItem>()
                if (docs != null) for (i in 0 until docs.length()) {
                    val d = docs.optJSONObject(i) ?: continue
                    val id = d.optString("identifier")
                    if (id.isNotBlank()) found.add(FilmItem(id, d.optString("title", "فيلم")))
                }
                runOnUiThread {
                    if (reset) films.clear()
                    films.addAll(found); adapter.notifyDataSetChanged()
                    page++; busy = false
                    status.text = if (films.isEmpty()) "لا توجد نتائج حالياً" else "الأفلام: " + films.size + " • Internet Archive"
                }
            } catch (_: Exception) {
                runOnUiThread { busy = false; status.text = "تعذّر تحميل الأفلام. تحقق من الإنترنت." }
            }
        }
    }

    private fun play(film: FilmItem) {
        status.text = "جارٍ تجهيز " + film.title
        io.execute {
            try {
                val files = JSONObject(request("https://archive.org/metadata/" + URLEncoder.encode(film.id, "UTF-8"))).optJSONArray("files") ?: throw IllegalStateException()
                val candidates = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                    .filter { it.optString("name").endsWith(".mp4", true) }
                    .sortedByDescending { it.optString("format").equals("h.264", true) }
                val name = candidates.firstOrNull()?.optString("name") ?: throw IllegalStateException()
                val path = name.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                val stream = "https://archive.org/download/" + URLEncoder.encode(film.id, "UTF-8") + "/" + path
                runOnUiThread { startActivity(Intent(this, MoviePlayerActivity::class.java).putExtra("movie_title", film.title).putExtra("movie_url", stream)) }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "لا يوجد ملف MP4 متاح لهذا الفيلم", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private inner class FilmAdapter : RecyclerView.Adapter<FilmHolder>() {
        private val cache = mutableMapOf<String, android.graphics.Bitmap>()
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): FilmHolder {
            val box = LinearLayout(parent.context).apply { orientation = LinearLayout.VERTICAL; setPadding(5, 5, 5, 10) }
            val image = ImageView(parent.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF242630.toInt()) }
            box.addView(image, LinearLayout.LayoutParams(-1, (resources.displayMetrics.widthPixels / 2.15f * 1.42f).toInt()))
            val title = TextView(parent.context).apply { setTextColor(-1); textSize = 13f; maxLines = 2; setPadding(3, 5, 3, 0) }
            box.addView(title)
            return FilmHolder(box, image, title)
        }
        override fun getItemCount() = films.size
        override fun onBindViewHolder(holder: FilmHolder, position: Int) {
            val f = films[position]
            holder.title.text = f.title; holder.image.tag = f.id
            holder.image.setImageBitmap(cache[f.id])
            if (cache[f.id] == null) io.execute {
                try {
                    val bmp = URL("https://archive.org/services/img/" + URLEncoder.encode(f.id, "UTF-8")).openStream().use { BitmapFactory.decodeStream(it) }
                    if (bmp != null) {
                        cache[f.id] = bmp
                        runOnUiThread { if (holder.image.tag == f.id) holder.image.setImageBitmap(bmp) }
                    }
                } catch (_: Exception) {}
            }
            holder.itemView.setOnClickListener { play(f) }
        }
    }
    private class FilmHolder(v: android.view.View, val image: ImageView, val title: TextView) : RecyclerView.ViewHolder(v)
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
