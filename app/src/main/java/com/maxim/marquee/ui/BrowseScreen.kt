package com.maxim.marquee.ui

import android.view.ViewGroup
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.viewinterop.AndroidView
import com.maxim.marquee.data.Store
import kotlinx.coroutines.delay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.foundation.border
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxim.marquee.AppModel
import com.maxim.marquee.BrowseState
import com.maxim.marquee.RowModel
import com.maxim.marquee.RowSource
import com.maxim.marquee.Tab
import com.maxim.marquee.data.CardItem

val CARD_W = 112.dp

private val BROWSE_TABS = setOf(Tab.HOME, Tab.SERIES, Tab.MOVIES, Tab.SPORTS)

/**
 * The menu bar along the top and the current tab. Home, Series, Movies and Sports stay built once
 * visited (hidden, not drawn), so switching back to one, or coming back from a show's page, is
 * instant and lands exactly where you were. [active] is false while another screen is on top.
 */
@Composable
fun MainShell(model: AppModel, railRequesters: Map<Tab, FocusRequester>, active: Boolean = true) {
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        // Another tab, or a screen on top: the trailer stops (the next billboard starts its own).
        LaunchedEffect(active, model.tab) { model.stopTrailer() }
        val visited = remember { LinkedHashSet<Tab>() }
        if (model.tab in BROWSE_TABS) visited += model.tab
        visited.forEach { t ->
            androidx.compose.runtime.key(t) {
                KeepAlive(visible = model.tab == t, modifier = Modifier.fillMaxSize()) {
                    FocusGated({ model.tab == t }) {
                        BrowseScreen(model, model.browse.getValue(t), active = active && model.tab == t)
                    }
                }
            }
        }
        if (model.tab !in BROWSE_TABS) {
            // Starts below the menu bar: moving down from the menu has to find it below.
            Box(Modifier.fillMaxSize().padding(top = TOP_BAR)) {
                when (model.tab) {
                    Tab.SEARCH -> SearchScreen(model)
                    Tab.MYLIST -> MyListScreen(model)
                    Tab.CALENDAR -> CalendarScreen(model)
                    Tab.SETTINGS -> SettingsScreen(model)
                    else -> Unit
                }
            }
        }
        TopNav(model, railRequesters)
    }
}

private val NAV_TEXT_TABS = listOf(Tab.HOME, Tab.SERIES, Tab.MOVIES, Tab.SPORTS, Tab.MYLIST, Tab.CALENDAR, Tab.SETTINGS)

