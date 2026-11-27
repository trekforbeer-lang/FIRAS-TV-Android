package com.firas.tv

import android.content.pm.ActivityInfo
import android.app.PictureInPictureParams
import android.os.Build
import android.util.Rational
import android.util.Log
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Bundle
import android.view.TextureView
import android.view.ViewGroup
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaSession
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
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
    val advanced: StreamConfig?,
    val qualityUrls: Map<String, String> = emptyMap(),
    val defaultUrl: String = legacyUrl
)

@OptIn(UnstableApi::class)
class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private lateinit var playerView: PlayerView
    private lateinit var texturePlayerView: PlayerView
    private lateinit var surfacePlayerView: PlayerView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var list: RecyclerView
    private lateinit var playPause: Button
    private lateinit var fullscreen: Button
    private lateinit var zoom: Button
    private lateinit var retry: Button
    private lateinit var server: Button
    private lateinit var quality: Button
    private lateinit var centerControls: View
    private lateinit var lockButton: Button
    private var controlsLocked = false
    private var channels: List<DisplayChannel> = emptyList()
    private var current: DisplayChannel? = null
    private var currentAdvanced: StreamConfig? = null
    private var resolving = false
    private var currentUrls: List<String> = emptyList()
    private var currentUrlIndex = 0
    // Start video playback in immersive landscape, like a dedicated streaming app.
    private var isFullscreen = true
    private var resumePlaybackOnResume = false
    private var recoveryAttempts = 0
    private var currentVideoResolution = ""
    private var firstVideoFrameRendered = false
    private var surfaceFallbackAttempted = false
    private val firstFrameTimeout = Runnable {
        val active = player
        if (!firstVideoFrameRendered && active?.playbackState == Player.STATE_READY && current != null) {
            if (switchToSurfaceViewFallback()) return@Runnable
            showLoading(false)
            status.text = "البث متصل لكن لم تظهر صورة الفيديو • راجع سجل Decoder والترميز"
        }
    }
    private var displayMode = 0
    private val playerPrefs by lazy { getSharedPreferences("rahmani_player_preferences", MODE_PRIVATE) }
    private var recoveryTask: Runnable? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var networkWasLost = false
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val hideOverlayTask = Runnable {
        if (player?.isPlaying == true && !controlsLocked && findViewById<View>(R.id.channelPanel).visibility != View.VISIBLE) {
            setOverlayVisible(false)
        }
    }
    private val refreshTask = object : Runnable {
        override fun run() {
            val channel = current
            if (channel != null && channel.advanced != null) refreshAdvancedAndPlay(channel)
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        setContentView(R.layout.activity_player)
        applyFullscreenSystemUi()
        texturePlayerView = findViewById(R.id.playerView)
        surfacePlayerView = findViewById(R.id.playerViewSurface)
        playerView = texturePlayerView
        surfacePlayerView.visibility = View.GONE
        // Initialize every view used by applyDisplayMode before calling it.
        zoom = findViewById(R.id.zoomButton)
        // Default to Fill on a fresh install; preserve the viewer's last selected mode.
        // Mode order: Fit, Zoom, Fill, 16:9, 4:3.
        displayMode = playerPrefs.getInt("display_mode_v9", 1).coerceIn(0, 2)
        applyDisplayMode()
        title = findViewById(R.id.channelTitle)
        status = findViewById(R.id.statusText)
        loadingIndicator = findViewById(R.id.loadingIndicator)
        list = findViewById(R.id.channelList)
        playPause = findViewById(R.id.playPauseButton)
        fullscreen = findViewById(R.id.fullscreenButton)
        fullscreen.text = "⤡"
        retry = findViewById(R.id.retryButton)
        server = findViewById(R.id.serverButton)
        quality = findViewById(R.id.qualityButton)
        centerControls = findViewById(R.id.centerControls)
        lockButton = findViewById(R.id.lockButton)
        findViewById<Button>(R.id.playerBackButton).setOnClickListener { handleBackAction() }
        lockButton.setOnClickListener { controlsLocked = !controlsLocked; setOverlayVisible(!controlsLocked); lockButton.text = if (controlsLocked) "🔒" else "♙" }
        findViewById<Button>(R.id.pictureInPictureButton).setOnClickListener { enterPictureInPictureIfSupported() }
        findViewById<Button>(R.id.channelListButton).setOnClickListener {
            val opening = findViewById<View>(R.id.channelPanel).visibility != View.VISIBLE
            findViewById<View>(R.id.channelPanel).visibility = if (opening) View.VISIBLE else View.GONE
            if (opening) {
                findViewById<View>(R.id.channelPanel).alpha = 1f
                setOverlayVisible(true)
            } else {
                scheduleOverlayHide()
            }
        }
        findViewById<Button>(R.id.subtitleButton).setOnClickListener {
            val active = player
            if (active == null) {
                Toast.makeText(this, "شغّل قناة أولاً", Toast.LENGTH_SHORT).show()
            } else {
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("الصوت والترجمة")
                    .setItems(arrayOf("اختيار الترجمة", "اختيار مسار الصوت")) { _, which ->
                        val trackType = if (which == 0) C.TRACK_TYPE_TEXT else C.TRACK_TYPE_AUDIO
                        val dialogTitle = if (which == 0) "الترجمة" else "مسار الصوت"
                        TrackSelectionDialogBuilder(this, dialogTitle, active, trackType).build().show()
                    }
                    .setNegativeButton("إغلاق", null)
                    .show()
            }
        }
        findViewById<Button>(R.id.previousChannelButton).setOnClickListener { changeChannel(-1) }
        findViewById<Button>(R.id.nextChannelButton).setOnClickListener { changeChannel(1) }
        installPlayerSurfaceClickListener(texturePlayerView)
        installPlayerSurfaceClickListener(surfacePlayerView)

        val advanced = UniversalStreamEngine.load(this)
        channels = UniversalStreamEngine.loadLegacy(this).map { legacy ->
            DisplayChannel(
                legacy.id, legacy.name, legacy.stream, legacy.headers, legacy.logo,
                UniversalStreamEngine.match(advanced, legacy.id, legacy.name), legacy.qualityUrls
            )
        }
        list.layoutManager = LinearLayoutManager(this, RecyclerView.VERTICAL, false)
        list.adapter = PlayerChannelAdapter(channels) {
            play(it)
            findViewById<View>(R.id.channelPanel).visibility = View.GONE
            setOverlayVisible(true)
        }

        playPause.setOnClickListener {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
        }
        fullscreen.setOnClickListener { toggleFullscreen() }
        zoom.setOnClickListener { showDisplayModePicker() }
        retry.setOnClickListener { hardRefreshCurrentStream(resetRecoveryAttempts = true) }
        server.setOnClickListener { nextCandidateOrRetry() }
        quality.setOnClickListener { showQualityPicker() }
        findViewById<Button>(R.id.imageSettingsButton).setOnClickListener { showImageSettings() }

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

        // Do not silently start an unrelated channel when an explicit channel request is invalid.
        // Only use the first available channel when the player was opened without a selection.
        return existing ?: if (requestedId.isBlank() && requestedName.isBlank() && requestedUrl.isBlank()) {
            channels.firstOrNull()
        } else {
            null
        }
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
        if (::texturePlayerView.isInitialized && playerView !== texturePlayerView) {
            playerView.player = null
            playerView.visibility = View.GONE
            texturePlayerView.visibility = View.VISIBLE
            playerView = texturePlayerView
        }
        surfaceFallbackAttempted = false
        playerView.player = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null

        val headers = requestHeaders(channel).toMutableMap()
        val userAgentKey = headers.keys.firstOrNull { it.equals("User-Agent", true) }
        val userAgent = userAgentKey?.let { headers.remove(it) } ?: "RAHMANI/1.0"

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(2500, 30000, 750, 1500)
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
                    // Prefer the highest quality actually exposed by the stream; this cannot
                    // create HD detail if the channel source itself is low resolution.
                    .setForceHighestSupportedBitrate(true)
                    // Keep subtitles off by default for a clean live-view screen.
                    // Viewers can enable a subtitle track from the player menu.
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            )
        }

        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)

        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sourceFactory)
            .build().also { exo ->
                playerView.player = exo
                applyVideoAdjustments(exo)
                exo.setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    true
                )
                mediaSession = MediaSession.Builder(this, exo).build()
                exo.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onRenderedFirstFrame(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        firstVideoFrameRendered = true
                        handler.removeCallbacks(firstFrameTimeout)
                        showLoading(false)
                        Log.i(TAG, "First video frame rendered; surface=${playerView.javaClass.simpleName}")
                        if (exo.playbackState == Player.STATE_READY) {
                            status.text = if (currentVideoResolution.isNotEmpty()) {
                                "RAHMANI TV • ${currentVideoResolution}"
                            } else "RAHMANI TV • الصورة تعمل"
                        }
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        Log.i(TAG, "Video decoder=$decoderName init=${initializationDurationMs}ms")
                    }

                    override fun onVideoInputFormatChanged(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        format: androidx.media3.common.Format,
                        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?
                    ) {
                        val color = format.colorInfo
                        Log.i(TAG, "Video format mime=${format.sampleMimeType}, codecs=${format.codecs}, " +
                            "size=${format.width}x${format.height}, fps=${format.frameRate}, " +
                            "colorRange=${color?.colorRange}, colorSpace=${color?.colorSpace}, colorTransfer=${color?.colorTransfer}")
                    }

                    override fun onVideoCodecError(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        videoCodecError: Exception
                    ) {
                        Log.e(TAG, "Video codec error", videoCodecError)
                    }
                })
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(TAG, "Player failure: ${error.message}", error)
                        var responseCode: Int? = null
                        var cause: Throwable? = error
                        while (cause != null) {
                            if (cause is HttpDataSource.InvalidResponseCodeException) {
                                responseCode = cause.responseCode
                                break
                            }
                            cause = cause.cause
                        }
                        showLoading(true)
                        status.text = when (responseCode) {
                            401, 403 -> "رفض المصدر الوصول (HTTP $responseCode). تحقق من Cookie وبيانات القناة في لوحة الإدارة."
                            else -> "تعذر تشغيل البث، جارٍ إعادة الاتصال..."
                        }
                        scheduleRecovery()
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        currentVideoResolution = if (videoSize.width > 0 && videoSize.height > 0) {
                            "${videoSize.width}×${videoSize.height}"
                        } else ""
                        val ratio = if (videoSize.height > 0) videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height else 0f
                        Log.i(TAG, "Video size=${videoSize.width}x${videoSize.height}, ratio=$ratio, rotation=${videoSize.unappliedRotationDegrees}")
                        // Re-measure the active texture/surface after the stream reports
                        // its real dimensions; keep the requested full-frame stretch.
                        playerView.post { applyDisplayMode() }
                        if (exo.playbackState == Player.STATE_READY) {
                            status.text = if (currentVideoResolution.isNotEmpty()) {
                                "RAHMANI TV • ${currentVideoResolution}"
                            } else "RAHMANI TV • البث جاهز"
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        status.text = when (state) {
                            Player.STATE_BUFFERING -> {
                                showLoading(true)
                                "جارٍ تحميل البث..."
                            }
                            Player.STATE_READY -> {
                                showLoading(false)
                                recoveryAttempts = 0
                                recoveryTask?.let(handler::removeCallbacks)
                                recoveryTask = null
                                handler.removeCallbacks(firstFrameTimeout)
                                if (!firstVideoFrameRendered) handler.postDelayed(firstFrameTimeout, 12000L)
                                if (currentVideoResolution.isNotEmpty()) "RAHMANI TV • ${currentVideoResolution}" else "RAHMANI TV • البث جاهز"
                            }
                            Player.STATE_ENDED -> {
                                showLoading(false)
                                "انتهى البث"
                            }
                            else -> "RAHMANI TV • بث مباشر"
                        }
                        playPause.text = if (exo.isPlaying) "Ⅱ" else "▶"
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        playPause.text = if (isPlaying) "Ⅱ" else "▶"
                        if (isPlaying) {
                            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            scheduleOverlayHide()
                        } else {
                            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            handler.removeCallbacks(hideOverlayTask)
                        }
                    }
                })
            }
    }

    private fun showQualityPicker() {
        val channel = current ?: run {
            Toast.makeText(this, "شغّل قناة أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        val entries = mutableListOf<Pair<String, String>>()
        if (isHttpUrl(channel.defaultUrl)) entries += "الرابط الأساسي" to channel.defaultUrl
        channel.qualityUrls.forEach { (label, url) -> if (isHttpUrl(url)) entries += label to url }
        if (entries.isEmpty()) {
            val active = player
            if (active != null) TrackSelectionDialogBuilder(this, "مسارات الجودة داخل البث", active, C.TRACK_TYPE_VIDEO).build().show()
            else Toast.makeText(this, "لا توجد روابط جودة لهذه القناة", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = entries.map { (label, _) -> if (label == "الرابط الأساسي") label else "جودة $label" }.toTypedArray()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("اختر جودة المشاهدة")
            .setItems(labels) { _, which ->
                val selected = channel.copy(
                    legacyUrl = entries[which].second,
                    advanced = if (entries[which].first == "الرابط الأساسي") channel.advanced else null
                )
                playerPrefs.edit().putString("quality_${channel.id}", entries[which].first).apply()
                current = selected
                currentAdvanced = null
                recoveryAttempts = 0
                play(selected)
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun play(channel: DisplayChannel) {
        val preferred = playerPrefs.getString("quality_${channel.id}", "الرابط الأساسي").orEmpty()
        val preferredUrl = if (preferred == "الرابط الأساسي") channel.defaultUrl else channel.qualityUrls[preferred].orEmpty()
        val playbackChannel = if (isHttpUrl(preferredUrl) && preferredUrl != channel.legacyUrl) {
            channel.copy(legacyUrl = preferredUrl, advanced = if (preferred == "الرابط الأساسي") channel.advanced else null)
        } else channel
        recoveryTask?.let(handler::removeCallbacks)
        recoveryTask = null
        current = playbackChannel
        currentAdvanced = playbackChannel.advanced
        resolving = false
        recoveryAttempts = 0
        currentUrlIndex = 0
        currentUrls = emptyList()
        showLoading(true)
        currentVideoResolution = ""
        firstVideoFrameRendered = false
        handler.removeCallbacks(firstFrameTimeout)
        title.text = playbackChannel.name
        ensurePlayer(playbackChannel)

        if (currentAdvanced == null) {
            if (!isHttpUrl(playbackChannel.legacyUrl)) {
                status.text = "رابط القناة غير صالح. تحقق من لوحة الإدارة."
                return
            }
            currentUrls = listOf(playbackChannel.legacyUrl)
            playUrl(playbackChannel.legacyUrl)
        } else {
            status.text = "جارٍ تجهيز القناة..."
            resolveAndPlay(playbackChannel, keepPosition = false)
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
                    status.text = "RAHMANI TV • البث مستمر"
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
        val itemBuilder = MediaItem.Builder()
            .setUri(url)
            .setMediaId(current?.id ?: "firas")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(current?.name ?: "بث مباشر")
                    .setArtist("RAHMANI TV")
                    .build()
            )
        // Preserve HLS handling for extension-less and PHP resolver endpoints,
        // which commonly return an HLS playlist. Use progressive inference only
        // for file extensions that clearly identify a non-HLS media file.
        val path = Uri.parse(url).path.orEmpty().lowercase()
        val progressive = listOf(".mp4", ".m4v", ".webm", ".mkv", ".mp3", ".m4a", ".aac", ".wav")
            .any { path.endsWith(it) }
        if (!progressive) {
            itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
        }
        val item = itemBuilder.build()
        showLoading(true)
        firstVideoFrameRendered = false
        handler.removeCallbacks(firstFrameTimeout)
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

    private fun installPlayerSurfaceClickListener(view: PlayerView) {
        view.setOnClickListener {
            if (controlsLocked) {
                controlsLocked = false
                lockButton.text = "♙"
                setOverlayVisible(true)
            } else {
                setOverlayVisible(findViewById<View>(R.id.topBar).visibility != View.VISIBLE)
            }
        }
    }

    private fun switchToSurfaceViewFallback(): Boolean {
        if (surfaceFallbackAttempted || playerView !== texturePlayerView) return false
        val active = player ?: return false
        surfaceFallbackAttempted = true
        playerView.player = null
        texturePlayerView.visibility = View.GONE
        surfacePlayerView.visibility = View.VISIBLE
        playerView = surfacePlayerView
        playerView.player = active
        applyDisplayMode()
        playerView.requestLayout()
        showLoading(true)
        status.text = "لم تظهر الصورة، نجرب سطح عرض بديل..."
        handler.postDelayed(firstFrameTimeout, 8000L)
        return true
    }

    private fun applyVideoAdjustments(target: ExoPlayer) {
        val brightness = playerPrefs.getFloat("video_brightness", 0f).coerceIn(-1f, 1f)
        val contrast = playerPrefs.getFloat("video_contrast", 0f).coerceIn(-1f, 1f)
        val effects = mutableListOf<androidx.media3.common.Effect>()
        if (brightness != 0f) effects += Brightness(brightness)
        if (contrast != 0f) effects += Contrast(contrast)
        target.setVideoEffects(effects)
    }

    private fun showImageSettings() {
        val density = resources.displayMetrics.density
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * density).toInt(), (12 * density).toInt(), (20 * density).toInt(), 0)
        }
        fun addAdjustment(labelText: String, key: String) {
            val label = TextView(this).apply { textSize = 14f; setTextColor(Color.WHITE) }
            val seek = SeekBar(this).apply {
                max = 200
                progress = ((playerPrefs.getFloat(key, 0f).coerceIn(-1f, 1f) + 1f) * 100f).toInt()
            }
            fun update(progress: Int) {
                val value = (progress - 100) / 100f
                label.text = "$labelText: ${"%.2f".format(value)}"
                playerPrefs.edit().putFloat(key, value).apply()
                player?.let(::applyVideoAdjustments)
            }
            seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) update(progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
            content.addView(label)
            content.addView(seek)
            update(seek.progress)
        }
        addAdjustment("السطوع", "video_brightness")
        addAdjustment("التباين", "video_contrast")
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("ضبط الصورة")
            .setView(content)
            .setPositiveButton("إغلاق", null)
            .setNeutralButton("إعادة الضبط") { _, _ ->
                playerPrefs.edit().putFloat("video_brightness", 0f).putFloat("video_contrast", 0f).apply()
                player?.let(::applyVideoAdjustments)
            }
            .show()
    }

    private fun showLoading(visible: Boolean) {
        loadingIndicator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun hardRefreshCurrentStream(resetRecoveryAttempts: Boolean) {
        val channel = current ?: return
        recoveryTask?.let(handler::removeCallbacks)
        recoveryTask = null
        if (resetRecoveryAttempts) recoveryAttempts = 0

        // Stop and release the current player immediately. This clears its active
        // network request and in-memory playback buffer before a new URL is resolved.
        resolving = true
        showLoading(true)
        playerView.player = null
        mediaSession?.release()
        mediaSession = null
        player?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
            release()
        }
        player = null

        worker.execute {
            val latestConfigs = UniversalStreamEngine.load(this@PlayerActivity)
            val latestLegacy = UniversalStreamEngine.loadLegacy(this@PlayerActivity)
            val refreshedLegacy = latestLegacy.firstOrNull { it.id == channel.id }
                ?: latestLegacy.firstOrNull { it.name.equals(channel.name, ignoreCase = true) }
            val refreshedChannel = if (refreshedLegacy != null) {
                channel.copy(
                    legacyUrl = refreshedLegacy.stream,
                    legacyHeaders = refreshedLegacy.headers,
                    logo = refreshedLegacy.logo
                )
            } else channel
            val refreshedConfig = UniversalStreamEngine.match(
                latestConfigs, refreshedChannel.id, refreshedChannel.name
            ) ?: channel.advanced

            runOnUiThread {
                if (isFinishing || isDestroyed || current?.id != channel.id) {
                    resolving = false
                    return@runOnUiThread
                }
                current = refreshedChannel
                currentAdvanced = refreshedConfig
                currentUrls = emptyList()
                currentUrlIndex = 0
                resolving = false
                ensurePlayer(refreshedChannel)

                if (refreshedConfig != null) {
                    resolveAndPlay(refreshedChannel, keepPosition = false)
                } else if (isHttpUrl(refreshedChannel.legacyUrl)) {
                    currentUrls = listOf(refreshedChannel.legacyUrl)
                    playUrl(refreshedChannel.legacyUrl)
                } else {
                    showLoading(false)
                    status.text = "رابط القناة غير متوفر. تحقق من لوحة الإدارة."
                }
            }
        }
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
            hardRefreshCurrentStream(resetRecoveryAttempts = false)
        }
        recoveryTask = task
        handler.postDelayed(task, delay)
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

    private fun applyFullscreenSystemUi() {
        // Immersive edge-to-edge player: draw behind system bars and the display cutout.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (isFullscreen) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                }
            }
        }

        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, !isFullscreen)
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            if (isFullscreen) {
                systemBarsBehavior =
                    androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            } else {
                show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            }
        }

        window.decorView.post {
            window.decorView.requestLayout()
            findViewById<View>(R.id.playerView)?.apply {
                setPadding(0, 0, 0, 0)
                requestLayout()
                invalidate()
            }
            findViewById<View>(R.id.playerViewSurface)?.requestLayout()
        }
    }

    private fun applyDisplayMode() {
        // Keep the PlayerView and its content frame free of a manually imposed
        // source aspect ratio. Media3 applies the video ratio itself; forcing
        // exo_content_frame to the source ratio cancels the effect of ZOOM.
        val selectedMode = when (displayMode) {
            0 -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        }

        texturePlayerView.resizeMode = selectedMode
        surfacePlayerView.resizeMode = selectedMode
        playerView.resizeMode = selectedMode

        listOf(texturePlayerView, surfacePlayerView).forEach { view ->
            view.findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
                ?.setAspectRatio(0f)
            view.requestLayout()
        }

        zoom.text = when (displayMode) {
            0 -> "Fit"
            1 -> "Zoom"
            else -> "Fill"
        }
        playerView.requestLayout()
        playerView.invalidate()
    }
    private fun showDisplayModePicker() {
        displayMode = (displayMode + 1) % 3
        playerPrefs.edit().putInt("display_mode_v9", displayMode).apply()
        applyDisplayMode()

        val label = when (displayMode) {
            0 -> "Fit"
            1 -> "Zoom"
            else -> "Fill"
        }
        Toast.makeText(this, "وضع العرض: $label", Toast.LENGTH_SHORT).show()
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        // Keep the player in landscape in both immersive and controls-visible states.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        applyFullscreenSystemUi()
        applyDisplayMode()
        fullscreen.text = if (isFullscreen) "⤡" else "⛶"
        playerView.post {
            playerView.requestLayout()
            playerView.invalidate()
        }
        if (isFullscreen) scheduleOverlayHide() else handler.removeCallbacks(hideOverlayTask)
    }

    private fun handleBackAction() {
        if (isFullscreen) {
            isFullscreen = false
            // Exit immersive mode without rotating the phone player into portrait.
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            applyFullscreenSystemUi()
            applyDisplayMode()
            fullscreen.text = "⛶"
            handler.removeCallbacks(hideOverlayTask)
        } else {
            finish()
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        playerView.post {
            player?.let { active -> if (playerView.player !== active) playerView.player = active }
            applyDisplayMode()
            playerView.requestLayout()
            playerView.invalidate()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) applyFullscreenSystemUi()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        handleBackAction()
    }

    private fun scheduleOverlayHide() {
        handler.removeCallbacks(hideOverlayTask)
        if (!controlsLocked && player?.isPlaying == true) {
            handler.postDelayed(hideOverlayTask, 3000L)
        }
    }

    private fun setOverlayVisible(visible: Boolean) {
        val overlays = listOf(
            findViewById<View>(R.id.playerTopScrim),
            findViewById<View>(R.id.playerBottomScrim),
            findViewById<View>(R.id.topBar),
            findViewById<View>(R.id.bottomPanel),
            centerControls
        )
        if (!visible && findViewById<View>(R.id.channelPanel).visibility == View.VISIBLE) {
            findViewById<View>(R.id.channelPanel).animate().cancel()
            findViewById<View>(R.id.channelPanel).animate().alpha(0f).setDuration(200L).withEndAction {
                if (findViewById<View>(R.id.channelPanel).alpha == 0f) findViewById<View>(R.id.channelPanel).visibility = View.GONE
            }.start()
        }
        overlays.forEach { view ->
            view.animate().cancel()
            if (visible) {
                if (view.visibility != View.VISIBLE) {
                    view.alpha = 0f
                    view.visibility = View.VISIBLE
                }
                view.animate().alpha(1f).setDuration(200L).start()
            } else if (view.visibility == View.VISIBLE) {
                view.animate().alpha(0f).setDuration(200L).withEndAction {
                    if (view.alpha == 0f) view.visibility = View.GONE
                }.start()
            }
        }
        handler.removeCallbacks(hideOverlayTask)
        if (visible) scheduleOverlayHide()
    }

    private fun changeChannel(direction: Int) {
        if (channels.isEmpty()) return
        val currentIndex = channels.indexOfFirst { it.id == current?.id }
        val start = if (currentIndex < 0) 0 else currentIndex
        val nextIndex = (start + direction).coerceIn(0, channels.lastIndex)
        if (nextIndex != currentIndex) play(channels[nextIndex])
    }

    override fun onStart() {
        super.onStart()
        registerPlaybackNetworkCallback()
    }

    private fun registerPlaybackNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) {
                networkWasLost = true
                handler.post {
                    if (!isFinishing && current != null) {
                        status.text = "انقطع اتصال الإنترنت، ننتظر عودته..."
                    }
                }
            }

            override fun onAvailable(network: Network) {
                if (!networkWasLost) return
                networkWasLost = false
                handler.post {
                    if (isFinishing || isDestroyed) return@post
                    val channel = current ?: return@post
                    recoveryTask?.let(handler::removeCallbacks)
                    recoveryTask = null
                    recoveryAttempts = 0
                    resolving = false
                    status.text = "عاد الاتصال، جارٍ استئناف البث..."
                    if (currentAdvanced != null) {
                        resolveAndPlay(channel, keepPosition = false)
                    } else if (isHttpUrl(channel.legacyUrl)) {
                        playUrl(channel.legacyUrl)
                    }
                }
            }
        }
        try {
            manager.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback
            )
            networkCallback = callback
        } catch (_: Exception) {
            networkCallback = null
        }
    }

    override fun onStop() {
        networkCallback?.let { callback ->
            try {
                (getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager)?.unregisterNetworkCallback(callback)
            } catch (_: Exception) {
            }
        }
        networkCallback = null
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (isFullscreen) applyFullscreenSystemUi()
        player?.let { active ->
            if (playerView.player !== active) playerView.player = active
            playerView.post { playerView.requestLayout(); playerView.invalidate() }
        }
        // Resume only if playback was active before the Activity was paused.
        if (resumePlaybackOnResume) player?.playWhenReady = true
        resumePlaybackOnResume = false
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, 5 * 60 * 1000L)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        // Remember the viewer's intent so a manually paused video stays paused on return.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !isInPictureInPictureMode) {
            resumePlaybackOnResume = player?.playWhenReady == true
            player?.pause()
            window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        super.onPause()
    }

    private fun enterPictureInPictureIfSupported() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "ميزة الشاشة المصغّرة تحتاج Android 8 أو أحدث", Toast.LENGTH_SHORT).show()
            return
        }
        val exo = player
        if (exo == null || !exo.isPlaying) {
            Toast.makeText(this, "ابدأ تشغيل الفيديو أولاً", Toast.LENGTH_SHORT).show()
            return
        }
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            Toast.makeText(this, "الشاشة المصغّرة غير مدعومة على هذا الجهاز", Toast.LENGTH_SHORT).show()
            return
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        enterPictureInPictureMode(params)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && player?.isPlaying == true &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            enterPictureInPictureIfSupported()
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            player?.let { active -> if (playerView.player !== active) playerView.player = active }
            setOverlayVisible(false)
            list.visibility = View.GONE
        } else {
            player?.let { active ->
                if (playerView.player !== active) playerView.player = active
                playerView.requestLayout()
            }
            setOverlayVisible(true)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        recoveryTask = null
        playerView.player = null
        if (::texturePlayerView.isInitialized) texturePlayerView.player = null
        if (::surfacePlayerView.isInitialized) surfacePlayerView.player = null
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        worker.shutdownNow()
        super.onDestroy()
    }

    // Use neutral initials when the official channel logo is unavailable.
    // This avoids drawing imitation broadcaster trademarks.
    private fun makeChannelLogo(name: String): Bitmap {
        val size = 128
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 24) }
        canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 18f, 18f, bg)
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val initials = when {
            words.isEmpty() -> "TV"
            words.size > 1 -> words.take(2).joinToString("") { it.first().uppercase() }
            else -> words.first().take(2).uppercase()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(217, 180, 91)
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = if (initials.length > 1) 48f else 58f
        }
        canvas.drawText(initials, 64f, 78f, paint)
        return bitmap
    }

    private class PlayerChannelAdapter(
        private val items: List<DisplayChannel>,
        private val click: (DisplayChannel) -> Unit
    ) : RecyclerView.Adapter<ChannelHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ChannelHolder {
            val context = parent.context
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 9), dp(context, 6), dp(context, 10), dp(context, 6))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.rgb(18, 18, 18))
                    cornerRadius = dp(context, 10).toFloat()
                    setStroke(dp(context, 1), Color.rgb(74, 74, 74))
                }
                layoutParams = RecyclerView.LayoutParams(-1, dp(context, 58)).apply {
                    topMargin = dp(context, 3)
                    bottomMargin = dp(context, 3)
                }
            }
            val logo = ImageView(context).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(makeStaticLogo("TV"))
            }
            card.addView(logo, LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)))
            val label = TextView(context).apply {
                setTextColor(Color.rgb(217, 180, 91))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(context, 8), 0, 0, 0)
            }
            card.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
            return ChannelHolder(card, logo, label)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            val channel = items[position]
            holder.label.text = channel.name
            holder.logo.setImageBitmap(makeStaticLogo(channel.name))
            holder.itemView.setOnClickListener { click(channel) }
        }

        private fun makeStaticLogo(name: String): Bitmap {
            val size = 128
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(24, 24, 24) }
            canvas.drawRoundRect(0f, 0f, size.toFloat(), size.toFloat(), 18f, 18f, bg)
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val initials = when {
                words.isEmpty() -> "TV"
                words.size > 1 -> words.take(2).joinToString("") { it.first().uppercase() }
                else -> words.first().take(2).uppercase()
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(217, 180, 91)
                textAlign = Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                textSize = if (initials.length > 1) 48f else 58f
            }
            canvas.drawText(initials, 64f, 78f, paint)
            return bitmap
        }

        private fun dp(context: android.content.Context, value: Int): Int =
            (value * context.resources.displayMetrics.density).toInt()
    }

    private class ChannelHolder(
        view: View,
        val logo: ImageView,
        val label: TextView
    ) : RecyclerView.ViewHolder(view)

    companion object {
        private const val TAG = "RahmaniPlayer"
        const val EXTRA_NAME = "channel_name"
        const val EXTRA_URL = "channel_url"
        const val EXTRA_REFERER = "channel_referer"
        const val EXTRA_CHANNEL_ID = "channel_id"
        const val EXTRA_LOGO = "channel_logo"
        const val EXTRA_USER_AGENT = "channel_user_agent"
        const val EXTRA_COOKIE = "channel_cookie"
    }
}