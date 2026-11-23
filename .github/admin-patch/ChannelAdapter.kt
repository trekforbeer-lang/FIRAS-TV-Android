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
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class ChannelAdapter(private val onClick: (Channel) -> Unit) :
    ListAdapter<Channel, ChannelAdapter.Holder>(Diff) {

    private val io = Executors.newFixedThreadPool(3)
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val failedUntil = ConcurrentHashMap<String, Long>()
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
            minimumHeight = dp(152)
            elevation = dp(2).toFloat()
            background = android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Color.rgb(105, 43, 61)),
                GradientDrawable().apply {
                    setColor(Color.rgb(28, 18, 24))
                    cornerRadius = dp(16).toFloat()
                    setStroke(dp(1), Color.rgb(78, 43, 53))
                },
                null
            )
        }
        val logoFrame = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(13).toFloat()
                setStroke(dp(1), Color.rgb(92, 61, 70))
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
        holder.subtitle.text = if (channel.url.isBlank()) "لا يوجد رابط" else "رابط مضاف"
        holder.subtitle.setTextColor(if (channel.url.isBlank()) Color.rgb(255, 190, 132) else Color.rgb(255, 137, 158))
        holder.subtitle.background = GradientDrawable().apply {
            setColor(if (channel.url.isBlank()) Color.rgb(54, 39, 31) else Color.rgb(58, 28, 38))
            cornerRadius = dp(9).toFloat()
        }
        holder.subtitle.setPadding(dp(7), dp(3), dp(7), dp(3))
        holder.card.contentDescription = if (channel.url.isBlank()) "${channel.name}، الرابط غير مضاف" else "${channel.name}، جاهزة للتشغيل"
        holder.card.setOnClickListener { onClick(channel) }
        holder.logo.tag = channel.logo
        val cached = synchronized(cache) { cache[channel.logo] }
        val localPath = channel.logo.removePrefix("file://")
        val localBitmap = if (cached == null && localPath.isNotBlank() && !channel.logo.startsWith("http", true)) {
            try { BitmapFactory.decodeFile(localPath) } catch (_: Exception) { null }
        } else null
        if (localBitmap != null) synchronized(cache) { cache[channel.logo] = localBitmap }
        holder.logo.setImageBitmap(cached ?: localBitmap ?: fallbackLogo(channel.name))
        val retryAt = failedUntil[channel.logo] ?: 0L
        if (cached == null && localBitmap == null && channel.logo.startsWith("http", true) &&
            System.currentTimeMillis() >= retryAt && inFlight.add(channel.logo)) {
            val imageView = holder.logo
            val logoKey = MessageDigest.getInstance("SHA-256").digest(channel.logo.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            val cacheFile = java.io.File(holder.itemView.context.cacheDir, "rahmani-logo-$logoKey.png")
            io.execute {
                var bitmap: Bitmap? = null
                try {
                    if (cacheFile.isFile && cacheFile.length() in 1..(4L * 1024L * 1024L)) {
                        bitmap = BitmapFactory.decodeFile(cacheFile.absolutePath)
                    }
                    if (bitmap == null) {
                        val connection = URL(channel.logo).openConnection() as HttpURLConnection
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        connection.instanceFollowRedirects = true
                        connection.useCaches = true
                        connection.setRequestProperty("User-Agent", "RAHMANI-Android/4.0 (channel-logo)")
                        connection.setRequestProperty("Accept", "image/svg+xml,image/png,image/webp,image/jpeg,image/*;q=0.9,*/*;q=0.5")
                        bitmap = try {
                            val responseCode = connection.responseCode
                            if (responseCode !in 200..299) throw java.io.IOException("Logo HTTP $responseCode")
                            val bytes = connection.inputStream.use { input ->
                                val output = java.io.ByteArrayOutputStream()
                                val buffer = ByteArray(8192)
                                var total = 0
                                while (true) {
                                    val count = input.read(buffer)
                                    if (count < 0) break
                                    total += count
                                    if (total > 4 * 1024 * 1024) throw IllegalStateException("Logo too large")
                                    output.write(buffer, 0, count)
                                }
                                output.toByteArray()
                            }
                            val type = connection.contentType.orEmpty().lowercase()
                            val prefix = bytes.take(1024).toByteArray().toString(Charsets.UTF_8).trimStart()
                            when {
                                type.contains("svg") || prefix.startsWith("<svg") || prefix.startsWith("<?xml") && prefix.contains("<svg") -> {
                                    val svg = SVG.getFromString(bytes.toString(Charsets.UTF_8))
                                    val rendered = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
                                    svg.renderToCanvas(Canvas(rendered))
                                    rendered
                                }
                                type.contains("html") || prefix.startsWith("<!doctype html", true) || prefix.startsWith("<html", true) -> null
                                else -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            }
                        } finally { connection.disconnect() }
                        bitmap?.let { image ->
                            try {
                                cacheFile.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                            } catch (_: Exception) { }
                        }
                    }
                    if (bitmap != null) synchronized(cache) { cache[channel.logo] = bitmap!! }
                } catch (_: Exception) {
                    failedUntil[channel.logo] = System.currentTimeMillis() + 60_000L
                } finally {
                    if (bitmap == null) failedUntil[channel.logo] = System.currentTimeMillis() + 60_000L
                    inFlight.remove(channel.logo)
                }
                val result = bitmap
                if (result != null) imageView.post {
                    if (imageView.tag == channel.logo) imageView.setImageBitmap(result)
                }
            }
        }
    }

    // A neutral fallback is used only when the official logo cannot be loaded.
    // Never draw an imitation of a broadcaster's trademark as if it were its real logo.
    private fun fallbackLogo(name: String): Bitmap {
        val size = 160
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(39, 29, 35) }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 24f, 24f, background)

        val normalized = name.trim().replace(Regex("\\s+"), " ")
        val words = normalized.split(" ").filter { it.isNotBlank() }
        val initials = when {
            words.isEmpty() -> "TV"
            words.size >= 2 -> words.take(2).joinToString("") { it.first().uppercase() }
            else -> words.first().take(2).uppercase()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (initials.length > 1) 58f else 68f
        }
        canvas.drawText(initials, 80f, 101f, paint)
        return bitmap
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
