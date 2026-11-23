package com.firas.tv

import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

private data class MatchCard(
    val league: String, val home: String, val away: String,
    val homeScore: String, val awayScore: String, val time: String,
    val status: String, val isLive: Boolean, val channel: String, val commentator: String
)

class SportsActivity : AppCompatActivity() {
    private val io = Executors.newFixedThreadPool(6)
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private val matches = mutableListOf<MatchCard>()
    @Volatile private var loadGeneration = 0
    private val leagues = listOf(
        "eng.1" to "الدوري الإنجليزي",
        "esp.1" to "الدوري الإسباني",
        "ita.1" to "الدوري الإيطالي",
        "ger.1" to "الدوري الألماني",
        "fra.1" to "الدوري الفرنسي",
        "uefa.champions" to "دوري أبطال أوروبا",
        "uefa.europa" to "الدوري الأوروبي"
    )

    override fun onDestroy() {
        loadGeneration++
        io.shutdownNow()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF080B14.toInt()
        window.navigationBarColor = 0xFF101522.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF080B14.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(10), dp(16), dp(8))
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val back = label("‹", 34, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        top.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))
        val heading = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heading.addView(label("المباريات والنتائج", 21, -1, true))
        heading.addView(label("تابع مباريات اليوم والبطولات", 11, 0xFF9CA8BD.toInt(), false))
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = label("تحديث", 12, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF242E43.toInt(), 14)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { loadMatches() }
        }
        top.addView(refresh)
        root.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF6D315D.toInt(), 0xFF30264C.toInt(), 0xFF18263B.toInt())).apply {
                cornerRadius = dp(22).toFloat()
            }
        }
        hero.addView(label("RAHMANI SPORTS", 11, 0xFFFFC7D7.toInt(), true))
        hero.addView(label("كل المباريات في مكان واحد", 20, -1, true).apply { setPadding(0, dp(6), 0, dp(4)) })
        hero.addView(label("نتائج مباشرة • مباريات اليوم • جدول القادم", 11, 0xFFE0DCEB.toInt(), false))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(12) })

        status = label("جارٍ تحميل المباريات...", 12, 0xFFB7C2D5.toInt(), false)
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        val scroll = ScrollView(this).apply { isFillViewport = true }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadMatches()
    }

    private fun loadMatches() {
        val generation = ++loadGeneration
        status.text = "جارٍ تحديث النتائج..."
        content.removeAllViews()
        synchronized(matches) { matches.clear() }
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        var completed = 0
        for ((code, leagueName) in leagues) {
            io.execute {
                try {
                    val url = URL("https://site.api.espn.com/apis/site/v2/sports/soccer/$code/scoreboard?dates=$date")
                    val conn = (url.openConnection() as HttpURLConnection).apply {
                        connectTimeout = 12000; readTimeout = 15000
                        setRequestProperty("User-Agent", "RAHMANI/1.0")
                    }
                    val json = try {
                        val code = conn.responseCode
                        if (code !in 200..299) throw java.io.IOException("ESPN HTTP $code")
                        conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    } finally { conn.disconnect() }
                    val events = JSONObject(json).optJSONArray("events")
                    val found = mutableListOf<MatchCard>()
                    if (events != null) for (i in 0 until events.length()) {
                        val event = events.optJSONObject(i) ?: continue
                        val comp = event.optJSONArray("competitions")?.optJSONObject(0) ?: JSONObject()
                        val competitors = comp.optJSONArray("competitors")
                        var home = "الفريق الأول"; var away = "الفريق الثاني"
                        var hs = ""; var ascore = ""
                        if (competitors != null) for (j in 0 until competitors.length()) {
                            val team = competitors.optJSONObject(j) ?: continue
                            val name = team.optJSONObject("team")?.optString("displayName").orEmpty().ifBlank { "فريق" }
                            if (team.optString("homeAway") == "home") { home = name; hs = team.optString("score") }
                            else { away = name; ascore = team.optString("score") }
                        }
                        val state = comp.optJSONObject("status")?.optJSONObject("type")
                        val statusText = state?.optString("shortDetail").orEmpty().ifBlank {
                            state?.optString("description").orEmpty().ifBlank { "موعد المباراة" }
                        }
                        // ESPN exposes a machine-readable state: "in" means currently in progress.
                        val isLive = state?.optString("state").equals("in", ignoreCase = true)
                        val iso = event.optString("date")
                        val time = try {
                            val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mmX", Locale.US).parse(iso)
                            if (parsed != null) SimpleDateFormat("HH:mm", Locale.getDefault()).format(parsed) else "--:--"
                        } catch (_: Exception) { "--:--" }
                        val broadcasts = comp.optJSONArray("broadcasts")
                        val channel = if (broadcasts != null && broadcasts.length() > 0) {
                            val b = broadcasts.optJSONObject(0)
                            ((b?.optJSONArray("names")?.optString(0))?.takeIf { !it.isNullOrBlank() }) ?: "متاحة في تفاصيل البث"
                        } else "غير متاحة من المصدر"
                        val commentator = comp.optJSONArray("commentators")?.optJSONObject(0)?.optString("name")
                            ?.takeIf { !it.isNullOrBlank() } ?: "غير متاح من المصدر"
                        found.add(MatchCard(leagueName, home, away, hs, ascore, time, statusText, isLive, channel, commentator))
                    }
                    if (generation == loadGeneration) synchronized(matches) { matches.addAll(found) }
                } catch (_: Exception) { }
                runOnUiThread {
                    if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                    completed++
                    if (completed == leagues.size) renderMatches()
                }
            }
        }
    }

    private fun renderMatches() {
        content.removeAllViews()
        val ordered = synchronized(matches) { matches.sortedBy { if (it.isLive) 0 else 1 } }
        status.text = if (ordered.isEmpty()) "لا توجد مباريات مسجلة اليوم حالياً • جرّب التحديث لاحقاً"
            else "مباريات اليوم: ${ordered.size} • يتم تحديث البيانات عند الضغط على تحديث"
        if (ordered.isEmpty()) {
            val empty = label("لا توجد مباريات في تغذية النتائج لهذا اليوم", 14, 0xFF9CA8BD.toInt(), false)
            empty.gravity = Gravity.CENTER
            content.addView(empty, LinearLayout.LayoutParams(-1, dp(130)))
            return
        }
        for (m in ordered) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = rounded(0xFF151D2C.toInt(), 18)
            }
            val leagueRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            leagueRow.addView(label(m.league, 11, 0xFFB6C3D9.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
            val live = m.isLive
            leagueRow.addView(label(if (live) "● مباشر" else m.status, 10,
                if (live) 0xFFFF657A.toInt() else 0xFF8EA2C0.toInt(), true))
            card.addView(leagueRow)
            val teams = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(13), 0, dp(12))
            }
            teams.addView(label(m.home, 14, -1, true).apply { gravity = Gravity.CENTER; maxLines = 2 },
                LinearLayout.LayoutParams(0, -2, 1f))
            val score = if (m.homeScore.isNotBlank() || m.awayScore.isNotBlank()) "${m.homeScore}  -  ${m.awayScore}" else m.time
            teams.addView(label(score, 18, 0xFFFFD166.toInt(), true).apply { gravity = Gravity.CENTER })
            teams.addView(label(m.away, 14, -1, true).apply { gravity = Gravity.CENTER; maxLines = 2 },
                LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(teams)
            card.addView(label("القناة الناقلة: ${m.channel}", 11, 0xFFB6C3D9.toInt(), false))
            card.addView(label("المعلّق: ${m.commentator}", 11, 0xFF8795AC.toInt(), false).apply { setPadding(0, dp(5), 0, 0) })
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        }
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
