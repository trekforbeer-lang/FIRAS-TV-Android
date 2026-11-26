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
import java.util.TimeZone
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
    @Volatile private var loadFailures = 0
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
        window.statusBarColor = 0xFF081321.toInt()
        window.navigationBarColor = 0xFF0B1B2D.toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF081321.toInt())
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
        heading.addView(label("تابع مباريات اليوم والبطولات", 11, 0xFF9BB5C8.toInt(), false))
        top.addView(heading, LinearLayout.LayoutParams(0, -2, 1f))
        val refresh = label("تحديث", 12, 0xFFFFFFFF.toInt(), true).apply {
            gravity = Gravity.CENTER
            background = rounded(0xFF123B58.toInt(), 14)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            setOnClickListener { loadMatches() }
        }
        top.addView(refresh)
        root.addView(top)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF174A69.toInt(), 0xFF102D49.toInt(), 0xFF0A192A.toInt())).apply {
                cornerRadius = dp(22).toFloat()
            }
        }
        hero.addView(label("RAHMANI SPORTS", 11, 0xFF7CEBFF.toInt(), true))
        hero.addView(label("كل المباريات في مكان واحد", 20, -1, true).apply { setPadding(0, dp(6), 0, dp(4)) })
        hero.addView(label("نتائج مباشرة • مباريات اليوم • جدول القادم", 11, 0xFFE7DDE0.toInt(), false))
        root.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14); bottomMargin = dp(12) })

        status = label("جارٍ تحميل المباريات...", 12, 0xFFB7C9D9.toInt(), false)
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
        status.text = "جارٍ تحميل نتائج API-Football..."
        content.removeAllViews()
        synchronized(matches) { matches.clear() }
        io.execute {
            var failure: String? = null
            val found = mutableListOf<MatchCard>()
            try {
                val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getTimeZone("Europe/Madrid") }
                val date = dateFormat.format(Date())
                val url = URL("https://europe-west1-rahmani-tv.cloudfunctions.net/footballFixtures?date=$date")
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 20000
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "RAHMANI-TV-Android/4")
                }
                val body = try {
                    val code = conn.responseCode
                    val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                    val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (code !in 200..299) throw java.io.IOException("HTTP $code: $text")
                    text
                } finally { conn.disconnect() }
                val root = JSONObject(body)
                if (root.has("errors") && root.optJSONObject("errors")?.length() ?: 0 > 0) {
                    throw java.io.IOException("API-Football returned errors")
                }
                val response = root.optJSONArray("response") ?: org.json.JSONArray()
                for (i in 0 until response.length()) {
                    val item = response.optJSONObject(i) ?: continue
                    val fixture = item.optJSONObject("fixture") ?: JSONObject()
                    val league = item.optJSONObject("league")?.optString("name").orEmpty().ifBlank { "بطولة غير محددة" }
                    val teams = item.optJSONObject("teams") ?: JSONObject()
                    val home = teams.optJSONObject("home")?.optString("name").orEmpty().ifBlank { "الفريق الأول" }
                    val away = teams.optJSONObject("away")?.optString("name").orEmpty().ifBlank { "الفريق الثاني" }
                    val goals = item.optJSONObject("goals") ?: JSONObject()
                    val homeScore = if (goals.isNull("home")) "" else goals.optString("home")
                    val awayScore = if (goals.isNull("away")) "" else goals.optString("away")
                    val state = fixture.optJSONObject("status") ?: JSONObject()
                    val short = state.optString("short")
                    val elapsed = fixture.optInt("elapsed", -1)
                    val isLive = short in setOf("1H", "HT", "2H", "ET", "BT", "P", "LIVE")
                    val statusText = when (short) {
                        "NS" -> "لم تبدأ"
                        "1H", "2H", "ET", "P", "LIVE" -> if (elapsed >= 0) "مباشر • $elapsed′" else "مباشر"
                        "HT" -> "استراحة"
                        "FT", "AET", "PEN" -> "انتهت"
                        "PST" -> "مؤجلة"
                        "CANC", "ABD" -> "ألغيت"
                        "SUSP" -> "متوقفة"
                        else -> state.optString("long").ifBlank { "موعد المباراة" }
                    }
                    val iso = fixture.optString("date")
                    val time = try {
                        val parsed = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(iso)
                        if (parsed != null) SimpleDateFormat("HH:mm", Locale.getDefault()).format(parsed) else "--:--"
                    } catch (_: Exception) { "--:--" }
                    found.add(MatchCard(league, home, away, homeScore, awayScore, time, statusText, isLive, "", ""))
                }
            } catch (e: Exception) {
                failure = e.message ?: "تعذّر الاتصال بخدمة النتائج"
            }
            if (generation == loadGeneration) {
                if (failure == null) loadFailures = 0 else loadFailures++
                synchronized(matches) { matches.addAll(found) }
            }
            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) return@runOnUiThread
                if (failure != null) status.text = "تعذّر تحميل النتائج من API-Football • تحقق من إعداد الخادم والإنترنت"
                else status.text = if (found.isEmpty()) "لا توجد مباريات في تغذية النتائج لهذا اليوم" else "مباريات اليوم: ${found.size} • المصدر: API-Football"
                renderMatches()
                if (failure != null && found.isEmpty()) {
                    Toast.makeText(this, "خدمة النتائج غير جاهزة بعد", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun renderMatches() {
        content.removeAllViews()
        val ordered = synchronized(matches) { matches.sortedBy { if (it.isLive) 0 else 1 } }
        if (ordered.isNotEmpty()) status.text = "مباريات اليوم: ${ordered.size} • المصدر: API-Football"
        if (ordered.isEmpty()) {
            val empty = label(if (loadFailures == leagues.size) "فشل تحميل النتائج من جميع المصادر. أعد المحاولة." else "لا توجد مباريات في تغذية النتائج لهذا اليوم", 14, 0xFF9BB5C8.toInt(), false)
            empty.gravity = Gravity.CENTER
            content.addView(empty, LinearLayout.LayoutParams(-1, dp(130)))
            return
        }
        for (m in ordered) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = rounded(0xFF10243A.toInt(), 18)
            }
            val leagueRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            leagueRow.addView(label(m.league, 11, 0xFFD2E0EC.toInt(), true), LinearLayout.LayoutParams(0, -2, 1f))
            val live = m.isLive
            leagueRow.addView(label(if (live) "● مباشر" else m.status, 10,
                if (live) 0xFF46D6F2.toInt() else 0xFF9BB5C8.toInt(), true))
            card.addView(leagueRow)
            val teams = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(13), 0, dp(12))
            }
            teams.addView(label(m.home, 14, -1, true).apply { gravity = Gravity.CENTER; maxLines = 2 },
                LinearLayout.LayoutParams(0, -2, 1f))
            val score = if (m.homeScore.isNotBlank() || m.awayScore.isNotBlank()) "${m.homeScore}  -  ${m.awayScore}" else m.time
            teams.addView(label(score, 18, 0xFF7CEBFF.toInt(), true).apply { gravity = Gravity.CENTER })
            teams.addView(label(m.away, 14, -1, true).apply { gravity = Gravity.CENTER; maxLines = 2 },
                LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(teams)
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
