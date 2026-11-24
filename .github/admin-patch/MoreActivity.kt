package com.firas.tv

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MoreActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(9, 10, 16)
        window.navigationBarColor = Color.rgb(9, 10, 16)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(9, 10, 16))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(28))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(18))
        }
        header.addView(TextView(this).apply {
            text = "المزيد"
            textSize = 27f
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(this).apply {
            text = "إعدادات التطبيق وخدمات RAHMANI"
            textSize = 12f
            setTextColor(Color.rgb(157, 166, 184))
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(header)

        section(content, "المشاهدة")
        item(content, "⚽", "الرياضة والمباريات", "تابع المباريات والنتائج", Color.rgb(225, 72, 111)) {
            startActivity(Intent(this, SportsActivity::class.java))
        }
        item(content, "★", "قائمتي", "القنوات التي حفظتها", Color.rgb(190, 126, 54)) {
            startActivity(Intent(this, MyListActivity::class.java))
        }
        item(content, "▶", "الأفلام", "تصفح مكتبة الأفلام", Color.rgb(72, 125, 185)) {
            startActivity(Intent(this, MoviesActivity::class.java))
        }

        section(content, "التخصيص")
        item(content, "♥", "اهتماماتي الرياضية", "اختر الفرق التي تتابعها", Color.rgb(160, 83, 139)) {
            startActivity(Intent(this, TeamPreferencesActivity::class.java))
        }
        item(content, "⌕", "اكتشف القنوات", "ابحث حسب الاسم والتصنيف", Color.rgb(65, 145, 137)) {
            startActivity(Intent(this, DiscoverActivity::class.java))
        }

        section(content, "الإدارة")
        item(content, "⚙", "لوحة الإدارة", "إدارة القنوات والروابط والشعارات", Color.rgb(99, 108, 133)) {
            startActivity(Intent(this, AdminActivity::class.java))
        }

        val about = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(20), dp(16), dp(18))
            background = panelBackground(Color.rgb(21, 23, 32))
        }
        about.addView(TextView(this).apply {
            text = "RAHMANI"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(238, 82, 119))
            gravity = Gravity.CENTER
        })
        about.addView(TextView(this).apply {
            text = "تطبيق ترفيه ومشاهدة"
            textSize = 11f
            setTextColor(Color.rgb(157, 166, 184))
            gravity = Gravity.CENTER
            setPadding(0, dp(5), 0, 0)
        })
        content.addView(about, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(255, 145, 168))
            setPadding(dp(3), dp(10), dp(3), dp(9))
        })
    }

    private fun item(parent: LinearLayout, symbol: String, title: String, detail: String, accent: Int, action: () -> Unit) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            background = panelBackground(Color.rgb(24, 25, 35))
            isClickable = true
            isFocusable = true
            setOnClickListener { action() }
        }
        val icon = TextView(this).apply {
            text = symbol
            gravity = Gravity.CENTER
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(accent)
            }
        }
        row.addView(icon, LinearLayout.LayoutParams(dp(46), dp(46)))
        val labels = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
        }
        labels.addView(TextView(this).apply {
            text = title
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        })
        labels.addView(TextView(this).apply {
            text = detail
            textSize = 10f
            setTextColor(Color.rgb(153, 161, 179))
            setPadding(0, dp(4), 0, 0)
        })
        row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(TextView(this).apply {
            text = "‹"
            textSize = 25f
            setTextColor(Color.rgb(151, 157, 174))
        })
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(9) })
    }

    private fun panelBackground(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.rgb(49, 42, 53))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()
}
