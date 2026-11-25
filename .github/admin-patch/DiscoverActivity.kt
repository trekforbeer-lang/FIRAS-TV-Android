package com.firas.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

class DiscoverActivity : AppCompatActivity() {
    private data class Entry(val category: String, val channel: Channel)

    private val entries = mutableListOf<Entry>()
    private var selectedCategory = "الكل"
    private var query = ""
    private lateinit var adapter: ChannelAdapter
    private lateinit var emptyView: TextView
    private lateinit var countView: TextView
    private lateinit var categoryRow: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(0, 0, 0)
        window.navigationBarColor = Color.rgb(0, 0, 0)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val sections = ChannelRepository.load(this)
        sections.forEach { section -> section.items.forEach { entries.add(Entry(section.title, it)) } }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0, 0, 0))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(18), dp(16), dp(10))
        }
        setContentView(root)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleBlock = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        titleBlock.addView(TextView(this).apply {
            text = "اكتشف"
            setTextColor(Color.WHITE)
            textSize = 25f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleBlock.addView(TextView(this).apply {
            text = "ابحث بين القنوات وتصفّح التصنيفات"
            setTextColor(Color.rgb(157, 166, 184))
            textSize = 12f
            setPadding(0, dp(4), 0, 0)
        })
        top.addView(titleBlock)
        top.addView(TextView(this).apply {
            text = "R"
            gravity = Gravity.CENTER
            textSize = 21f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(229, 9, 20))
            }
        }, LinearLayout.LayoutParams(dp(46), dp(46)))
        root.addView(top)

        val search = EditText(this).apply {
            hint = "ابحث عن قناة أو تصنيف..."
            setHintTextColor(Color.rgb(133, 145, 164))
            setTextColor(Color.WHITE)
            textSize = 14f
            setSingleLine(true)
            setPadding(dp(15), 0, dp(15), 0)
            background = GradientDrawable().apply {
                setColor(Color.rgb(14, 14, 14))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.rgb(90, 23, 29))
            }
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(50)).apply { topMargin = dp(18) })

        val categoryScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
        }
        categoryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryScroll.addView(categoryRow)
        root.addView(categoryScroll, LinearLayout.LayoutParams(-1, dp(52)).apply { topMargin = dp(9) })

        val resultsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }
        resultsHeader.addView(TextView(this).apply {
            text = "القنوات المتاحة"
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        countView = TextView(this).apply {
            textSize = 11f
            setTextColor(Color.rgb(229, 9, 20))
        }
        resultsHeader.addView(countView)
        root.addView(resultsHeader)

        val content = FrameLayout(this)
        adapter = ChannelAdapter(onClick = { channel -> openChannel(channel) }, onFavoriteChanged = { renderResults() })
        val recycler = RecyclerView(this).apply {
            layoutManager = androidx.recyclerview.widget.LinearLayoutManager(this@DiscoverActivity)
            adapter = this@DiscoverActivity.adapter
            setHasFixedSize(false)
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(12))
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        content.addView(recycler, FrameLayout.LayoutParams(-1, -1))
        emptyView = TextView(this).apply {
            text = "لا توجد نتائج مطابقة\nجرّب كلمة أخرى أو اختر تصنيفاً مختلفاً"
            gravity = Gravity.CENTER
            textSize = 13f
            setTextColor(Color.rgb(157, 166, 184))
            visibility = View.GONE
        }
        content.addView(emptyView, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        buildCategories(sections.map { it.title }.distinct())
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                renderResults()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        renderResults()
    }

    private fun buildCategories(categories: List<String>) {
        categoryRow.removeAllViews()
        (listOf("الكل") + categories).forEach { category ->
            val selected = category == selectedCategory
            val chip = TextView(this).apply {
                text = category
                gravity = Gravity.CENTER
                textSize = 11f
                typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (selected) Color.WHITE else Color.rgb(181, 185, 198))
                setPadding(dp(15), 0, dp(15), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(18).toFloat()
                    setColor(if (selected) Color.rgb(229, 9, 20) else Color.rgb(31, 32, 43))
                    if (selected) setStroke(dp(1), Color.rgb(229, 9, 20))
                }
                isClickable = true
                isFocusable = true
                contentDescription = "تصنيف $category"
                setOnClickListener {
                    selectedCategory = category
                    buildCategories(categories)
                    renderResults()
                }
            }
            categoryRow.addView(chip, LinearLayout.LayoutParams(-2, dp(36)).apply {
                marginEnd = dp(8)
                topMargin = dp(5)
                bottomMargin = dp(5)
            })
        }
    }

    private fun renderResults() {
        val result = entries.asSequence()
            .filter { selectedCategory == "الكل" || it.category == selectedCategory }
            .filter { query.isBlank() || it.channel.name.contains(query, true) || it.category.contains(query, true) }
            .map { it.channel }
            .toList()
        adapter.submitList(result)
        countView.text = "${result.size} قناة"
        emptyView.visibility = if (result.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun openChannel(channel: Channel) {
        if (channel.url.isBlank()) {
            Toast.makeText(this, "رابط البث غير مضاف لهذه القناة بعد.", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, PlayerActivity::class.java)
            .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
            .putExtra(PlayerActivity.EXTRA_URL, channel.url)
            .putExtra(PlayerActivity.EXTRA_REFERER, channel.referer)
            .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
            .putExtra(PlayerActivity.EXTRA_LOGO, channel.logo))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