/** The menu: logo, the sections as words, search and your profile on the right. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun TopNav(model: AppModel, requesters: Map<Tab, FocusRequester>) {
    Box(Modifier.fillMaxWidth().height(TOP_BAR + 24.dp).background(Brush.verticalGradient(0f to Color(0xB3000000), 1f to Color.Transparent)))
    Row(
        Modifier
            .fillMaxWidth()
            .height(TOP_BAR)
            .padding(horizontal = 48.dp)
            .focusProperties { enter = { requesters.getValue(model.tab) } }
            .onFocusChanged { model.railFocused = it.hasFocus }
            .focusGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MarqueeLogo()
        Spacer(Modifier.width(26.dp))
        val tabs = model.tabs
        NAV_TEXT_TABS.filter { it in tabs }.forEach { t ->
            val selected = t == model.tab
            Tile(
                shape = RoundedCornerShape(6.dp),
                focusedScale = 1f,
                border = false,
                focusRequester = requesters.getValue(t),
                onClick = { model.selectTab(t) },
            ) { f ->
                Column(
                    Modifier.background(if (f) Color.White else Color.Transparent).padding(horizontal = 11.dp, vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        t.label,
                        color = when {
                            f -> Color.Black
                            selected -> Color.White
                            else -> Color(0xFFBDBDBD)
                        },
                        fontSize = 15.sp,
                        fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                    Box(
                        Modifier.padding(top = 3.dp).width(18.dp).height(2.dp)
                            .background(if (selected && !f) C.Red else Color.Transparent, RoundedCornerShape(1.dp)),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
        }
        Spacer(Modifier.weight(1f))
        if (Tab.SEARCH in tabs) {
            Tile(
                shape = RoundedCornerShape(8.dp),
                focusedScale = 1f,
                border = false,
                focusRequester = requesters.getValue(Tab.SEARCH),
                onClick = { model.selectTab(Tab.SEARCH) },
            ) { f ->
                Row(
                    Modifier.width(210.dp).height(36.dp)
                        .background(if (f) Color.White else Color(0x33000000))
                        .border(1.dp, if (f) Color.White else Color(0x40FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val q = model.searchQuery
                    Text(
                        q.ifBlank { "Search" },
                        color = if (f) Color.Black else if (model.tab == Tab.SEARCH) Color.White else Color(0xFF9E9E9E),
                        fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.Filled.Search, "Search", tint = if (f) Color.Black else Color(0xFFBDBDBD), modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.width(16.dp))
        }
        if (Tab.PROFILE in tabs) {
            Tile(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(19.dp),
                focusedScale = 1.1f,
                border = false,
                focusRequester = requesters.getValue(Tab.PROFILE),
                onClick = { model.selectTab(Tab.PROFILE) },
            ) { f ->
                Box(
                    Modifier.fillMaxSize().border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(19.dp)).padding(3.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Avatar(Store.activeProfile, 30.dp)
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun BrowseScreen(model: AppModel, b: BrowseState, active: Boolean = true) {
    // Shown again (back from a show's page, or this tab picked again): focus where you left off.
    LaunchedEffect(active) {
        if (active && b.lastFocusKey != null) b.pendingFocusKey = b.lastFocusKey
    }
    LaunchedEffect(model.contentFocusTick, active) {
        if (active && model.contentFocusTick > model.handledTick) {
            model.handledTick = model.contentFocusTick
            b.pendingFocusKey = b.lastFocusKey ?: b.firstKey()
        }
    }
    val density = LocalDensity.current
    val rowSpec = remember(density) { PivotSpec(with(density) { 48.dp.toPx() }) }
    // The focused card's top edge; leaves room for the row title above it.
    val colSpec = remember(density) { PivotSpec(with(density) { 40.dp.toPx() }) }
    // Coming in from the menu: the card you were on (else the first one), not whichever button
    // or card happens to sit below the menu item.
    var heroFull by remember { mutableStateOf(false) }
    val playFr = remember { FocusRequester() }
    // Up from the first row: the billboard opens and Play takes focus.
    var focusPlay by remember { mutableStateOf(0) }
    LaunchedEffect(focusPlay) {
        if (focusPlay == 0) return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (runCatching { playFr.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val cardEntry: () -> FocusRequester = { (b.lastFocusKey ?: b.firstKey())?.let { b.cardRequesters[it] } ?: FocusRequester.Default }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Full billboard (with Play / More Info) while you're on it; shorter while you browse the
        // rows, so the posters are never cut off at the bottom.
        val heroH = maxHeight * (if (heroFull) 0.6f else 0.44f)
        // The billboard lives in its own composables: changing title only redraws the billboard,
        // never the rows below it.
        HeroBackdrop(model, b, active, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(maxHeight * 0.88f))

        // Below the menu bar (not under it), so moving down from the menu finds this screen.
        Column(Modifier.fillMaxSize().padding(top = TOP_BAR).focusProperties { enter = { cardEntry() } }.focusGroup()) {
            Box(Modifier.fillMaxWidth().height(heroH - TOP_BAR)) {
                HeroInfo(model, b, heroFull, playFr) { heroFull = true }
            }

            val hasCatalogs = b.rows.any { it.source !is RowSource.Continue && it.source !is RowSource.NewEpisodes }
            val err = model.addonError
            Box(Modifier.fillMaxWidth().weight(1f).padding(start = 34.dp)) {
                if (err != null && !hasCatalogs) {
                    AddonErrorPanel(model, err)
                } else if (b.rows.isNotEmpty() && b.rows.all { b.loaded[it.key] == true && b.rowItems[it.key].isNullOrEmpty() }) {
                    // Everything answered, nothing to show (e.g. no games on right now).
                    Centered(
                        if (b.sportsOnly) "No games right now" else "Nothing to show yet",
                        if (b.sportsOnly) "Your sports addons didn't list any events. Check back closer to game time." else null,
                    )
                } else if (!hasCatalogs && b.rowItems["continue"].isNullOrEmpty()) {
                    if (model.syncing) {
                        Column { repeat(2) { SkeletonRow(withTitle = true) } }
                    } else {
                        Centered("Nothing to show yet", "Install catalog addons in Stremio and they'll appear here.")
                    }
                } else {
                    CompositionLocalProvider(LocalBringIntoViewSpec provides colSpec) {
                        LazyColumn(
                            state = b.columnState,
                            // From Play / More Info: into the row below, at its first poster
                            // (each row decides, see CatalogRow).
                            modifier = Modifier.fillMaxSize()
                                .onFocusChanged { if (it.hasFocus) heroFull = false }
                                .onPreviewKeyEvent { e ->
                                    val topRow = b.rows.firstOrNull { !b.rowItems[it.key].isNullOrEmpty() }?.key
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && b.hero != null &&
                                        topRow != null && b.lastFocusKey?.substringBefore('#') == topRow
                                    ) {
                                        heroFull = true
                                        focusPlay++
                                        true
                                    } else false
                                }
                                .focusGroup(),
                            contentPadding = PaddingValues(bottom = 300.dp),
                        ) {
                            items(b.rows, key = { it.key }) { row ->
                                CatalogRow(model, b, row, rowSpec)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeroBackdrop(model: AppModel, b: BrowseState, active: Boolean, modifier: Modifier) {
    val hero = b.hero
    // On an episode you're watching: that episode's own picture.
    val bg = rememberHeroEpisode(b)?.art ?: b.heroMeta?.backdropUrl ?: hero?.meta?.backdropUrl
    // Trailer behind the billboard once you rest on a title for a couple of seconds.
    val trailersOn = Store.trailerMode != 0 && active
    val heroMeta = b.heroMeta
    val extras = heroMeta?.let { rememberExtras(it) }
    val ids = (heroMeta?.trailers ?: emptyList()) + (extras?.trailers ?: emptyList())
    LaunchedEffect(ids.joinToString(","), hero?.meta?.id, trailersOn) {
        if (!trailersOn) return@LaunchedEffect
        model.stopTrailer()
        if (ids.isEmpty()) return@LaunchedEffect
        // Look the stream up while you're still deciding, so it starts right on time.
        delay(700)
        model.trailer.prefetch(ids)
        delay(1_500)
        model.trailer.play(ids, muted = Store.trailerMode == 1)
    }
    DisposableEffect(Unit) { onDispose { model.stopTrailer() } }
    val trailerLayer: (@Composable () -> Unit)? = if (trailersOn) {
        { TrailerLayer(model) }
    } else {
        null
    }
    Backdrop(bg, modifier, wide = true, video = trailerLayer)
}


/** Play from the billboard: carry on where you were, else the first (or next) episode. */
private fun heroPlay(model: AppModel, item: CardItem, full: com.maxim.marquee.data.MetaItem?) {
    when {
        item.videoId != null -> model.resume(item)
        full != null && full.isSeries && full.videos.isNotEmpty() -> model.play(model.reqFor(full, model.resumeTarget(full)))
        full != null && full.type == "movie" -> model.play(model.reqFor(full, null))
        else -> model.openDetail(item.meta)
    }
}

