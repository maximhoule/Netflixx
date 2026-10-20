package com.maxim.marquee.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
            Tab.HOME, Tab.SERIES, Tab.MOVIES -> BrowseScreen(model, model.browse.getValue(model.tab))
            Tab.SEARCH -> SearchScreen(model)
            Tab.MYLIST -> MyListScreen(model)
            Tab.SETTINGS -> SettingsScreen(model)
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
            Tab.entries.forEach { t ->
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
                        Icon(t.icon, t.label, tint = tint, modifier = Modifier.size(22.dp))
                        if (expanded) {
                            Spacer(Modifier.width(18.dp))
                            Text(
                                t.label,
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
        val hero = b.hero
        val bg = b.heroMeta?.background ?: hero?.meta?.background
        Backdrop(bg, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.74f).height(maxHeight * 0.72f))

        Column(Modifier.fillMaxSize().padding(start = RAIL + 12.dp)) {
            Box(Modifier.fillMaxWidth().height(heroH)) {
                if (hero != null) {
                    val m = b.heroMeta ?: hero.meta
                    Column(
                        Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 14.dp).width(560.dp),
                    ) {
                        TitleArt(m, big = true)
                        Spacer(Modifier.height(10.dp))
                        val line = infoLine(m)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            hero.badge?.let {
                                Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(12.dp))
                            }
                            if (line.isNotEmpty()) Text(line, color = C.Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        m.description?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, color = Color(0xFFE6E6E6), fontSize = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, lineHeight = 21.sp)
                        }
                    }
                }
            }

            val hasCatalogs = b.rows.any { it.source !is RowSource.Continue }
            if (!hasCatalogs && b.rowItems["continue"].isNullOrEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    Centered(
                        if (model.syncing) "Loading your addons…" else "Nothing to show yet",
                        if (model.syncing) null else "Install catalog addons in Stremio and they'll appear here.",
                    )
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

    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Text(
            row.title,
            color = Color(0xFFE5E5E5),
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 14.dp, top = 6.dp),
        )
        if (items.isNullOrEmpty()) {
            Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(7) {
                    Box(Modifier.width(CARD_W).height(CARD_W * 1.5f).background(C.Surface, RoundedCornerShape(6.dp)))
                }
            }
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
                        LaunchedEffect(b.pendingFocusKey) {
                            if (b.pendingFocusKey == key) {
                                withFrameNanos { }
                                try {
                                    fr.requestFocus()
                                } catch (e: Exception) {
                                    // not attached yet
                                }
                                b.pendingFocusKey = null
                            }
                        }
                        PosterCard(
                            item = item,
                            width = CARD_W,
                            focusRequester = fr,
                            onFocus = {
                                model.onCardFocus(b, key, item)
                                if (i >= items.size - 6) model.loadMore(b, row)
                            },
                            onLongClick = { model.openCardMenu(b, item, row.source is RowSource.Continue) },
                            onClick = {
                                when {
                                    onClick != null -> onClick(item)
                                    item.videoId != null -> model.resume(item)
                                    else -> model.openDetail(item.meta)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}
