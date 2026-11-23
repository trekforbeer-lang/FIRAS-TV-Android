package com.firas.tv

import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
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
    val logo: String,
    val advanced: StreamConfig?
)

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var channelLogo: ImageView
    private val logoWorker = Executors.newSingleThreadExecutor()
    private var logoRequestId = 0
    private lateinit var title: TextView
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
    private var retryAttempts = 0
    private var currentUrls: List<String> = emptyList()
    private var currentUrlIndex = 0
    private var isFullscreen = false
    private var zoomed = false
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
        channelLogo = findViewById(R.id.channelLogo)
        title = findViewById(R.id.channelTitle)
        status = findViewById(R.id.statusText)
        list = findViewById(R.id.channelList)
        playPause = findViewById(R.id.playPauseButton)
        fullscreen = findViewById(R.id.fullscreenButton)
        zoom = findViewById(R.id.zoomButton)
        retry = findViewById(R.id.retryButton)
        server = findViewById(R.id.serverButton)
        quality = findViewById(R.id.qualityButton)

        val advanced = UniversalStreamEngine.load(this)
        channels = UniversalStreamEngine.loadLegacy(this).map { legacy ->
            DisplayChannel(legacy.id, legacy.name, legacy.stream, legacy.headers, legacy.logo,
                UniversalStreamEngine.match(advanced, legacy.id, legacy.name))
        }
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.adapter = ChannelAdapter(channels) { play(it) }

        playPause.setOnClickListener {
            player?.let { p -> if (p.isPlaying) p.pause() else p.play() }
        }
        fullscreen.setOnClickListener { toggleFullscreen() }
        zoom.setOnClickListener {
            zoomed = !zoomed
            playerView.resizeMode = if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT
            zoom.text = if (zoomed) "ملاءمة" else "زووم"
        }
        retry.setOnClickListener { current?.let { play(it) } }
        server.setOnClickListener { nextCandidateOrRetry() }
        quality.setOnClickListener {
            val active = player
            if (active == null) Toast.makeText(this, "شغّل قناة أولاً", Toast.LENGTH_SHORT).show()
            else TrackSelectionDialogBuilder(this, "اختيار الجودة", active, C.TRACK_TYPE_VIDEO).build().show()
        }
        if (channels.isNotEmpty()) play(channels.first())
        else status.text = "لا توجد قنوات في ملف channels.json"
    }

    private fun showChannelLogo(channel: DisplayChannel) {
        val requestId = ++logoRequestId
        channelLogo.setImageDrawable(null)
        val logoUrl = channel.logo.trim()
        channelLogo.visibility = View.GONE
        if (logoUrl.isBlank()) return
        logoWorker.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL(logoUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 10000
                    instanceFollowRedirects = true
                }
                connection.inputStream.use { input ->
                    val bitmap = BitmapFactory.decodeStream(input)
                    runOnUiThread {
                        if (!isFinishing && requestId == logoRequestId && current?.id == channel.id && bitmap != null) {
                            channelLogo.setImageBitmap(bitmap)
                            channelLogo.visibility = View.VISIBLE
                        }
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    if (requestId == logoRequestId) channelLogo.visibility = View.GONE
                }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun ensurePlayer(channel: DisplayChannel) {
        playerView.player = null
        player?.release()
        player = null
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(1500, 12000, 500, 1000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val sourceFactory = if (currentAdvanced != null) {
            DefaultMediaSourceFactory(this).setDataSourceFactory(UniversalStreamEngine.okhttpFactory(currentAdvanced!!))
        } else {
            DefaultMediaSourceFactory(this).setDataSourceFactory(
                DefaultHttpDataSource.Factory().setUserAgent(channel.legacyHeaders["User-Agent"] ?: "FIRAS-TV/3.0.2")
                    .setDefaultRequestProperties(channel.legacyHeaders).setAllowCrossProtocolRedirects(true)
            )
        }
        player = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sourceFactory)
            .build().also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        scheduleRecovery()
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        status.text = when (state) {
                            Player.STATE_BUFFERING -> "جارٍ تحميل البث..."
                            Player.STATE_READY -> {
                                retryAttempts = 0
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
        retryAttempts = 0
        current = channel
        currentAdvanced = channel.advanced
        showChannelLogo(channel)
        currentUrlIndex = 0
        title.text = channel.name
        ensurePlayer(channel)
        if (currentAdvanced == null) {
            // Preserve the original Media3 HTTP playback path for channels without an advanced config match.
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
                if (current?.id != channel.id) return@runOnUiThread
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
                if (current?.id != channel.id) return@runOnUiThread
                currentUrls = urls
                currentUrlIndex = 0
                if (urls.isEmpty()) {
                    status.text = "لم يتم العثور على رابط صالح"
                    return@runOnUiThread
                }
                playUrl(urls.first(), oldPosition)
            }
        }
    }

    private fun playUrl(url: String, position: Long = 0L) {
        val item = MediaItem.Builder().setUri(url).setMediaId(current?.id ?: "firas").build()
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
        if (retryAttempts >= 3) {
            status.text = "تعذر تشغيل القناة. تحقق من الرابط أو صلاحية Cookie."
            return
        }
        val attempt = ++retryAttempts
        val delayMs = 1500L * (1L shl (attempt - 1))
        status.text = "انقطع البث، إعادة المحاولة ${'
        val channel = current ?: return
        val config = currentAdvanced ?: return
        if (currentUrlIndex + 1 < currentUrls.size) {
            currentUrlIndex++
            playUrl(currentUrls[currentUrlIndex])
        } else {
            worker.execute {
                val refreshed = UniversalStreamEngine.resolve(config)
                runOnUiThread {
                    if (current?.id == channel.id && refreshed.isNotEmpty()) {
                        currentUrls = refreshed
                        currentUrlIndex = 0
                        playUrl(refreshed.first())
                    } else status.text = "تعذر الوصول إلى البث"
                }
            }
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        requestedOrientation = if (isFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val flags = if (isFullscreen) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else View.SYSTEM_UI_FLAG_VISIBLE
        window.decorView.systemUiVisibility = flags
        findViewById<View>(R.id.topBar).visibility = if (isFullscreen) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottomPanel).visibility = if (isFullscreen) View.GONE else View.VISIBLE
        fullscreen.text = if (isFullscreen) "تصغير" else "ملء الشاشة"
    }

    override fun onResume() { super.onResume(); player?.playWhenReady = true; handler.postDelayed(refreshTask, 5 * 60 * 1000L) }
    override fun onPause() { handler.removeCallbacks(refreshTask); player?.pause(); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        playerView.player = null
        player?.release()
        player = null
        worker.shutdownNow()
        logoRequestId++
        logoWorker.shutdownNow()
        super.onDestroy()
    }

    private class ChannelAdapter(
        private val items: List<DisplayChannel>, private val click: (DisplayChannel) -> Unit
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
}{attempt}/3..."
        handler.postDelayed({
            if (!isFinishing && current != null) nextCandidateOrRetry()
        }, delayMs)
    }

    private fun nextCandidateOrRetry() {
        val channel = current ?: return
        val config = currentAdvanced ?: return
        if (currentUrlIndex + 1 < currentUrls.size) {
            currentUrlIndex++
            playUrl(currentUrls[currentUrlIndex])
        } else {
            worker.execute {
                val refreshed = UniversalStreamEngine.resolve(config)
                runOnUiThread {
                    if (current?.id == channel.id && refreshed.isNotEmpty()) {
                        currentUrls = refreshed
                        currentUrlIndex = 0
                        playUrl(refreshed.first())
                    } else status.text = "تعذر الوصول إلى البث"
                }
            }
        }
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        requestedOrientation = if (isFullscreen) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        val flags = if (isFullscreen) (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) else View.SYSTEM_UI_FLAG_VISIBLE
        window.decorView.systemUiVisibility = flags
        findViewById<View>(R.id.topBar).visibility = if (isFullscreen) View.GONE else View.VISIBLE
        findViewById<View>(R.id.bottomPanel).visibility = if (isFullscreen) View.GONE else View.VISIBLE
        fullscreen.text = if (isFullscreen) "تصغير" else "ملء الشاشة"
    }

    override fun onResume() { super.onResume(); player?.playWhenReady = true; handler.postDelayed(refreshTask, 5 * 60 * 1000L) }
    override fun onPause() { handler.removeCallbacks(refreshTask); player?.pause(); super.onPause() }
    override fun onDestroy() {
        handler.removeCallbacks(refreshTask)
        playerView.player = null
        player?.release()
        player = null
        worker.shutdownNow()
        logoRequestId++
        logoWorker.shutdownNow()
        super.onDestroy()
    }

    private class ChannelAdapter(
        private val items: List<DisplayChannel>, private val click: (DisplayChannel) -> Unit
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
