package com.maxim.marquee.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C as MC
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.util.Locale

enum class Panel { NONE, AUDIO_SUBS, SPEED, PICTURE, EPISODES, INFO }

data class EpisodeRef(val id: String, val label: String, val title: String)

/** Everything the on-screen controls show; written by the player activity, read by Compose. */
@Stable
class PlayerUiState {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var playing by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var controls by mutableStateOf(false)
    var panel by mutableStateOf(Panel.NONE)
    var tracks by mutableStateOf(Tracks.EMPTY)
    var speed by mutableFloatStateOf(1f)
    var resize by mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT)
    /** Label + where to jump, while inside an intro or recap. */
    var skip by mutableStateOf<Pair<String, Long>?>(null)
    var nextCard by mutableStateOf(false)
    var countdown by mutableIntStateOf(-1)
    var stillWatching by mutableStateOf(false)
    var info by mutableStateOf("")
    var focusTick by mutableIntStateOf(0)
    var settingsTick by mutableIntStateOf(0)
    var seekFlash by mutableStateOf<String?>(null)
    var live by mutableStateOf(false)
}

interface PlayerHost {
    val title: String
    val episode: String?
    val next: EpisodeRef?
    val episodes: List<EpisodeRef>
    val currentVideoId: String
    fun togglePlay()
    fun seekBy(ms: Long)
    fun skip()
    fun playNext(auto: Boolean)
    fun dismissNext()
    fun openEpisode(id: String)
    fun openPanel(p: Panel)
    fun selectAudio(group: Tracks.Group, index: Int)
    fun selectSubtitle(group: Tracks.Group?, index: Int)
    fun setSpeed(v: Float)
    fun setResize(mode: Int)
    fun setSubSize(v: Int)
    fun setSubStyle(v: Int)
    fun subSize(): Int
    fun subStyle(): Int
}

private fun tryFocus(fr: FocusRequester) {
    try {
        fr.requestFocus()
    } catch (e: Exception) {
        // not attached
    }
}

fun formatTime(ms: Long): String {
    val t = (ms.coerceAtLeast(0) / 1000)
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

fun languageName(code: String?): String? {
    if (code.isNullOrBlank() || code == "und") return null
    val name = try {
        Locale.forLanguageTag(code).getDisplayLanguage(Locale.getDefault())
    } catch (e: Exception) {
        null
    }
    return name?.takeIf { it.isNotBlank() && !it.equals(code, ignoreCase = true) }?.replaceFirstChar { it.uppercase() } ?: code
}

fun audioCodec(f: Format): String? = when (f.sampleMimeType) {
    "audio/eac3-joc" -> "Dolby Atmos"
    "audio/eac3" -> "Dolby Digital+"
    "audio/ac3" -> "Dolby Digital"
    "audio/true-hd" -> "TrueHD"
    "audio/vnd.dts.hd" -> "DTS-HD"
    "audio/vnd.dts.uhd" -> "DTS:X"
    "audio/vnd.dts" -> "DTS"
    "audio/mp4a-latm" -> "AAC"
    "audio/opus" -> "Opus"
    "audio/flac" -> "FLAC"
    "audio/mpeg" -> "MP3"
    else -> null
}

fun channels(n: Int): String? = when (n) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> if (n > 0) "$n ch" else null
}

private fun audioLabel(f: Format): String {
    val parts = mutableListOf<String>()
    parts += languageName(f.language) ?: f.label ?: "Audio"
    f.label?.let { l -> if (parts.none { it.equals(l, true) }) parts += l }
    channels(f.channelCount)?.let { parts += it }
    audioCodec(f)?.let { parts += it }
    return parts.joinToString(" · ")
}

private fun subtitleLabel(f: Format): String {
    val parts = mutableListOf<String>()
    parts += languageName(f.language) ?: f.label ?: "Subtitles"
    f.label?.let { l -> if (parts.none { it.equals(l, true) }) parts += l }
    if (f.selectionFlags and MC.SELECTION_FLAG_FORCED != 0) parts += "Forced"
    return parts.joinToString(" · ")
}