/** The episode a Continue Watching tile stands for: its title, summary and picture. */
private class HeroEpisode(val video: com.maxim.marquee.data.Video, val art: String?, val summary: String?)

@Composable
private fun rememberHeroEpisode(b: BrowseState): HeroEpisode? {
    val hero = b.hero ?: return null
    val vid = hero.videoId ?: return null
    val m = b.heroMeta ?: return null
    if (!m.isSeries || m.id != hero.meta.id) return null
    val v = remember(m, vid) { m.videos.firstOrNull { it.id == vid } } ?: return null
    val T = com.maxim.marquee.data.TvMaze
    // (first = looked up yet; the picture only switches once we know which one is best)
    val maze by produceState(T.cached(m.id)?.let { true to T.match(it, v) } ?: (false to null), m.id, vid) {
        if (!value.first) value = true to runCatching { T.match(T.episodes(m.id), v) }.getOrNull()
    }
    val art = if (maze.first) maze.second?.image ?: v.thumbnail?.takeIf { it.startsWith("http") } else null
    return HeroEpisode(v, art, v.overview?.takeIf { it.isNotBlank() } ?: maze.second?.summary)
}

@Composable
private fun BoxScope.HeroInfo(model: AppModel, b: BrowseState, full: Boolean, playFr: FocusRequester, onButtons: () -> Unit) {
    val hero = b.hero
    if (hero != null) {
        val m = b.heroMeta ?: hero.meta
        val extras = rememberExtras(m)
        val ep = rememberHeroEpisode(b)
        // A new title fades in with its picture instead of snapping (the buttons stay put).
        val heroKey = hero.meta.id + "|" + hero.videoId
        val fade = remember { Animatable(1f) }
        var shownKey by remember { mutableStateOf(heroKey) }
        LaunchedEffect(heroKey) {
            if (shownKey != heroKey) {
                fade.snapTo(0f)
                shownKey = heroKey
                fade.animateTo(1f, tween(170))
            }
        }
        val shift = with(LocalDensity.current) { 8.dp.toPx() }
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 4.dp).width(580.dp),
        ) {
          Column(
            Modifier.graphicsLayer {
                val a = if (shownKey != heroKey) 0f else fade.value
                alpha = a
                translationY = (1f - a) * shift
            },
          ) {
            TitleArt(m, big = false, extraLogo = extras?.logo)
            Spacer(Modifier.height(10.dp))
            // (the episode gets its own line below, so no "S4:E12" here as well)
            InfoRow(m, extras?.certification, leading = if (ep != null) hero.badge?.takeIf { it.startsWith("NEXT") }?.let { "NEXT" } else hero.badge ?: model.ribbonFor(m))
            if (ep != null) {
                Spacer(Modifier.height(7.dp))
                Text(
                    ep.video.label + "  ·  " + ep.video.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            (ep?.summary ?: m.description)?.let {
                Spacer(Modifier.height(if (ep != null) 4.dp else 7.dp))
                Text(
                    it, color = Color(0xFFE6E6E6), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp, modifier = Modifier.width(520.dp),
                )
            }
          }
            // The buttons only while you're up here; browsing the rows, they make room for the posters.
            if (full) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val resume = hero.videoId != null && hero.progress != null
                    PillButton(
                        if (resume) "Resume" else "Play", PlayerIcons.Play, primary = true,
                        focusRequester = playFr, onFocus = onButtons,
                    ) {
                        heroPlay(model, hero, b.heroMeta)
                    }
                    PillButton("More Info", Icons.Outlined.Info, onFocus = onButtons) { model.openDetail(hero.meta) }
                }
            }
        }
    } else if (model.addonError == null && b.rows.any { b.loaded[it.key] != true }) {
        // Only while rows are still loading (nothing to show at all stays still, not shimmering).
        SkeletonHero(Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 14.dp))
    }
}

