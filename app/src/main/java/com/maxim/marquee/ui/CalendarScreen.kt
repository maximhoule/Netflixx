package com.maxim.marquee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.maxim.marquee.AppModel

private val CAL_W = 236.dp

/**
 * New and upcoming episodes of the shows you watch or follow: a week back, six weeks ahead, one
 * row per day. Opens on today (or the next day that has something).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CalendarScreen(model: AppModel) {
    LaunchedEffect(Unit) { model.loadCalendar() }
    val days = model.calendar
    val today = remember { model.calendarToday() }
    val listState = remember { LazyListState() }
    // Every card's focus handle, "day#index", so a row can be entered at its first card.
    val requesters = remember { HashMap<String, FocusRequester>() }
    val startIndex = days?.indexOfFirst { it.day >= today }?.takeIf { it >= 0 } ?: days?.lastIndex?.coerceAtLeast(0) ?: 0

    var focusTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(days != null, model.contentFocusTick) {
        if (days.isNullOrEmpty()) return@LaunchedEffect
        listState.scrollToItem(startIndex)
        if (model.contentFocusTick > model.handledTick) {
            model.handledTick = model.contentFocusTick
            focusTick++
        }
    }
    LaunchedEffect(focusTick) {
        if (focusTick == 0) return@LaunchedEffect
        val d = days?.getOrNull(startIndex) ?: return@LaunchedEffect
        repeat(4) {
            withFrameNanos { }
            if (runCatching { requesters["${d.day}#0"]?.requestFocus() }.isSuccess && requesters["${d.day}#0"] != null) return@LaunchedEffect
        }
    }

    Column(Modifier.fillMaxSize().padding(start = RAIL + 24.dp, top = 28.dp)) {
        Text("Calendar", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Text(
            "New and upcoming episodes of the shows you watch and follow",
            color = C.Dim, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        when {
            days == null -> Column { repeat(2) { SkeletonRow(CardShape.LANDSCAPE, withTitle = true) } }
            days.isEmpty() -> Centered(
                "Nothing coming up",
                "When shows you watch or follow have new episodes in the next few weeks, they'll show up here.",
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().focusGroup(),
                contentPadding = PaddingValues(bottom = 200.dp),
            ) {
                items(days, key = { it.day }) { d ->
                    val label = model.calendarLabel(d.day)
                    val upcoming = d.day > today
                    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(start = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                label,
                                color = if (d.day == today) C.Red else Color.White,
                                fontSize = 19.sp, fontWeight = FontWeight.Bold,
                            )
                            if (label == "Today" || label == "Tomorrow" || label == "Yesterday") {
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    java.time.LocalDate.ofEpochDay(d.day).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d")),
                                    color = C.Dim, fontSize = 14.sp,
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                if (d.entries.size == 1) "1 episode" else "${d.entries.size} episodes",
                                color = Color(0xFF7A7A7A), fontSize = 13.sp,
                            )
                        }
                        val rowState = remember(d.day) { LazyListState() }
                        LazyRow(
                            state = rowState,
                            // Into a day from above or below: its first card showing.
                            modifier = Modifier
                                .focusProperties {
                                    enter = { requesters["${d.day}#${rowState.firstVisibleItemIndex}"] ?: FocusRequester.Default }
                                }
                                .focusGroup(),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            itemsIndexed(d.entries, key = { _, e -> e.ep.id }) { i, e ->
                                val key = "${d.day}#$i"
                                val fr = remember { FocusRequester() }
                                DisposableEffect(key) {
                                    requesters[key] = fr
                                    onDispose { if (requesters[key] === fr) requesters.remove(key) }
                                }
                                CalendarCard(e, upcoming, fr) { model.openDetail(e.meta) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarCard(e: AppModel.CalEntry, upcoming: Boolean, fr: FocusRequester, onClick: () -> Unit) {
    Tile(
        modifier = Modifier.width(CAL_W),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.05f,
        border = false,
        focusRequester = fr,
        onClick = onClick,
    ) { f ->
        Column(
            Modifier
                .fillMaxWidth()
                .background(if (f) Color(0xFF2A2A2A) else Color(0xFF161616))
                .border(2.5.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(10.dp)),
        ) {
            Box(Modifier.fillMaxWidth().height(CAL_W * 9f / 16f).clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)).background(C.Surface)) {
                val img = e.ep.thumbnail ?: e.meta.background ?: e.meta.poster
                if (img != null) AsyncImage(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Box(
                    Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color(0xD9000000))),
                )
                Text(
                    e.meta.name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 7.dp),
                )
                val badge = when {
                    e.watched -> null
                    upcoming -> "UPCOMING"
                    else -> "NEW"
                }
                if (e.watched) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).background(Color(0xCC000000), RoundedCornerShape(11.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(PlayerIcons.Check, "Watched", tint = Color.White, modifier = Modifier.size(15.dp)) }
                }
                badge?.let {
                    Text(
                        it, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                        modifier = Modifier.align(Alignment.TopStart).padding(7.dp)
                            .background(if (upcoming) Color(0xCC333333) else C.Red, RoundedCornerShape(3.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    e.ep.label + "  ·  " + e.ep.title,
                    color = if (upcoming) Color(0xFFCFCFCF) else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
