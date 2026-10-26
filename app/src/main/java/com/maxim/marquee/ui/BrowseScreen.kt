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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

val CARD_W = 118.dp

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
            Box(Modifier.fillMaxSize().padding(top = TOP_BAR - 8.dp)) {
                when (model.tab) {
                    Tab.SEARCH -> SearchScreen(model)
                    Tab.MYLIST -> MyListScreen(model)
                    Tab.SETTINGS -> SettingsScreen(model)
                    else -> Unit
                }
            }
        }
        TopNav(model, railRequesters)
    }
}

private val NAV_TEXT_TABS = listOf(Tab.HOME, Tab.SERIES, Tab.MOVIES, Tab.SPORTS, Tab.MYLIST, Tab.SETTINGS)

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
        Text("MARQUEE", color = C.Red, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
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
    val cardEntry: () -> FocusRequester = { (b.lastFocusKey ?: b.firstKey())?.let { b.cardRequesters[it] } ?: FocusRequester.Default }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroH = maxHeight * 0.565f
        // The billboard lives in its own composables: changing title only redraws the billboard,
        // never the rows below it.
        HeroBackdrop(model, b, active, Modifier.align(Alignment.TopCenter).fillMaxWidth().height(maxHeight * 0.88f))

        Column(Modifier.fillMaxSize().focusProperties { enter = { cardEntry() } }.focusGroup()) {
            Box(Modifier.fillMaxWidth().height(heroH)) {
                HeroInfo(model, b)
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
                            modifier = Modifier.fillMaxSize()
                                .focusProperties { enter = { cardEntry() } }
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
    val bg = b.heroMeta?.background ?: hero?.meta?.backdropUrl
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

private fun kindLabel(m: com.maxim.marquee.data.MetaItem) = when (m.type) {
    "movie" -> "FILM"
    "series" -> "SERIES"
    "tv", "channel" -> "LIVE"
    else -> m.type.uppercase()
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

@Composable
private fun BoxScope.HeroInfo(model: AppModel, b: BrowseState) {
    val hero = b.hero
    if (hero != null) {
        val m = b.heroMeta ?: hero.meta
        val extras = rememberExtras(m)
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 48.dp, bottom = 4.dp).width(580.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MARQUEE", color = C.Red, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
                Spacer(Modifier.width(7.dp))
                Text(kindLabel(m), color = Color(0xFFE0E0E0), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            }
            Spacer(Modifier.height(4.dp))
            TitleArt(m, big = false, extraLogo = extras?.logo)
            Spacer(Modifier.height(10.dp))
            InfoRow(m, extras?.certification, leading = hero.badge ?: model.ribbonFor(m))
            m.description?.let {
                Spacer(Modifier.height(7.dp))
                Text(
                    it, color = Color(0xFFE6E6E6), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    lineHeight = 20.sp, modifier = Modifier.width(520.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val resume = hero.videoId != null && hero.progress != null
                PillButton(if (resume) "Resume" else "Play", PlayerIcons.Play, primary = true) {
                    heroPlay(model, hero, b.heroMeta)
                }
                PillButton("More Info", Icons.Outlined.Info) { model.openDetail(hero.meta) }
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

@OptIn(ExperimentalFoundationApi::class)
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
        Text(
            row.title,
            color = Color.White,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 14.dp, top = 4.dp),
        )
        if (items.isNullOrEmpty()) {
            SkeletonRow(rowShape)
        } else {
            CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
                LazyRow(
                    state = b.rowState(row.key),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    itemsIndexed(items, key = { i, it -> "$i|${it.meta.id}" }) { i, item ->
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
                                if (i >= items.size - 6) model.loadMore(b, row)
                            },
                            onLongClick = { model.openCardMenu(b, item, row.source is RowSource.Continue, row.title) },
                            onClick = onCardClick,
                        )
                    }
                }
            }
        }
    }
}
