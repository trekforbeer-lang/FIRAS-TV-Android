package com.firas.tv

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val FAN_PREFS = "rahmani_fan_preferences"
private const val FAN_CHANNEL_ID = "rahmani_match_alerts"

class TeamPreferencesActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences(FAN_PREFS, MODE_PRIVATE) }
    private lateinit var teamContainer: LinearLayout
    private lateinit var customInput: EditText
    private lateinit var matchStartSwitch: Switch
    private lateinit var scoreSwitch: Switch
    private var pendingPermissionFinish = false
    private val selectedTeams = linkedSetOf<String>()
    private val customTeams = linkedSetOf<String>()

    private val teamGroups = linkedMapOf(
        "الدوري الإنجليزي" to listOf("Arsenal", "Manchester City", "Liverpool", "Manchester United", "Chelsea", "Tottenham Hotspur", "Newcastle United", "Aston Villa"),
        "الدوري الإسباني" to listOf("Real Madrid", "Barcelona", "Atletico Madrid", "Sevilla", "Athletic Club"),
        "الدوري الإيطالي" to listOf("Inter Milan", "AC Milan", "Juventus", "Napoli", "Roma"),
        "الدوري الألماني" to listOf("Bayern Munich", "Borussia Dortmund", "Bayer Leverkusen", "RB Leipzig"),
        "الدوري الفرنسي" to listOf("Paris Saint-Germain", "Marseille", "Monaco", "Lyon"),
        "الدوري الهولندي" to listOf("Ajax", "PSV Eindhoven", "Feyenoord", "AZ Alkmaar", "FC Twente"),
        "الدوري البرتغالي" to listOf("Benfica", "Porto", "Sporting CP", "Braga"),
        "الدوري التركي" to listOf("Galatasaray", "Fenerbahce", "Besiktas", "Trabzonspor"),
        "الدوري الاسكتلندي" to listOf("Celtic", "Rangers", "Hearts", "Aberdeen"),
        "الدوري الأمريكي" to listOf("Inter Miami", "LAFC", "LA Galaxy", "New York City FC", "Seattle Sounders"),
        "أندية أخرى" to listOf("Al Ahly", "Zamalek", "Wydad AC", "Raja CA", "Al Hilal", "Al Nassr", "Al Ittihad")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 11, 20)
        window.navigationBarColor = Color.rgb(16, 21, 34)
        selectedTeams.addAll(prefs.getStringSet("favorite_teams", emptySet()) ?: emptySet())
        customTeams.addAll(prefs.getStringSet("custom_teams", emptySet()) ?: emptySet())

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(16))
            setBackgroundColor(Color.rgb(8, 11, 20))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val back = text("‹", 32, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(48)))
        header.addView(text("اهتماماتي وإشعاراتي", 21, Color.WHITE, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(header)

        root.addView(text("اختر فرقك المفضلة", 19, Color.rgb(255, 145, 168), true).apply {
            setPadding(0, dp(14), 0, dp(5))
        })
        root.addView(text("يمكنك اختيار أكثر من فريق، وستظهر تنبيهات المباريات الخاصة بالفرق التي تحددها فقط.", 12, 0xFFB6C3D9.toInt(), false))

        val customRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }
        customInput = EditText(this).apply {
            hint = "اسم الفريق كما يظهر في النتائج"
            textSize = 13f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(0xFF8795AC.toInt())
            background = rounded(0xFF182235.toInt(), 12)
            setPadding(dp(12), 0, dp(12), 0)
        }
        customRow.addView(customInput, LinearLayout.LayoutParams(0, dp(48), 1f))
        val add = Button(this).apply {
            text = "إضافة"
            isAllCaps = false
            setOnClickListener {
                val name = customInput.text.toString().trim()
                if (name.length < 2) {
                    Toast.makeText(this@TeamPreferencesActivity, "اكتب اسم فريق صحيحاً", Toast.LENGTH_SHORT).show()
                } else if (teamGroups.values.flatten().any { it.equals(name, true) } || customTeams.any { it.equals(name, true) }) {
                    Toast.makeText(this@TeamPreferencesActivity, "الفريق موجود بالفعل", Toast.LENGTH_SHORT).show()
                } else {
                    customTeams.add(name)
                    selectedTeams.add(name)
                    customInput.text.clear()
                    renderTeams()
                }
            }
        }
        customRow.addView(add, LinearLayout.LayoutParams(dp(84), dp(48)).apply { marginStart = dp(8) })
        root.addView(customRow, LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(12); bottomMargin = dp(10)
        })

        val scroll = ScrollView(this)
        teamContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(teamContainer)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(text("أنواع التنبيهات", 18, Color.WHITE, true).apply {
            setPadding(0, dp(12), 0, dp(6))
        })
        matchStartSwitch = Switch(this).apply {
            text = "تنبيه عند بداية مباراة فريقي"
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = prefs.getBoolean("notify_match_start", true)
        }
        scoreSwitch = Switch(this).apply {
            text = "تنبيه عند تغيّر النتيجة أو تسجيل هدف"
            setTextColor(Color.WHITE)
            textSize = 13f
            isChecked = prefs.getBoolean("notify_score_change", true)
        }
        root.addView(matchStartSwitch)
        root.addView(scoreSwitch)
        root.addView(text("تعمل متابعة المباريات في الخلفية عند توفر الإنترنت. قد تتأخر التنبيهات بسبب قيود تحديث الخلفية في أندرويد.", 11, 0xFF8795AC.toInt(), false).apply {
            setPadding(0, dp(7), 0, dp(8))
        })
        val save = Button(this).apply {
            text = "حفظ اهتماماتي"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFE8496F.toInt())
            setOnClickListener { savePreferences() }
        }
        root.addView(save, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(8) })
        setContentView(root)
        renderTeams()
    }

    private fun renderTeams() {
        teamContainer.removeAllViews()
        val groups = LinkedHashMap(teamGroups)
        if (customTeams.isNotEmpty()) groups["فرق أضفتها"] = customTeams.toList()
        for ((title, names) in groups) {
            teamContainer.addView(text(title, 13, 0xFFFFC7D7.toInt(), true).apply {
                setPadding(dp(4), dp(10), dp(4), dp(4))
            })
            for (name in names) {
                val check = CheckBox(this).apply {
                    text = name
                    textSize = 13f
                    setTextColor(Color.WHITE)
                    buttonTintList = android.content.res.ColorStateList.valueOf(0xFFFF6687.toInt())
                    isChecked = selectedTeams.contains(name)
                    setOnCheckedChangeListener { _, checked ->
                        if (checked) selectedTeams.add(name) else selectedTeams.remove(name)
                    }
                }
                teamContainer.addView(check, LinearLayout.LayoutParams(-1, dp(42)))
            }
        }
    }

    private fun savePreferences() {
        prefs.edit()
            .putStringSet("favorite_teams", selectedTeams.toSet())
            .putStringSet("custom_teams", customTeams.toSet())
            .putBoolean("notify_match_start", matchStartSwitch.isChecked)
            .putBoolean("notify_score_change", scoreSwitch.isChecked)
            .apply()
        FanMatchWorker.schedule(this)
        Toast.makeText(this, "تم حفظ فرقك وإعدادات التنبيهات", Toast.LENGTH_LONG).show()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingPermissionFinish = true
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 701)
        } else {
            finish()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 701 && pendingPermissionFinish) finish()
    }

    private fun text(value: String, size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
    }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

class FanMatchWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences(FAN_PREFS, Context.MODE_PRIVATE)
        val teams = prefs.getStringSet("favorite_teams", emptySet()) ?: emptySet()
        if (teams.isEmpty() || (!prefs.getBoolean("notify_match_start", true) && !prefs.getBoolean("notify_score_change", true))) {
            return Result.success()
        }
        createNotificationChannel()
        val date = SimpleDateFormat("yyyyMMdd", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
        val leagues = listOf("eng.1", "esp.1", "ita.1", "ger.1", "fra.1", "ned.1", "por.1", "tur.1", "sco.1", "usa.1", "uefa.champions", "uefa.europa")
        return try {
            for (league in leagues) {
                val connection = (URL("https://site.api.espn.com/apis/site/v2/sports/soccer/$league/scoreboard?dates=$date")
                    .openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 12000
                    setRequestProperty("User-Agent", "RAHMANI-Android/4.0")
                }
                val body = try {
                    if (connection.responseCode !in 200..299) continue
                    connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                } finally { connection.disconnect() }
                val events = JSONObject(body).optJSONArray("events") ?: continue
                for (i in 0 until events.length()) {
                    val event = events.optJSONObject(i) ?: continue
                    val competition = event.optJSONArray("competitions")?.optJSONObject(0) ?: continue
                    val competitors = competition.optJSONArray("competitors") ?: continue
                    var home = ""; var away = ""; var homeScore = ""; var awayScore = ""
                    for (j in 0 until competitors.length()) {
                        val item = competitors.optJSONObject(j) ?: continue
                        val name = item.optJSONObject("team")?.optString("displayName").orEmpty()
                        if (item.optString("homeAway") == "home") { home = name; homeScore = item.optString("score") }
                        else { away = name; awayScore = item.optString("score") }
                    }
                    val matched = teams.firstOrNull { fav ->
                        fav.equals(home, true) || fav.equals(away, true) ||
                            home.contains(fav, true) || away.contains(fav, true) ||
                            fav.contains(home, true) || fav.contains(away, true)
                    } ?: continue
                    val eventId = event.optString("id").ifBlank { "$league-$home-$away" }
                    val state = competition.optJSONObject("status")?.optJSONObject("type")?.optString("state").orEmpty()
                    val score = "$homeScore-$awayScore"
                    val stateKey = "event_$eventId"
                    val previous = prefs.getString(stateKey, null)
                    if (previous == null && state == "in" && prefs.getBoolean("notify_match_start", true)) {
                        notify(eventId, "مباراة فريقك المفضل مباشرة", "$home ضد $away • المباراة جارية الآن")
                    } else if (previous != null) {
                        val old = previous.split("|", limit = 2)
                        val oldState = old.getOrNull(0).orEmpty()
                        val oldScore = old.getOrNull(1).orEmpty()
                        if (prefs.getBoolean("notify_match_start", true) && state == "in" && oldState != "in") {
                            notify(eventId, "بدأت مباراة $matched", "$home ضد $away • بدأت المباراة الآن")
                        } else if (prefs.getBoolean("notify_score_change", true) && oldScore.isNotBlank() &&
                            score != oldScore && homeScore.isNotBlank() && awayScore.isNotBlank()) {
                            notify(eventId, "تغيّرت نتيجة مباراة $matched", "$home $homeScore - $awayScore $away")
                        }
                    }
                    prefs.edit().putString(stateKey, "$state|$score").apply()
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(
                FAN_CHANNEL_ID, "تنبيهات المباريات المفضلة", NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "بداية المباريات وتغيّر نتائج الفرق التي تتابعها" })
        }
    }

    private fun notify(eventId: String, title: String, message: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(applicationContext, SportsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            applicationContext, eventId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, FAN_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(eventId.hashCode(), notification)
    }

    companion object {
        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<FanMatchWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "rahmani_favorite_team_match_alerts",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}
