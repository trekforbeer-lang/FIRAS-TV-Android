package com.firas.tv

import android.content.pm.ActivityInfo
import android.net.Uri
import android.os.Bundle
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors
import java.net.HttpURLConnection
import java.net.URL

private data class DisplayChannel(
    val id: String,
    val name: String,
    val legacyUrl: String,
    val legacyHeaders: Map<String, String>,
    val logo: String,
    val advanced: StreamConfig?
)

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var watermark: ImageView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var playPause: Button
    private lateinit var fullscreen: Button
    private lateinit var zoom: Button
    private lateinit var retry: Button
    private lateinit var server: Button
    private lateinit var quality: Button
    private var channels: List<DisplayChannel> = emptyList()
    private var current: DisplayChannel? = null
    private var currentAdvanced: StreamConfig? = null
    private var resolving = false
    private var currentUrls: List<String> = emptyList()
    private var currentUrlIndex = 0
    private var isFullscreen = false
    private var recoveryAttempts = 0
    private var zoomed = true
    private var recoveryTask: Runnable? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            val channel = current
            if (channel != null && channel.advanced != null) refreshAdvancedAndPlay(channel)
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        // Start in edge-to-edge zoom mode so widescreen broadcasts fill the player area.
        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        title = findViewById(R.id.channelTitle)
        watermark = findViewById(R.id.channelWatermark)
        status = findViewById(R.id.statusText)
        list = findViewById(R.id.channelList)
        playPause = findViewById(R.id.playPauseButton)
        fullscreen = findViewById(R.id.fullscreenButton)
        zoom = findViewById(R.id.zoomButton)
        zoom.text = "ملاءمة"
        retry = findViewById(R.id.retryButton)
        server = findViewById(R.id.serverButton)
        quality = findViewById(R.id.qualityButton)

        val advanced = UniversalStreamEngine.load(this)
        channels = UniversalStreamEngine.loadLegacy(this).map { legacy ->
            DisplayChannel(
                legacy.id, legacy.name, legacy.stream, legacy.headers, legacy.logo,
                UniversalStreamEngine.match(advanced, legacy.id, legacy.name)
            )
        }
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.adapter = PlayerChannelAdapter(channels) { play(it) }

        playPause.setOnClickListener {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
        }
        fullscreen.setOnClickListener { toggleFullscreen() }
        zoom.setOnClickListener {
            zoomed = !zoomed
            playerView.resizeMode = if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            zoom.text = if (zoomed) "ملاءمة" else "زووم"
        }
        retry.setOnClickListener {
            recoveryAttempts = 0
            current?.let { play(it) }
        }
        server.setOnClickListener { nextCandidateOrRetry() }
        quality.setOnClickListener {
            val active = player
            if (active == null) Toast.makeText(this, "شغّل قناة أولاً", Toast.LENGTH_SHORT).show()
            else TrackSelectionDialogBuilder(this, "اختيار الجودة", active, C.TRACK_TYPE_VIDEO).build().show()
        }

        val requestedId = intent.getStringExtra(EXTRA_CHANNEL_ID).orEmpty()
        val requestedUrl = intent.getStringExtra(EXTRA_URL).orEmpty().trim()
        val requestedName = intent.getStringExtra(EXTRA_NAME).orEmpty()
        val requestedLogo = intent.getStringExtra(EXTRA_LOGO).orEmpty()
        val selected = resolveRequestedChannel(requestedId, requestedUrl, requestedName, requestedLogo)
        if (selected != null) {
            play(selected)
        } else {
            status.text = "لا توجد قناة صالحة. أضف رابط بث من لوحة الإدارة."
        }
    }

    private fun isHttpUrl(value: String): Boolean {
        if (value.isBlank()) return false
        return try {
            val uri = Uri.parse(value)
            (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
                !uri.host.isNullOrBlank()
        } catch (_: Exception) {
            false
        }
    }

    private fun resolveRequestedChannel(
        requestedId: String,
        requestedUrl: String,
        requestedName: String,
        requestedLogo: String
    ): DisplayChannel? {
        val existing = channels.firstOrNull {
            (requestedId.isNotBlank() && it.id == requestedId) ||
                (requestedUrl.isNotBlank() && it.legacyUrl == requestedUrl) ||
                (requestedName.isNotBlank() && it.name.equals(requestedName, true))
        }

        if (requestedUrl.isNotBlank()) {
            if (!isHttpUrl(requestedUrl)) return null
            val extraHeaders = linkedMapOf<String, String>()
            intent.getStringExtra(EXTRA_USER_AGENT)?.takeIf { it.isNotBlank() }?.let {
                extraHeaders["User-Agent"] = it
            }
            intent.getStringExtra(EXTRA_REFERER)?.takeIf { it.isNotBlank() }?.let {
                extraHeaders["Referer"] = it
            }
            intent.getStringExtra(EXTRA_COOKIE)?.takeIf { it.isNotBlank() }?.let {
                extraHeaders["Cookie"] = it
            }

            if (existing != null) {
                val merged = existing.legacyHeaders.toMutableMap()
                extraHeaders.forEach { (key, value) ->
                    merged.keys.firstOrNull { it.equals(key, true) }?.let(merged::remove)
                    merged[key] = value
                }
                val changedUrl = requestedUrl != existing.legacyUrl
                return existing.copy(
                    legacyUrl = requestedUrl,
                    legacyHeaders = merged,
                    logo = requestedLogo.ifBlank { existing.logo },
                    advanced = if (changedUrl) null else existing.advanced
                )
            }

            return DisplayChannel(
                id = requestedId.ifBlank { "external-channel" },
                name = requestedName.ifBlank { "بث مباشر" },
                legacyUrl = requestedUrl,
                legacyHeaders = extraHeaders,
                logo = requestedLogo,
                advanced = null
            )
        }

        return existing ?: channels.firstOrNull()
    }

    private fun requestHeaders(channel: DisplayChannel): Map<String, String> {
        val result = linkedMapOf<String, String>()
        fun putHeader(name: String, value: String) {
            result.keys.firstOrNull { it.equals(name, ignoreCase = true) }?.let(result::remove)
            result[name] = value
        }
        channel.legacyHeaders.forEach { (name, value) -> putHeader(name, value) }
        currentAdvanced?.let { config ->
            UniversalStreamEngine.mergedHeaders(config).forEach { (name, value) -> putHeader(name, value) }
        }
        return result
    }

    private fun ensurePlayer(channel: DisplayChannel) {
        playerView.player = null
        player?.release()
        player = null

        val headers = requestHeaders(channel).toMutableMap()
        val userAgentKey = headers.keys.firstOrNull { it.equals("User-Agent", true) }
        val userAgent = userAgentKey?.let { headers.remove(it) } ?: "FIRAS-TV/4.0"

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2500, 15000, 750, 1500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // Advanced channels use an isolated OkHttp cookie jar. The same DataSource
        // factory is used for playlists, child playlists, keys and media segments.
        val sourceFactory = if (currentAdvanced != null) {
            val advancedConfig = currentAdvanced!!.copy(
                headers = requestHeaders(channel),
                cookie = ""
            )
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(UniversalStreamEngine.okhttpFactory(advancedConfig))
        } else {
            val httpFactory = DefaultHttpDataSource.Factory()
                .setUserAgent(userAgent)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(25000)
            DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory)
        }

        // Allow high-resolution streams up to 4K while keeping adaptive bitrate
        // selection enabled so playback can step down when the connection slows.
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(3840, 2160)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setForceHighestSupportedBitrate(false)
                    .build()
            )
        }

        player = ExoPlayer.Builder(this)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sourceFactory)
            .build().also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        var responseCode: Int? = null
                        var cause: Throwable? = error
                        while (cause != null) {
                            if (cause is HttpDataSource.InvalidResponseCodeException) {
                                responseCode = cause.responseCode
                                break
                            }
                            cause = cause.cause
                        }
                        status.text = when (responseCode) {
                            401, 403 -> "رفض المصدر الوصول (HTTP $responseCode). تحقق من Cookie وبيانات القناة في لوحة الإدارة."
                            else -> "تعذر تشغيل البث، جارٍ إعادة الاتصال..."
                        }
                        scheduleRecovery()
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        status.text = when (state) {
                            Player.STATE_BUFFERING -> "جارٍ تحميل البث..."
                            Player.STATE_READY -> {
                                recoveryAttempts = 0
                                recoveryTask?.let(handler::removeCallbacks)
                                recoveryTask = null
                                "FIRAS TV • البث جاهز"
                            }
                            Player.STATE_ENDED -> "انتهى البث"
                            else -> "FIRAS TV • بث مباشر"
                        }
                        playPause.text = if (exo.isPlaying) "إيقاف مؤقت" else "تشغيل"
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playPause.text = if (isPlaying) "إيقاف مؤقت" else "تشغيل"
                    }
                })
            }
    }

    private fun play(channel: DisplayChannel) {
        recoveryTask?.let(handler::removeCallbacks)
        recoveryTask = null
        current = channel
        currentAdvanced = channel.advanced
        resolving = false
        recoveryAttempts = 0
        currentUrlIndex = 0
        currentUrls = emptyList()
        title.text = channel.name
        loadWatermark(channel.logo)
        ensurePlayer(channel)

        if (currentAdvanced == null) {
            if (!isHttpUrl(channel.legacyUrl)) {
                status.text = "رابط القناة غير صالح. تحقق من لوحة الإدارة."
                return
            }
            currentUrls = listOf(channel.legacyUrl)
            playUrl(channel.legacyUrl)
        } else {
            status.text = "جارٍ تجهيز القناة..."
            resolveAndPlay(channel, keepPosition = false)
        }
    }

    private fun refreshAdvancedAndPlay(channel: DisplayChannel) {
        worker.execute {
            val latest = UniversalStreamEngine.load(this@PlayerActivity)
            val updated = UniversalStreamEngine.match(latest, channel.id, channel.name) ?: channel.advanced
            runOnUiThread {
                if (isFinishing || current?.id != channel.id) return@runOnUiThread
                if (updated != null) {
                    val changed = updated != currentAdvanced
                    val position = player?.currentPosition ?: 0L
                    currentAdvanced = updated
                    if (changed) ensurePlayer(channel)
                    resolveAndPlay(channel, keepPosition = !changed, resumePosition = if (changed) position else null)
                }
            }
        }
    }

    private fun resolveAndPlay(channel: DisplayChannel, keepPosition: Boolean, resumePosition: Long? = null) {
        val config = currentAdvanced ?: return
        if (resolving) return
        resolving = true
        val oldPosition = resumePosition ?: if (keepPosition) player?.currentPosition ?: 0L else 0L
        status.text = "جارٍ تحديث رابط البث..."
        worker.execute {
            val urls = UniversalStreamEngine.resolve(config)
            runOnUiThread {
                resolving = false
                if (isFinishing || current?.id != channel.id) return@runOnUiThread
                val validUrls = urls.filter(::isHttpUrl)
                if (validUrls.isEmpty()) {
                    status.text = "لم يتم العثور على رابط بث صالح"
                    scheduleRecovery()
                    return@runOnUiThread
                }
                val previousUrl = currentUrls.firstOrNull()
                currentUrls = validUrls
                currentUrlIndex = 0
                if (keepPosition && previousUrl == validUrls.first()) {
                    status.text = "FIRAS TV • البث مستمر"
                    return@runOnUiThread
                }
                playUrl(validUrls.first(), oldPosition)
            }
        }
    }

    private fun playUrl(url: String, position: Long = 0L) {
        if (!isHttpUrl(url)) {
            status.text = "رابط البث غير صالح"
            return
        }
        val item = MediaItem.Builder()
            .setUri(url)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setMediaId(current?.id ?: "firas")
            .build()
        player?.apply {
            stop()
            clearMediaItems()
            setMediaItem(item)
            prepare()
            if (position > 0L) seekTo(position)
            playWhenReady = true
        }
        status.text = "جارٍ الاتصال..."
    }

    private fun scheduleRecovery() {
        if (recoveryTask != null || isFinishing || isDestroyed) return
        if (recoveryAttempts >= 5) {
            status.text = "تعذر الاتصال بعد 5 محاولات. تحقق من الرابط أو اضغط تحديث."
            return
        }
        recoveryAttempts++
        val delay = (1000L shl (recoveryAttempts - 1)).coerceAtMost(16000L)
        status.text = "انقطع البث، إعادة المحاولة ${recoveryAttempts}/5..."
        val task = Runnable {
            recoveryTask = null
            if (isFinishing || isDestroyed || current == null) return@Runnable
            if (currentAdvanced != null) {
                resolving = false
                current?.let { resolveAndPlay(it, keepPosition = false) }
            } else {
                current?.let { playUrl(it.legacyUrl) }
            }
        }
        recoveryTask = task
        handler.postDelayed(task, delay)
    }

    private fun loadWatermark(url: String) {
        watermark.setImageDrawable(null)
        watermark.visibility = if (isHttpUrl(url)) View.VISIBLE else View.GONE
        if (watermark.visibility == View.GONE) return
        val expected = url
        worker.execute {
            var bitmap: android.graphics.Bitmap? = null
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                connection.setRequestProperty("User-Agent", "FIRAS-TV/4.0")
                bitmap = try {
                    connection.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    connection.disconnect()
                }
            } catch (_: Exception) {
            }
            val result = bitmap
            runOnUiThread {
                if (!isFinishing && current?.logo == expected) {
                    if (result != null) watermark.setImageBitmap(result)
                    else watermark.visibility = View.GONE
                } else {
                    result?.recycle()
                }
            }
        }
    }

    private fun nextCandidateOrRetry() {
        val channel = current ?: return
        val config = currentAdvanced ?: run {
            recoveryAttempts = 0
            playUrl(channel.legacyUrl)
            return
        }
        if (currentUrlIndex + 1 < currentUrls.size) {
            currentUrlIndex++
            playUrl(currentUrls[currentUrlIndex])
        } else {
            status.text = "جارٍ تحديث رابط القناة..."
            worker.execute {
                val refreshed = UniversalStreamEngine.resolve(config).filter(::isHttpUrl)
                runOnUiThread {
                    if (!isFinishing && current?.id == channel.id && refreshed.isNotEmpty()) {
                        currentUrls = refreshed
                        currentUrlIndex = 0
                        recoveryAttempts = 0
                        playUrl(refreshed.first())
                    } else if (!isFinishing && current?.id == channel.id) {
                        status.text = "تعذر الوصول إلى مصدر البث"
                        scheduleRecovery()
                    }
                }
            }
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        requestedOrientation = if (isFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val flags = if (isFullscreen) {
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
        window.decorView.systemUiVisibility = flags
        findViewById<View>(R.id.topBar).visibility = if (isFullscreen) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottomPanel).visibility = if (isFullscreen) View.GONE else View.VISIBLE
        fullscreen.text = if (isFullscreen) "تصغير" else "ملء الشاشة"
    }

    override fun onResume() {
        super.onResume()
        if (player != null) player?.playWhenReady = true
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, 5 * 60 * 1000L)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recoveryTask = null
        playerView.player = null
        player?.release()
        player = null
        worker.shutdownNow()
        super.onDestroy()
    }

    private class PlayerChannelAdapter(
        private val items: List<DisplayChannel>,
        private val click: (DisplayChannel) -> Unit
    ) : RecyclerView.Adapter<ChannelHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ChannelHolder {
            val button = Button(parent.context).apply {
                isAllCaps = false
                textSize = 14f
                minHeight = 52
                setPadding(20, 8, 20, 8)
            }
            return ChannelHolder(button)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            (holder.itemView as Button).text = items[position].name
            holder.itemView.setOnClickListener { click(items[position]) }
        }
    }

    private class ChannelHolder(view: View) : RecyclerView.ViewHolder(view)

    companion object {
        const val EXTRA_NAME = "channel_name"
        const val EXTRA_URL = "channel_url"
        const val EXTRA_REFERER = "channel_referer"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_LOGO = "channel_logo"
        const val EXTRA_USER_AGENT = "channel_user_agent"
        const val EXTRA_COOKIE = "channel_cookie"
    }
}
