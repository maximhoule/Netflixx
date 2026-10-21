package com.maxim.marquee.ui

import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.PlayerView
import com.maxim.marquee.AppModel
import com.maxim.marquee.Screen
import com.maxim.marquee.data.Http
import com.maxim.marquee.data.YouTube
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A trailer played full screen inside Marquee (the same YouTube streams as the billboard trailers,
 * in the app's own player, with sound). OK pauses, left/right skip 10 seconds, Back closes; it
 * closes by itself at the end.
 */
@OptIn(UnstableApi::class)
@Composable
fun TrailerScreen(model: AppModel, s: Screen.Trailer) {
    val ctx = LocalContext.current
    var status by remember(s) { mutableStateOf<String?>(null) }
    var loading by remember(s) { mutableStateOf(true) }
    var paused by remember(s) { mutableStateOf(false) }
    // Bumped on every key press: the title shows for a few seconds after it.
    var poke by remember(s) { mutableIntStateOf(0) }
    var showInfo by remember(s) { mutableStateOf(true) }

    val player = remember(s) {
        val http = OkHttpDataSource.Factory(Http.client.newBuilder().cache(null).build())
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(HlsMediaSource.Factory(http))
            .setTrackSelector(
                DefaultTrackSelector(ctx).apply {
                    setParameters(buildUponParameters().setMaxVideoSize(1920, 1080))
                },
            )
            // Start sharp instead of climbing up from a blurry quality.
            .setBandwidthMeter(DefaultBandwidthMeter.Builder(ctx).setInitialBitrateEstimate(8_000_000).build())
            .build()
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED && model.stack.lastOrNull() == s) model.back()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) paused = false
            }
        }
        player.addListener(l)
        onDispose {
            player.removeListener(l)
            player.release()
        }
    }

    LaunchedEffect(s) {
        for (id in s.ids) {
            val url = withContext(Dispatchers.IO) { YouTube.hlsUrl(id) } ?: continue
            val started = CompletableDeferred<Boolean>()
            val probe = object : Player.Listener {
                override fun onRenderedFirstFrame() { started.complete(true) }
                override fun onPlayerError(error: PlaybackException) { started.complete(false) }
            }
            player.addListener(probe)
            player.setMediaItem(MediaItem.Builder().setUri(url).setMimeType(MimeTypes.APPLICATION_M3U8).build())
            player.prepare()
            player.playWhenReady = true
            val ok = withTimeoutOrNull(20_000) { started.await() } == true
            player.removeListener(probe)
            if (ok) {
                loading = false
                return@LaunchedEffect
            }
            player.stop()
        }
        loading = false
        status = "This trailer can't be played right now. YouTube may be refusing it, or the TV is offline."
    }
    LaunchedEffect(poke, paused) {
        showInfo = true
        if (!paused) {
            delay(3_500)
            showInfo = false
        }
    }

    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { fr.requestFocus() }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(fr)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                poke++
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.Spacebar -> {
                        if (player.isPlaying) {
                            player.pause(); paused = true
                        } else {
                            player.play(); paused = false
                        }
                        true
                    }
                    Key.DirectionRight, Key.MediaFastForward -> { player.seekTo(player.currentPosition + 10_000); true }
                    Key.DirectionLeft, Key.MediaRewind -> { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0)); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    useController = false
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    this.player = player
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (loading) {
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = C.Red, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                Text("Loading the trailer…", color = Color.White, fontSize = 17.sp)
            }
        }
        status?.let {
            Column(Modifier.align(Alignment.Center).padding(horizontal = 120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(it, color = Color.White, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                Text("Press Back to return", color = C.Dim, fontSize = 15.sp)
            }
        }
        AnimatedVisibility(
            visible = showInfo && !loading && status == null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth(),
        ) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color(0xCC000000)))
                    .padding(start = 56.dp, end = 56.dp, top = 60.dp, bottom = 36.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (paused) {
                        Icon(PlayerIcons.Play, "Paused", tint = Color.White, modifier = Modifier.size(34.dp))
                        Spacer(Modifier.width(12.dp))
                    }
                    Column {
                        Text("TRAILER", color = C.Red, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Text(s.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "OK: pause  ·  ◀ ▶: 10 seconds  ·  Back: close",
                            color = C.Dim, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 4.dp)
                                .background(Color.Transparent, RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }
    }
}
