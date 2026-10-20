package com.maxim.marquee.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxim.marquee.AppModel
import com.maxim.marquee.BrowseState
import com.maxim.marquee.PlayReq
import com.maxim.marquee.RowModel
import com.maxim.marquee.RowSource
import com.maxim.marquee.Screen
import com.maxim.marquee.data.Addon
import com.maxim.marquee.data.CardItem
import com.maxim.marquee.data.MetaItem
import com.maxim.marquee.data.Repo
import com.maxim.marquee.data.Store
import com.maxim.marquee.data.StreamItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun tryFocus(fr: FocusRequester) {
    try {
        fr.requestFocus()
    } catch (e: Exception) {
        // not attached
    }
}

// ---------------------------------------------------------------- sources

@Composable
fun StreamsScreen(model: AppModel, s: Screen.Streams) {
    val results = remember(s) { mutableStateMapOf<Int, Pair<Addon, List<StreamItem>>>() }
    var done by remember(s) { mutableStateOf(false) }
    LaunchedEffect(s) {
        Repo.streamsFlow(s.req.type, s.req.videoId).collect { (i, a, list) -> results[i] = a to list }
        done = true
    }
    val flat = results.keys.sorted().flatMap { k -> results.getValue(k).second }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(flat.isNotEmpty()) {
        if (flat.isNotEmpty()) {
            withFrameNanos { }
            tryFocus(firstFr)
        }
    }

    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(s.req.background, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.7f).fillMaxHeight())
        Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        Row(Modifier.fillMaxSize().padding(start = 56.dp, top = 48.dp, end = 40.dp)) {
            Column(Modifier.width(300.dp).padding(end = 28.dp)) {
                Text(s.req.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, lineHeight = 34.sp, maxLines = 3)
                s.req.episode?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = C.Dim, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    when {
                        !done -> "Finding sources…"
                        flat.isEmpty() -> "No playable sources found"
                        else -> "${flat.size} sources"
                    },
                    color = C.Dim, fontSize = 15.sp,
                )
                if (!done) {
                    Spacer(Modifier.height(14.dp))
                    CircularProgressIndicator(color = C.Red, modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                }
                if (done && flat.isEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Check that Duck Streams (or another stream addon) is installed and your debrid account is active.",
                        color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp,
                    )
                }
            }
            LazyColumn(
                Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 60.dp, top = 4.dp, start = 6.dp, end = 6.dp),
            ) {
                itemsIndexed(flat, key = { i, st -> "$i|${st.url}" }) { i, st ->
                    Tile(
                        modifier = Modifier.fillMaxWidth(),
                        focusedScale = 1.02f,
                        border = false,
                        focusRequester = if (i == 0) firstFr else null,
                        onClick = { model.playFromList(s.req, flat, i) },
                    ) { f ->
                        Row(
                            Modifier.fillMaxWidth()
                                .background(if (f) Color.White else Color(0x26FFFFFF))
                                .padding(horizontal = 18.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                st.name, color = if (f) Color.Black else Color.White, fontSize = 14.sp,
                                fontWeight = FontWeight.Bold, lineHeight = 18.sp, modifier = Modifier.width(150.dp),
                                maxLines = 4, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(14.dp))
                            Column {
                                val badges = st.quality.badges
                                if (badges.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 4.dp)) {
                                        badges.forEach { b -> Badge(b, f) }
                                    }
                                }
                                Text(
                                    st.title, color = if (f) Color(0xFF333333) else C.Dim, fontSize = 13.sp,
                                    lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, focused: Boolean) {
    val accent = when (text) {
        "4K", "DV", "HDR", "HDR10+", "Atmos", "REMUX" -> true
        else -> false
    }
    Text(
        text,
        color = when {
            focused -> Color.White
            accent -> Color.Black
            else -> Color.White
        },
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(
                when {
                    focused -> Color(0xFF333333)
                    accent -> Color(0xFFE6E6E6)
                    else -> Color(0x40FFFFFF)
                },
                RoundedCornerShape(3.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

// ---------------------------------------------------------------- long-press menu

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CardMenuOverlay(model: AppModel) {
    val m = model.cardMenu ?: return
    val meta = m.item.meta
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(m) {
        withFrameNanos { }
        tryFocus(firstFr)
    }
    Box(Modifier.fillMaxSize().background(Color(0xCC000000)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(420.dp)
                .focusProperties { exit = { FocusRequester.Cancel } }
                .focusGroup()
                .background(Color(0xFF181818), RoundedCornerShape(10.dp)).padding(24.dp),
        ) {
            Text(meta.name, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            m.item.badge?.let { Text(it, color = C.Dim, fontSize = 14.sp) }
            Spacer(Modifier.height(18.dp))
            val options = mutableListOf<Pair<String, () -> Unit>>()
            options += "Details" to { model.closeCardMenu(); model.openDetail(meta) }
            if (m.item.videoId != null) options += "Resume" to { model.closeCardMenu(); model.resume(m.item) }
            if (!com.maxim.marquee.data.Emby.isEmby(meta.id)) {
                options += (if (model.inMyList(meta.id)) "Remove from My List" else "Add to My List") to {
                    model.toggleMyList(meta); model.closeCardMenu()
                }
            }
            if (meta.type == "series" || meta.type == "movie") {
                options += (if (meta.type == "series") "Mark show as watched" else "Mark as watched") to {
                    model.markWatched(meta); model.closeCardMenu()
                }
            }
            if (m.fromContinue) options += "Remove from Continue Watching" to { model.dismissContinue(m.item); model.closeCardMenu() }
            m.rowTitle?.takeIf { !m.fromContinue }?.let { title ->
                options += "Hide the \"$title\" row" to { model.closeCardMenu(); model.hideRow(title) }
            }
            options.forEachIndexed { i, (label, action) ->
                Tile(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                    focusedScale = 1.02f, border = false,
                    focusRequester = if (i == 0) firstFr else null,
                    onClick = action,
                ) { f ->
                    Text(
                        label, color = if (f) Color.Black else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().background(if (f) Color.White else Color(0x1AFFFFFF)).padding(horizontal = 18.dp, vertical = 13.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- loading overlay

@Composable
fun LoadingOverlay(req: PlayReq) {
    Box(Modifier.fillMaxSize().background(C.Bg)) {
        Backdrop(req.background, Modifier.align(Alignment.TopEnd).fillMaxWidth(0.75f).fillMaxHeight())
        Box(Modifier.fillMaxSize().background(Color(0x88000000)))
        Column(Modifier.align(Alignment.CenterStart).padding(start = 64.dp)) {
            Text(req.name, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            req.episode?.let { Text(it, color = C.Dim, fontSize = 17.sp, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(color = C.Red, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(14.dp))
                Text("Finding the best source…", color = Color.White, fontSize = 17.sp)
            }
            Spacer(Modifier.height(10.dp))
            Text("Press Back to cancel", color = C.Dim, fontSize = 13.sp)
        }
    }
}

// ---------------------------------------------------------------- search

private val KEYS = ("abcdefghijklmnopqrstuvwxyz1234567890").map { it.toString() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SearchScreen(model: AppModel) {
    val b = model.search
    var query by model::searchQuery
    var busy by remember { mutableStateOf(false) }
    val firstKey = remember { FocusRequester() }
    val density = LocalDensity.current
    val rowSpec = remember(density) { PivotSpec(with(density) { 14.dp.toPx() }) }
    val colSpec = remember(density) { PivotSpec(with(density) { 46.dp.toPx() }) }

    LaunchedEffect(Unit) {
        // Coming back from a title: put focus back where it was.
        if (b.lastFocusKey != null) b.pendingFocusKey = b.lastFocusKey
    }
    LaunchedEffect(model.contentFocusTick) {
        if (model.contentFocusTick > model.handledTick) {
            model.handledTick = model.contentFocusTick
            withFrameNanos { }
            tryFocus(firstKey)
        }
    }
    LaunchedEffect(query) {
        if (query == model.searchedQuery) return@LaunchedEffect
        if (query.length < 2) {
            if (query.isEmpty()) { b.reset(emptyList()); model.searchedQuery = "" }
            return@LaunchedEffect
        }
        delay(600)
        busy = true
        val res = Repo.search(query)
        val defs = res.mapIndexed { i, (title, _) -> RowModel("s$i|$title", title, RowSource.Static) }
        b.reset(defs)
        res.forEachIndexed { i, (_, items) ->
            b.rowItems[defs[i].key] = items.map { CardItem(it) }
            b.loaded[defs[i].key] = true
        }
        model.searchedQuery = query
        busy = false
    }

    Row(Modifier.fillMaxSize().padding(start = RAIL + 16.dp, top = 40.dp)) {
        Column(Modifier.width(300.dp)) {
            Text(
                if (query.isEmpty()) "Search" else query,
                color = if (query.isEmpty()) C.Dim else Color.White,
                fontSize = 24.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp, bottom = 14.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyTile("🎤", Modifier.width(52.dp)) { model.voiceSearch() }
                KeyTile("SPACE", Modifier.weight(1f)) { query += " " }
                KeyTile("⌫", Modifier.width(64.dp)) { query = query.dropLast(1) }
                KeyTile("CLEAR", Modifier.width(70.dp)) { query = "" }
            }
            Spacer(Modifier.height(6.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().height(290.dp),
            ) {
                itemsIndexed(KEYS) { i, k ->
                    KeyTile(k.uppercase(), Modifier.fillMaxWidth(), if (i == 0) firstKey else null) { query += k }
                }
            }
        }
        Spacer(Modifier.width(20.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) {
            when {
                busy && b.rows.isEmpty() -> Centered("Searching…")
                query.length >= 2 && model.searchedQuery == query && b.rows.isEmpty() -> Centered("No results", "Try a different title")
                b.rows.isEmpty() -> Centered("Find movies and series", "Uses the search catalogs from your addons")
                else -> CompositionLocalProvider(LocalBringIntoViewSpec provides colSpec) {
                    LazyColumn(Modifier.fillMaxSize(), state = b.columnState, contentPadding = PaddingValues(bottom = 300.dp)) {
                        items(b.rows, key = { it.key }) { row -> CatalogRow(model, b, row, rowSpec) }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyTile(label: String, modifier: Modifier, fr: FocusRequester? = null, onClick: () -> Unit) {
    Tile(modifier = modifier.height(40.dp), focusedScale = 1.1f, border = false, focusRequester = fr, onClick = onClick) { f ->
        Box(
            Modifier.fillMaxSize().background(if (f) Color.White else Color(0x26FFFFFF)),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, color = if (f) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------- my list

@Composable
fun MyListScreen(model: AppModel) {
    val items = model.library.filter { it.inMyList }.sortedByDescending { it.mtime }
    val firstFr = remember { FocusRequester() }
    LaunchedEffect(model.contentFocusTick, items.isNotEmpty()) {
        if (model.contentFocusTick > model.handledTick && items.isNotEmpty()) {
            model.handledTick = model.contentFocusTick
            withFrameNanos { }
            tryFocus(firstFr)
        }
    }
    Column(Modifier.fillMaxSize().padding(start = RAIL + 24.dp, top = 36.dp, end = 24.dp)) {
        Text("My List", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        if (items.isEmpty()) {
            Centered("Your list is empty", "Titles you add to your Stremio library show up here")
        } else {
            LazyVerticalGrid(
                state = model.myListGrid,
                columns = GridCells.Adaptive(CARD_W),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 80.dp),
            ) {
                itemsIndexed(items, key = { _, e -> e.id }) { i, e ->
                    val meta = MetaItem(id = e.id, type = e.type, name = e.name, poster = e.poster, posterShape = e.posterShape)
                    val fr = if (i == 0) firstFr else remember { FocusRequester() }
                    LaunchedEffect(Unit) {
                        if (i == model.myListFocus) {
                            withFrameNanos { }
                            tryFocus(fr)
                            model.myListFocus = -1
                        }
                    }
                    PosterCard(
                        item = CardItem(meta),
                        width = CARD_W,
                        focusRequester = fr,
                        onClick = { model.myListFocus = i; model.openDetail(meta) },
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- settings

@Composable
fun SettingsScreen(model: AppModel) {
    var version by remember { mutableIntStateOf(0) }
    val firstFr = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(model.contentFocusTick) {
        if (model.contentFocusTick > model.handledTick) {
            model.handledTick = model.contentFocusTick
            withFrameNanos { }
            tryFocus(firstFr)
        }
    }
    fun onOff(v: Boolean) = if (v) "On" else "Off"
    if (version < 0) return // reading version re-renders the values below after each toggle
    val langName = Store.subtitleLanguages.firstOrNull { it.first == Store.subLang }?.second ?: Store.subLang

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = RAIL + 24.dp, end = 120.dp, top = 36.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 10.dp))
        SettingRow(
            "Profile", Store.activeProfile.name,
            "Each profile has its own Stremio account, Continue Watching, My List and settings · select to switch or add",
            firstFr,
        ) { model.openProfiles() }
        model.copySources().forEach { p ->
            SettingRow("Copy addons from ${p.name}", "", "Replaces this account's Stremio addons with ${p.name}'s") { model.offerCopyFrom = p }
        }
        SettingRow("Play the best source automatically", onOff(Store.autoplay), "Off shows the source list every time") {
            Store.autoplay = !Store.autoplay; version++
        }
        SettingRow("Autoplay next episode", onOff(Store.autoNext)) { Store.autoNext = !Store.autoNext; version++ }
        SettingRow("Sync progress to Stremio", onOff(Store.syncProgress), "Keeps Continue Watching in step with your other devices") {
            Store.syncProgress = !Store.syncProgress; version++
        }
        SettingRow("Subtitles on by default", onOff(Store.subsOn), "You can always toggle them with CC in the player") {
            Store.subsOn = !Store.subsOn; version++
        }
        SettingRow("Subtitle language", langName) {
            val langs = Store.subtitleLanguages
            val i = langs.indexOfFirst { it.first == Store.subLang }
            Store.subLang = langs[(i + 1) % langs.size].first; version++
        }
        SettingRow(
            "Max quality",
            when (Store.maxQuality) { 2160 -> "4K"; 1080 -> "1080p"; 720 -> "720p"; else -> "Best available" },
            "Caps the source picked automatically; lower it if your connection or box struggles",
        ) {
            val opts = listOf(0, 2160, 1080, 720)
            Store.maxQuality = opts[(opts.indexOf(Store.maxQuality).coerceAtLeast(0) + 1) % opts.size]; version++
        }
        SettingRow(
            "Trailers on the home screen",
            when (Store.trailerMode) { 0 -> "Off"; 2 -> "With sound"; else -> "Muted" },
            "Plays the trailer behind the billboard when you rest on a title",
        ) {
            Store.trailerMode = (Store.trailerMode + 1) % 3; version++
        }
        val hiddenCount = Store.hiddenRows.size
        if (hiddenCount > 0) {
            SettingRow("Hidden rows", "$hiddenCount hidden", "Select to show them all again") { model.showHiddenRows(); version++ }
        }
        SettingRow("Sports recaps & highlights", if (Store.showRecaps) "Shown" else "Hidden", "Replays of past games in your sports rows") {
            model.setShowRecaps(!Store.showRecaps); version++
        }
        SettingRow("Expanding cards", onOff(Store.expandCards), "Posters widen into a preview when you rest on them") {
            Store.expandCards = !Store.expandCards; version++
        }
        SettingRow("Skip intros automatically", onOff(Store.autoSkipIntro), "Otherwise a Skip Intro button appears (IntroDB data)") {
            Store.autoSkipIntro = !Store.autoSkipIntro; version++
        }
        SettingRow(
            "Audio passthrough", if (Store.passthrough) "Auto" else "Off",
            "Auto sends Dolby / DTS straight to your TV or soundbar. Off decodes it here (try this if you get silence or crackling)",
        ) { Store.passthrough = !Store.passthrough; version++ }
        SettingRow("Night mode", onOff(Store.nightMode), "Evens out loud and quiet scenes and lifts voices · also in the player's audio menu") {
            Store.nightMode = !Store.nightMode; version++
        }
        SettingRow("Match frame rate", onOff(Store.matchFrameRate), "Switches the TV to 24p/25p/etc. for smoother motion") {
            Store.matchFrameRate = !Store.matchFrameRate; version++
        }
        SettingRow("Subtitle size", listOf("Small", "Medium", "Large", "Extra large")[Store.subSize.coerceIn(0, 3)]) {
            Store.subSize = (Store.subSize + 1) % 4; version++
        }
        SettingRow("Subtitle style", if (Store.subStyle == 1) "On a dark box" else "White with shadow") {
            Store.subStyle = (Store.subStyle + 1) % 2; version++
        }
        SettingRow("Player", if (Store.externalPlayer) "External app" else "Built-in", "External sends videos to VLC, Just Player, MX Player…") {
            Store.externalPlayer = !Store.externalPlayer; version++
        }
        SettingRow("Reload addons", if (model.syncing) "Loading…" else "${model.addonCount} installed", "Pick up changes made in Stremio") {
            scope.launch { model.start() }
        }
        if (com.maxim.marquee.data.Emby.connected) {
            SettingRow(
                "Emby server",
                "Connected · ${Store.embyUserName ?: ""}",
                (Store.embyServer ?: "") + " — select to disconnect",
            ) { model.embySignOut(); version++ }
            SettingRow(
                "Emby rows",
                when (Store.embyRows) { 1 -> "Series & Movies tabs"; 2 -> "Everywhere"; else -> "Off" },
                "\"New in …\" rows from your server. Its titles are always in Continue Watching, search and sources",
            ) { model.setEmbyRows((Store.embyRows + 1) % 3); version++ }
        } else {
            SettingRow("Emby server", "Not connected", "Add your Emby (or Jellyfin) server to see its shows here") {
                model.stack.add(com.maxim.marquee.Screen.EmbyLogin)
            }
        }
        SettingRow(
            "Update automatically",
            onOff(Store.autoUpdate),
            "Checks every time you open Marquee · installed: build ${com.maxim.marquee.Updater.currentBuild(LocalContext.current)}",
        ) { Store.autoUpdate = !Store.autoUpdate; version++ }
        SettingRow("Check for updates now", "") { model.checkForUpdate(manual = true) }
        SettingRow("Set up from your phone", "", "Scan a QR code to sign in to Stremio, Emby, Trakt and TMDB without the remote") {
            model.stack.add(com.maxim.marquee.Screen.PhoneSetup)
        }
        val trakt = com.maxim.marquee.data.Trakt
        when {
            trakt.connected -> SettingRow("Trakt", "Connected", "Scrobbling, watchlist and recommendations · select to disconnect") {
                model.traktSignOut(); version++
            }
            trakt.hasKeys -> SettingRow("Trakt", "Not connected", "Select to approve Marquee on trakt.tv") {
                model.stack.add(com.maxim.marquee.Screen.TraktActivate)
            }
            else -> SettingRow("Trakt", "Not set up", "Add your Trakt keys with \"Set up from your phone\"") {
                model.stack.add(com.maxim.marquee.Screen.PhoneSetup)
            }
        }
        SettingRow(
            "TMDB key", if (com.maxim.marquee.data.Tmdb.configured) "Saved" else "Not set",
            "Enables movie collections on detail pages",
        ) { model.stack.add(com.maxim.marquee.Screen.PhoneSetup) }
        SettingRow("Signed in as", Store.email ?: "", "Select to sign out") { model.signOut() }
        Text(
            "Addons: " + Repo.addons.joinToString(", ") { it.name },
            color = C.Dim, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun SettingRow(title: String, value: String, hint: String? = null, fr: FocusRequester? = null, onClick: () -> Unit) {
    Tile(modifier = Modifier.fillMaxWidth(), focusedScale = 1.01f, border = false, focusRequester = fr, onClick = onClick) { f ->
        Row(
            Modifier.fillMaxWidth().background(if (f) Color.White else Color(0x1AFFFFFF)).padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = if (f) Color.Black else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                hint?.let { Text(it, color = if (f) Color(0xFF444444) else C.Dim, fontSize = 13.sp) }
            }
            Text(value, color = if (f) Color.Black else C.Dim, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

// ---------------------------------------------------------------- update banner

@Composable
fun UpdateBanner(text: String, progress: Float) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Column(
            Modifier.padding(top = 24.dp, end = 32.dp).width(360.dp)
                .background(Color(0xF01A1A1A), RoundedCornerShape(8.dp)).padding(16.dp),
        ) {
            Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (progress >= 0f) {
                Spacer(Modifier.height(10.dp))
                ProgressLine(progress, Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------- Emby sign-in

@Composable
fun EmbyLoginScreen(model: AppModel) {
    var address by remember { mutableStateOf(Store.embyServer ?: "") }
    var user by remember { mutableStateOf(Store.embyUserName ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val userFr = remember { FocusRequester() }
    // Start on the Connect button: focusing a text box pops the keyboard up over the screen.
    val connectFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; tryFocus(connectFr) }

    fun submit() {
        if (busy) return
        if (user.isBlank()) { tryFocus(userFr); return }
        busy = true; error = null
        model.embyLogin(address, user, password) { msg -> error = msg; busy = false }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF666666),
        focusedLabelColor = Color.White, unfocusedLabelColor = C.Dim, cursorColor = C.Red,
    )
    val setupUrl = rememberPhoneSetup(model)
    Column(
        Modifier.fillMaxSize().background(C.Bg).padding(horizontal = 64.dp, vertical = 36.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Connect Emby", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            "Use your Emby Connect email and password (leave Server URL empty: your server is found for you), " +
                "or your server URL with your server username. Jellyfin servers work too.",
            color = C.Dim, fontSize = 15.sp, lineHeight = 21.sp,
        )
        Spacer(Modifier.height(20.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(400.dp)) {
                OutlinedTextField(
                    value = user, onValueChange = { user = it }, singleLine = true,
                    label = { Text("Emby Connect email or username") }, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(userFr),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Password") }, colors = fieldColors,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    label = { Text("Server URL (optional with Emby Connect)") }, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(14.dp))
                ActionButton(
                    if (busy) "Connecting…" else "Connect", modifier = Modifier.fillMaxWidth(), focusRequester = connectFr,
                    onClick = { submit() },
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = C.Red, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
            Spacer(Modifier.width(56.dp))
            Text("or", color = C.Dim, fontSize = 20.sp)
            Spacer(Modifier.width(56.dp))
            PhoneSetupPanel(setupUrl, "Type it on your phone")
        }
    }
}

// ---------------------------------------------------------------- login

@Composable
fun LoginScreen(model: AppModel) {
    var email by remember { mutableStateOf(Store.email ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val emailFr = remember { FocusRequester() }
    // Start on the Sign in button, not the email box: focusing a text box pops the keyboard
    // up over half the screen (and over the QR code).
    val signInFr = remember { FocusRequester() }
    LaunchedEffect(Unit) { withFrameNanos { }; tryFocus(signInFr) }

    fun submit() {
        if (busy || email.isBlank() || password.isEmpty()) return
        busy = true; error = null
        model.login(email, password) { msg -> error = msg; busy = false }
    }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Color.White, unfocusedTextColor = Color.White,
        focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF666666),
        focusedLabelColor = Color.White, unfocusedLabelColor = C.Dim, cursorColor = C.Red,
    )

    val setupUrl = rememberPhoneSetup(model)
    // TV screens are wide but short: title on top, then the form and the QR code side by side.
    Column(
        Modifier.fillMaxSize().background(C.Bg).padding(horizontal = 64.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("MARQUEE", color = C.Red, fontSize = 44.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Spacer(Modifier.height(8.dp))
        Text(
            if (model.profiles.size > 1) "${Store.activeProfile.name}: sign in with a Stremio account" else "Sign in with your Stremio account",
            color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Your addons (Duck Streams included), library, Continue Watching and progress load automatically.",
            color = C.Dim, fontSize = 16.sp, lineHeight = 22.sp,
        )
        Spacer(Modifier.height(28.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.width(400.dp)) {
                OutlinedTextField(
                    value = email, onValueChange = { email = it }, singleLine = true,
                    label = { Text("Stremio email") }, colors = fieldColors,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(emailFr),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    label = { Text("Password") }, colors = fieldColors,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { focus.moveFocus(FocusDirection.Down) }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
                ActionButton(
                    if (busy) "Signing in…" else "Sign in", modifier = Modifier.fillMaxWidth(), focusRequester = signInFr,
                    onClick = { if (email.isBlank() || password.isEmpty()) tryFocus(emailFr) else submit() },
                )
                if (model.profiles.size > 1) {
                    Spacer(Modifier.height(10.dp))
                    ActionButton("Switch profile", modifier = Modifier.fillMaxWidth(), onClick = { model.openProfiles() })
                }
                error?.let {
                    Spacer(Modifier.height(10.dp))
                    Text(it, color = C.Red, fontSize = 15.sp)
                }
            }
            Spacer(Modifier.width(56.dp))
            Text("or", color = C.Dim, fontSize = 20.sp)
            Spacer(Modifier.width(56.dp))
            PhoneSetupPanel(setupUrl, "Sign in from your phone")
        }
    }
}
