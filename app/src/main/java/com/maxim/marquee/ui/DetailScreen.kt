package com.maxim.marquee.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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

private enum class Side { EPISODES, COLLECTION, MORE }

/**
 * A title's page:
 * - on the left, over the full-screen artwork: the logo, facts, genres, cast, the description and
 *   the buttons (Play first, the rest as round icons whose name shows underneath);
 * - on the right, a panel: for a show, its episodes (Prev / Season ▾ / Next, a search box, then
 *   the episode list); for a film, the rest of its collection or similar titles.
 * Right from the buttons goes into the panel (at the episode you're up to); left comes back.
 */
@OptIn(ExperimentalComposeUiApi::class)
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

    val sides = buildList {
        if (meta.isSeries && episodes.isNotEmpty()) add(Side.EPISODES)
        if (collection != null) add(Side.COLLECTION)
        if (related.isNotEmpty()) add(Side.MORE)
    }
    var side by remember(s) { mutableStateOf<Side?>(null) }
    val shownSide = side?.takeIf { it in sides } ?: sides.firstOrNull()

    val playFr = remember { FocusRequester() }
    LaunchedEffect(s) {
        withFrameNanos { }
        runCatching { playFr.requestFocus() }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(meta.background ?: s.preview.backdropUrl, Modifier.fillMaxSize(), wide = true, dim = 0.4f)
        val extras = rememberExtras(meta)
        Column(
            Modifier.fillMaxHeight().width(if (shownSide != null) 540.dp else 720.dp)
                .padding(start = 64.dp, top = 40.dp, bottom = 22.dp),
        ) {
            TitleArt(meta, big = false, extraLogo = extras?.logo)
            Spacer(Modifier.height(16.dp))
            FactsRow(meta, extras?.certification)
            if (meta.genres.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                ChipSection("GENRES", meta.genres.take(4))
            }
            if (meta.cast.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                ChipSection("CAST", meta.cast.take(3))
            }
            meta.description?.let {
                Spacer(Modifier.height(16.dp))
                Text(
                    it, color = Color(0xFFE6E6E6), fontSize = 15.sp, lineHeight = 22.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.weight(1f))
            progress?.let {
                ProgressLine(it, Modifier.width(240.dp))
                Spacer(Modifier.height(10.dp))
            }
            ActionsRow(model, meta, req, target, progress, playFr, extras?.trailers.orEmpty())
        }
        if (shownSide != null) {
            SidePanel(
                model = model, meta = meta, sides = sides, side = shownSide, onSide = { side = it },
                episodes = episodes, seasons = seasons, target = target, tick = tick,
                collection = collection, related = related, back = playFr,
            )
        }
    }
}

