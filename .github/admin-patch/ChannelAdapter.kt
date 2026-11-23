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
import com.caverock.androidsvg.SVG
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
                    connection.setRequestProperty("User-Agent", "RAHMANI/1.0")
                    connection.setRequestProperty("Accept", "image/avif,image/webp,image/svg+xml,image/png,image/*;q=0.9,*/*;q=0.5")
                    bitmap = try {
                        val bytes = connection.inputStream.use { it.readBytes() }
                        val type = connection.contentType.orEmpty().lowercase()
                        val prefix = bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart()
                        if (type.contains("svg") || prefix.startsWith("<svg") || prefix.startsWith("<?xml") && prefix.contains("<svg")) {
                            val svg = SVG.getFromString(bytes.toString(Charsets.UTF_8))
                            val rendered = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
                            val canvas = Canvas(rendered)
                            svg.renderToCanvas(canvas, 192, 192)
                            rendered
                        } else {
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }
                    } finally { connection.disconnect() }
                    if (bitmap != null) synchronized(cache) { cache[channel.logo] = bitmap!! }
                } catch (_: Exception) { }
                val result = bitmap
                if (result != null) holder.logo.post {
                    if (holder.logo.tag == channel.logo) holder.logo.setImageBitmap(result)
                }
            }
        }
    }

    // Always draw a local channel badge first. Remote logo URLs are optional:
    // several catalog entries point to SVG/redirect URLs that BitmapFactory cannot decode.
    private fun fallbackLogo(name: String): Bitmap {
        val size = 160
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val lower = name.lowercase()
        val isBein = lower.contains("bein")
        val isMbc = lower.contains("mbc")
        val isCountry = lower == "egypt" || lower == "algeria" || lower == "morocco"

        val backgroundColor = when {
            isBein -> Color.rgb(25, 28, 58)
            isMbc -> Color.rgb(250, 250, 250)
            isCountry -> Color.rgb(245, 245, 245)
            else -> Color.rgb(48, 42, 67)
        }
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = backgroundColor }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 22f, 22f, bg)

        if (isCountry) {
            val stripe = Paint(Paint.ANTI_ALIAS_FLAG)
            when (lower) {
                "egypt" -> {
                    stripe.color = Color.rgb(206, 17, 38)
                    canvas.drawRect(0f, 0f, size.toFloat(), 53f, stripe)
                    stripe.color = Color.WHITE
                    canvas.drawRect(0f, 53f, size.toFloat(), 107f, stripe)
                    stripe.color = Color.BLACK
                    canvas.drawRect(0f, 107f, size.toFloat(), size.toFloat(), stripe)
                }
                "algeria" -> {
                    stripe.color = Color.rgb(0, 100, 0)
                    canvas.drawRect(0f, 0f, 80f, size.toFloat(), stripe)
                    stripe.color = Color.WHITE
                    canvas.drawRect(80f, 0f, size.toFloat(), size.toFloat(), stripe)
                    stripe.color = Color.rgb(210, 16, 52)
                    canvas.drawCircle(83f, 80f, 25f, stripe)
                    stripe.color = Color.WHITE
                    canvas.drawCircle(92f, 73f, 21f, stripe)
                }
                else -> {
                    stripe.color = Color.rgb(193, 39, 45)
                    canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), stripe)
                    stripe.color = Color.rgb(0, 98, 51)
                    stripe.style = Paint.Style.STROKE
                    stripe.strokeWidth = 6f
                    val path = android.graphics.Path().apply {
                        moveTo(80f, 47f); lineTo(88f, 70f); lineTo(112f, 70f)
                        lineTo(93f, 84f); lineTo(100f, 108f); lineTo(80f, 94f)
                        lineTo(60f, 108f); lineTo(67f, 84f); lineTo(48f, 70f)
                        lineTo(72f, 70f); close()
                    }
                    canvas.drawPath(path, stripe)
                }
            }
            return bitmap
        }

        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isMbc) Color.rgb(35, 35, 45) else Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        if (isBein) {
            white.textSize = 49f
            canvas.drawText("beIN", 80f, 75f, white)
            white.textSize = 19f
            white.letterSpacing = 1.5f
            canvas.drawText("SPORTS", 80f, 101f, white)
            val number = Regex("(\\d+)").find(name)?.groupValues?.getOrNull(1)
            if (number != null) {
                white.textSize = 17f
                canvas.drawText(number, 80f, 133f, white)
            }
        } else if (isMbc) {
            val letters = listOf("M", "B", "C")
            val colors = listOf(Color.rgb(0, 150, 210), Color.rgb(235, 65, 105), Color.rgb(245, 155, 35))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = 55f
            }
            val xs = listOf(48f, 82f, 116f)
            letters.forEachIndexed { index, letter ->
                paint.color = colors[index]
                canvas.drawText(letter, xs[index], 91f, paint)
            }
            val suffix = name.substringAfter("MBC", "").trim()
            if (suffix.isNotBlank()) {
                white.color = Color.DKGRAY
                white.textSize = if (suffix.length > 10) 13f else 17f
                canvas.drawText(suffix.uppercase(), 80f, 126f, white)
            }
        } else {
            val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val label = when {
                parts.isEmpty() -> "TV"
                parts.size > 1 -> parts.take(2).joinToString(" ") { it.uppercase() }
                else -> parts[0].take(8).uppercase()
            }
            white.textSize = if (label.length > 9) 22f else 34f
            canvas.drawText(label, 80f, 91f, white)
        }
        return bitmap
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