private data class TrackOpt(val group: Tracks.Group?, val index: Int, val label: String, val selected: Boolean)

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(host: PlayerHost, ui: PlayerUiState, playerView: PlayerView) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { playerView }, modifier = Modifier.fillMaxSize(), update = { it.resizeMode = ui.resize })

        if (ui.buffering) {
            CircularProgressIndicator(color = C.Red, strokeWidth = 4.dp, modifier = Modifier.align(Alignment.Center).size(56.dp))
        }

        ui.seekFlash?.let {
            if (!ui.controls) {
                Text(
                    it, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(10.dp))
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
        }

        if (ui.controls) Controls(host, ui)

        val bottomPad = if (ui.controls) 200.dp else 64.dp
        ui.skip?.let { (label, _) ->
            if (!ui.nextCard) {
                Box(Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = bottomPad)) {
                    Tile(shape = RoundedCornerShape(6.dp), focusedScale = 1.05f, border = false, onClick = { host.skip() }) { f ->
                        Row(
                            Modifier.background(if (f) Color.White else Color(0xD91A1A1A)).padding(horizontal = 22.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(PlayerIcons.Next, null, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(label, color = if (f) Color.Black else Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        val next = host.next
        if (ui.nextCard && next != null) {
            val nextFr = remember { FocusRequester() }
            LaunchedEffect(Unit) {
                withFrameNanos { }
                tryFocus(nextFr)
            }
            Column(
                Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = bottomPad).width(440.dp)
                    .background(Color(0xEB141414), RoundedCornerShape(10.dp)).padding(20.dp),
            ) {
                Text(if (ui.stillWatching) "Are you still watching?" else "Next Episode", color = C.Dim, fontSize = 14.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "${next.label}  ${next.title}", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionButton(
                        text = when {
                            ui.stillWatching -> "Keep watching"
                            ui.countdown >= 0 -> "Next Episode  ${ui.countdown}"
                            else -> "Next Episode"
                        },
                        icon = PlayerIcons.Play,
                        focusRequester = nextFr,
                        onClick = { host.playNext(false) },
                    )
                    ActionButton(text = "Watch Credits", onClick = { host.dismissNext() })
                }
            }
        }

        if (ui.panel != Panel.NONE) PanelOverlay(host, ui)
    }
}

@Composable
private fun Controls(host: PlayerHost, ui: PlayerUiState) {
    val playFr = remember { FocusRequester() }
    LaunchedEffect(ui.focusTick) {
        if (ui.panel == Panel.NONE) {
            withFrameNanos { }
            tryFocus(playFr)
        }
    }
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().height(170.dp)
                .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))),
        )
        Column(Modifier.align(Alignment.TopStart).padding(start = 48.dp, top = 30.dp)) {
            Text(host.title, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            host.episode?.let { Text(it, color = C.Dim, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        Box(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(280.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEB000000)))),
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 48.dp, end = 48.dp, bottom = 30.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTime(ui.position), color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.width(16.dp))
                SeekBar(host, ui, Modifier.weight(1f))
                Spacer(Modifier.width(16.dp))
                if (ui.live) {
                    Text(
                        "LIVE", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(C.Red, RoundedCornerShape(3.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                } else {
                    Text("-" + formatTime(ui.duration - ui.position), color = Color.White, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PlayerButton(
                    if (ui.playing) PlayerIcons.Pause else PlayerIcons.Play,
                    if (ui.playing) "Pause" else "Play",
                    fr = playFr,
                ) { host.togglePlay() }
                PlayerButton(PlayerIcons.Replay, "Back 10s") { host.seekBy(-10_000) }
                PlayerButton(PlayerIcons.Forward, "Forward 10s") { host.seekBy(10_000) }
                if (host.episodes.size > 1) PlayerButton(PlayerIcons.Episodes, "Episodes") { host.openPanel(Panel.EPISODES) }
                PlayerButton(PlayerIcons.Subtitles, "Audio & Subtitles") { host.openPanel(Panel.AUDIO_SUBS) }
                PlayerButton(PlayerIcons.Speed, "Speed" + if (ui.speed != 1f) " ${ui.speed}x" else "") { host.openPanel(Panel.SPEED) }
                PlayerButton(PlayerIcons.Picture, "Picture") { host.openPanel(Panel.PICTURE) }
                PlayerButton(Icons.Filled.Info, "Info") { host.openPanel(Panel.INFO) }
                Spacer(Modifier.weight(1f))
                if (host.next != null) PlayerButton(PlayerIcons.Next, "Next Episode", alwaysLabel = true) { host.playNext(false) }
            }
        }
    }
}

@Composable
private fun PlayerButton(
    icon: ImageVector,
    label: String,
    fr: FocusRequester? = null,
    alwaysLabel: Boolean = false,
    onClick: () -> Unit,
) {
    Tile(shape = RoundedCornerShape(24.dp), focusedScale = 1.08f, border = false, focusRequester = fr, onClick = onClick) { f ->
        Row(
            Modifier.height(48.dp).background(if (f) Color.White else Color(0x33FFFFFF))
                .padding(horizontal = if (f || alwaysLabel) 18.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, label, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(26.dp))
            if (f || alwaysLabel) {
                Spacer(Modifier.width(8.dp))
                Text(label, color = if (f) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun SeekBar(host: PlayerHost, ui: PlayerUiState, modifier: Modifier) {
    var focused by remember { mutableStateOf(false) }
    val dur = ui.duration.coerceAtLeast(1)
    val frac = (ui.position.toFloat() / dur).coerceIn(0f, 1f)
    val buf = (ui.buffered.toFloat() / dur).coerceIn(0f, 1f)
    Box(
        modifier
            .height(30.dp)
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                val r = e.nativeKeyEvent.repeatCount
                val step = when {
                    r < 3 -> 10_000L
                    r < 15 -> 30_000L
                    else -> 60_000L
                }
                when (e.key) {
                    Key.DirectionLeft -> { host.seekBy(-step); true }
                    Key.DirectionRight -> { host.seekBy(step); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { host.togglePlay(); true }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier.fillMaxWidth().height(if (focused) 8.dp else 5.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x4DFFFFFF)),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(buf).background(Color(0x66FFFFFF)))
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(C.Red))
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val d = if (focused) 20.dp else 12.dp
            Box(Modifier.offset(x = maxWidth * frac - d / 2).size(d).background(C.Red, CircleShape))
        }
    }
}

// ---------------------------------------------------------------- panels

@Composable
private fun PanelOverlay(host: PlayerHost, ui: PlayerUiState) {
    val wide = ui.panel == Panel.AUDIO_SUBS
    Box(Modifier.fillMaxSize().background(Color(0x80000000))) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(if (wide) 780.dp else 440.dp)
                .background(Color(0xF5141414)).padding(start = 32.dp, end = 32.dp, top = 36.dp),
        ) {
            when (ui.panel) {
                Panel.AUDIO_SUBS -> AudioSubsPanel(host, ui)
                Panel.SPEED -> SpeedPanel(host, ui)
                Panel.PICTURE -> PicturePanel(host, ui)
                Panel.EPISODES -> EpisodesPanel(host)
                Panel.INFO -> InfoPanel(ui)
                Panel.NONE -> Unit
            }
        }
    }
}

@Composable
private fun PanelTitle(t: String) {
    Text(t, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 14.dp))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, fr: FocusRequester? = null, sub: String? = null, onClick: () -> Unit) {
    Tile(modifier = Modifier.fillMaxWidth(), focusedScale = 1.02f, border = false, focusRequester = fr, onClick = onClick) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) Color.White else Color.Transparent).padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(30.dp)) {
                if (selected) Icon(PlayerIcons.Check, null, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(20.dp))
            }
            Column {
                Text(
                    label, color = if (f) Color.Black else if (selected) Color.White else Color(0xFFCCCCCC),
                    fontSize = 16.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                sub?.let { Text(it, color = if (f) Color(0xFF444444) else C.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
    }
}

@Composable
private fun FocusFirstSelected(fr: FocusRequester) {
    LaunchedEffect(Unit) {
        withFrameNanos { }
        tryFocus(fr)
    }
}

@Composable
private fun AudioSubsPanel(host: PlayerHost, ui: PlayerUiState) {
    val audio = mutableListOf<TrackOpt>()
    val subs = mutableListOf<TrackOpt>()
    var anySubSelected = false
    ui.tracks.groups.forEach { g ->
        for (i in 0 until g.length) {
            if (!g.isTrackSupported(i)) continue
            val f = g.getTrackFormat(i)
            when (g.type) {
                MC.TRACK_TYPE_AUDIO -> audio += TrackOpt(g, i, audioLabel(f), g.isTrackSelected(i))
                MC.TRACK_TYPE_TEXT -> {
                    if (g.isTrackSelected(i)) anySubSelected = true
                    subs += TrackOpt(g, i, subtitleLabel(f), g.isTrackSelected(i))
                }
            }
        }
    }
    val allSubs = listOf(TrackOpt(null, -1, "Off", !anySubSelected)) + subs
    val audioFr = remember { FocusRequester() }
    val subFr = remember { FocusRequester() }
    FocusFirstSelected(if (audio.isNotEmpty()) audioFr else subFr)

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).padding(end = 16.dp)) {
            PanelTitle("Audio")
            LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(audio) { i, o ->
                    val sel = audio.indexOfFirst { it.selected }.let { if (it < 0) 0 else it }
                    OptionRow(o.label, o.selected, if (i == sel) audioFr else null) { o.group?.let { host.selectAudio(it, o.index) } }
                }
            }
        }
        Column(Modifier.weight(1f)) {
            PanelTitle("Subtitles")
            LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
                itemsIndexed(allSubs) { i, o ->
                    val sel = allSubs.indexOfFirst { it.selected }.let { if (it < 0) 0 else it }
                    OptionRow(o.label, o.selected, if (i == sel) subFr else null) { host.selectSubtitle(o.group, o.index) }
                }
            }
        }
    }
}

