package com.maxim.marquee

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView
import com.maxim.marquee.data.LocalProgress
import com.maxim.marquee.data.Repo
import com.maxim.marquee.data.Store
import com.maxim.marquee.data.StreamItem
import com.maxim.marquee.data.SubTrack
import com.maxim.marquee.data.USER_AGENT
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {

    companion object {
        fun intent(ctx: Context, req: PlayReq, s: StreamItem, subs: List<SubTrack>, startMs: Long): Intent {
            val headers = JSONObject().apply { s.headers.forEach { (k, v) -> put(k, v) } }
            val subArr = JSONArray().apply {
                subs.forEach { put(JSONObject().put("url", it.url).put("lang", it.lang).put("label", it.label)) }
            }
            return Intent(ctx, PlayerActivity::class.java)
                .putExtra("url", s.url)
                .putExtra("headers", headers.toString())
                .putExtra("subs", subArr.toString())
                .putExtra("start", startMs)
                .putExtra("type", req.type)
                .putExtra("metaId", req.metaId)
                .putExtra("videoId", req.videoId)
                .putExtra("name", req.name)
                .putExtra("poster", req.poster)
                .putExtra("episode", req.episode)
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var view: PlayerView
    private lateinit var titleBar: View

    private lateinit var url: String
    private var subs: List<SubTrack> = emptyList()
    private lateinit var type: String
    private lateinit var metaId: String
    private lateinit var videoId: String
    private var name = ""
    private var poster: String? = null
    private var episode: String? = null

    private var outcome = "exit"
    private var retriedWithoutSubs = false
    private var retriedOnce = false
    private var started = false
    private var consumedKey = -1
    private var ticks = 0
    private var saver: Job? = null
    private var finishing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        url = intent.getStringExtra("url") ?: return finish()
        type = intent.getStringExtra("type") ?: "movie"
        metaId = intent.getStringExtra("metaId") ?: ""
        videoId = intent.getStringExtra("videoId") ?: metaId
        name = intent.getStringExtra("name") ?: ""
        poster = intent.getStringExtra("poster")
        episode = intent.getStringExtra("episode")
        val start = intent.getLongExtra("start", 0)

        val headers = mutableMapOf<String, String>()
        runCatching {
            val o = JSONObject(intent.getStringExtra("headers") ?: "{}")
            o.keys().forEach { k -> headers[k] = o.getString(k) }
        }
        subs = runCatching {
            val a = JSONArray(intent.getStringExtra("subs") ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                SubTrack(o.getString("url"), o.optString("lang", "und"), o.optString("label", "Subtitles"))
            }
        }.getOrDefault(emptyList())

        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(headers.remove("User-Agent") ?: USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20_000)
            .setReadTimeoutMs(30_000)
            .setDefaultRequestProperties(headers)

        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(this)
        trackSelector.setParameters(
            trackSelector.buildUponParameters()
                .setPreferredTextLanguage(Store.subLang.ifBlank { null })
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !Store.subsOn),
        )

        player = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .build()
        player.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )

        buildUi()

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) started = true
                if (state == Player.STATE_ENDED) {
                    outcome = "ended"
                    finish()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val pos = player.currentPosition
                if (!retriedWithoutSubs && subs.isNotEmpty() && !started) {
                    // A broken subtitle file can stop playback; try again without them.
                    retriedWithoutSubs = true
                    player.setMediaItem(mediaItem(withSubs = false), pos)
                    player.prepare(); player.play()
                    return
                }
                if (started && !retriedOnce) {
                    // Network hiccup mid-show: reconnect once from the same spot.
                    retriedOnce = true
                    player.prepare(); player.seekTo(pos); player.play()
                    return
                }
                outcome = if (started) "exit" else "error"
                finish()
            }
        })

        player.setMediaItem(mediaItem(withSubs = true), start)
        player.prepare()
        player.playWhenReady = true

        saver = lifecycleScope.launch {
            while (isActive) {
                delay(15_000)
                ticks++
                saveProgress(sync = ticks % 4 == 0)
            }
        }
    }

    private fun mediaItem(withSubs: Boolean): MediaItem {
        val subConfigs = if (!withSubs) emptyList() else subs.map {
            val lower = it.url.lowercase()
            val mime = when {
                lower.contains(".vtt") -> MimeTypes.TEXT_VTT
                lower.contains(".ass") || lower.contains(".ssa") -> MimeTypes.TEXT_SSA
                else -> MimeTypes.APPLICATION_SUBRIP
            }
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.url))
                .setMimeType(mime)
                .setLanguage(it.lang)
                .setLabel(it.label)
                .setSelectionFlags(0)
                .build()
        }
        return MediaItem.Builder()
            .setUri(url)
            .setSubtitleConfigurations(subConfigs)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
    }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun buildUi() {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        view = PlayerView(this).apply {
            player = this@PlayerActivity.player
            useController = true
            controllerShowTimeoutMs = 3_500
            controllerAutoShow = true
            setShowSubtitleButton(true)
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowShuffleButton(false)
            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            keepScreenOn = true
        }
        root.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        titleBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(48), dp(28), dp(48), dp(40))
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(200, 0, 0, 0), Color.TRANSPARENT),
            )
            addView(TextView(this@PlayerActivity).apply {
                text = name
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                paint.isFakeBoldText = true
            })
            episode?.let { ep ->
                addView(TextView(this@PlayerActivity).apply {
                    text = ep
                    setTextColor(Color.rgb(200, 200, 200))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                })
            }
        }
        root.addView(
            titleBar,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP),
        )
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> titleBar.visibility = v })
        setContentView(root)
        view.requestFocus()
    }

    /** Netflix-style remote: left/right skip 10s and OK pauses, without opening the controls first. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::view.isInitialized) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP && code == consumedKey) {
            consumedKey = -1
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && !view.isControllerFullyVisible) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { player.seekBack(); view.showController(); consumedKey = code; return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { player.seekForward(); view.showController(); consumedKey = code; return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (player.isPlaying) player.pause() else player.play()
                    view.showController(); consumedKey = code; return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BACK && view.isControllerFullyVisible) {
            view.hideController(); consumedKey = code; return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun saveProgress(sync: Boolean) {
        if (!::player.isInitialized) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val finished = outcome == "ended" || pos >= dur * 0.92
        val p = if (finished) dur else pos
        Store.saveProgress(LocalProgress(metaId, type, name, poster, videoId, p, dur, System.currentTimeMillis()))
        if (sync && Store.syncProgress) {
            val t = type; val m = metaId; val v = videoId
            AppScope.launch { Repo.syncProgress(t, m, v, p, dur, finished) }
        }
    }

    override fun finish() {
        if (!finishing) {
            finishing = true
            if (started) saveProgress(sync = true)
            setResult(RESULT_OK, Intent().putExtra("outcome", outcome))
        }
        super.finish()
    }

    override fun onStop() {
        super.onStop()
        if (::player.isInitialized && !finishing) {
            saveProgress(sync = true)
            player.pause()
        }
    }

    override fun onDestroy() {
        saver?.cancel()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
