package com.firas.tv

import android.content.pm.ActivityInfo
import android.media.AudioManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.media3.ui.TrackSelectionDialogBuilder

class MoviePlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var resizeButton: Button
    private var zoomed = false
    private var resumePlaybackOnStart = false
    private var gestureStartY = 0f
    private var gestureStartX = 0f
    private var gestureStartVolume = 0
    private var gestureStartBrightness = 0.5f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.decorView.systemUiVisibility = immersiveFlags()
        window.statusBarColor = Color.BLACK
        window.navigationBarColor = Color.BLACK

        val movieTitle = intent.getStringExtra("movie_title") ?: "فيلم"
        val url = intent.getStringExtra("movie_url")
        if (url.isNullOrBlank()) {
            Toast.makeText(this, "رابط الفيلم غير صالح", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            useController = true
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            controllerShowTimeoutMs = 3500
            setKeepContentOnPlayerReset(true)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        val audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        val seekGesture = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean {
                gestureStartX = event.x
                gestureStartY = event.y
                gestureStartVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                gestureStartBrightness = window.attributes.screenBrightness.takeIf { it >= 0f } ?: 0.5f
                return true
            }

            override fun onScroll(
                first: MotionEvent?,
                current: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (playerView.width <= 0 || playerView.height <= 0) return false
                val progress = ((gestureStartY - current.y) / playerView.height).coerceIn(-1f, 1f)
                if (gestureStartX < playerView.width / 2f) {
                    val params = window.attributes
                    params.screenBrightness = (gestureStartBrightness + progress).coerceIn(0.05f, 1f)
                    window.attributes = params
                } else {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val volume = (gestureStartVolume + progress * maxVolume).toInt().coerceIn(0, maxVolume)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
                }
                return true
            }

            override fun onDoubleTap(event: MotionEvent): Boolean {
                val active = player ?: return false
                val delta = if (event.x < playerView.width / 2f) -10_000L else 10_000L
                val duration = active.duration.takeIf { it > 0L } ?: Long.MAX_VALUE
                val target = (active.currentPosition + delta).coerceIn(0L, duration)
                active.seekTo(target)
                Toast.makeText(
                    this@MoviePlayerActivity,
                    if (delta < 0) "رجوع 10 ثوانٍ" else "تقديم 10 ثوانٍ",
                    Toast.LENGTH_SHORT
                ).show()
                return true
            }
        })
        playerView.setOnTouchListener { _, event ->
            seekGesture.onTouchEvent(event)
            false
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setBackgroundColor(0x99090A0F.toInt())
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val back = Button(this).apply {
            text = "رجوع"
            isAllCaps = false
            textSize = 12f
            setOnClickListener { finish() }
        }
        topBar.addView(back, LinearLayout.LayoutParams(dp(64), dp(42)))
        val heading = TextView(this).apply {
            text = movieTitle
            setTextColor(Color.WHITE)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), 0, dp(10), 0)
        }
        topBar.addView(heading, LinearLayout.LayoutParams(0, -1, 1f))
        resizeButton = Button(this).apply {
            text = "ملاءمة"
            isAllCaps = false
            textSize = 11f
            setOnClickListener { toggleResize() }
        }
        topBar.addView(resizeButton, LinearLayout.LayoutParams(dp(78), dp(42)))
        val qualityButton = Button(this).apply {
            text = "الجودة"
            isAllCaps = false
            textSize = 11f
            setOnClickListener {
                val active = player
                if (active == null) Toast.makeText(this@MoviePlayerActivity, "انتظر تحميل الفيلم", Toast.LENGTH_SHORT).show()
                else TrackSelectionDialogBuilder(this@MoviePlayerActivity, "اختيار الجودة", active, C.TRACK_TYPE_VIDEO).build().show()
            }
        }
        topBar.addView(qualityButton, LinearLayout.LayoutParams(dp(78), dp(42)))
        root.addView(topBar, FrameLayout.LayoutParams(-1, dp(54), Gravity.TOP))
        setContentView(root)

        val selector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters()
                .setMaxVideoSize(3840, 2160)
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setForceHighestSupportedBitrate(false)
                .build())
        }
        val dataSource = DefaultHttpDataSource.Factory()
            .setUserAgent("RAHMANI/1.0")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(25000)
        val sourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(dataSource)

        player = ExoPlayer.Builder(this)
            .setTrackSelector(selector)
            .setMediaSourceFactory(sourceFactory)
            .build().also { exo ->
                playerView.player = exo
                exo.addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        Toast.makeText(this@MoviePlayerActivity, "تعذر تشغيل الفيلم. قد يكون الرابط غير متاح.", Toast.LENGTH_LONG).show()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                })
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                exo.playWhenReady = true
            }
    }

    private fun toggleResize() {
        zoomed = !zoomed
        playerView.resizeMode = if (zoomed) AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            else AspectRatioFrameLayout.RESIZE_MODE_FIT
        resizeButton.text = if (zoomed) "زووم" else "ملاءمة"
    }

    private fun immersiveFlags(): Int =
        View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility = immersiveFlags()
    }

    override fun onStop() {
        resumePlaybackOnStart = player?.isPlaying == true
        player?.pause()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        if (resumePlaybackOnStart) player?.playWhenReady = true
        resumePlaybackOnStart = false
    }

    override fun onDestroy() {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playerView.player = null
        player?.release()
        player = null
        super.onDestroy()
    }
}
