package com.firas.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class ChannelAdapter(private val onClick: (Channel) -> Unit) :
    ListAdapter<Channel, ChannelAdapter.Holder>(Diff) {

    private val io = Executors.newFixedThreadPool(3)
    private val cache = object : LinkedHashMap<String, Bitmap>(48, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 48
    }

    object Diff : DiffUtil.ItemCallback<Channel>() {
        override fun areItemsTheSame(oldItem: Channel, newItem: Channel) =
            if (oldItem.id.isNotBlank() && newItem.id.isNotBlank()) oldItem.id == newItem.id
            else oldItem.url == newItem.url
        override fun areContentsTheSame(oldItem: Channel, newItem: Channel) = oldItem == newItem
    }

    class Holder(val card: LinearLayout, val logo: ImageView, val title: TextView, val subtitle: TextView) :
        RecyclerView.ViewHolder(card)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val card = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(23, 24, 29))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.rgb(43, 44, 52))
            }
        }
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(5), dp(5), dp(5), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
            }
        }
        card.addView(logo, LinearLayout.LayoutParams(dp(58), dp(58)))
        val texts = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(14), 0, 0, 0)
        }
        val title = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val subtitle = TextView(context).apply {
            setTextColor(Color.LTGRAY); textSize = 11f; maxLines = 1
        }
        texts.addView(title)
        texts.addView(subtitle)
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        return Holder(card, logo, title, subtitle)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = getItem(position)
        holder.title.text = channel.name
        holder.subtitle.text = "قناة مباشرة  •  اضغط للمشاهدة"
        holder.card.setOnClickListener { onClick(channel) }
        holder.logo.tag = channel.logo
        val cached = synchronized(cache) { cache[channel.logo] }
        holder.logo.setImageBitmap(cached)
        if (cached == null && channel.logo.startsWith("http", true)) {
            io.execute {
                try {
                    val connection = URL(channel.logo).openConnection() as HttpURLConnection
                    connection.connectTimeout = 7000
                    connection.readTimeout = 7000
                    connection.setRequestProperty("User-Agent", "FIRAS-TV/4.0")
                    val bitmap = try { connection.inputStream.use { BitmapFactory.decodeStream(it) } }
                    finally { connection.disconnect() }
                    if (bitmap != null) {
                        synchronized(cache) { cache[channel.logo] = bitmap }
                        holder.logo.post {
                            if (holder.logo.tag == channel.logo) holder.logo.setImageBitmap(bitmap)
                        }
                    }
                } catch (_: Exception) { }
            }
        }
        if (cached == null) {
            holder.logo.setImageDrawable(GradientDrawable().apply {
                setColor(Color.rgb(55, 34, 75)); cornerRadius = dp(10).toFloat()
            })
        }
    }

    private fun dp(value: Int) = (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
