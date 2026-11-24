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

private data class FilmItem(val id: String, val title: String, val stream: String = "", val poster: String = "", val country: String = "")

class MoviesActivity : AppCompatActivity() {
    private val io = Executors.newFixedThreadPool(4)
    private val films = mutableListOf<FilmItem>()
    private lateinit var adapter: FilmAdapter
    private lateinit var status: TextView
    private var page = 1
    private var busy = false
    private var reloadAfterBusy = false
    private var hasMore = true
    private var query = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF090A0F.toInt()
        window.navigationBarColor = 0xFF15161D.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 8, 14, 8)
            setBackgroundColor(0xFF090A0F.toInt())
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL
            setPadding(2, 4, 2, 10)
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 34f
            gravity = android.view.Gravity.CENTER
            setTextColor(-1)
            setPadding(8, 0, 14, 0)
            setOnClickListener { finish() }
        }
        toolbar.addView(back, LinearLayout.LayoutParams(48, 48))
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = ViewGroup.LAYOUT_DIRECTION_RTL }
        titleBlock.addView(TextView(this).apply {
            text = "مكتبة الأفلام"; textSize = 22f; setTextColor(-1)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        titleBlock.addView(TextView(this).apply {
            text = "اكتشف أفلاماً مجانية متاحة للمشاهدة"; textSize = 11f
            setTextColor(0xFF9B9EAA.toInt())
        })
        toolbar.addView(titleBlock, LinearLayout.LayoutParams(0, -2, 1f))
        toolbar.addView(TextView(this).apply {
            text = "RAHMANI"; textSize = 14f; typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(0xFFF0445E.toInt())
        })
        root.addView(toolbar)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(18, 12, 18, 12)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(0xFF512039.toInt(), 0xFF242033.toInt(), 0xFF171820.toInt())
            ).apply { cornerRadius = 22f * resources.displayMetrics.density }
        }
        hero.addView(TextView(this).apply {
            text = "ليلتك السينمائية تبدأ هنا"; textSize = 18f
            setTextColor(-1); typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        hero.addView(TextView(this).apply {
            text = "تصفّح الملصقات واختر الفيلم الذي يناسبك"; textSize = 11f
            setTextColor(0xFFD0CCD5.toInt()); setPadding(0, 4, 0, 0)
        })
        root.addView(hero, LinearLayout.LayoutParams(-1, 104).apply { bottomMargin = 10 })

        val search = EditText(this).apply {
            hint = "ابحث عن فيلم..."
            setSingleLine(true)
            setTextColor(-1)
            setHintTextColor(0xFF9295A2.toInt())
            textSize = 14f
            setPadding(16, 0, 16, 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF1A1B23.toInt())
                cornerRadius = 14f * resources.displayMetrics.density
                setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1), 0xFF343541.toInt())
            }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, 48).apply { bottomMargin = 6 })
        status = TextView(this).apply {
            text = "جارٍ تحميل الأفلام..."
            setTextColor(0xFFB8BAC4.toInt())
            textSize = 11f
            setPadding(4, 5, 4, 7)
        }
        root.addView(status)
        val list = RecyclerView(this)
        list.layoutManager = GridLayoutManager(this, 2)
        list.setHasFixedSize(true)
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
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "RAHMANI/1.0")
        return try {
            val code = c.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally { c.disconnect() }
    }

    private fun load(reset: Boolean) {
        if (busy) {
            if (reset) reloadAfterBusy = true
            return
        }
        if (reset) {
            page = 1; hasMore = true; films.clear()
            try {
                val catalog = java.io.File(filesDir, "movies.json")
                if (catalog.isFile) {
                    val rows = JSONObject(catalog.readText(Charsets.UTF_8)).optJSONArray("movies")
                    if (rows != null) for (i in 0 until rows.length()) {
                        val row = rows.optJSONObject(i) ?: continue
                        val id = row.optString("id").ifBlank { "local-" + i }
                        val title = row.optString("title").trim()
                        val stream = row.optString("url").trim()
                        if (title.isNotBlank() && (stream.startsWith("https://", true) || stream.startsWith("http://", true))) {
                            films.add(FilmItem("local:" + id, title, stream, row.optString("poster"), row.optString("country")))
                        }
                    }
                }
            } catch (_: Exception) { }
            adapter.notifyDataSetChanged()
        }
        if (!hasMore) return
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
                    if (id.isNotBlank()) found.add(FilmItem("archive:" + id, d.optString("title", "فيلم")))
                }
                runOnUiThread {
                    if (reset) films.clear()
                    val existing = films.mapTo(HashSet()) { it.id }
                    films.addAll(found.filter { existing.add(it.id) })
                    adapter.notifyDataSetChanged()
                    page++; hasMore = found.size >= 30
                    busy = false
                    if (reloadAfterBusy) {
                        reloadAfterBusy = false
                        load(true)
                        return@runOnUiThread
                    }
                    status.text = if (films.isEmpty()) "لا توجد نتائج حالياً" else "الأفلام: " + films.size + " • Internet Archive"
                }
            } catch (_: Exception) {
                runOnUiThread {
                    busy = false
                    if (reloadAfterBusy) {
                        reloadAfterBusy = false
                        load(true)
                    } else status.text = "تعذّر تحميل الأفلام. تحقق من الإنترنت."
                }
            }
        }
    }

    private fun play(film: FilmItem) {
        status.text = "جارٍ تجهيز " + film.title
        io.execute {
            try {
                val files = JSONObject(request("https://archive.org/metadata/" + URLEncoder.encode(film.id.removePrefix("archive:"), "UTF-8"))).optJSONArray("files") ?: throw IllegalStateException()
                val candidates = (0 until files.length()).mapNotNull { files.optJSONObject(it) }
                    .filter { it.optString("name").endsWith(".mp4", true) }
                    .sortedByDescending { it.optString("format").equals("h.264", true) }
                val name = candidates.firstOrNull()?.optString("name") ?: throw IllegalStateException()
                val path = name.split("/").joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
                val stream = "https://archive.org/download/" + URLEncoder.encode(film.id.removePrefix("archive:"), "UTF-8") + "/" + path
                runOnUiThread { startActivity(Intent(this, MoviePlayerActivity::class.java).putExtra("movie_title", film.title).putExtra("movie_url", stream)) }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "لا يوجد ملف MP4 متاح لهذا الفيلم", Toast.LENGTH_LONG).show() }
            }
        }
    }

    private inner class FilmAdapter : RecyclerView.Adapter<FilmHolder>() {
        // Keep poster memory bounded; the cache evicts least-recently-used images.
        private val cache = object : android.util.LruCache<String, android.graphics.Bitmap>(16 * 1024) {
            override fun sizeOf(key: String, value: android.graphics.Bitmap): Int =
                (value.byteCount / 1024).coerceAtLeast(1)
        }
        private val loading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

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
            val film = films[position]
            holder.title.text = film.title
            holder.image.tag = film.id
            val cached = cache.get(film.id)
            holder.image.setImageBitmap(cached)
            if (cached == null && loading.add(film.id)) {
                io.execute {
                    var bitmap: android.graphics.Bitmap? = null
                    try {
                        val address = if (film.poster.startsWith("http://", true) || film.poster.startsWith("https://", true))
                            film.poster else "https://archive.org/services/img/" + URLEncoder.encode(film.id.removePrefix("archive:"), "UTF-8")
                        val connection = URL(address).openConnection() as HttpURLConnection
                        connection.connectTimeout = 8000
                        connection.readTimeout = 10000
                        connection.instanceFollowRedirects = true
                        connection.setRequestProperty("User-Agent", "RAHMANI/1.0")
                        try {
                            if (connection.responseCode in 200..299) {
                                connection.inputStream.use { bitmap = BitmapFactory.decodeStream(it) }
                            }
                        } finally {
                            connection.disconnect()
                        }
                        bitmap?.let { cache.put(film.id, it) }
                    } catch (_: Exception) {
                    } finally {
                        loading.remove(film.id)
                    }
                    val result = bitmap
                    if (result != null) runOnUiThread {
                        if (!isFinishing && holder.image.tag == film.id) holder.image.setImageBitmap(result)
                    }
                }
            }
            holder.itemView.setOnClickListener { play(film) }
        }
    }
    private class FilmHolder(v: android.view.View, val image: ImageView, val title: TextView) : RecyclerView.ViewHolder(v)
    override fun onDestroy() { io.shutdownNow(); super.onDestroy() }
}
