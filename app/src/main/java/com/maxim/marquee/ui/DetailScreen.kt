package com.maxim.marquee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maxim.marquee.AppModel
import com.maxim.marquee.Screen
import com.maxim.marquee.data.Repo
import com.maxim.marquee.data.Video

@Composable
fun DetailScreen(model: AppModel, s: Screen.Detail) {
    var meta by remember(s) { mutableStateOf(Repo.cachedMeta(s.type, s.id) ?: s.preview) }
    LaunchedEffect(s) { Repo.meta(s.type, s.id)?.let { meta = it } }

    // Re-read progress whenever playback reports back.
    val tick = model.localVersion
    val episodes = remember(meta) { model.sortedEpisodes(meta) }
    val seasons = remember(episodes) { episodes.map { it.season }.distinct() }
    val target: Video? = remember(meta, tick, model.library) { if (meta.isSeries) model.resumeTarget(meta) else null }
    val req = model.reqFor(meta, target)
    val progress = remember(req, tick, model.library) { model.progressFor(meta.id, req.videoId) }
    var season by remember(s, seasons) { mutableStateOf(target?.season ?: seasons.firstOrNull() ?: 1) }

    var related by remember(s) { mutableStateOf<List<com.maxim.marquee.data.MetaItem>>(emptyList()) }
    var collection by remember(s) { mutableStateOf<com.maxim.marquee.data.Tmdb.Collection?>(null) }
    LaunchedEffect(meta.id, meta.genres.size) {
        related = model.relatedFor(meta)
        if (meta.type == "movie") collection = com.maxim.marquee.data.Tmdb.collectionFor(meta.id)
    }

    LaunchedEffect(req.videoId) {
        kotlinx.coroutines.delay(500)
        model.prefetch(req.type, req.videoId)
    }

    val playFr = remember { FocusRequester() }
    LaunchedEffect(s) {
        withFrameNanos { }
        try {
            playFr.requestFocus()
        } catch (e: Exception) {
            // ignore
        }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(meta.background ?: s.preview.backdropUrl, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.78f).fillMaxHeight())
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 56.dp, top = 56.dp, bottom = 40.dp),
        ) {
            val extras = rememberExtras(meta)
            TitleArt(meta, big = true, extraLogo = extras?.logo)
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val onEmby = com.maxim.marquee.data.Emby.isEmby(meta.id) || com.maxim.marquee.data.Emby.hasTitle(meta.id)
                if (onEmby) {
                    Text(
                        "ON YOUR EMBY SERVER", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.background(Color(0xFF52B54B), RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                }
                InfoRow(meta, extras?.certification, leading = model.ribbonFor(meta))
            }
            meta.description?.let {
                Spacer(Modifier.height(10.dp))
                Text(
                    it, color = Color(0xFFE6E6E6), fontSize = 15.sp, lineHeight = 21.sp,
                    maxLines = 4, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(560.dp),
                )
            }
            if (meta.cast.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Starring: " + meta.cast.take(4).joinToString(", "),
                    color = C.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(560.dp),
                )
            }
            Spacer(Modifier.height(22.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val epText = target?.let { " ${it.label}" } ?: ""
                ActionButton(
                    text = (if (progress != null) "Resume" else "Play") + epText,
                    icon = Icons.Filled.PlayArrow,
                    focusRequester = playFr,
                    onClick = { model.play(req) },
                )
                if (progress != null) {
                    ActionButton(text = "Start Over", icon = PlayerIcons.Restart, onClick = { model.play(req.copy(startOver = true)) })
                }
                meta.trailer?.let { yt ->
                    ActionButton(text = "Trailer", icon = PlayerIcons.Trailer, onClick = { model.playTrailer(yt) })
                }
                if (!com.maxim.marquee.data.Emby.isEmby(meta.id)) {
                    val inList = model.inMyList(meta.id)
                    ActionButton(
                        text = "My List",
                        icon = if (inList) PlayerIcons.Check else Icons.Filled.Add,
                        onClick = { model.toggleMyList(meta) },
                    )
                }
                ActionButton(
                    text = "Sources",
                    icon = Icons.AutoMirrored.Filled.List,
                    onClick = { model.play(req, chooseSource = true) },
                )
            }
            progress?.let {
                Spacer(Modifier.height(10.dp))
                ProgressLine(it, Modifier.width(260.dp))
            }

            if (meta.isSeries && seasons.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 40.dp)) {
                    items(seasons) { sn ->
                        val selected = sn == season
                        Tile(
                            shape = RoundedCornerShape(20.dp),
                            focusedScale = 1.05f,
                            border = false,
                            onFocus = { season = sn },
                            onClick = { season = sn },
                        ) { f ->
                            Text(
                                if (sn == 0) "Specials" else "Season $sn",
                                color = if (f) Color.Black else if (selected) Color.White else C.Dim,
                                fontSize = 15.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier
                                    .background(if (f) Color.White else if (selected) Color(0x33FFFFFF) else Color.Transparent)
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                val eps = episodes.filter { it.season == season }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(vertical = 10.dp, horizontal = 4.dp)) {
                    items(eps, key = { it.id }) { ep ->
                        EpisodeCard(
                            ep = ep,
                            fallbackImage = meta.background ?: meta.poster,
                            progress = model.progressFor(meta.id, ep.id).also { tick },
                            watched = model.watched(meta.id, ep.id),
                            onClick = { model.play(model.reqFor(meta, ep)) },
                        )
                    }
                }
            }
            collection?.let { c ->
                DetailRow(c.name, c.items.map { com.maxim.marquee.data.CardItem(it) }, model)
            }
            if (related.isNotEmpty()) {
                DetailRow("More Like This", related.map { com.maxim.marquee.data.CardItem(it) }, model)
            }
        }
    }
}

@Composable
private fun DetailRow(title: String, cards: List<com.maxim.marquee.data.CardItem>, model: AppModel) {
    Spacer(Modifier.height(26.dp))
    Text(title, color = Color(0xFFE5E5E5), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 12.dp, horizontal = 4.dp)) {
        items(cards, key = { it.meta.id }) { item ->
            PosterCard(item = item, width = CARD_W, topRank = model.top10[item.meta.id], onClick = { model.openDetail(item.meta) })
        }
    }
}

@Composable
private fun EpisodeCard(ep: Video, fallbackImage: String?, progress: Float?, watched: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(250.dp)) {
        Tile(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f), focusedScale = 1.05f, onClick = onClick) {
            Box(Modifier.fillMaxSize().background(C.Surface))
            val img = ep.thumbnail ?: fallbackImage
            if (img != null) {
                AsyncImage(img, ep.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (watched) {
                Text(
                    "Watched", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                        .background(Color(0xCC000000), RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
            progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth()) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "${ep.episode}. ${ep.title}", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        ep.overview?.let {
            Text(it, color = C.Dim, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
