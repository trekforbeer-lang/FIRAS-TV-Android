package com.firas.tv

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

class MoviePlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        val view = PlayerView(this).apply { useController = true; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS) }
        setContentView(FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()); addView(view, FrameLayout.LayoutParams(-1, -1)) })
        title = intent.getStringExtra("movie_title") ?: "فيلم"
        val url = intent.getStringExtra("movie_url")
        if (url.isNullOrBlank()) { finish(); return }
        val factory = DefaultMediaSourceFactory(this).setDataSourceFactory(DefaultHttpDataSource.Factory().setUserAgent("FIRAS-TV/4.0").setAllowCrossProtocolRedirects(true))
        player = ExoPlayer.Builder(this).setMediaSourceFactory(factory).build().also {
            view.player = it; it.setMediaItem(MediaItem.fromUri(url)); it.prepare(); it.playWhenReady = true
        }
    }
    override fun onStop() { player?.pause(); super.onStop() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }
}
