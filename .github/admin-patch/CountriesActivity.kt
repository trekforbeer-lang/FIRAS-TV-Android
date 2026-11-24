package com.firas.tv

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

private data class TvCountry(val name: String, val code: String, val flag: String, val arab: Boolean)

class CountriesActivity : AppCompatActivity() {
    private lateinit var body: FrameLayout
    private lateinit var heading: TextView
    private var channelAdapter: ChannelAdapter? = null
    private var rows: List<TvCountry> = emptyList()
    private var tab = "arab"
    private var channelsOpen = false
    @Volatile private var generation = 0
    private val countryExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var countryTask: Future<*>? = null

    private val arabic = mapOf(
        "DZ" to "الجزائر", "BH" to "البحرين", "KM" to "جزر القمر",
        "DJ" to "جيبوتي", "EG" to "مصر", "IQ" to "العراق",
        "JO" to "الأردن", "KW" to "الكويت", "LB" to "لبنان",
        "LY" to "ليبيا", "MR" to "موريتانيا", "MA" to "المغرب",
        "OM" to "عُمان", "PS" to "فلسطين", "QA" to "قطر",
        "SA" to "السعودية", "SO" to "الصومال", "SD" to "السودان",
        "SY" to "سوريا", "TN" to "تونس", "AE" to "الإمارات",
        "YE" to "اليمن"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 13, 22)
        window.navigationBarColor = Color.rgb(9, 13, 22)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 13, 22))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        bar.addView(Button(this).apply {
            text = "‹"
            textSize = 25f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = "رجوع"
            setOnClickListener {
                if (channelsOpen) showCountries() else finish()
            }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        heading = TextView(this).apply {
            text = "قنوات حسب الدولة"
            textSize = 19f
            setTextColor(Color.WHITE)
        }
        bar.addView(heading)
        root.addView(bar)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        rows = assets.open("countries.csv").bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.drop(1).mapNotNull { line ->
                val parts = line.split(",", limit = 4)
                if (parts.size < 4) null else {
                    val countryCode = parts[1].uppercase(Locale.ROOT)
                    TvCountry(
                        arabic[countryCode] ?: parts[0],
                        countryCode.lowercase(Locale.ROOT),
                        parts[3],
                        arabic.containsKey(countryCode)
                    )
                }
            }.toList().sortedBy { it.name }
        }
        showCountries()
    }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun stopCountryWork() {
        generation++
        countryTask?.cancel(true)
        countryTask = null
        channelAdapter?.shutdown()
        channelAdapter = null
    }

    private fun showCountries() {
        stopCountryWork()
        channelsOpen = false
        heading.text = "قنوات حسب الدولة"
        body.removeAllViews()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val tabs = LinearLayout(this).apply { gravity = Gravity.CENTER }

        fun tabButton(label: String, key: String) = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(if (tab == key) Color.rgb(177, 45, 81) else Color.rgb(37, 45, 61))
            }
            setOnClickListener {
                tab = key
                showCountries()
            }
        }

        tabs.addView(tabButton("الدول العربية", "arab"),
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginEnd = dp(5) })
        tabs.addView(tabButton("الدول الأجنبية", "foreign"),
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { marginStart = dp(5) })
        root.addView(tabs)

        val scroll = ScrollView(this)
        val grid = GridLayout(this).apply { columnCount = 2 }
        rows.filter { it.arab == (tab == "arab") }.forEach { country ->
            val button = Button(this).apply {
                text = country.flag + "  " + country.name
                isAllCaps = false
                textSize = 13f
                setTextColor(Color.WHITE)
                setOnClickListener { loadCountry(country) }
            }
            grid.addView(button, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(58)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        scroll.addView(grid)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(root)
    }

    private fun loadCountry(country: TvCountry) {
        stopCountryWork()
        channelsOpen = true
        val requestGeneration = generation
        heading.text = country.flag + "  " + country.name
        body.removeAllViews()

        val wait = TextView(this).apply {
            text = "جارٍ تحميل قنوات " + country.name + "..."
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        body.addView(wait, FrameLayout.LayoutParams(-1, -1))
        body.addView(ProgressBar(this), FrameLayout.LayoutParams(dp(42), dp(42), Gravity.CENTER))

        countryTask = countryExecutor.submit {
            val cacheFile = File(cacheDir, "country-" + country.code + ".m3u")
            val list = try {
                if (!cacheFile.exists() ||
                    System.currentTimeMillis() - cacheFile.lastModified() > 43_200_000L
                ) {
                    download(country, cacheFile)
                }
                if (Thread.currentThread().isInterrupted) return@submit
                parse(cacheFile)
            } catch (_: Exception) {
                if (Thread.currentThread().isInterrupted) return@submit
                if (cacheFile.exists()) {
                    try { parse(cacheFile) } catch (_: Exception) { emptyList() }
                } else {
                    emptyList()
                }
            }

            if (Thread.currentThread().isInterrupted) return@submit
            runOnUiThread {
                if (requestGeneration != generation || isFinishing || isDestroyed) return@runOnUiThread
                body.removeAllViews()
                if (list.isEmpty()) {
                    body.addView(TextView(this).apply {
                        text = "لا توجد قنوات متاحة أو تعذر الاتصال. حاول لاحقاً."
                        gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                    }, FrameLayout.LayoutParams(-1, -1))
                } else {
                    val newAdapter = ChannelAdapter(onClick = { channel ->
                        startActivity(
                            android.content.Intent(this, PlayerActivity::class.java)
                                .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
                                .putExtra(PlayerActivity.EXTRA_URL, channel.url)
                                .putExtra(PlayerActivity.EXTRA_REFERER, channel.referer)
                                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                                .putExtra(PlayerActivity.EXTRA_LOGO, channel.logo)
                        )
                    })
                    channelAdapter = newAdapter
                    newAdapter.submitList(list)

                    val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
                    wrap.addView(TextView(this).apply {
                        text = list.size.toString() + " قناة"
                        setTextColor(Color.WHITE)
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                    })
                    wrap.addView(RecyclerView(this).apply {
                        layoutManager = GridLayoutManager(this@CountriesActivity, 2)
                        adapter = newAdapter
                        setHasFixedSize(false)
                    }, LinearLayout.LayoutParams(-1, 0, 1f))
                    body.addView(wrap)
                }
            }
        }
    }

    private fun download(country: TvCountry, file: File) {
        val connection = URL(
            "https://iptv-org.github.io/iptv/countries/" + country.code + ".m3u"
        ).openConnection() as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "FIRAS-TV/4.1")
        val temp = File(file.parentFile, file.name + ".tmp")
        try {
            if (connection.responseCode !in 200..299) {
                throw java.io.IOException("Playlist unavailable")
            }
            var size = 0L
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) {
                            throw InterruptedException("Country request cancelled")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        if (size > 6L * 1024L * 1024L) {
                            throw java.io.IOException("Playlist too large")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
        } finally {
            temp.delete()
            connection.disconnect()
        }
    }

    private fun parse(file: File): List<Channel> {
        val result = ArrayList<Channel>()
        var name = ""
        var logo = ""
        file.bufferedReader(Charsets.UTF_8).use { reader ->
            while (true) {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Country parse cancelled")
                }
                val line = (reader.readLine() ?: break).trim()
                if (line.startsWith("#EXTINF", ignoreCase = true)) {
                    name = line.substringAfterLast(',').trim()
                    logo = Regex("""tvg-logo="([^"]*)"""")
                        .find(line)?.groupValues?.getOrNull(1).orEmpty()
                } else if (
                    (line.startsWith("http://", ignoreCase = true) ||
                        line.startsWith("https://", ignoreCase = true)) &&
                    name.isNotBlank()
                ) {
                    result.add(Channel(name, logo, line, "", "country-" + line.hashCode()))
                    name = ""
                    logo = ""
                    if (result.size >= 1200) break
                }
            }
        }
        return result
    }

    override fun onDestroy() {
        stopCountryWork()
        countryExecutor.shutdownNow()
        super.onDestroy()
    }
}
