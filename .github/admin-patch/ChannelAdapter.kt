package com.firas.tv

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
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
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(7), dp(10), dp(7), dp(9))
            background = GradientDrawable().apply {
                setColor(Color.rgb(23, 24, 31))
                cornerRadius = dp(15).toFloat()
                setStroke(dp(1), Color.rgb(43, 45, 56))
            }
        }
        val logoFrame = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(13).toFloat()
            }
        }
        val logo = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = "شعار القناة"
        }
        logoFrame.addView(logo, LinearLayout.LayoutParams(dp(66), dp(66)))
        card.addView(logoFrame, LinearLayout.LayoutParams(dp(78), dp(78)))
        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(2), dp(7), dp(2), 0)
        }
        card.addView(title, LinearLayout.LayoutParams(-1, -2))
        val subtitle = TextView(context).apply {
            setTextColor(Color.rgb(157, 160, 172))
            textSize = 9f
            gravity = Gravity.CENTER
            text = "مباشر"
            setPadding(0, dp(3), 0, 0)
        }
        card.addView(subtitle, LinearLayout.LayoutParams(-1, -2))
        card.setOnClickListener { }
        return Holder(card, logo, title, subtitle)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val channel = getItem(position)
        holder.title.text = channel.name
        holder.card.setOnClickListener { onClick(channel) }
        holder.logo.tag = channel.logo
        val cached = synchronized(cache) { cache[channel.logo] }
        holder.logo.setImageBitmap(cached ?: fallbackLogo(channel.name))
        if (cached == null && channel.logo.startsWith("http", true)) {
            io.execute {
                var bitmap: Bitmap? = null
                try {
                    val connection = URL(channel.logo).openConnection() as HttpURLConnection
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = true
                    connection.setRequestProperty("User-Agent", "FIRAS-TV/4.0")
                    bitmap = try { connection.inputStream.use { BitmapFactory.decodeStream(it) } }
                    finally { connection.disconnect() }
                    if (bitmap != null) synchronized(cache) { cache[channel.logo] = bitmap!! }
                } catch (_: Exception) { }
                val result = bitmap
                if (result != null) holder.logo.post {
                    if (holder.logo.tag == channel.logo) holder.logo.setImageBitmap(result)
                }
            }
        }
    }

    private fun fallbackLogo(name: String): Bitmap {
        val size = 160
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(63, 35, 75) }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 24f, 24f, bg)
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val label = when {
            parts.isEmpty() -> "TV"
            parts.size > 1 -> parts.take(2).joinToString("") { it.take(1) }.uppercase()
            else -> parts[0].take(3).uppercase()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (label.length > 2) 42f else 56f
        }
        canvas.drawText(label, size / 2f, size / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        return bitmap
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
