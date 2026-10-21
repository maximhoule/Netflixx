package com.maxim.marquee.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maxim.marquee.AppModel
import com.maxim.marquee.Screen
import com.maxim.marquee.data.CardItem
import com.maxim.marquee.data.MetaItem
import com.maxim.marquee.data.Repo
import com.maxim.marquee.data.Video

private enum class DetailTab { EPISODES, COLLECTION, MORE }

/**
 * A title's page, laid out for a remote:
 * - on top, the title (always visible), facts, description and the actions;
 * - below, tabs (Episodes · More Like This) and their content. For a show: seasons in a list on the
 *   left, that season's episodes in a list on the right (up/down to browse, left to change season).
 * Moving down into the tabs shrinks the top to a single title line so the episodes get the screen;
 * Back (or moving up) brings the actions back.
 */
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

    var related by remember(s) { mutableStateOf<List<MetaItem>>(emptyList()) }
    var collection by remember(s) { mutableStateOf<com.maxim.marquee.data.Tmdb.Collection?>(null) }
    LaunchedEffect(meta.id, meta.genres.size) {
        related = model.relatedFor(meta)
        if (meta.type == "movie") collection = com.maxim.marquee.data.Tmdb.collectionFor(meta.id)
    }

    LaunchedEffect(req.videoId) {
        kotlinx.coroutines.delay(500)
        model.prefetch(req.type, req.videoId)
    }

    val tabs = buildList {
        if (meta.isSeries && episodes.isNotEmpty()) add(DetailTab.EPISODES)
        if (collection != null) add(DetailTab.COLLECTION)
        if (related.isNotEmpty()) add(DetailTab.MORE)
    }
    var tab by remember(s) { mutableStateOf<DetailTab?>(null) }
    val shownTab = tab?.takeIf { it in tabs } ?: tabs.firstOrNull()

    val playFr = remember { FocusRequester() }
    val contentFr = remember { FocusRequester() }
    LaunchedEffect(s) {
        withFrameNanos { }
        runCatching { playFr.requestFocus() }
    }
    // Focus below the actions (tabs, seasons, episodes): the top shrinks to one title line.
    var browsing by remember(s) { mutableStateOf(false) }
    // Back while browsing the episodes goes back up to the actions first.
    BackHandler(enabled = browsing) { runCatching { playFr.requestFocus() } }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(meta.background ?: s.preview.backdropUrl, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.78f).fillMaxHeight())
        // Darker while browsing, so the episode text reads cleanly over the picture.
        val dim by animateFloatAsState(if (browsing) 0.6f else 0.15f, tween(250), label = "detailDim")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(C.Bg))

        Column(Modifier.fillMaxSize().padding(start = 56.dp, top = if (browsing) 28.dp else 44.dp)) {
            val extras = rememberExtras(meta)
            Column(Modifier.animateContentSize(tween(220))) {
                if (browsing) {
                    // Compact: the title stays on screen, with its facts beside it.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TitleArt(meta, small = true, extraLogo = extras?.logo)
                        Spacer(Modifier.width(20.dp))
                        InfoRow(meta, extras?.certification)
                    }
                } else {
                    TitleArt(meta, big = true, extraLogo = extras?.logo)
                    Spacer(Modifier.height(12.dp))
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
                            maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(600.dp),
                        )
                    }
                    if (meta.cast.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Starring: " + meta.cast.take(4).joinToString(", "),
                            color = C.Dim, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.width(600.dp),
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    ActionsRow(model, meta, req, target, progress, playFr, extras?.trailers.orEmpty())
                    progress?.let {
                        Spacer(Modifier.height(8.dp))
                        ProgressLine(it, Modifier.width(220.dp))
                    }
                }
            }

            if (shownTab != null) {
                Column(
                    Modifier
                        .weight(1f)
                        .onFocusChanged { browsing = it.hasFocus }
                        .focusGroup(),
                ) {
                    Spacer(Modifier.height(if (browsing) 14.dp else 22.dp))
                    // Tabs: moving onto one shows it.
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        tabs.forEach { t ->
                            TabChip(
                                label = when (t) {
                                    DetailTab.EPISODES -> "Episodes"
                                    DetailTab.COLLECTION -> collection?.name ?: "Collection"
                                    DetailTab.MORE -> "More Like This"
                                },
                                selected = t == shownTab,
                                onFocus = { tab = t },
                                down = contentFr,
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    when (shownTab) {
                        DetailTab.EPISODES -> EpisodesPane(
                            model = model, meta = meta, episodes = episodes, seasons = seasons,
                            season = season, onSeason = { season = it }, target = target, tick = tick,
                            contentFr = contentFr,
                        )
                        DetailTab.COLLECTION -> PosterStrip(model, collection?.items.orEmpty(), contentFr)
                        DetailTab.MORE -> PosterStrip(model, related, contentFr)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionsRow(
    model: AppModel,
    meta: MetaItem,
    req: com.maxim.marquee.PlayReq,
    target: Video?,
    progress: Float?,
    playFr: FocusRequester,
    extraTrailers: List<String>,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val epText = target?.let { " ${it.label}" } ?: ""
        ActionButton(
            text = (if (progress != null) "Resume" else "Play") + epText,
            icon = Icons.Filled.PlayArrow,
            focusRequester = playFr,
            onClick = { model.play(req) },
        )
        // The other actions are round icons; the focused one shows its name.
        if (progress != null) {
            IconAction(PlayerIcons.Restart, "Start over") { model.play(req.copy(startOver = true)) }
        }
        val trailerIds = (meta.trailers + extraTrailers).distinct()
        if (trailerIds.isNotEmpty()) IconAction(PlayerIcons.Trailer, "Trailer") { model.openTrailer(meta.name, trailerIds) }
        if (!com.maxim.marquee.data.Emby.isEmby(meta.id)) {
            val inList = model.inMyList(meta.id)
            IconAction(if (inList) PlayerIcons.Check else Icons.Filled.Add, if (inList) "In My List" else "My List") {
                model.toggleMyList(meta)
            }
        }
        IconAction(Icons.AutoMirrored.Filled.List, "Sources") { model.play(req, chooseSource = true) }
        if (meta.isSeries || meta.type == "movie") {
            IconAction(PlayerIcons.DoneAll, if (meta.isSeries) "Mark show watched" else "Mark watched") { model.markWatched(meta) }
        }
    }
}

/** A round icon button that widens to show its name while focused. */
@Composable
private fun IconAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Tile(
        modifier = Modifier.height(46.dp),
        shape = RoundedCornerShape(23.dp),
        focusedScale = 1.05f,
        border = false,
        onClick = onClick,
    ) { f ->
        Row(
            Modifier
                .fillMaxHeight()
                .background(if (f) Color.White else Color(0x40FFFFFF))
                .animateContentSize(tween(150))
                .padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, label, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(24.dp))
            if (f) {
                Spacer(Modifier.width(8.dp))
                Text(label, color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.width(4.dp))
            }
        }
    }
}

@Composable
private fun TabChip(label: String, selected: Boolean, onFocus: () -> Unit, down: FocusRequester) {
    Tile(
        modifier = Modifier.focusProperties { this.down = down },
        shape = RoundedCornerShape(20.dp),
        focusedScale = 1f,
        border = false,
        onFocus = onFocus,
        onClick = onFocus,
    ) { f ->
        Column(
            Modifier.background(if (f) Color.White else Color.Transparent).padding(horizontal = 16.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                label,
                color = when {
                    f -> Color.Black
                    selected -> Color.White
                    else -> C.Dim
                },
                fontSize = 18.sp,
                fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
            if (selected && !f) {
                Spacer(Modifier.height(3.dp))
                Box(Modifier.width(22.dp).height(3.dp).background(C.Red, RoundedCornerShape(2.dp)))
            }
        }
    }
}

/** Seasons down the left, that season's episodes down the right. */
@Composable
private fun EpisodesPane(
    model: AppModel,
    meta: MetaItem,
    episodes: List<Video>,
    seasons: List<Int>,
    season: Int,
    onSeason: (Int) -> Unit,
    target: Video?,
    tick: Int,
    contentFr: FocusRequester,
) {
    val eps = remember(episodes, season) { episodes.filter { it.season == season } }
    val seasonFrs = remember(seasons) { seasons.associateWith { FocusRequester() } }
    val targetFr = remember { FocusRequester() }
    var targetShown by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val targetIndex = eps.indexOfFirst { it.id == target?.id }
    // A new season: start at the episode you're up to (if it's in this season), else the first.
    LaunchedEffect(season, eps.size) {
        listState.scrollToItem(if (targetIndex >= 0) maxOf(0, targetIndex - 1) else 0)
    }

    Row(Modifier.fillMaxSize()) {
        if (seasons.size > 1) {
            LazyColumn(
                modifier = Modifier
                    .width(170.dp)
                    .fillMaxHeight()
                    // Coming in from the episodes: land on the season being shown.
                    .focusProperties { enter = { seasonFrs[season] ?: FocusRequester.Default } }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(top = 4.dp, bottom = 40.dp),
            ) {
                items(seasons, key = { it }) { sn ->
                    val selected = sn == season
                    Tile(
                        modifier = Modifier.fillMaxWidth().height(42.dp),
                        shape = RoundedCornerShape(6.dp),
                        focusedScale = 1f,
                        border = false,
                        focusRequester = seasonFrs[sn],
                        onFocus = { onSeason(sn) },
                        onClick = { onSeason(sn) },
                    ) { f ->
                        Row(
                            Modifier.fillMaxSize().background(if (f) Color.White else if (selected) Color(0x26FFFFFF) else Color.Transparent),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier.width(4.dp).fillMaxHeight().padding(vertical = 9.dp)
                                    .background(if (selected && !f) C.Red else Color.Transparent),
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                if (sn == 0) "Specials" else "Season $sn",
                                color = if (f) Color.Black else if (selected) Color.White else C.Dim,
                                fontSize = 16.sp,
                                fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.width(18.dp))
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(contentFr)
                // Coming in (from the tabs or the seasons): the episode you're up to, if it's here.
                .focusProperties { enter = { if (targetShown) targetFr else FocusRequester.Default } }
                .focusGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 4.dp, bottom = 60.dp, end = 48.dp),
        ) {
            itemsIndexed(eps, key = { _, ep -> ep.id }) { _, ep ->
                val isTarget = ep.id == target?.id
                if (isTarget) {
                    DisposableEffect(ep.id) {
                        targetShown = true
                        onDispose { targetShown = false }
                    }
                }
                EpisodeRow(
                    ep = ep,
                    fallbackImage = meta.background ?: meta.poster,
                    progress = model.progressFor(meta.id, ep.id).also { tick },
                    watched = model.watched(meta.id, ep.id),
                    upNext = isTarget,
                    focusRequester = if (isTarget) targetFr else null,
                    onClick = { model.play(model.reqFor(meta, ep)) },
                )
            }
        }
    }
}

private val airDate = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())

@Composable
private fun EpisodeRow(
    ep: Video,
    fallbackImage: String?,
    progress: Float?,
    watched: Boolean,
    upNext: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    Tile(
        modifier = Modifier.fillMaxWidth().height(118.dp),
        shape = RoundedCornerShape(8.dp),
        focusedScale = 1f,
        border = false,
        focusRequester = focusRequester,
        onClick = onClick,
    ) { f ->
        Row(
            Modifier.fillMaxSize().background(if (f) Color(0x33FFFFFF) else Color.Transparent).padding(7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(184.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(C.Surface)
                    .then(if (f) Modifier.border(3.dp, Color.White, RoundedCornerShape(6.dp)) else Modifier),
            ) {
                val img = ep.thumbnail ?: fallbackImage
                if (img != null) {
                    AsyncImage(img, ep.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                if (watched) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(PlayerIcons.Check, "Watched", tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                }
                progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth()) }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${ep.episode}. ${ep.title}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val facts = listOfNotNull(
                    if (upNext) "UP NEXT" else null,
                    ep.label,
                    ep.released.takeIf { it > 0 }?.let { r ->
                        val d = airDate.format(java.util.Date(r))
                        if (r > System.currentTimeMillis()) "Coming $d" else d
                    },
                    if (watched) "Watched" else null,
                )
                Text(
                    facts.joinToString("  ·  "),
                    color = if (upNext) Color.White else C.Dim, fontSize = 13.sp,
                    fontWeight = if (upNext) FontWeight.Bold else FontWeight.Normal, maxLines = 1,
                )
                ep.overview?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = Color(0xFFCCCCCC), fontSize = 13.sp, lineHeight = 17.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun PosterStrip(model: AppModel, items: List<MetaItem>, contentFr: FocusRequester) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().focusRequester(contentFr).focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(top = 10.dp, bottom = 12.dp, start = 4.dp, end = 48.dp),
    ) {
        items(items, key = { it.id }) { m ->
            PosterCard(item = CardItem(m), width = CARD_W, topRank = model.top10[m.id], onClick = { model.openDetail(m) })
        }
    }
}