/** "41 min   2021–2024   [9.0 IMDb]" */
@Composable
private fun FactsRow(meta: MetaItem, cert: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val gap = Modifier.width(18.dp)
        val length = if (meta.isSeries && meta.seasonCount > 0) seasonsLabel(meta.seasonCount) else formatRuntime(meta.runtime)
        length?.let {
            Text(it, color = Color(0xFFE0E0E0), fontSize = 17.sp)
            Spacer(gap)
        }
        meta.releaseInfo?.let {
            Text(it, color = Color(0xFFE0E0E0), fontSize = 17.sp)
            Spacer(gap)
        }
        (cert ?: meta.certification)?.let {
            Text(
                it, color = Color(0xFFDDDDDD), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(1.dp, Color(0xFF9A9A9A), RoundedCornerShape(3.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
            Spacer(gap)
        }
        meta.imdbRating?.takeIf { it.isNotBlank() }?.let { r ->
            Row(
                Modifier
                    .background(Color(0x26F5C518), RoundedCornerShape(6.dp))
                    .border(1.5.dp, Color(0xFFB8962E), RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(r, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(10.dp))
                Text(
                    "IMDb", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                )
            }
            Spacer(gap)
        }
        val onEmby = com.maxim.marquee.data.Emby.isEmby(meta.id) || com.maxim.marquee.data.Emby.hasTitle(meta.id)
        if (onEmby) {
            Text(
                "ON YOUR EMBY", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xFF52B54B), RoundedCornerShape(3.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }
    }
}

@Composable
private fun ChipSection(title: String, values: List<String>) {
    Text(title, color = Color(0xFFA8A8A8), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        values.forEach { v ->
            Text(
                v, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .widthIn(max = 170.dp)
                    .background(Color(0x1FFFFFFF), RoundedCornerShape(18.dp))
                    .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            )
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
    // The name of the focused round button, shown under the row.
    var caption by remember { mutableStateOf<String?>(null) }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        val epText = target?.let { " ${it.label}" } ?: ""
        PillButton(
            text = (if (progress != null) "Resume" else "Play") + epText,
            icon = PlayerIcons.Play,
            primary = true,
            focusRequester = playFr,
            onFocus = { caption = null },
            onClick = { model.play(req) },
        )
        val actions = buildList<Triple<ImageVector, String, () -> Unit>> {
            if (progress != null) add(Triple(PlayerIcons.Restart, "Start over") { model.play(req.copy(startOver = true)) })
            val trailerIds = (meta.trailers + extraTrailers).distinct()
            if (trailerIds.isNotEmpty()) add(Triple(PlayerIcons.Trailer, "Trailer") { model.openTrailer(meta.name, trailerIds) })
            if (!com.maxim.marquee.data.Emby.isEmby(meta.id)) {
                val inList = model.inMyList(meta.id)
                add(Triple(if (inList) PlayerIcons.Check else Icons.Filled.Add, if (inList) "In My List" else "Add to My List") { model.toggleMyList(meta) })
            }
            if (meta.isSeries || meta.type == "movie") {
                add(Triple(PlayerIcons.DoneAll, if (meta.isSeries) "Mark show watched" else "Mark watched") { model.markWatched(meta) })
            }
            add(Triple(Icons.AutoMirrored.Filled.List, "Choose source") { model.play(req, chooseSource = true) })
        }
        actions.forEach { (icon, label, onClick) ->
            RoundAction(icon, label, onFocus = { caption = label }, onClick = onClick)
        }
    }
    Text(
        caption ?: "",
        color = Color(0xFFCFCFCF), fontSize = 13.sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(top = 6.dp, start = 4.dp).height(18.dp),
    )
}

/** The panel on the right: episodes for a show, else the collection / similar titles. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.SidePanel(
    model: AppModel,
    meta: MetaItem,
    sides: List<Side>,
    side: Side,
    onSide: (Side) -> Unit,
    episodes: List<Video>,
    seasons: List<Int>,
    target: Video?,
    tick: Int,
    collection: com.maxim.marquee.data.Tmdb.Collection?,
    related: List<MetaItem>,
    back: FocusRequester,
) {
    val entry = remember { EntryPoint() }
    Column(
        Modifier
            .align(Alignment.TopEnd)
            .padding(top = 30.dp, end = 26.dp, bottom = 22.dp)
            .width(392.dp)
            .fillMaxHeight()
            .background(Color(0xE6151515), RoundedCornerShape(18.dp))
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(18.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp)
            // In from the buttons: the episode you're up to (else the list's first item).
            .focusProperties {
                enter = { entry.target() ?: FocusRequester.Default }
                exit = { if (it == FocusDirection.Left) back else FocusRequester.Default }
            }
            .focusGroup(),
    ) {
        if (sides.size > 1) {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                sides.forEach { sd ->
                    val label = when (sd) {
                        Side.EPISODES -> "Episodes"
                        Side.COLLECTION -> collection?.name ?: "Collection"
                        Side.MORE -> "More Like This"
                    }
                    SmallTab(label, sd == side) { onSide(sd) }
                }
            }
        }
        when (side) {
            Side.EPISODES -> EpisodesPanel(model, meta, episodes, seasons, target, tick, entry)
            Side.COLLECTION -> PosterGrid(model, collection?.items.orEmpty(), entry)
            Side.MORE -> PosterGrid(model, related, entry)
        }
    }
}

/** Where focus lands when it moves into the panel; set by whichever list is showing. */
private class EntryPoint {
    var preferred: FocusRequester? = null
    var first: FocusRequester? = null
    fun target() = preferred ?: first
}

@Composable
private fun SmallTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Tile(shape = RoundedCornerShape(16.dp), focusedScale = 1f, border = false, onFocus = onClick, onClick = onClick) { f ->
        Text(
            label,
            color = when {
                f -> Color.Black
                selected -> Color.White
                else -> Color(0xFF9E9E9E)
            },
            fontSize = 14.sp,
            fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier
                .background(if (f) Color.White else if (selected) Color(0x26FFFFFF) else Color.Transparent)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

private val airDate = java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.getDefault())

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ColumnScope.EpisodesPanel(
    model: AppModel,
    meta: MetaItem,
    episodes: List<Video>,
    seasons: List<Int>,
    target: Video?,
    tick: Int,
    entry: EntryPoint,
) {
    var season by remember(meta.id, seasons) { mutableIntStateOf(target?.season ?: seasons.firstOrNull() ?: 1) }
    var picking by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    val q = query.trim()
    val eps = remember(episodes, season, q) {
        if (q.isEmpty()) episodes.filter { it.season == season }
        else episodes.filter { it.title.contains(q, ignoreCase = true) || it.episode.toString() == q || it.label.equals(q, true) }
    }
    val listState = rememberLazyListState()
    // Episode pictures: the list's own, else your Emby server's, else TMDB's (when a key is set).
    // A picture shared by several episodes is the show's artwork, not the episode's: not used.
    val embyThumbs by androidx.compose.runtime.produceState(emptyMap<String, String>(), meta.id) {
        value = runCatching { com.maxim.marquee.data.Emby.episodeThumbs(meta.id) }.getOrDefault(emptyMap())
    }
    val tmdbThumbs by androidx.compose.runtime.produceState(emptyMap<Int, String>(), meta.id, season) {
        value = runCatching { com.maxim.marquee.data.Tmdb.seasonStills(meta.id, season) }.getOrDefault(emptyMap())
    }
    val sharedThumbs = remember(episodes) {
        episodes.mapNotNull { it.thumbnail }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
    }
    val targetIndex = eps.indexOfFirst { it.id == target?.id }
    val targetFr = remember { FocusRequester() }
    val firstFr = remember { FocusRequester() }
    val seasonFr = remember { FocusRequester() }
    val searchFr = remember { FocusRequester() }
    val searchTileFr = remember { FocusRequester() }
    var targetShown by remember { mutableStateOf(false) }
    var firstShown by remember { mutableStateOf(false) }
    entry.preferred = if (!picking && targetShown) targetFr else null
    entry.first = if (!picking && firstShown) firstFr else null

    // A new season (or search): start at the episode you're up to (if it's here), else the top.
    LaunchedEffect(season, q, eps.size) {
        listState.scrollToItem(if (targetIndex >= 0) maxOf(0, targetIndex - 1) else 0)
    }
    // Season chosen from the list: back to its episodes, focus on the first (or the one you're up to).
    var focusAfterPick by remember { mutableIntStateOf(0) }
    LaunchedEffect(focusAfterPick) {
        if (focusAfterPick == 0) return@LaunchedEffect
        repeat(3) {
            withFrameNanos { }
            val fr = if (targetShown) targetFr else firstFr
            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    BackHandler(enabled = picking) { picking = false; runCatching { seasonFr.requestFocus() } }

    val si = seasons.indexOf(season)
    // Prev · Season ▾ · Next
    Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        val prev = seasons.getOrNull(si - 1)
        val next = seasons.getOrNull(si + 1)
        NavText("Prev", Icons.AutoMirrored.Filled.KeyboardArrowLeft, iconFirst = true, enabled = prev != null && q.isEmpty()) {
            prev?.let { season = it }
        }
        Spacer(Modifier.weight(1f))
        Tile(
            shape = RoundedCornerShape(10.dp), focusedScale = 1f, border = false, focusRequester = seasonFr,
            onClick = { if (seasons.size > 1) picking = !picking },
        ) { f ->
            Row(
                Modifier
                    .background(if (f) Color.White else Color(0x1AFFFFFF))
                    .border(1.dp, if (f) Color.White else Color(0x33FFFFFF), RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (q.isNotEmpty()) "All seasons" else if (season == 0) "Specials" else "Season $season",
                    color = if (f) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
                if (seasons.size > 1) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.KeyboardArrowDown, null, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
        Spacer(Modifier.weight(1f))
        NavText("Next", Icons.AutoMirrored.Filled.KeyboardArrowRight, iconFirst = false, enabled = next != null && q.isEmpty()) {
            next?.let { season = it }
        }
    }
    Spacer(Modifier.height(8.dp))

    // Search box: OK opens the keyboard; the list filters as you type (all seasons).
    Tile(
        modifier = Modifier.fillMaxWidth().height(44.dp),
        shape = RoundedCornerShape(12.dp), focusedScale = 1f, border = false,
        focusRequester = searchTileFr,
        onClick = { editing = true },
    ) { f ->
        Row(
            Modifier.fillMaxSize()
                .background(Color(0x14FFFFFF))
                .border(1.dp, if (f || editing) Color.White else Color(0x2EFFFFFF), RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (editing) {
                    LaunchedEffect(Unit) { withFrameNanos { }; runCatching { searchFr.requestFocus() } }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(Color.White),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { editing = false; runCatching { searchTileFr.requestFocus() } }),
                        modifier = Modifier.fillMaxWidth().focusRequester(searchFr),
                    )
                } else {
                    Text(
                        query.ifEmpty { "Search episodes" },
                        color = if (query.isEmpty()) Color(0xFF8E8E8E) else Color.White, fontSize = 15.sp, maxLines = 1,
                    )
                }
            }
            Icon(Icons.Filled.Search, "Search", tint = Color(0xFFBDBDBD), modifier = Modifier.size(20.dp))
        }
    }
    if (editing) BackHandler { editing = false; runCatching { searchTileFr.requestFocus() } }
    Spacer(Modifier.height(8.dp))

    if (picking) {
        val pickFrs = remember(seasons) { seasons.associateWith { FocusRequester() } }
        LaunchedEffect(Unit) { withFrameNanos { }; runCatching { pickFrs[season]?.requestFocus() } }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f).focusGroup(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            items(seasons, key = { it }) { sn ->
                Tile(
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(10.dp), focusedScale = 1f, border = false,
                    focusRequester = pickFrs[sn],
                    onClick = {
                        season = sn; query = ""; picking = false; focusAfterPick++
                    },
                ) { f ->
                    val count = episodes.count { it.season == sn }
                    Row(
                        Modifier.fillMaxSize().background(if (f) Color.White else if (sn == season) Color(0x1FFFFFFF) else Color.Transparent)
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (sn == 0) "Specials" else "Season $sn",
                            color = if (f) Color.Black else Color.White, fontSize = 16.sp,
                            fontWeight = if (sn == season || f) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Text("$count episodes", color = if (f) Color.DarkGray else C.Dim, fontSize = 13.sp)
                    }
                }
            }
        }
        return
    }

    if (eps.isEmpty()) {
        Text("No episodes match \"$q\"", color = C.Dim, fontSize = 14.sp, modifier = Modifier.padding(12.dp))
        return
    }
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f)
            .focusProperties { enter = { if (targetShown) targetFr else FocusRequester.Default } }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp),
    ) {
        itemsIndexed(eps, key = { _, ep -> ep.id }) { i, ep ->
            val isTarget = ep.id == target?.id
            if (isTarget) {
                DisposableEffect(ep.id) {
                    targetShown = true
                    onDispose { targetShown = false }
                }
            }
            if (i == 0) {
                DisposableEffect(ep.id) {
                    firstShown = true
                    onDispose { firstShown = false }
                }
            }
            EpisodeRow(
                ep = ep,
                showSeason = q.isNotEmpty(),
                images = listOfNotNull(
                    ep.thumbnail?.takeIf { it !in sharedThumbs },
                    embyThumbs["${ep.season}:${ep.episode}"],
                    if (ep.season == season) tmdbThumbs[ep.episode] else null,
                ).distinct(),
                progress = model.progressFor(meta.id, ep.id).also { tick },
                watched = model.watched(meta.id, ep.id),
                upNext = isTarget,
                focusRequester = when {
                    isTarget -> targetFr
                    i == 0 -> firstFr
                    else -> null
                },
                onClick = { model.play(model.reqFor(meta, ep)) },
            )
        }
    }
}

/** "‹ Prev" / "Next ›": plain text that turns into a white pill when focused; dimmed when there's nowhere to go. */
@Composable
private fun NavText(label: String, icon: ImageVector, iconFirst: Boolean, enabled: Boolean, onClick: () -> Unit) {
    if (!enabled) {
        Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (iconFirst) Icon(icon, null, tint = Color(0x55FFFFFF), modifier = Modifier.size(22.dp))
            Text(label, color = Color(0x55FFFFFF), fontSize = 16.sp)
            if (!iconFirst) Icon(icon, null, tint = Color(0x55FFFFFF), modifier = Modifier.size(22.dp))
        }
        return
    }
    Tile(shape = RoundedCornerShape(10.dp), focusedScale = 1f, border = false, onClick = onClick) { f ->
        Row(
            Modifier.background(if (f) Color.White else Color.Transparent).padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val c = if (f) Color.Black else Color.White
            if (iconFirst) Icon(icon, null, tint = c, modifier = Modifier.size(22.dp))
            Text(label, color = c, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (!iconFirst) Icon(icon, null, tint = c, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun EpisodeRow(
    ep: Video,
    showSeason: Boolean,
    images: List<String>,
    progress: Float?,
    watched: Boolean,
    upNext: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
) {
    Tile(
        modifier = Modifier.fillMaxWidth().height(96.dp),
        shape = RoundedCornerShape(12.dp),
        focusedScale = 1f,
        border = false,
        focusRequester = focusRequester,
        onClick = onClick,
    ) { f ->
        Row(
            Modifier
                .fillMaxSize()
                .background(if (f) Color(0x1AFFFFFF) else Color.Transparent)
                .border(2.5.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(12.dp))
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(142.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(8.dp))
                    .background(C.Surface),
            ) {
                // Tries each picture in turn; with none, the episode number (never the show's poster).
                var attempt by remember(images) { mutableIntStateOf(0) }
                val img = images.getOrNull(attempt)
                if (img == null) {
                    Text(
                        "E${ep.episode}", color = Color(0x66FFFFFF), fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    AsyncImage(
                        img, ep.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                        onError = { attempt++ },
                    )
                }
                if (watched) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(5.dp).size(20.dp)
                            .background(Color(0xCC000000), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(PlayerIcons.Check, "Watched", tint = Color.White, modifier = Modifier.size(14.dp))
                    }
                }
                progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth()) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 2.dp)) {
                Text(
                    "${ep.episode}. ${ep.title}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                val date = ep.released.takeIf { it > 0 }?.let { r ->
                    val d = airDate.format(java.util.Date(r))
                    if (r > System.currentTimeMillis()) "Coming $d" else d
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (upNext) {
                        Text("UP NEXT", color = C.Red, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        listOfNotNull(if (showSeason) ep.label else null, date).joinToString("  ·  "),
                        color = Color(0xFF8F8F8F), fontSize = 12.sp, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun PosterGrid(model: AppModel, items: List<MetaItem>, entry: EntryPoint) {
    val firstFr = remember { FocusRequester() }
    entry.preferred = null
    entry.first = if (items.isNotEmpty()) firstFr else null
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize().focusGroup(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 24.dp),
    ) {
        items(items.size, key = { items[it].id }) { i ->
            val m = items[i]
            PosterCard(
                item = CardItem(m), width = 108.dp, topRank = model.top10[m.id], showRating = true,
                focusRequester = if (i == 0) firstFr else null,
                onClick = { model.openDetail(m) },
            )
        }
    }
}
