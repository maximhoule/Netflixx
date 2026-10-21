package com.maxim.marquee

import android.content.Context
import android.graphics.Matrix
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.maxim.marquee.data.Http
import com.maxim.marquee.data.YouTube
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Plays YouTube trailers behind the home billboard with the app's own video player: full
 * picture (cropped to fill the billboard), no YouTube logo, buttons or web page. One player is
 * reused for the whole app; switching titles only swaps the stream.
 */
@OptIn(UnstableApi::class)
class TrailerPlayer(ctx: Context) {
    private val texture = TextureView(ctx)

    /** What the billboard shows; null if the player couldn't be created (the feature then stays off). */
    val view: View?

    /** True once the current trailer's first frame is on screen (so the artwork can fade away). */
    var playing by mutableStateOf(false)
        private set
    /** True from the moment a trailer is asked for until it stops (the video surface must exist meanwhile). */
    var active by mutableStateOf(false)
        private set
    var currentId: String? = null
        private set(v) {
            field = v
            active = v != null
        }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null
    private var outcome: CompletableDeferred<Boolean>? = null
    private var videoW = 0
    private var videoH = 0
    private var pixelRatio = 1f

    private val player: ExoPlayer? = try {
        build(ctx)
    } catch (e: Throwable) {
        android.util.Log.w("Marquee", "trailer player unavailable: $e")
        null
    }

    init {
        view = if (player == null) null else FrameLayout(ctx).apply {
            // Never steal D-pad focus from the rows.
            isFocusable = false
            isFocusableInTouchMode = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        texture.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> crop() }
    }

    private fun build(ctx: Context): ExoPlayer {
        val client = Http.client.newBuilder().cache(null).build()
        val http = OkHttpDataSource.Factory(client)
        val selector = DefaultTrackSelector(ctx).apply {
            setParameters(
                buildUponParameters()
                    // The TV is 1080p and this sits behind the UI: no point decoding more.
                    .setMaxVideoSize(1920, 1080)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true),
            )
        }
        val load = DefaultLoadControl.Builder()
            // Short buffers: starts fast and keeps memory low (it's a background video).
            .setBufferDurationsMs(4_000, 15_000, 1_000, 2_000)
            .build()
        // Start at a sharp quality instead of climbing up from a blurry one.
        val meter = DefaultBandwidthMeter.Builder(ctx).setInitialBitrateEstimate(8_000_000).build()
        return ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setTrackSelector(selector)
            .setLoadControl(load)
            .setBandwidthMeter(meter)
            .setMediaSourceFactory(HlsMediaSource.Factory(http))
            .build().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), false)
                repeatMode = Player.REPEAT_MODE_ONE
                setVideoTextureView(texture)
                addListener(object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        android.util.Log.i("Marquee", "trailer playing")
                        outcome?.complete(true)
                        if (currentId != null) playing = true
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        android.util.Log.w("Marquee", "trailer stream failed: ${error.errorCodeName} ${error.cause?.message?.take(120) ?: ""}")
                        playing = false
                        outcome?.complete(false)
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        videoW = videoSize.width
                        videoH = videoSize.height
                        pixelRatio = videoSize.pixelWidthHeightRatio
                        crop()
                    }
                })
            }
    }

    /** Scale the picture so it fills the billboard (like a backdrop image), cropping the edges. */
    private fun crop() {
        val vw = texture.width.toFloat()
        val vh = texture.height.toFloat()
        if (vw <= 0f || vh <= 0f || videoW <= 0 || videoH <= 0) return
        val video = videoW * pixelRatio / videoH
        val box = vw / vh
        val sx = if (video > box) video / box else 1f
        val sy = if (video > box) 1f else box / video
        texture.setTransform(Matrix().apply { setScale(sx, sy, vw / 2f, vh / 2f) })
    }

    /** Looks up the first trailer's stream ahead of time, so playback starts the moment it's due. */
    fun prefetch(youtubeIds: List<String>) {
        if (player == null) return
        val id = clean(youtubeIds).firstOrNull() ?: return
        if (YouTube.cached(id) != null) return
        AppScope.launch { YouTube.hlsUrl(id) }
    }

    /**
     * Plays the first of [youtubeIds] that YouTube will stream (some uploads are blocked or
     * region-locked, so the next one is tried automatically).
     */
    fun play(youtubeIds: List<String>, muted: Boolean) {
        val p = player ?: return
        val ids = clean(youtubeIds)
        if (ids.isEmpty()) return
        val key = ids.joinToString(",")
        if (currentId == key) return
        currentId = key
        playing = false
        job?.cancel()
        job = scope.launch {
            for (id in ids) {
                val url = withContext(Dispatchers.IO) { YouTube.hlsUrl(id) } ?: continue
                if (!isActive || currentId != key) return@launch
                val done = CompletableDeferred<Boolean>()
                outcome = done
                p.volume = if (muted) 0f else 0.7f
                // Skip the studio logos at the very start.
                p.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build(), 4_000L)
                p.prepare()
                p.playWhenReady = true
                if (withTimeoutOrNull(20_000) { done.await() } == true) return@launch
                android.util.Log.w("Marquee", "trailer $id didn't start, trying the next one")
                p.stop()
            }
            if (currentId == key) playing = false
        }
    }

    fun stop() {
        val p = player ?: return
        job?.cancel()
        job = null
        outcome = null
        if (currentId == null && !playing) return
        currentId = null
        playing = false
        // Stopping also frees the video decoder for the main player.
        p.stop()
        p.clearMediaItems()
    }

    fun onPause() = stop()

    fun onResume() = Unit

    fun destroy() {
        stop()
        scope.cancel()
        runCatching {
            (view?.parent as? ViewGroup)?.removeView(view)
            player?.release()
        }
    }

    private fun clean(ids: List<String>) =
        ids.map { id -> id.filter { it.isLetterOrDigit() || it == '-' || it == '_' } }.filter { it.isNotEmpty() }.distinct()
}
