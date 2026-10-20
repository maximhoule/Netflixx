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

@Composable
fun MainShell(model: AppModel, railRequesters: Map<Tab, FocusRequester>) {
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        when (model.tab) {
            Tab.HOME, Tab.SERIES, Tab.MOVIES, Tab.SPORTS -> BrowseScreen(model, model.browse.getValue(model.tab))
            Tab.SEARCH -> SearchScreen(model)
            Tab.MYLIST -> MyListScreen(model)
            Tab.SETTINGS -> SettingsScreen(model)
            Tab.PROFILE -> Unit
        }
        Rail(model, railRequesters)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun Rail(model: AppModel, requesters: Map<Tab, FocusRequester>) {
    var expanded by remember { mutableStateOf(false) }
    val width by animateDpAsState(if (expanded) 230.dp else RAIL, tween(140), label = "railWidth")
    Box(Modifier.fillMaxHeight()) {
        if (expanded) {
            Box(
                Modifier.fillMaxHeight().width(520.dp).background(
                    Brush.horizontalGradient(0f to Color(0xF2000000), 0.5f to Color(0xB3000000), 1f to Color.Transparent),
                ),
            )
        }
        Text(
            "M",
            color = C.Red,
            fontSize = 30.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(start = 24.dp, top = 18.dp),
        )
        Column(
            Modifier
                .fillMaxHeight()
                .width(width)
                .focusProperties { enter = { requesters.getValue(model.tab) } }
                .onFocusChanged { expanded = it.hasFocus; model.railFocused = it.hasFocus }
                .focusGroup()
                .padding(horizontal = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            model.tabs.forEach { t ->
                val selected = t == model.tab
                Tile(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    shape = RoundedCornerShape(8.dp),
                    focusedScale = 1f,
                    border = false,
                    focusRequester = requesters.getValue(t),
                    onClick = { model.selectTab(t) },
                ) { f ->
                    Row(
                        Modifier.fillMaxSize().background(if (f) Color.White else Color.Transparent).padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val tint = when {
                            f -> Color.Black
                            selected -> Color.White
                            else -> C.Dim
                        }
                        if (t == Tab.PROFILE) {
                            Avatar(com.maxim.marquee.data.Store.activeProfile, 24.dp)
                        } else {
                            Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        if (expanded) {
                            Spacer(Modifier.width(18.dp))
                            Text(
                                if (t == Tab.PROFILE) com.maxim.marquee.data.Store.activeProfile.name else t.label,
                                color = tint,
                                fontSize = 17.sp,
                                fontWeight = if (selected || f) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                            )
                        }
                    }
                    if (selected && !f && !expanded) {
                        Box(
                            Modifier.align(Alignment.BottomCenter).padding(bottom = 4.dp)
                                .width(16.dp).height(3.dp).background(C.Red, RoundedCornerShape(2.dp)),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BrowseScreen(model: AppModel, b: BrowseState) {
    LaunchedEffect(b) {
        if (b.lastFocusKey != null) b.pendingFocusKey = b.lastFocusKey
    }
    LaunchedEffect(model.contentFocusTick) {
        if (model.contentFocusTick > model.handledTick) {
            model.handledTick = model.contentFocusTick
            b.pendingFocusKey = b.lastFocusKey ?: b.firstKey()
        }
    }
    val density = LocalDensity.current
    val rowSpec = remember(density) { PivotSpec(with(density) { 14.dp.toPx() }) }
    // The focused card's top edge; leaves room for the row title above it.
    val colSpec = remember(density) { PivotSpec(with(density) { 46.dp.toPx() }) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val heroH = maxHeight * 0.48f
        // The billboard lives in its own composables: changing title only redraws the billboard,
        // never the rows below it.
        HeroBackdrop(model, b, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.74f).height(maxHeight * 0.72f))

        Column(Modifier.fillMaxSize().padding(start = RAIL + 12.dp)) {
            Box(Modifier.fillMaxWidth().height(heroH)) {
                HeroInfo(model, b)
            }

            val hasCatalogs = b.rows.any { it.source !is RowSource.Continue && it.source !is RowSource.NewEpisodes }
            val err = model.addonError
            if (err != null && !hasCatalogs) {
                Box(Modifier.fillMaxWidth().weight(1f)) { AddonErrorPanel(model, err) }
            } else if (b.rows.isNotEmpty() && b.rows.all { b.loaded[it.key] == true && b.rowItems[it.key].isNullOrEmpty() }) {
                // Everything answered, nothing to show (e.g. no games on right now).
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Centered(
                        if (b.sportsOnly) "No games right now" else "Nothing to show yet",
                        if (b.sportsOnly) "Your sports addons didn't list any events. Check back closer to game time." else null,
                    )
                }
            } else if (!hasCatalogs && b.rowItems["continue"].isNullOrEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (model.syncing) {
                        Column { repeat(2) { SkeletonRow(withTitle = true) } }
                    } else {
                        Centered("Nothing to show yet", "Install catalog addons in Stremio and they'll appear here.")
                    }
                }
            } else {
                CompositionLocalProvider(LocalBringIntoViewSpec provides colSpec) {
                    LazyColumn(
                        state = b.columnState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
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

@Composable
private fun HeroBackdrop(model: AppModel, b: BrowseState, modifier: Modifier) {
    val hero = b.hero
    val bg = b.heroMeta?.background ?: hero?.meta?.backdropUrl
    // Trailer behind the billboard once you rest on a title for a couple of seconds.
    val trailersOn = Store.trailerMode != 0
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
    Backdrop(bg, modifier, video = trailerLayer)
}

@Composable
private fun BoxScope.HeroInfo(model: AppModel, b: BrowseState) {
    val hero = b.hero
    if (hero != null) {
        val m = b.heroMeta ?: hero.meta
        val extras = rememberExtras(m)
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp).width(620.dp),
        ) {
            TitleArt(m, big = true, extraLogo = extras?.logo)
            Spacer(Modifier.height(12.dp))
            InfoRow(m, extras?.certification, leading = hero.badge ?: model.ribbonFor(m))
            m.description?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = Color(0xFFE6E6E6), fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
            }
        }
    } else if (model.addonError == null) {
        SkeletonHero(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp))
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
    val rowShape = declared ?: detected ?: guess ?: CardShape.POSTER
    val expandCards = Store.expandCards

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            row.title,
            color = Color(0xFFE5E5E5),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp),
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
