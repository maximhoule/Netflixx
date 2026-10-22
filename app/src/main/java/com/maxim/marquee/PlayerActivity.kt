package com.maxim.marquee

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.darkColorScheme
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.maxim.marquee.data.LocalProgress
import com.maxim.marquee.data.Repo
import com.maxim.marquee.data.Store
import com.maxim.marquee.data.StreamItem
import com.maxim.marquee.data.SubTrack
import com.maxim.marquee.data.USER_AGENT
import com.maxim.marquee.data.Video
import com.maxim.marquee.ui.EpisodeRef
import com.maxim.marquee.ui.Panel
import com.maxim.marquee.ui.PlayerHost
import com.maxim.marquee.ui.PlayerScreen
import com.maxim.marquee.ui.PlayerUiState
import com.maxim.marquee.ui.audioCodec
import com.maxim.marquee.ui.channels
import com.maxim.marquee.ui.formatTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity(), PlayerHost {

    companion object {
        fun intent(
            ctx: Context,
            req: PlayReq,
            s: StreamItem,
            subs: List<SubTrack>,
            startMs: Long,
            next: Video?,
            episodes: List<Video>,
            autoChain: Int,
        ): Intent {
            val headers = JSONObject().apply { s.headers.forEach { (k, v) -> put(k, v) } }
            val subArr = JSONArray().apply {
                subs.forEach { put(JSONObject().put("url", it.url).put("lang", it.lang).put("label", it.label)) }
            }
            val eps = JSONArray().apply {
                episodes.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("title", it.title)) }
            }
            return Intent(ctx, PlayerActivity::class.java)
                .putExtra("url", s.url)
                .putExtra("streamName", s.name.replace('\n', ' '))
                .putExtra("headers", headers.toString())
                .putExtra("subs", subArr.toString())
                .putExtra("start", startMs)
                .putExtra("type", req.type)
                .putExtra("metaId", req.metaId)
                .putExtra("videoId", req.videoId)
                .putExtra("name", req.name)
                .putExtra("poster", req.poster)
                .putExtra("episode", req.episode)
                .putExtra("nextId", next?.id)
                .putExtra("nextLabel", next?.label)
                .putExtra("nextTitle", next?.title)
                .putExtra("episodes", eps.toString())
                .putExtra("autoChain", autoChain)
                .putExtra("embyId", s.embyId)
        }
    }

    private lateinit var player: ExoPlayer
    private lateinit var playerView: PlayerView
    private val ui = PlayerUiState()

    private lateinit var url: String
    private var streamName = ""
    private var subs: List<SubTrack> = emptyList()
    private lateinit var type: String
    private lateinit var metaId: String
    private lateinit var videoId: String
    private var name = ""
    private var poster: String? = null
    private var autoChain = 0
    private var embyId: String? = null

    override val title: String get() = name
    override var episode: String? = null
    override var next: EpisodeRef? = null
    override var episodes: List<EpisodeRef> = emptyList()
    override val currentVideoId: String get() = videoId

    private var outcome = "exit"
    private var outcomeVideo: String? = null
    private var outcomeAuto = false
    private var netRetries = 0
    private var forceHls = false
    private var started = false
    private var consumedKey = -1
    private var finishing = false
    private var lastInput = System.currentTimeMillis()
    private var userInteracted = false

    private var segments: Repo.Segments? = null
    private var thumbs: com.maxim.marquee.data.SeekThumbnails? = null
    private var thumbJob: Job? = null
    /** HTTP status of the failure that ended playback (401/403 = the service refused). */
    private var httpCode = 0
    private var autoSkipped = HashSet<Long>()
    private var nextDismissed = false
    private var countdownAt = 0L
    private var frameRateApplied = false
    private var flashJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        url = intent.getStringExtra("url") ?: return finish()
        streamName = intent.getStringExtra("streamName") ?: ""
        type = intent.getStringExtra("type") ?: "movie"
        metaId = intent.getStringExtra("metaId") ?: ""
        videoId = intent.getStringExtra("videoId") ?: metaId
        name = intent.getStringExtra("name") ?: ""
        poster = intent.getStringExtra("poster")
        episode = intent.getStringExtra("episode")
        autoChain = intent.getIntExtra("autoChain", 0)
        embyId = intent.getStringExtra("embyId")
        intent.getStringExtra("nextId")?.let { id ->
            next = EpisodeRef(id, intent.getStringExtra("nextLabel") ?: "", intent.getStringExtra("nextTitle") ?: "")
        }
        episodes = runCatching {
            val a = JSONArray(intent.getStringExtra("episodes") ?: "[]")
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                EpisodeRef(o.getString("id"), o.optString("label", ""), o.optString("title", ""))
            }
        }.getOrDefault(emptyList())
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

        userAgent = headers.remove("User-Agent") ?: USER_AGENT
        httpHeaders = headers

        // This show's own audio / subtitle choices win over the general ones.
        val sp = if (type == "series" && metaId.isNotEmpty()) Store.showPrefs(metaId) else null
        prefAudio = sp?.audioLang ?: Store.audioLang
        prefSubLang = sp?.subLang ?: Store.subLang
        prefSubsOn = sp?.subsOn ?: Store.subsOn
        nightMode.enabled = Store.nightMode
        ui.nightMode = Store.nightMode

        playerView = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        applySubtitleStyle()

        player = buildPlayer()
        playerView.player = player
        // No start position = default position, which is the live edge for live streams.
        if (start > 0) player.setMediaItem(mediaItem(), start) else player.setMediaItem(mediaItem())
        player.prepare()
        player.playWhenReady = true

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                PlayerScreen(this, ui, playerView)
            }
        }

        if (type == "series") lifecycleScope.launch { segments = Repo.segments(videoId) }
        // Preview pictures while skipping: only your server's ready-made ones (Emby / Jellyfin).
        embyId?.let { id -> lifecycleScope.launch { thumbs = com.maxim.marquee.data.Emby.seekThumbnails(id) } }

        lifecycleScope.launch { ticker() }
        lifecycleScope.launch {
            var n = 0
            while (isActive) {
                delay(15_000)
                n++
                saveProgress(sync = n % 4 == 0)
            }
        }
    }

    private fun mediaItem(): MediaItem {
        val lowerUrl = url.lowercase()
        val mime = when {
            forceHls || lowerUrl.contains(".m3u8") || lowerUrl.contains("m3u8") -> MimeTypes.APPLICATION_M3U8
            lowerUrl.contains(".mpd") -> MimeTypes.APPLICATION_MPD
            else -> null
        }
        return MediaItem.Builder()
            .setUri(url)
            .apply { if (mime != null) setMimeType(mime) }
            .setMediaMetadata(MediaMetadata.Builder().setTitle(name).build())
            .build()
    }

    // ---------------------------------------------------------------- building the player

    private var userAgent = USER_AGENT
    private var httpHeaders: Map<String, String> = emptyMap()
    private var prefAudio: String? = null
    private var prefSubLang = ""
    private var prefSubsOn = false
    private val nightMode = NightModeProcessor()
    /** Set after the TV/soundbar refused Dolby/DTS passthrough: decode audio in the app instead. */
    private var forcePcm = false
    private var passthroughActive = false
    private var audioOutEncoding = C.ENCODING_INVALID
    private var audioOutChannels = 0

    /**
     * The player, set up for the best picture and sound this box can give: hardware video
     * decoding with software fallback, FFmpeg for DTS / TrueHD / anything the chip can't decode,
     * Dolby/DTS passthrough when the TV or soundbar supports it, and a deep, memory-aware buffer.
     */
    private fun buildPlayer(keep: androidx.media3.common.TrackSelectionParameters? = null): ExoPlayer {
        val client = com.maxim.marquee.data.Http.client.newBuilder()
            .cache(null)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        val http = androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(httpHeaders)

        // Night mode needs decoded sound, so it turns passthrough off while it's on.
        val passthrough = Store.passthrough && !forcePcm && !Store.nightMode
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean,
            ): androidx.media3.exoplayer.audio.AudioSink {
                @Suppress("DEPRECATION")
                val b = if (passthrough && Store.convertDts) {
                    // Pass through only Dolby Digital / Dolby Digital Plus (which every TV plays);
                    // DTS and TrueHD are decoded here so they can't come out silent.
                    val caps = androidx.media3.exoplayer.audio.AudioCapabilities.getCapabilities(context)
                    val keep = intArrayOf(C.ENCODING_AC3, C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC, C.ENCODING_AC4)
                        .filter { caps.supportsEncoding(it) }
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder().setAudioCapabilities(
                        androidx.media3.exoplayer.audio.AudioCapabilities(
                            (listOf(C.ENCODING_PCM_16BIT) + keep).toIntArray(),
                            caps.maxChannelCount,
                        ),
                    )
                } else if (passthrough) {
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                } else {
                    // Without a context the sink assumes no passthrough support: everything is decoded here.
                    androidx.media3.exoplayer.audio.DefaultAudioSink.Builder()
                }
                return b.setEnableFloatOutput(false)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(nightMode))
                    .build()
            }
        }
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            // TV boxes often ship a DTS / TrueHD decoder that "works" but only outputs silence
            // (no licence). With conversion on, those are skipped so the app's own FFmpeg decoder
            // handles them.
            .setMediaCodecSelector { mime, secure, tunneling ->
                val m = mime.lowercase()
                if (Store.convertDts && (m.startsWith("audio/vnd.dts") || m == "audio/true-hd" || m == "audio/mlp")) {
                    emptyList()
                } else {
                    androidx.media3.exoplayer.mediacodec.MediaCodecSelector.DEFAULT.getDecoderInfos(mime, secure, tunneling)
                }
            }
            .setEnableDecoderFallback(true)

        val trackSelector = DefaultTrackSelector(this)
        if (keep == null) {
            val b = trackSelector.buildUponParameters()
                .setPreferredAudioLanguage(prefAudio)
                .setExceedRendererCapabilitiesIfNecessary(true)
            if (prefSubsOn) {
                b.setPreferredTextLanguage(prefSubLang.ifBlank { null })
            } else {
                forcedOnly(b)
            }
            trackSelector.setParameters(b)
        }

        // Buffer by memory, not just time: a 4K remux at 80 Mbps fills 100 MB in 10 s.
        val heap = Runtime.getRuntime().maxMemory()
        val bytes = (heap * 0.3).toLong().coerceIn(32L shl 20, 300L shl 20).toInt()
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(50_000, 180_000, 1_500, 4_000)
            .setTargetBufferBytes(bytes)
            .setPrioritizeTimeOverSizeThresholds(false)
            // Keep the last 30 s so "back 10 s" is instant instead of re-downloading.
            .setBackBuffer(30_000, true)
            .build()

        val sources = DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http))
            .setLoadErrorHandlingPolicy(androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy(6))

        val p = ExoPlayer.Builder(this, renderers)
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(sources)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        p.setAudioAttributes(
            androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
            true,
        )
        if (keep != null) p.trackSelectionParameters = keep
        p.addListener(listener)
        p.addAnalyticsListener(analytics)
        return p
    }

    /** Subtitles "off", Netflix-style: still shows forced subtitles (foreign-language bits) for your audio language. */
    private fun forcedOnly(b: androidx.media3.common.TrackSelectionParameters.Builder) {
        b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguage(null)
            .setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
    }

    /** Recreates the player with new audio settings and carries on from the same spot. */
    private fun rebuildPlayer() {
        if (!::player.isInitialized || finishing) return
        val pos = player.currentPosition
        val play = player.playWhenReady
        val params = player.trackSelectionParameters
        player.removeListener(listener)
        player.release()
        player = buildPlayer(params)
        playerView.player = player
        if (player.isCurrentMediaItemLive || pos <= 0) player.setMediaItem(mediaItem()) else player.setMediaItem(mediaItem(), pos)
        player.prepare()
        player.playWhenReady = play
    }

    private val analytics = object : androidx.media3.exoplayer.analytics.AnalyticsListener {
        override fun onAudioTrackInitialized(
            eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
            audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig,
        ) {
            audioOutEncoding = audioTrackConfig.encoding
            audioOutChannels = Integer.bitCount(audioTrackConfig.channelConfig)
            passthroughActive = !androidx.media3.common.util.Util.isEncodingLinearPcm(audioTrackConfig.encoding)
        }
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            ui.buffering = state == Player.STATE_BUFFERING || (state == Player.STATE_IDLE && !started)
            if (state == Player.STATE_READY) started = true
            if (state == Player.STATE_ENDED) onEnded()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            ui.playing = isPlaying
            if (!finishing) scrobble(if (isPlaying) "start" else "pause")
        }

        override fun onTracksChanged(tracks: Tracks) {
            ui.tracks = tracks
            matchFrameRate()
            if (tracks.groups.isEmpty()) return
            // A video or audio format this box can't decode would give a black picture or silence:
            // treat it as a failed source so the next one is tried.
            if (!started) {
                fun unplayable(kind: Int): Boolean {
                    val groups = tracks.groups.filter { it.type == kind }
                    return groups.isNotEmpty() && groups.none { g -> (0 until g.length).any { g.isTrackSupported(it, true) } }
                }
                when {
                    unplayable(C.TRACK_TYPE_VIDEO) -> { failReason = "video"; outcome = "error"; finish(); return }
                    unplayable(C.TRACK_TYPE_AUDIO) -> { failReason = "audio"; outcome = "error"; finish(); return }
                }
            }
            autoPickSubtitles(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            val pos = player.currentPosition
            val live = player.isCurrentMediaItemLive
            val code = error.errorCode
            when {
                // Live stream fell behind the window: jump back to the live edge.
                code == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    player.seekToDefaultPosition(); player.prepare(); player.play()
                    return
                }
                // The TV/soundbar refused the Dolby/DTS stream: decode it here instead and carry on.
                code in 5001..5004 && !forcePcm -> {
                    forcePcm = true
                    flash("Switched to decoded audio")
                    lifecycleScope.launch { rebuildPlayer() }
                    return
                }
                // Unrecognised container: many IPTV/live links are HLS without ".m3u8" in the URL.
                !started && code == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED && !forceHls -> {
                    forceHls = true
                    player.setMediaItem(mediaItem())
                    player.prepare(); player.play()
                    return
                }
                // Network hiccup mid-show: reconnect from the same spot, backing off a little each time.
                started && code in 2000..2999 && netRetries < 4 -> {
                    netRetries++
                    flash("Reconnecting…")
                    lifecycleScope.launch {
                        delay(1_000L * netRetries)
                        player.prepare()
                        if (!live) player.seekTo(pos)
                        player.play()
                    }
                    return
                }
            }
            // Unrecoverable: let the app switch to the next source (it resumes at the same spot).
            var cause: Throwable? = error
            while (cause != null && cause !is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) cause = cause.cause
            httpCode = (cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode ?: 0
            if (code in 4000..4999) failReason = "decoder"
            outcome = "error"
            finish()
        }
    }

    /** Why playback gave up: "video" / "audio" (format not supported) or "decoder". */
    private var failReason: String? = null

    // ---------------------------------------------------------------- addon subtitles (drawn by the app)

    override val externalSubs: List<SubTrack> get() = subs
    private var extCues: List<com.maxim.marquee.data.Subtitles.Cue>? = null
    private var extJob: Job? = null
    private var autoSubsDone = false

    /**
     * Subtitles on: if the video has its own track in your language the player picks it; otherwise
     * the best matching addon subtitle is loaded automatically.
     */
    private fun autoPickSubtitles(tracks: Tracks) {
        if (autoSubsDone) return
        autoSubsDone = true
        if (!prefSubsOn || subs.isEmpty()) return
        val want = Store.normLang(prefSubLang.ifBlank { "eng" })
        val embedded = tracks.groups.any { g ->
            g.type == C.TRACK_TYPE_TEXT && (0 until g.length).any { i ->
                g.getTrackFormat(i).language?.let { Store.normLang(it) == want } == true &&
                    g.getTrackFormat(i).selectionFlags and C.SELECTION_FLAG_FORCED == 0
            }
        }
        if (embedded) return
        val i = subs.indexOfFirst { Store.normLang(it.lang) == want }.takeIf { it >= 0 } ?: return
        loadExternal(i, remember = false)
    }

    override fun selectExternal(index: Int) = loadExternal(index, remember = true)

    private fun loadExternal(index: Int, remember: Boolean) {
        val st = subs.getOrNull(index) ?: return
        extJob?.cancel()
        extCues = null
        ui.extText = null
        ui.extIndex = index
        // The player's own subtitles off while an addon subtitle is showing.
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        if (remember) rememberSubs(true, st.lang)
        extJob = lifecycleScope.launch {
            val cues = com.maxim.marquee.data.Subtitles.load(st.url)
            if (cues == null) {
                flash("Couldn't load those subtitles")
                if (ui.extIndex == index) ui.extIndex = -1
                return@launch
            }
            extCues = cues
            // Draw them in step with the video (finer than the 250 ms control ticker).
            while (isActive && ui.extIndex == index) {
                val c = com.maxim.marquee.data.Subtitles.at(cues, player.currentPosition - ui.subDelay)
                val t = c?.text
                if (t != ui.extText) ui.extText = t
                delay(50)
            }
        }
    }

    private fun clearExternal() {
        extJob?.cancel()
        extCues = null
        ui.extIndex = -1
        ui.extText = null
    }

    override fun shiftSubtitles(deltaMs: Long) {
        ui.subDelay = if (deltaMs == 0L) 0 else ui.subDelay + deltaMs
        val s = ui.subDelay / 1000.0
        flash(if (ui.subDelay == 0L) "Subtitle timing reset" else "Subtitles " + (if (s > 0) "+" else "") + String.format(java.util.Locale.US, "%.1f s", s))
    }

    private fun rememberSubs(on: Boolean, lang: String?) {
        Store.subsOn = on
        lang?.takeIf { it != "und" }?.let { Store.subLang = it }
        if (type == "series" && metaId.isNotEmpty()) Store.saveShowPrefs(metaId, subLang = lang?.takeIf { it != "und" }, subsOn = on)
    }

    // ---------------------------------------------------------------- night mode

    override fun setNightMode(on: Boolean) {
        Store.nightMode = on
        nightMode.enabled = on
        ui.nightMode = on
        flash(if (on) "Night mode on" else "Night mode off")
        // Passthrough sends the sound out untouched, so night mode needs the player to decode it.
        if (on && passthroughActive) rebuildPlayer()
    }

    // ---------------------------------------------------------------- ticker: progress, skip, next

    private suspend fun ticker() {
        var lastSecond = 0L
        while (true) {
            delay(250)
            if (!::player.isInitialized) continue
            val pos = player.currentPosition
            val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L
            ui.position = pos
            ui.duration = dur
            ui.live = player.isCurrentMediaItemLive
            ui.buffered = player.bufferedPosition
            val now = System.currentTimeMillis()

            // Skip intro / recap
            val seg = segments
            val active = listOfNotNull(
                seg?.intro?.let { Triple("Skip Intro", it.startMs, it.endMs) },
                seg?.recap?.let { Triple("Skip Recap", it.startMs, it.endMs) },
            ).firstOrNull { pos >= it.second && pos < it.third - 1_500 }
            if (active != null && Store.autoSkipIntro && autoSkipped.add(active.second)) {
                player.seekTo(active.third)
                flash(active.first.replace("Skip", "Skipped"))
                ui.skip = null
            } else {
                ui.skip = active?.let { it.first to it.third }
            }

            // Next episode card at the credits
            val nx = next
            if (nx != null && dur > 10 * 60_000L) {
                val creditsAt = seg?.outro?.startMs?.takeIf { it < dur } ?: (dur - 40_000)
                if (pos >= creditsAt && !nextDismissed) {
                    if (!ui.nextCard) {
                        ui.nextCard = true
                        ui.stillWatching = autoChain >= 3 && !userInteracted
                        ui.countdown = if (ui.stillWatching) -1 else 10
                        countdownAt = now
                    }
                } else if (pos < creditsAt - 5_000 && ui.nextCard) {
                    ui.nextCard = false
                }
                if (ui.nextCard && ui.countdown > 0 && ui.playing && now - countdownAt >= 1_000) {
                    countdownAt = now
                    ui.countdown -= 1
                    if (ui.countdown == 0) playNext(auto = true)
                }
            }

            // Auto-hide the controls after a few idle seconds
            if (ui.controls && ui.panel == Panel.NONE && ui.playing && now - lastInput > 5_000) ui.controls = false

            if (now - lastSecond >= 1_000) {
                lastSecond = now
                if (ui.panel == Panel.INFO) ui.info = infoText()
                ui.badges = qualityBadges()
            }
        }
    }

    private fun flash(text: String) {
        ui.seekFlash = text
        flashJob?.cancel()
        flashJob = lifecycleScope.launch {
            delay(1_200)
            ui.seekFlash = null
        }
    }

    private fun onEnded() {
        if (next != null && Store.autoNext && !(autoChain >= 3 && !userInteracted)) {
            playNext(auto = true)
        } else {
            outcome = "ended"
            finish()
        }
    }

    // ---------------------------------------------------------------- remote control

    private fun showControls() {
        ui.controls = true
        lastInput = System.currentTimeMillis()
        ui.focusTick++
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!::player.isInitialized) return super.dispatchKeyEvent(event)
        val code = event.keyCode
        val down = event.action == KeyEvent.ACTION_DOWN
        if (down) {
            lastInput = System.currentTimeMillis()
            userInteracted = true
        }
        if (!down && code == consumedKey) {
            consumedKey = -1
            return true
        }
        fun consume(): Boolean {
            consumedKey = code
            return true
        }
        if (down) {
            when (code) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return consume() }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { player.play(); return consume() }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { player.pause(); showControls(); return consume() }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(30_000); return consume() }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-30_000); return consume() }
                KeyEvent.KEYCODE_MEDIA_NEXT -> { if (next != null) playNext(false); return consume() }
            }
        }

        // An open panel: Back closes it, everything else navigates the panel.
        if (ui.panel != Panel.NONE) {
            if (down && code == KeyEvent.KEYCODE_BACK) {
                ui.panel = Panel.NONE
                showControls()
                return consume()
            }
            return super.dispatchKeyEvent(event)
        }

        if (ui.controls) {
            if (down && code == KeyEvent.KEYCODE_BACK) {
                ui.controls = false
                return consume()
            }
            return super.dispatchKeyEvent(event)
        }

        // Controls hidden.
        if (ui.nextCard) {
            if (down && code == KeyEvent.KEYCODE_BACK) {
                dismissNext()
                return consume()
            }
            if (code == KeyEvent.KEYCODE_DPAD_UP) {
                if (down) showControls()
                return consume()
            }
            return super.dispatchKeyEvent(event)
        }
        if (down) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (ui.skip != null) skip() else {
                        togglePlay()
                        showControls()
                    }
                    return consume()
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val r = event.repeatCount
                    val step = if (r < 3) 10_000L else if (r < 15) 30_000L else 60_000L
                    seekBy(if (code == KeyEvent.KEYCODE_DPAD_LEFT) -step else step)
                    return consume()
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> {
                    showControls()
                    return consume()
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------------------------------------------------------------- PlayerHost

    override fun togglePlay() {
        if (player.isPlaying) player.pause() else {
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }
    }

    override fun seekBy(ms: Long) {
        val dur = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val target = (player.currentPosition + ms).coerceIn(0, dur)
        player.seekTo(target)
        ui.position = target
        if (!ui.controls) flash((if (ms < 0) "◀◀  " else "▶▶  ") + formatTime(target))
        showThumb(target)
    }

    /** Preview picture of where you're seeking to, while you're seeking. */
    private fun showThumb(pos: Long) {
        val t = thumbs ?: return
        thumbJob?.cancel()
        thumbJob = lifecycleScope.launch {
            t.frame(pos)?.let { ui.thumb = it.asImageBitmap() }
            delay(1_400)
            ui.thumb = null
        }
    }

    override fun skip() {
        val s = ui.skip ?: return
        player.seekTo(s.second)
        ui.skip = null
    }

    override fun playNext(auto: Boolean) {
        val n = next ?: return
        outcome = "next"
        outcomeVideo = n.id
        outcomeAuto = auto
        finish()
    }

    override fun dismissNext() {
        nextDismissed = true
        ui.nextCard = false
        ui.countdown = -1
    }

    override fun openEpisode(id: String) {
        outcome = "episode"
        outcomeVideo = id
        finish()
    }

    override fun openPanel(p: Panel) {
        ui.panel = p
        if (p == Panel.INFO) ui.info = infoText()
    }

    override fun selectAudio(group: Tracks.Group, index: Int) {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            .build()
        group.getTrackFormat(index).language?.takeIf { it != "und" }?.let { lang ->
            Store.audioLang = lang
            if (type == "series" && metaId.isNotEmpty()) Store.saveShowPrefs(metaId, audioLang = lang)
        }
    }

    override fun selectSubtitle(group: Tracks.Group?, index: Int) {
        clearExternal()
        val b = player.trackSelectionParameters.buildUpon()
        if (group == null) {
            // "Off" still shows forced subtitles (signs, foreign-language lines).
            forcedOnly(b)
            rememberSubs(false, null)
        } else {
            b.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setIgnoredTextSelectionFlags(0)
                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, index))
            rememberSubs(true, group.getTrackFormat(index).language)
        }
        player.trackSelectionParameters = b.build()
    }

    override fun setSpeed(v: Float) {
        player.setPlaybackSpeed(v)
        ui.speed = v
    }

    override fun setResize(mode: Int) {
        ui.resize = mode
    }

    override fun subSize(): Int = Store.subSize
    override fun subStyle(): Int = Store.subStyle

    override fun setSubSize(v: Int) {
        Store.subSize = v
        applySubtitleStyle()
        ui.settingsTick++
    }

    override fun setSubStyle(v: Int) {
        Store.subStyle = v
        applySubtitleStyle()
        ui.settingsTick++
    }

    private fun applySubtitleStyle() {
        val sv = playerView.subtitleView ?: return
        val scale = when (Store.subSize) {
            0 -> 0.8f
            2 -> 1.3f
            3 -> 1.6f
            else -> 1.05f
        }
        sv.setApplyEmbeddedFontSizes(false)
        sv.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * scale)
        sv.setBottomPaddingFraction(0.07f)
        sv.setStyle(
            if (Store.subStyle == 1) {
                CaptionStyleCompat(Color.WHITE, Color.argb(190, 0, 0, 0), Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_NONE, Color.BLACK, Typeface.DEFAULT_BOLD)
            } else {
                CaptionStyleCompat(Color.WHITE, Color.TRANSPARENT, Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, Color.BLACK, Typeface.DEFAULT_BOLD)
            },
        )
    }

    // ---------------------------------------------------------------- display / info

    /** Switches the TV to the video's frame rate (24p etc.) when the setting is on. */
    private fun matchFrameRate() {
        if (frameRateApplied || !Store.matchFrameRate) return
        val fps = player.videoFormat?.frameRate ?: return
        if (fps <= 0f) return
        frameRateApplied = true
        try {
            @Suppress("DEPRECATION")
            val display = windowManager.defaultDisplay
            val cur = display.mode
            val best = display.supportedModes
                .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
                .mapNotNull { m ->
                    val err = (1..3).minOf { k -> abs(m.refreshRate - fps * k) / (fps * k) }
                    if (err < 0.002f) m to err else null
                }
                .minByOrNull { it.second }?.first ?: return
            if (best.modeId != cur.modeId) {
                val lp = window.attributes
                lp.preferredDisplayModeId = best.modeId
                window.attributes = lp
            }
        } catch (e: Exception) {
            // Some boxes don't allow mode switching; ignore.
        }
    }

    private fun encodingName(e: Int): String = when (e) {
        C.ENCODING_AC3 -> "Dolby Digital"
        C.ENCODING_E_AC3 -> "Dolby Digital+"
        C.ENCODING_E_AC3_JOC -> "Dolby Atmos"
        C.ENCODING_AC4 -> "Dolby AC-4"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        else -> "encoded"
    }

    /** "4K · Dolby Vision · Dolby Atmos" for the player's title area. */
    private fun qualityBadges(): List<String> {
        val out = mutableListOf<String>()
        player.videoFormat?.let { v ->
            val h = minOf(v.height, v.width).takeIf { it > 0 } ?: v.height
            val w = maxOf(v.height, v.width)
            when {
                w >= 3200 || h >= 2000 -> out += "4K"
                w >= 2400 || h >= 1400 -> out += "1440p"
                w >= 1800 || h >= 1000 -> out += "1080p"
                w >= 1200 || h >= 700 -> out += "720p"
                h > 0 -> out += "SD"
            }
            when {
                v.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION || v.codecs?.startsWith("dv") == true -> out += "Dolby Vision"
                v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> out += "HDR10"
                v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> out += "HLG"
            }
        }
        player.audioFormat?.let { a ->
            when (a.sampleMimeType) {
                MimeTypes.AUDIO_E_AC3_JOC -> out += "Dolby Atmos"
                MimeTypes.AUDIO_TRUEHD -> out += "Dolby TrueHD"
                MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_X -> out += "DTS-HD"
                MimeTypes.AUDIO_DTS -> out += "DTS"
                MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_AC3 -> out += "Dolby Digital"
            }
            channels(a.channelCount)?.takeIf { a.channelCount > 2 }?.let { out += it }
        }
        return out
    }

    private fun infoText(): String {
        val v = player.videoFormat
        val a = player.audioFormat
        val lines = mutableListOf<String>()
        if (streamName.isNotBlank()) lines += "Source: $streamName"
        if (v != null) {
            val codec = when (v.sampleMimeType) {
                "video/hevc" -> "HEVC"
                "video/avc" -> "H.264"
                "video/av01" -> "AV1"
                "video/x-vnd.on2.vp9" -> "VP9"
                "video/dolby-vision" -> "Dolby Vision"
                else -> v.sampleMimeType ?: "?"
            }
            val dynamic = when {
                v.sampleMimeType == "video/dolby-vision" || v.codecs?.startsWith("dv") == true -> "Dolby Vision"
                v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084 -> "HDR10"
                v.colorInfo?.colorTransfer == C.COLOR_TRANSFER_HLG -> "HLG"
                else -> "SDR"
            }
            val fps = if (v.frameRate > 0) String.format(java.util.Locale.US, " · %.3f fps", v.frameRate) else ""
            lines += "Video: ${v.width}×${v.height}$fps · $codec · $dynamic"
            if (v.bitrate > 0) lines += "Video bitrate: ${"%.1f".format(v.bitrate / 1_000_000f)} Mbps"
        }
        if (a != null) {
            lines += "Audio: " + listOfNotNull(
                com.maxim.marquee.ui.languageName(a.language), channels(a.channelCount), audioCodec(a) ?: a.sampleMimeType,
            ).joinToString(" · ")
        }
        lines += "Audio output: " + when {
            audioOutEncoding == C.ENCODING_INVALID -> "starting…"
            passthroughActive -> "passthrough to TV / soundbar (" + encodingName(audioOutEncoding) + ")"
            else -> "decoded here, $audioOutChannels channels" + if (ui.nightMode) " · night mode" else ""
        }
        lines += "Buffered ahead: ${player.totalBufferedDuration / 1000} s"
        lines += "Dropped frames: ${player.videoDecoderCounters?.droppedBufferCount ?: 0}"
        try {
            @Suppress("DEPRECATION")
            val m = windowManager.defaultDisplay.mode
            lines += "Display: ${m.physicalWidth}×${m.physicalHeight} @ ${"%.3f".format(m.refreshRate)} Hz"
        } catch (e: Exception) {
            // ignore
        }
        return lines.joinToString("\n")
    }

    // ---------------------------------------------------------------- progress + exit

    private fun saveProgress(sync: Boolean, stopped: Boolean = false) {
        if (!::player.isInitialized) return
        val dur = player.duration
        val pos = player.currentPosition
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val finished = outcome == "ended" || outcome == "next" || pos >= dur * 0.92
        val p = if (finished) dur else pos
        Store.saveProgress(LocalProgress(metaId, type, name, poster, videoId, p, dur, System.currentTimeMillis()))
        if (sync && Store.syncProgress) {
            val t = type; val m = metaId; val v = videoId; val nx = next?.id; val n = name; val ps = poster
            AppScope.launch { Repo.syncProgress(t, m, v, p, dur, finished, nx, n, ps, stopped) }
            // Played from your Emby server: keep Emby's resume point and watched state in step too.
            val e = embyId
            if (e != null && e != v) AppScope.launch { runCatching { com.maxim.marquee.data.Emby.report(e, p, finished, stopped) } }
        }
    }

    /** Tells Trakt what's playing (start / pause / stop with % watched). */
    private fun scrobble(action: String) {
        if (!started || !com.maxim.marquee.data.Trakt.connected) return
        val dur = player.duration
        if (dur <= 0 || dur == C.TIME_UNSET) return
        val pct = if (outcome == "ended" || outcome == "next") 100f else player.currentPosition * 100f / dur
        val t = type; val v = videoId
        AppScope.launch { runCatching { com.maxim.marquee.data.Trakt.scrobble(action, t, v, pct) } }
    }

    override fun finish() {
        if (!finishing) {
            if (started && ::player.isInitialized) scrobble("stop")
            finishing = true
            if (started) saveProgress(sync = true, stopped = true)
            setResult(
                RESULT_OK,
                Intent().putExtra("outcome", outcome).putExtra("videoId", outcomeVideo).putExtra("auto", outcomeAuto)
                    .putExtra("httpCode", httpCode).putExtra("reason", failReason),
            )
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
        thumbs?.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