@Composable
private fun SpeedPanel(host: PlayerHost, ui: PlayerUiState) {
    val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
    val fr = remember { FocusRequester() }
    FocusFirstSelected(fr)
    PanelTitle("Playback speed")
    LazyColumn {
        itemsIndexed(speeds) { _, v ->
            OptionRow(if (v == 1f) "1x (Normal)" else "${v}x", ui.speed == v, if (ui.speed == v) fr else null) { host.setSpeed(v) }
        }
    }
}

@Composable
private fun PicturePanel(host: PlayerHost, ui: PlayerUiState) {
    val fr = remember { FocusRequester() }
    FocusFirstSelected(fr)
    @Suppress("UNUSED_VARIABLE") val tick = ui.settingsTick
    val modes = listOf(
        AspectRatioFrameLayout.RESIZE_MODE_FIT to "Fit (original)",
        AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Fill screen (crop)",
        AspectRatioFrameLayout.RESIZE_MODE_FILL to "Stretch",
    )
    val sizes = listOf("Small", "Medium", "Large", "Extra large")
    val styles = listOf("White with shadow", "On a dark box")
    PanelTitle("Picture & subtitles")
    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        item { Text("Zoom", color = C.Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp, bottom = 4.dp)) }
        itemsIndexed(modes) { _, (m, label) ->
            OptionRow(label, ui.resize == m, if (ui.resize == m) fr else null) { host.setResize(m) }
        }
        item { Text("Subtitle size", color = C.Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp)) }
        itemsIndexed(sizes) { i, label -> OptionRow(label, host.subSize() == i) { host.setSubSize(i) } }
        item { Text("Subtitle style", color = C.Dim, fontSize = 14.sp, modifier = Modifier.padding(start = 14.dp, top = 16.dp, bottom = 4.dp)) }
        itemsIndexed(styles) { i, label -> OptionRow(label, host.subStyle() == i) { host.setSubStyle(i) } }
    }
}

@Composable
private fun EpisodesPanel(host: PlayerHost) {
    val fr = remember { FocusRequester() }
    FocusFirstSelected(fr)
    val current = host.episodes.indexOfFirst { it.id == host.currentVideoId }.coerceAtLeast(0)
    PanelTitle("Episodes")
    LazyColumn(contentPadding = PaddingValues(bottom = 40.dp)) {
        itemsIndexed(host.episodes) { i, ep ->
            OptionRow(ep.title, ep.id == host.currentVideoId, if (i == current) fr else null, sub = ep.label) {
                if (ep.id != host.currentVideoId) host.openEpisode(ep.id)
            }
        }
    }
}

@Composable
private fun InfoPanel(ui: PlayerUiState) {
    val fr = remember { FocusRequester() }
    FocusFirstSelected(fr)
    PanelTitle("Playback info")
    Box(Modifier.focusRequester(fr).focusable()) {
        Text(ui.info, color = Color(0xFFDDDDDD), fontSize = 15.sp, lineHeight = 24.sp)
    }
}
