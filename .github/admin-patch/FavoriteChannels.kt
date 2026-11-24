package com.firas.tv

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONArray
import org.json.JSONObject

object FavoriteChannels {
    private const val PREFS = "rahmani_saved_channels"
    private const val KEY = "items"

    fun contains(context: Context, channel: Channel): Boolean =
        read(context).any { identity(it) == identity(channel) }

    @Synchronized
    fun toggle(context: Context, channel: Channel): Boolean {
        val items = read(context).toMutableList()
        val index = items.indexOfFirst { identity(it) == identity(channel) }
        val added = index < 0
        if (added) items.add(channel) else items.removeAt(index)
        write(context, items)
        return added
    }

    fun all(context: Context): List<Channel> = read(context)

    private fun identity(channel: Channel): String =
        channel.id.takeIf { it.isNotBlank() } ?: channel.url.ifBlank { channel.name }

    private fun read(context: Context): List<Channel> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Channel(item.optString("name"), item.optString("logo"), item.optString("url"),
                    item.optString("referer"), item.optString("id"))
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun write(context: Context, channels: List<Channel>) {
        val array = JSONArray()
        channels.forEach { channel ->
            array.put(JSONObject().put("name", channel.name).put("logo", channel.logo)
                .put("url", channel.url).put("referer", channel.referer).put("id", channel.id))
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY, array.toString()).apply()
    }
}

class MyListActivity : AppCompatActivity() {
    private lateinit var adapter: ChannelAdapter
    private lateinit var empty: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 8, 10))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(14))
        }
        header.addView(TextView(this).apply {
            text = "قائمتي"; textSize = 24f; setTextColor(Color.WHITE)
            setTypeface(typeface, Typeface.BOLD)
        })
        header.addView(TextView(this).apply {
            text = "القنوات التي حفظتها للرجوع إليها بسرعة"
            textSize = 12f; setTextColor(Color.rgb(155, 155, 165))
            setPadding(0, dp(5), 0, 0)
        })
        root.addView(header)
        val content = android.widget.FrameLayout(this)
        val recycler = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MyListActivity,
                if (resources.configuration.screenWidthDp >= 600) 4 else 2)
            setPadding(dp(10), dp(4), dp(10), dp(12))
            clipToPadding = false
        }
        empty = TextView(this).apply {
            text = "قائمتك فارغة حالياً\nاضغط على ☆ في بطاقة أي قناة لإضافتها هنا"
            gravity = Gravity.CENTER; textSize = 14f
            setTextColor(Color.rgb(170, 170, 180)); visibility = View.GONE
        }
        content.addView(recycler, android.widget.FrameLayout.LayoutParams(-1, -1))
        content.addView(empty, android.widget.FrameLayout.LayoutParams(-1, -1))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        adapter = ChannelAdapter(onClick = { channel ->
            if (channel.url.isBlank()) {
                android.widget.Toast.makeText(this, "رابط البث غير متوفر لهذه القناة", android.widget.Toast.LENGTH_SHORT).show()
            } else startActivity(android.content.Intent(this, PlayerActivity::class.java)
                .putExtra(PlayerActivity.EXTRA_NAME, channel.name)
                .putExtra(PlayerActivity.EXTRA_URL, channel.url)
                .putExtra(PlayerActivity.EXTRA_REFERER, channel.referer)
                .putExtra(PlayerActivity.EXTRA_CHANNEL_ID, channel.id)
                .putExtra(PlayerActivity.EXTRA_LOGO, channel.logo))
        }, onFavoriteChanged = { loadFavorites() })
        recycler.adapter = adapter
        loadFavorites()
    }

    override fun onResume() { super.onResume(); if (::adapter.isInitialized) loadFavorites() }

    private fun loadFavorites() {
        val items = FavoriteChannels.all(this)
        adapter.submitList(items)
        empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