@Composable
private fun TrailerLayer(model: AppModel) {
    val tp = model.trailerIfReady ?: return
    val video = tp.view ?: return
    val alpha by animateFloatAsState(if (tp.playing) 1f else 0f, tween(if (tp.playing) 800 else 200), label = "trailerAlpha")
    AndroidView(
        factory = {
            (video.parent as? ViewGroup)?.removeView(video)
            video
        },
        // Hidden while no trailer shows, so the screen doesn't keep blending an empty full-screen
        // video layer on every frame (that costs smoothness on TV boxes while you scroll).
        // (Visible as soon as a trailer is requested: the picture needs a live surface to start.)
        update = { it.visibility = if (tp.active || alpha > 0f) android.view.View.VISIBLE else android.view.View.INVISIBLE },
        modifier = Modifier.fillMaxSize().alpha(alpha),
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
fun CatalogRow(
    model: AppModel,
    b: BrowseState,
    row: RowModel,
    rowSpec: PivotSpec,
    onClick: ((CardItem) -> Unit)? = null,
) {
    LaunchedEffect(row.key) { model.loadRow(b, row) }
    val items = b.rowItems[row.key]
    val done = b.loaded[row.key] == true
    if (done && items.isNullOrEmpty()) return

    // Use the shape the addon declares; if it doesn't, measure the first artwork that loads.
    var detected by remember(row.key) { mutableStateOf<CardShape?>(null) }
    val declared = remember(items) {
        items?.mapNotNull { CardShape.of(it.meta.posterShape) }?.groupingBy { it }?.eachCount()?.maxByOrNull { it.value }?.key
    }
    // Live TV / sports catalogs are nearly always wide thumbnails: start their placeholders wide too.
    val guess = (row.source as? RowSource.Cat)?.catalog?.type?.let { if (it == "movie" || it == "series") null else CardShape.LANDSCAPE }
    // Continue Watching always uses posters (one wide card shouldn't stretch the whole row).
    val rowShape = if (row.source is RowSource.Continue || row.source is RowSource.NewEpisodes) CardShape.POSTER
    else declared ?: detected ?: guess ?: CardShape.POSTER
    val expandCards = Store.expandCards

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(Modifier.padding(start = 14.dp, top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(row.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            if (row.source is RowSource.Continue && !items.isNullOrEmpty()) {
                Spacer(Modifier.width(12.dp))
                Text("Hold OK for more options", color = Color(0xFF8A8A8A), fontSize = 12.sp, modifier = Modifier.padding(bottom = 2.dp))
            }
        }
        if (items.isNullOrEmpty()) {
            SkeletonRow(rowShape)
        } else {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                val rowState = b.rowState(row.key)
                // A row shows its first titles and ends with "See all" (the whole list as a grid).
                val seeAll = row.source !is RowSource.Top10 && onClick == null && items.size >= SEE_ALL_FROM
                val shown = if (seeAll && items.size > ROW_MAX) items.subList(0, ROW_MAX) else items
                val edgePx = with(LocalDensity.current) { 40.dp.toPx() }
                LazyRow(
                    state = rowState,
                    // Moving up or down into this row: the first poster showing at its left edge
                    // (the very first one unless you've scrolled this row), never the one that
                    // happens to sit under the card you came from.
                    modifier = Modifier
                        .focusProperties {
                            enter = {
                                val i = rowState.firstVisibleItemIndex + if (rowState.firstVisibleItemScrollOffset > edgePx) 1 else 0
                                b.cardRequesters["${row.key}#$i"] ?: b.cardRequesters["${row.key}#${rowState.firstVisibleItemIndex}"] ?: FocusRequester.Default
                            }
                        }
                        .focusGroup(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(shown, key = { i, it -> "$i|${it.meta.id}" }) { i, item ->
                        val key = "${row.key}#$i"
                        val fr = remember { FocusRequester() }
                        DisposableEffect(key) {
                            b.cardRequesters[key] = fr
                            onDispose { if (b.cardRequesters[key] === fr) b.cardRequesters.remove(key) }
                        }
                        // Only the card that should take focus runs this (not one task per card).
                        if (b.pendingFocusKey == key) {
                            LaunchedEffect(key) {
                                withFrameNanos { }
                                try {
                                    fr.requestFocus()
                                } catch (e: Exception) {
                                    // not attached yet
                                }
                                if (b.pendingFocusKey == key) b.pendingFocusKey = null
                            }
                        }
                        val onCardClick = {
                            when {
                                onClick != null -> onClick(item)
                                item.videoId != null -> model.resume(item)
                                else -> model.openDetail(item.meta)
                            }
                        }
                        if (row.source is RowSource.Top10) {
                            Top10Card(
                                rank = i + 1,
                                item = item,
                                focusRequester = fr,
                                onFocus = { model.onCardFocus(b, key, item) },
                                onLongClick = { model.openCardMenu(b, item, false, row.title) },
                                onClick = onCardClick,
                            )
                            return@itemsIndexed
                        }
                        val plainRow = row.source !is RowSource.Continue && row.source !is RowSource.NewEpisodes
                        PosterCard(
                            item = item,
                            width = CARD_W,
                            ribbon = if (plainRow) model.ribbonFor(item.meta) else null,
                            topRank = model.top10[item.meta.id],
                            expandable = expandCards,
                            showRating = plainRow,
                            shape = rowShape,
                            onImageAspect = if (declared == null && detected == null && i < 3) {
                                { a -> detected = CardShape.forAspect(a) }
                            } else null,
                            focusRequester = fr,
                            onFocus = {
                                model.onCardFocus(b, key, item)
                                if (i >= items.size - 6 && items.size < ROW_MAX) model.loadMore(b, row)
                            },
                            onLongClick = { model.openCardMenu(b, item, row.source is RowSource.Continue, row.title) },
                            onClick = onCardClick,
                        )
                    }
                    if (seeAll) item(key = "see-all") {
                        val key = "${row.key}#${shown.size}"
                        val fr = remember { FocusRequester() }
                        DisposableEffect(key) {
                            b.cardRequesters[key] = fr
                            onDispose { if (b.cardRequesters[key] === fr) b.cardRequesters.remove(key) }
                        }
                        SeeAllTile(rowShape, fr, onFocus = { b.lastFocusKey = key }) { model.openSeeAll(b, row) }
                    }
                }
            }
        }
    }
}

const val SEE_ALL_FROM = 10
const val ROW_MAX = 30

@Composable
private fun SeeAllTile(shape: CardShape, fr: FocusRequester, onFocus: () -> Unit, onClick: () -> Unit) {
    val w = if (shape == CardShape.POSTER) CARD_W else shape.width
    Tile(
        modifier = Modifier.width(w).height(w / shape.aspect),
        focusRequester = fr,
        onFocus = onFocus,
        onClick = onClick,
    ) { f ->
        Box(Modifier.fillMaxSize().background(if (f) Color(0xFF2E2E2E) else Color(0xFF1A1A1A)))
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(38.dp).border(1.5.dp, if (f) Color.White else Color(0xFF8A8A8A), RoundedCornerShape(19.dp)),
                contentAlignment = Alignment.Center,
            ) { Text("›", color = if (f) Color.White else Color(0xFFB0B0B0), fontSize = 24.sp, fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(8.dp))
            Text("See all", color = if (f) Color.White else Color(0xFFB0B0B0), fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The full list of one row as a grid. More titles load as you go down, for as long as the
 * catalog has them.
 */
@Composable
fun SeeAllScreen(model: AppModel, screen: com.maxim.marquee.Screen.SeeAll) {
    val b = screen.b
    val row = screen.row
    val items = b.rowItems[row.key] ?: emptyList()
    val shape = remember(row.key) {
        if (row.source is RowSource.Continue || row.source is RowSource.NewEpisodes) CardShape.POSTER
        else items.mapNotNull { CardShape.of(it.meta.posterShape) }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: CardShape.POSTER
    }
    val cell = if (shape == CardShape.POSTER) CARD_W else shape.width
    val plain = row.source !is RowSource.Continue && row.source !is RowSource.NewEpisodes
    Column(Modifier.fillMaxSize().background(C.Bg).padding(start = 44.dp, top = 30.dp, end = 36.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(row.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text("${items.size} titles", color = Color(0xFF8A8A8A), fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
            state = screen.grid,
            columns = androidx.compose.foundation.lazy.grid.GridCells.Adaptive(cell),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            contentPadding = PaddingValues(start = 8.dp, top = 14.dp, end = 8.dp, bottom = 90.dp),
            modifier = Modifier.fillMaxSize().focusGroup(),
        ) {
            items(items.size, key = { i -> "$i|${items[i].meta.id}" }) { i ->
                val item = items[i]
                val fr = remember { FocusRequester() }
                if (i == screen.focus) {
                    LaunchedEffect(Unit) {
                        repeat(3) {
                            withFrameNanos { }
                            if (runCatching { fr.requestFocus() }.isSuccess) return@LaunchedEffect
                        }
                    }
                }
                PosterCard(
                    item = item,
                    width = CARD_W,
                    shape = shape,
                    ribbon = if (plain) model.ribbonFor(item.meta) else null,
                    showRating = plain,
                    focusRequester = fr,
                    onFocus = {
                        screen.focus = i
                        if (i >= items.size - 14) model.loadMore(b, row)
                    },
                    onLongClick = { model.openCardMenu(b, item, row.source is RowSource.Continue, row.title) },
                    onClick = { if (item.videoId != null) model.resume(item) else model.openDetail(item.meta) },
                )
            }
        }
    }
}

/**
 * The opening animation: the MARQUEE sign lights up left to right while Home gets ready behind
 * it, then lifts away. It never waits longer than a couple of seconds and never takes the remote.
 */
@Composable
fun SplashOverlay(model: AppModel) {
    if (model.splashDone) return
    val draw = remember { Animatable(0f) }
    val out = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        draw.animateTo(1f, tween(750, easing = androidx.compose.animation.core.FastOutSlowInEasing))
        // Hold until Home has something to show (but not for long).
        var waited = 0
        while (!model.homeReady && model.loggedIn && model.profileGate == com.maxim.marquee.ProfileGate.NONE && waited < 1_500) { delay(50); waited += 50 }
        delay(150)
        out.animateTo(0f, tween(280))
        model.splashDone = true
    }
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = out.value }.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .graphicsLayer {
                        val s = 0.94f + 0.06f * draw.value + 0.05f * (1f - out.value)
                        scaleX = s; scaleY = s
                    }
                    // The letters appear from left to right, like a sign being switched on.
                    .drawWithContent {
                        val edge = size.width * draw.value
                        clipRect(left = -40f, top = -40f, right = edge, bottom = size.height * 2f) { this@drawWithContent.drawContent() }
                    },
            ) { MarqueeLogo(92.dp) }
            Spacer(Modifier.height(34.dp))
            // A thin red line that grows with it.
            Box(
                Modifier.width(260.dp).height(3.dp).graphicsLayer { scaleX = draw.value; alpha = draw.value }
                    .background(Brush.horizontalGradient(listOf(Color.Transparent, C.Red, Color.Transparent))),
            )
        }
    }
}

/**
 * The MARQUEE wordmark, cinema style: tall condensed red letters on a slight arc (the outer
 * letters a little taller, like a film title card), with a soft shadow.
 */
@Composable
fun MarqueeLogo(height: androidx.compose.ui.unit.Dp = 30.dp) {
    val letters = "MARQUEE"
    val font = androidx.compose.ui.text.font.FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD))
    val brush = Brush.verticalGradient(0f to Color(0xFFFF2A2A), 0.55f to Color(0xFFE50914), 1f to Color(0xFF9E0710))
    val style = androidx.compose.ui.text.TextStyle(
        brush = brush,
        fontFamily = font,
        fontWeight = FontWeight.Black,
        fontSize = with(LocalDensity.current) { (height * 0.82f).toSp() },
        shadow = androidx.compose.ui.graphics.Shadow(Color(0xAA000000), androidx.compose.ui.geometry.Offset(0f, 3f), 6f),
        letterSpacing = 0.sp,
    )
    Row(Modifier.height(height), verticalAlignment = Alignment.Top) {
        val mid = (letters.length - 1) / 2f
        letters.forEachIndexed { i, ch ->
            // 0 in the middle, 1 at the ends: the ends stretch taller, bottoms curve like a smile.
            val t = kotlin.math.abs(i - mid) / mid
            Text(
                ch.toString(),
                style = style,
                modifier = Modifier.graphicsLayer {
                    scaleY = 1.18f + 0.16f * t * t
                    transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
                },
            )
        }
    }
}
