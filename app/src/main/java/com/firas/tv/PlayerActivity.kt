package com.firas.tv

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

private data class DisplayChannel(
    val id: String,
    val name: String,
    val legacyUrl: String,
    val legacyHeaders: Map<String, String>,
    val advanced: StreamConfig?
)

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private lateinit var playPause: Button
    private lateinit var fullscreen: Button
    private lateinit var zoom: Button
    private lateinit var retry: Button
    private lateinit var server: Button
    private lateinit var quality: Button
    private lateinit var loadingIndicator: ProgressBar

    private var channels: List<DisplayChannel> = emptyList()
    private var current: DisplayChannel? = null
    private var currentAdvanced: StreamConfig? = null
    private var resolving = false
    private var automaticRetryUsed = false
    private var currentUrls: List<String> = emptyList()
    private var currentUrlIndex = 0
    private var isFullscreen = false
    private var zoomed = false

    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private val refreshTask = object : Runnable {
        override fun run() {
            val channel = current
            if (channel?.advanced != null && !resolving) refreshCurrentStream(automatic = false, keepPosition = true)
            handler.postDelayed(this, 5 * 60 * 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)
        title = findViewById(R.id.channelTitle)
        status = findViewById(R.id.statusText)
        list = findViewById(R.id.channelList)
        playPause = findViewById(R.id.playPauseButton)
        fullscreen = findViewById(R.id.fullscreenButton)
        zoom = findViewById(R.id.zoomButton)
        retry = findViewById(R.id.retryButton)
        server = findViewById(R.id.serverButton)
        quality = findViewById(R.id.qualityButton)
        loadingIndicator = findViewById(R.id.loadingIndicator)

        playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
        zoom.text = "تكبير"

        val advanced = UniversalStreamEngine.load(this)
        channels = UniversalStreamEngine.loadLegacy(this).map { legacy ->
            DisplayChannel(
                legacy.id,
                legacy.name,
                legacy.stream,
                legacy.headers,
                UniversalStreamEngine.match(advanced, legacy.id, legacy.name)
            )
        }

        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.adapter = ChannelAdapter(channels) { play(it) }

        playPause.setOnClickListener {
            player?.let { active ->
                if (active.isPlaying) active.pause() else active.play()
            }
        }

        fullscreen.setOnClickListener { toggleFullscreen() }

        zoom.setOnClickListener {
            zoomed = !zoomed
            playerView.resizeMode = if (zoomed) {
                AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            } else {
                AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
            zoom.text = if (zoomed) "ملاءمة" else "تكبير"
            playerView.requestLayout()
        }

        retry.setOnClickListener { refreshCurrentStream(automatic = false) }
        server.setOnClickListener { nextCandidateOrRefresh() }

        quality.setOnClickListener {
            val active = player
            if (active == null) {
                Toast.makeText(this, "شغّل قناة أولاً", Toast.LENGTH_SHORT).show()
            } else {
                TrackSelectionDialogBuilder(this, "اختيار الجودة", active, C.TRACK_TYPE_VIDEO).build().show()
            }
        }

        if (channels.isNotEmpty()) {
            play(channels.first())
        } else {
            status.text = "لا توجد قنوات في ملف channels.json"
            showLoading(false)
        }
    }

    private fun showLoading(show: Boolean) {
        loadingIndicator.visibility = if (show) View.VISIBLE else View.GONE
        if (show) status.text = "جارٍ تحميل البث..."
    }

    // Releasing the old ExoPlayer discards its in-memory buffer and active network session.
    // A new player/load-control instance is created for every hard refresh.
    private fun releaseCurrentPlayer() {
        playerView.player = null
        player?.run {
            playWhenReady = false
            stop()
            clearMediaItems()
            release()
        }
        player = null
    }

    private fun ensurePlayer(channel: DisplayChannel) {
        releaseCurrentPlayer()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 12000, 500, 1000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val sourceFactory = if (currentAdvanced != null) {
            DefaultMediaSourceFactory(this)
                .setDataSourceFactory(UniversalStreamEngine.okhttpFactory(currentAdvanced!!))
        } else {
            DefaultMediaSourceFactory(this).setDataSourceFactory(
                DefaultHttpDataSource.Factory()
                    .setUserAgent(channel.legacyHeaders["User-Agent"] ?: "FIRAS-TV/3.0.2")
                    .setDefaultRequestProperties(channel.legacyHeaders)
                    .setAllowCrossProtocolRedirects(true)
            )
        }

        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sourceFactory)
            .build()
            .also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        if (!automaticRetryUsed) {
                            status.text = "حدث خطأ في البث، جارٍ تحديث المصدر..."
                            refreshCurrentStream(automatic = true)
                        } else {
                            showLoading(false)
                            status.text = "تعذر تشغيل البث. اضغط تحديث للمحاولة مجدداً."
                        }
                    }

                    override fun onPlaybackStateChanged(state: Int) {
                        when (state) {
                            Player.STATE_BUFFERING -> showLoading(true)
                            Player.STATE_READY -> {
                                showLoading(false)
                                automaticRetryUsed = false
                                status.text = "FIRAS TV • البث جاهز"
                            }
                            Player.STATE_ENDED -> {
                                showLoading(false)
                                status.text = "انتهى البث"
                            }
                            Player.STATE_IDLE -> {
                                if (current != null && !resolving) status.text = "FIRAS TV • بث مباشر"
                            }
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
        current = channel
        currentAdvanced = channel.advanced
        currentUrlIndex = 0
        automaticRetryUsed = false
        title.text = channel.name
        showLoading(true)

        if (currentAdvanced == null) {
            currentUrls = listOf(channel.legacyUrl).filter { it.isNotBlank() }
            ensurePlayer(channel)
            val url = currentUrls.firstOrNull()
            if (url == null) {
                showLoading(false)
                status.text = "رابط القناة غير متوفر"
            } else {
                playUrl(url)
            }
        } else {
            refreshCurrentStream(automatic = false)
        }
    }

    /**
     * Hard refresh:
     * 1) stop/release the previous player and its buffer;
     * 2) reload the latest stream configuration;
     * 3) resolve a fresh URL on the worker thread;
     * 4) rebuild Media3 with the configured User-Agent/Referer/Cookie headers;
     * 5) prepare and play inside this Activity.
     */
    private fun refreshCurrentStream(
        automatic: Boolean,
        keepPosition: Boolean = false
    ) {
        val channel = current ?: return
        if (resolving) return

        if (automatic) {
            if (automaticRetryUsed) {
                showLoading(false)
                status.text = "تعذر تشغيل البث. اضغط تحديث للمحاولة مجدداً."
                return
            }
            automaticRetryUsed = true
        } else {
            automaticRetryUsed = false
        }

        val resumePosition = if (keepPosition) player?.currentPosition ?: 0L else 0L
        resolving = true
        showLoading(true)
        releaseCurrentPlayer()

        if (channel.advanced == null) {
            currentAdvanced = null
            currentUrls = listOf(channel.legacyUrl).filter { it.isNotBlank() }
            currentUrlIndex = 0
            resolving = false
            ensurePlayer(channel)
            val url = currentUrls.firstOrNull()
            if (url == null) {
                showLoading(false)
                status.text = "رابط القناة غير متوفر"
            } else {
                playUrl(url, resumePosition)
            }
            return
        }

        worker.execute {
            val latestConfigs = UniversalStreamEngine.load(this@PlayerActivity)
            val latestConfig = UniversalStreamEngine.match(latestConfigs, channel.id, channel.name)
                ?: channel.advanced
            val freshUrls = latestConfig?.let { UniversalStreamEngine.resolve(it) }.orEmpty()

            runOnUiThread {
                resolving = false
                if (current?.id != channel.id || isFinishing || isDestroyed) return@runOnUiThread

                currentAdvanced = latestConfig
                currentUrls = freshUrls
                currentUrlIndex = 0
                ensurePlayer(channel)

                val freshUrl = freshUrls.firstOrNull()
                if (freshUrl.isNullOrBlank()) {
                    showLoading(false)
                    status.text = "لم يتم العثور على رابط بث صالح"
                    return@runOnUiThread
                }
                playUrl(freshUrl, resumePosition)
            }
        }
    }

    private fun playUrl(url: String, position: Long = 0L) {
        val active = player ?: return
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaId(current?.id ?: "firas")
            .build()

        showLoading(true)
        active.playWhenReady = false
        active.stop()
        active.clearMediaItems()
        active.setMediaItem(item)
        active.prepare()
        if (position > 0L) active.seekTo(position)
        active.playWhenReady = true
        status.text = "جارٍ الاتصال بمصدر البث..."
    }

    private fun nextCandidateOrRefresh() {
        val channel = current ?: return
        if (currentUrlIndex + 1 < currentUrls.size) {
            currentUrlIndex++
            showLoading(true)
            playUrl(currentUrls[currentUrlIndex])
        } else {
            // The server button also forces a new resolver request instead of replaying a stale URL.
            refreshCurrentStream(automatic = false)
        }
    }

    private fun applyImmersiveMode(enabled: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (enabled) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            window.decorView.keepScreenOn = true
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.decorView.keepScreenOn = false
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen

        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        findViewById<View>(R.id.topBar).visibility = if (isFullscreen) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottomPanel).visibility = if (isFullscreen) View.GONE else View.VISIBLE
        fullscreen.text = if (isFullscreen) "تصغير" else "ملء الشاشة"

        applyImmersiveMode(isFullscreen)
        playerView.post {
            playerView.requestLayout()
            playerView.invalidate()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isFullscreen) applyImmersiveMode(true)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        playerView.post {
            playerView.requestLayout()
            playerView.invalidate()
        }
    }

    @Deprecated("Deprecated in Android API; retained for Android 5+ back handling")
    override fun onBackPressed() {
        if (isFullscreen) {
            isFullscreen = false
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            findViewById<View>(R.id.topBar).visibility = View.VISIBLE
            findViewById<View>(R.id.bottomPanel).visibility = View.VISIBLE
            fullscreen.text = "ملء الشاشة"
            applyImmersiveMode(false)
            playerView.requestLayout()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFullscreen) applyImmersiveMode(true)
        player?.playWhenReady = true
        handler.removeCallbacks(refreshTask)
        handler.postDelayed(refreshTask, 5 * 60 * 1000L)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshTask)
        player?.pause()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        releaseCurrentPlayer()
        worker.shutdownNow()
        super.onDestroy()
    }

    private class ChannelAdapter(
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
}
