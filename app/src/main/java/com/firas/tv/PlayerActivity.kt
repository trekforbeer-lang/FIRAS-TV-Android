package com.firas.tv

import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class PlayerActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var httpFactory: OkHttpDataSource.Factory? = null
    private lateinit var playerView: PlayerView
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var list: RecyclerView
    private var channels: List<StreamConfig> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        playerView = findViewById(R.id.playerView)
        title = findViewById(R.id.channelTitle)
        status = findViewById(R.id.statusText)
        list = findViewById(R.id.channelList)
        channels = UniversalStreamEngine.load(this)
        list.layoutManager = LinearLayoutManager(this, RecyclerView.HORIZONTAL, false)
        list.adapter = ChannelAdapter(channels) { play(it) }
        if (channels.isNotEmpty()) play(channels.first())
    }

    private fun initializePlayer() {
        if (player != null) return
        httpFactory = OkHttpDataSource.Factory(UniversalStreamEngine.client)
            .setUserAgent("FIRAS-TV/3.0.2")
        val mediaSourceFactory = DefaultMediaSourceFactory(this).setDataSourceFactory(httpFactory!!)
        player = ExoPlayer.Builder(this).setMediaSourceFactory(mediaSourceFactory).build().also {
            playerView.player = it
            it.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    status.text = "تعذر تشغيل البث: ${error.errorCodeName}"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    status.text = when (state) {
                        Player.STATE_BUFFERING -> "جارٍ تحميل البث..."
                        Player.STATE_READY -> "FIRAS TV • البث جاهز"
                        Player.STATE_ENDED -> "انتهى البث"
                        else -> "FIRAS TV • بث مباشر"
                    }
                }
            })
        }
    }

    private fun play(config: StreamConfig) {
        val urls = UniversalStreamEngine.candidates(config)
        if (urls.isEmpty()) { status.text = "لا يوجد رابط بث"; return }
        initializePlayer()
        title.text = config.name
        val headers = UniversalStreamEngine.mergedHeaders(config)
        httpFactory?.setDefaultRequestProperties(headers)
        val queue = if (config.mode.equals("sequential", true)) urls else listOf(urls.first())
        val items = queue.mapIndexed { index, url ->
            MediaItem.Builder().setUri(url).setMediaId("${config.id}-$index").build()
        }
        player?.apply { stop(); clearMediaItems(); setMediaItems(items); prepare(); playWhenReady = true }
        status.text = "جارٍ الاتصال..."
    }

    private fun enterFullscreen() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
    }

    override fun onResume() { super.onResume(); player?.playWhenReady = true }
    override fun onPause() { player?.pause(); super.onPause() }
    override fun onDestroy() { playerView.player = null; player?.release(); player = null; super.onDestroy() }

    private class ChannelAdapter(
        private val items: List<StreamConfig>, private val click: (StreamConfig) -> Unit
    ) : RecyclerView.Adapter<ChannelHolder>() {
        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ChannelHolder {
            val tv = TextView(parent.context).apply {
                setTextColor(android.graphics.Color.WHITE)
                setBackgroundColor(android.graphics.Color.rgb(35,35,35))
                textSize = 15f; gravity = android.view.Gravity.CENTER
                setPadding(24, 18, 24, 18)
            }
            return ChannelHolder(tv)
        }
        override fun getItemCount() = items.size
        override fun onBindViewHolder(holder: ChannelHolder, position: Int) {
            (holder.itemView as TextView).text = items[position].name
            holder.itemView.setOnClickListener { click(items[position]) }
        }
    }
    private class ChannelHolder(view: View) : RecyclerView.ViewHolder(view)
}
