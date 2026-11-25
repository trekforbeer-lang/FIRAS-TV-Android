package com.firas.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
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
import androidx.work.WorkManager
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
    private var selectedRegion = ""
    private var query = ""
    private var lastLaunchAt = 0L
    private var returningFromAdmin = false
    private var adminTapCount = 0
    private var adminTapStarted = 0L
    private lateinit var adapter: ChannelAdapter
    private lateinit var empty: TextView
    private lateinit var chips: ChipGroup
    private lateinit var restoreButton: Button
    private lateinit var restoreStatusPanel: View
    private lateinit var restoreStatusTitle: TextView
    private lateinit var restoreStatusDetail: TextView
    private lateinit var restoreStatusProgress: ProgressBar
    private lateinit var channelRecycler: RecyclerView
    private var drawerOverlay: View? = null
    private val homePrefs by lazy { getSharedPreferences("rahmani_home_preferences", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<TextView>(R.id.drawerMenuButton).setOnClickListener { openSideDrawer() }
        // Hidden owner-only entry: seven quick taps on the RAHMANI wordmark.
        findViewById<TextView>(R.id.adminSecretTrigger).setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now - adminTapStarted > 2500L) {
                adminTapCount = 0
                adminTapStarted = now
            }
            adminTapCount++
            if (adminTapCount >= 7) {
                adminTapCount = 0
                returningFromAdmin = true
                startActivity(Intent(this, AdminActivity::class.java))
            }
        }
        sections = ChannelRepository.load(this)
        val appCtx = applicationContext
        Thread { StreamConfigStore.ensureLoaded(appCtx) }.start()
        adapter = ChannelAdapter(onClick = { c ->
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
        }, onFavoriteChanged = { refresh() })
        val rv = findViewById<RecyclerView>(R.id.list)
        channelRecycler = rv
        rv.layoutManager = GridLayoutManager(this, if (resources.configuration.screenWidthDp >= 600) 2 else 1)
        rv.adapter = adapter
        // This RecyclerView wraps its content inside a scrolling parent, so its size is not fixed.
        rv.setHasFixedSize(false)
        empty = findViewById(R.id.empty)
        chips = findViewById(R.id.chips)
        restoreButton = findViewById(R.id.restoreChannelsButton)
        restoreStatusPanel = findViewById(R.id.restoreStatusPanel)
        restoreStatusTitle = findViewById(R.id.restoreStatusTitle)
        restoreStatusDetail = findViewById(R.id.restoreStatusDetail)
        restoreStatusProgress = findViewById(R.id.restoreStatusProgress)
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
        findViewById<View>(R.id.quickLiveButton).setOnClickListener {
            homePrefs.edit().putString("home_mode", "live").apply()
            selected = ""
            applyHomePreferences()
            refresh()
            channelRecycler.smoothScrollToPosition(0)
        }
        findViewById<View>(R.id.quickSportsButton).setOnClickListener { startActivity(Intent(this, SportsActivity::class.java)) }
        findViewById<View>(R.id.quickMoviesButton).setOnClickListener { startActivity(Intent(this, MoviesActivity::class.java)) }
        findViewById<TextView>(R.id.homeTab).setOnClickListener { selected = ""; rebuildChips(chips); refresh(); rv.smoothScrollToPosition(0) }
        findViewById<TextView>(R.id.channelsTab).setOnClickListener { rv.smoothScrollToPosition(0) }
        findViewById<TextView>(R.id.sportsTab).setOnClickListener { startActivity(Intent(this, SportsActivity::class.java)) }
        findViewById<Button>(R.id.sportsMatchesButton).setOnClickListener { startActivity(Intent(this, SportsActivity::class.java)) }
        updateBottomNavigation(R.id.navHome)
        findViewById<TextView>(R.id.navHome).setOnClickListener {
            updateBottomNavigation(R.id.navHome)
            selected = ""; rebuildChips(chips); refresh(); rv.smoothScrollToPosition(0)
        }
        findViewById<TextView>(R.id.navChannels).setOnClickListener {
            updateBottomNavigation(R.id.navChannels)
            rv.smoothScrollToPosition(0)
        }
        findViewById<TextView>(R.id.navMovies).setOnClickListener {
            updateBottomNavigation(R.id.navMovies)
            startActivity(Intent(this, MyListActivity::class.java))
        }
        findViewById<TextView>(R.id.navDiscover).setOnClickListener {
            updateBottomNavigation(R.id.navDiscover)
            startActivity(Intent(this, DiscoverActivity::class.java))
        }
        findViewById<TextView>(R.id.navAdmin).setOnClickListener {
            updateBottomNavigation(R.id.navAdmin)
            startActivity(Intent(this, MoreActivity::class.java))
        }
        restoreButton.setOnClickListener { startManualChannelRestore() }
        observeChannelRestore()
        refresh()
        CloudChannelSync.refreshPublic(applicationContext) { changed ->
            if (changed > 0 && !isFinishing && !isDestroyed) runOnUiThread {
                sections = ChannelRepository.load(this)
                selected = ""
                rebuildChips(chips)
                refresh()
            }
        }
        FanMatchWorker.schedule(this)
        findViewById<View>(R.id.fanPreferencesButton).setOnClickListener { startActivity(Intent(this, TeamPreferencesActivity::class.java)) }
        showWelcomeScreen()
    }

    private fun updateBottomNavigation(activeId: Int) {
        val ids = intArrayOf(R.id.navHome, R.id.navChannels, R.id.navDiscover, R.id.navMovies, R.id.navAdmin)
        ids.forEach { id ->
            findViewById<TextView>(id)?.let { item ->
                val active = id == activeId
                item.isSelected = active
                item.setTextColor(if (active) Color.WHITE else Color.rgb(151, 151, 157))
                item.alpha = if (active) 1f else 0.88f
            }
        }
    }

    private fun showWelcomeScreen() {
        if (homePrefs.getBoolean("welcome_seen", false)) return
        val root = findViewById<FrameLayout>(android.R.id.content)
        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(26), dp(28), dp(26), dp(28))
            setBackgroundColor(Color.rgb(5, 6, 9))
            isClickable = true
            isFocusable = true
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(34), dp(24), dp(28))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(48, 39, 20), Color.rgb(22, 22, 20), Color.rgb(8, 9, 11))
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.rgb(151, 119, 54))
            }
            elevation = dp(8).toFloat()
        }
        val mark = TextView(this).apply {
            text = "R"
            gravity = Gravity.CENTER
            textSize = 38f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(205, 164, 80))
                setStroke(dp(3), Color.rgb(255, 226, 160))
            }
        }
        panel.addView(mark, LinearLayout.LayoutParams(dp(82), dp(82)))

        fun addWelcomeText(value: String, size: Float, color: Int, bold: Boolean, top: Int) {
            val label = TextView(this).apply {
                text = value
                textSize = size
                setTextColor(color)
                gravity = Gravity.CENTER
                if (bold) setTypeface(typeface, Typeface.BOLD)
            }
            panel.addView(label, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(top) })
        }
        addWelcomeText("RAHMANI", 29f, Color.WHITE, true, 17)
        addWelcomeText("مرحباً بك في عالم الترفيه", 19f, Color.rgb(238, 207, 139), true, 9)
        addWelcomeText("قنوات مباشرة • أفلام • مسلسلات • رياضة", 13f, Color.rgb(218, 224, 238), false, 12)
        addWelcomeText("كل ما تحب مشاهدته، في مكان واحد", 12f, Color.rgb(169, 182, 204), false, 8)

        val features = TextView(this).apply {
            text = "● البث المباشر       ⚽ المباريات       ▶ الأفلام"
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(13), dp(8), dp(13))
            background = GradientDrawable().apply {
                setColor(Color.argb(55, 255, 255, 255))
                cornerRadius = dp(14).toFloat()
            }
        }
        panel.addView(features, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(25) })

        fun finishWelcome() {
            homePrefs.edit().putBoolean("welcome_seen", true).apply()
            root.removeView(overlay)
        }
        val start = Button(this).apply {
            text = "ابدأ المشاهدة"
            isAllCaps = false
            textSize = 15f
            setTextColor(Color.WHITE)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Color.rgb(205, 164, 80))
            setOnClickListener {
                finishWelcome()
                channelRecycler.smoothScrollToPosition(0)
            }
        }
        panel.addView(start, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
        ).apply { topMargin = dp(24) })

        val explore = TextView(this).apply {
            text = "استكشف التطبيق"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(238, 207, 139))
            setPadding(dp(12), dp(14), dp(12), dp(8))
            setOnClickListener { finishWelcome() }
        }
        panel.addView(explore, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        overlay.addView(panel, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
    }


    private fun openSideDrawer() {
        if (drawerOverlay != null) return
        val root = findViewById<FrameLayout>(android.R.id.content)
        val panelWidth = (resources.displayMetrics.widthPixels * 0.84f).toInt().coerceAtMost(dp(340))
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            isFocusable = true
        }
        val scrim = View(this).apply {
            setBackgroundColor(Color.argb(178, 0, 0, 0))
            setOnClickListener { closeSideDrawer() }
        }
        overlay.addView(scrim, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(18), dp(22), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(24, 28, 40), Color.rgb(9, 13, 22), Color.rgb(8, 10, 16))
            ).apply {
                setCornerRadii(floatArrayOf(dp(24).toFloat(), dp(24).toFloat(), 0f, 0f, 0f, 0f, dp(24).toFloat(), dp(24).toFloat()))
                setStroke(dp(1), Color.rgb(91, 77, 57))
            }
            elevation = dp(18).toFloat()
        }
        val panelParams = FrameLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.RIGHT)
        overlay.addView(panel, panelParams)
        panel.isClickable = true
        val menuScroll = android.widget.ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            isVerticalScrollBarEnabled = false
        }
        val menuContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        menuScroll.addView(menuContent, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        panel.addView(menuScroll, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        val brand = TextView(this).apply {
            text = "RAHMANI TV"
            textSize = 23f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.rgb(224, 190, 126))
            gravity = Gravity.CENTER_VERTICAL
            letterSpacing = 0.05f
        }
        menuContent.addView(brand, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(42)
        ))
        val subtitle = TextView(this).apply {
            text = "عالمك الترفيهي في مكان واحد"
            textSize = 11f
            setTextColor(Color.rgb(158, 169, 188))
            setPadding(0, 0, 0, dp(15))
        }
        menuContent.addView(subtitle)
        val line = View(this).apply { setBackgroundColor(Color.argb(90, 198, 166, 106)) }
        menuContent.addView(line, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))

        fun entry(label: String, description: String, action: () -> Unit) {
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(8), dp(14), dp(8))
                background = GradientDrawable().apply {
                    setColor(Color.argb(24, 255, 255, 255))
                    cornerRadius = dp(13).toFloat()
                    setStroke(dp(1), Color.argb(30, 198, 166, 106))
                }
                isClickable = true
                isFocusable = true
                setOnClickListener { closeSideDrawer(); action() }
            }
            val title = TextView(this).apply {
                text = label
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.rgb(238, 241, 247))
            }
            val detail = TextView(this).apply {
                text = description
                textSize = 10f
                setTextColor(Color.rgb(145, 157, 177))
                setPadding(0, dp(3), 0, 0)
            }
            item.addView(title)
            item.addView(detail)
            menuContent.addView(item, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(58)
            ).apply { topMargin = dp(8) })
        }
        entry("الرئيسية", "العودة إلى الصفحة الرئيسية") {
            selected = ""; selectedRegion = ""; rebuildChips(chips); refresh()
            updateBottomNavigation(R.id.navHome)
            channelRecycler.smoothScrollToPosition(0)
        }
        entry("القنوات المباشرة", "تصفح القنوات المتاحة") {
            selected = ""; selectedRegion = ""; rebuildChips(chips); refresh()
            updateBottomNavigation(R.id.navChannels)
            channelRecycler.smoothScrollToPosition(0)
        }
        entry("الدول العربية", "قنوات البلدان العربية") {
            selected = ""; selectedRegion = "arab"; rebuildChips(chips); refresh()
            updateBottomNavigation(R.id.navChannels)
            channelRecycler.smoothScrollToPosition(0)
        }
        entry("الدول الأجنبية", "قنوات البلدان الأجنبية") {
            selected = ""; selectedRegion = "foreign"; rebuildChips(chips); refresh()
            updateBottomNavigation(R.id.navChannels)
            channelRecycler.smoothScrollToPosition(0)
        }
        entry("الرياضة", "المباريات والأقسام الرياضية") {
            startActivity(Intent(this, SportsActivity::class.java))
        }
        entry("الأفلام", "اكتشف مكتبة الأفلام") {
            startActivity(Intent(this, MoviesActivity::class.java))
        }
        entry("قائمتي", "المفضلة والمحفوظات") {
            startActivity(Intent(this, MyListActivity::class.java))
        }
        entry("اكتشف", "استكشف المزيد من المحتوى") {
            startActivity(Intent(this, DiscoverActivity::class.java))
        }
        entry("المزيد والإعدادات", "خيارات التطبيق والمعلومات") {
            startActivity(Intent(this, MoreActivity::class.java))
        }
        val footer = TextView(this).apply {
            text = "RAHMANI  •  تجربة مشاهدة أنيقة"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(121, 132, 151))
        }
        menuContent.addView(footer, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(36)
        ))
        drawerOverlay = overlay
        root.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        panel.translationX = panelWidth.toFloat()
        panel.animate().translationX(0f).setDuration(220).start()
    }

    private fun closeSideDrawer() {
        val overlay = drawerOverlay as? FrameLayout ?: return
        val root = findViewById<FrameLayout>(android.R.id.content)
        val panel = overlay.getChildAt(1)
        val distance = panel.width.toFloat().takeIf { it > 0f } ?: dp(300).toFloat()
        panel.animate().translationX(distance).setDuration(180).withEndAction {
            root.removeView(overlay)
            drawerOverlay = null
        }.start()
    }

    @Deprecated("Use the OnBackPressedDispatcher for new back handling")
    override fun onBackPressed() {
        if (drawerOverlay != null) {
            closeSideDrawer()
            return
        }
        super.onBackPressed()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

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
        val options = arrayOf("عرض بانر الأعمال المميزة", "عرض قسم المباريات الرياضية", "عرض القنوات المباشرة", "عرض شريط البحث", "عرض القنوات بعمودين صغيرين")
        val keys = arrayOf("hero", "sports", "channels", "search", "compact_grid")
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
                keys.forEach { key -> homePrefs.edit().putBoolean(key, key != "compact_grid").apply() }
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
        val compactGrid = homePrefs.getBoolean("compact_grid", false)
        val columns = if (resources.configuration.screenWidthDp >= 600) 2 else if (compactGrid) 2 else 1
        (channelRecycler.layoutManager as? GridLayoutManager)?.spanCount = columns
        findViewById<View>(R.id.homeHeroSection)?.visibility = if (showHero) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homeSportsSection)?.visibility = if (showSports) View.VISIBLE else View.GONE
        findViewById<View>(R.id.homeChannelsSection)?.visibility = if (showChannels) View.VISIBLE else View.GONE
        (findViewById<View>(R.id.chips)?.parent as? View)?.visibility = if (showChannels) View.VISIBLE else View.GONE
        findViewById<View>(R.id.channelListSectionHeader)?.visibility = if (showChannels) View.VISIBLE else View.GONE
        findViewById<View>(R.id.channelListContainer)?.visibility = if (showChannels) View.VISIBLE else View.GONE
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
        group.setOnCheckedStateChangeListener(null)
        group.removeAllViews()
        fun addChip(label: String, tag: String, checked: Boolean) {
            val chip = layoutInflater.inflate(R.layout.item_chip, group, false) as Chip
            chip.id = View.generateViewId(); chip.text = label; chip.tag = tag; chip.isChecked = checked
            group.addView(chip)
        }
        addChip("الكل", "", selected.isEmpty() && selectedRegion.isEmpty())
        addChip("الدول العربية", "__region_arab__", selectedRegion == "arab")
        addChip("الدول الأجنبية", "__region_foreign__", selectedRegion == "foreign")
        sections.filter { selectedRegion.isEmpty() || it.region == selectedRegion }.forEach {
            addChip(countryFlag(it.countryCode) + " " + it.title, it.title, selected == it.title)
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            val tag = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? String }.orEmpty()
            when (tag) {
                "__region_arab__" -> { selectedRegion = "arab"; selected = "" }
                "__region_foreign__" -> { selectedRegion = "foreign"; selected = "" }
                "" -> { selectedRegion = ""; selected = "" }
                else -> selected = tag
            }
            rebuildChips(group)
            refresh()
        }
    }

    private fun countryFlag(code: String): String {
        val normalized = code.trim().uppercase()
        if (normalized.length != 2 || normalized.any { it !in 'A'..'Z' }) return "🌐"
        return String(Character.toChars(0x1F1E6 + (normalized[0] - 'A'))) +
            String(Character.toChars(0x1F1E6 + (normalized[1] - 'A')))
    }

    private fun refresh() {
        val list = sections.filter { (selectedRegion.isEmpty() || it.region == selectedRegion) && (selected.isEmpty() || it.title == selected) }.flatMap { it.items }
            .filter { query.isEmpty() || it.name.contains(query, ignoreCase = true) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name.trim() })
        adapter.submitList(list)
        findViewById<TextView>(R.id.channelCount)?.text = "${list.size} قناة"
        empty.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun startManualChannelRestore() {
        val started = RestoreChannelsWorker.start(applicationContext)
        if (!started) {
            restoreStatusPanel.visibility = View.VISIBLE
            restoreStatusTitle.text = "الاستعادة مستمرة"
            restoreStatusDetail.text = "هناك دفعة قيد المعالجة أو مجدولة. لن نبدأ نسخة مكررة من الاستيراد."
            restoreStatusProgress.isIndeterminate = true
            return
        }
        restoreButton.isEnabled = false
        restoreButton.text = "جارٍ استيراد القنوات..."
        restoreStatusPanel.visibility = View.VISIBLE
        restoreStatusTitle.text = "بدأت استعادة القنوات"
        restoreStatusDetail.text = "يجري فحص قوائم البث وإضافة ما يصل إلى 100 قناة في هذه الدفعة."
        restoreStatusProgress.isIndeterminate = true
    }

    private fun observeChannelRestore() {
        WorkManager.getInstance(applicationContext)
            .getWorkInfosByTagLiveData(RestoreChannelsWorker.TAG)
            .observe(this) { workInfos ->
                val active = workInfos.any { !it.state.isFinished }
                val runningNow = workInfos.any { it.state == androidx.work.WorkInfo.State.RUNNING }
                val prefs = getSharedPreferences(RestoreChannelsWorker.PREFS, MODE_PRIVATE)
                val total = prefs.getInt("total_added", 0)
                var lastError = prefs.getString("last_error", "").orEmpty()
                // A stale running flag with no active WorkManager request means the
                // restore was interrupted or cancelled; release the UI and allow retry.
                if (!active && prefs.getBoolean("running", false)) {
                    lastError = lastError.ifBlank { "توقفت الاستعادة قبل إكمالها" }
                    prefs.edit().putBoolean("running", false).putString("last_error", lastError).apply()
                }
                // Reload the on-device catalog after each completed batch so newly
                // imported channels appear without forcing the user to restart the app.
                val refreshedTotal = prefs.getInt("ui_refreshed_total", -1)
                if (total > refreshedTotal) {
                    sections = ChannelRepository.load(this)
                    rebuildChips(chips)
                    refresh()
                    prefs.edit().putInt("ui_refreshed_total", total).apply()
                }
                restoreButton.isEnabled = !active
                restoreButton.text = when {
                    active && !runningNow && total > 0 -> "تم تنزيل $total قناة • التالية بعد 30د"
                    active -> "استعادة القنوات..."
                    !active && lastError.isNotBlank() -> "إعادة محاولة الاستعادة"
                    total > 0 -> "استعادة المزيد"
                    else -> "استعادة القنوات"
                }
                restoreStatusPanel.visibility = if (active || total > 0 || lastError.isNotBlank()) View.VISIBLE else View.GONE
                restoreStatusProgress.isIndeterminate = runningNow
                restoreStatusTitle.text = when {
                    runningNow -> "جارٍ استيراد القنوات"
                    active && total > 0 -> "اكتملت الدفعة الحالية"
                    active -> "الاستعادة بانتظار الاتصال"
                    !active && lastError.isNotBlank() -> "تعذّر إكمال استعادة القنوات"
                    total > 0 -> "اكتملت الاستعادة"
                    else -> "استعادة القنوات"
                }
                restoreStatusDetail.text = when {
                    runningNow -> "نفحص قوائم البث ونضيف حتى 100 قناة في هذه الدفعة. تمت إضافة $total قناة حتى الآن."
                    active && total > 0 -> "تمت إضافة $total قناة. الدفعة التالية مجدولة بعد 30 دقيقة."
                    active -> "سيبدأ التنزيل تلقائياً عند توفر اتصال بالإنترنت."
                    !active && lastError.isNotBlank() -> "سبب الفشل: $lastError. تحقق من الإنترنت ثم اضغط إعادة المحاولة."
                    total > 0 -> "انتهت الدفعات الحالية. إجمالي القنوات التي أُضيفت: $total."
                    else -> "يمكنك استعادة القنوات يدوياً من هنا."
                }
            }
    }

}
