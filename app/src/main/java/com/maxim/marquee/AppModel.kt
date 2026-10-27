package com.maxim.marquee

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import com.maxim.marquee.data.Addon
import com.maxim.marquee.data.CardItem
import com.maxim.marquee.data.Catalog
import com.maxim.marquee.data.LibEntry
import com.maxim.marquee.data.LocalProgress
import com.maxim.marquee.data.MetaItem
import com.maxim.marquee.data.Repo
import com.maxim.marquee.data.Store
import com.maxim.marquee.data.StreamItem
import com.maxim.marquee.data.StremioApi
import com.maxim.marquee.data.SubTrack
import com.maxim.marquee.data.Video
import com.maxim.marquee.data.episodeLabel
import com.maxim.marquee.data.looksLikeError
import com.maxim.marquee.data.Net
import com.maxim.marquee.data.typeLabel
import com.maxim.marquee.ui.MovieIcon
import com.maxim.marquee.ui.TvIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlin.coroutines.coroutineContext

enum class Tab(val label: String, val icon: ImageVector) {
    PROFILE("Profiles", Icons.Filled.Person),
    SEARCH("Search", Icons.Filled.Search),
    HOME("Home", Icons.Filled.Home),
    SPORTS("Sports", com.maxim.marquee.ui.SportsIcon),
    SERIES("Series", TvIcon),
    MOVIES("Movies", MovieIcon),
    MYLIST("My List", Icons.Filled.Add),
    CALENDAR("Calendar", Icons.Filled.DateRange),
    SETTINGS("Settings", Icons.Filled.Settings),
}

data class PlayReq(
    val type: String,
    val metaId: String,
    val videoId: String,
    val name: String,
    val poster: String?,
    val background: String?,
    val episode: String? = null,
    val startOver: Boolean = false,
)

data class CardMenu(val item: CardItem, val browse: BrowseState?, val fromContinue: Boolean, val rowTitle: String? = null)

sealed class Screen {
    data class Detail(val type: String, val id: String, val preview: MetaItem) : Screen()
    data class Streams(val req: PlayReq) : Screen()
    data object EmbyLogin : Screen()
    data object PhoneSetup : Screen()
    data object TraktActivate : Screen()
    /** A title's trailer, full screen in the app's own player (YouTube ids, tried in order). */
    data class Trailer(val title: String, val ids: List<String>) : Screen()
    /** A friendly explanation when something goes wrong, with ways forward. */
    data class Problem(val kind: Kind, val message: String? = null, val req: PlayReq? = null) : Screen() {
        enum class Kind { OFFLINE, NO_SOURCES, SERVICE, PLAYBACK }
    }
}

enum class ProfileGate { NONE, PICK, MANAGE }

sealed class RowSource {
    data object Continue : RowSource()
    /** One catalog, plus any other addons' catalogs with the same name (merged, no duplicates). */
    data class Cat(val addon: Addon, val catalog: Catalog, val more: List<Pair<Addon, Catalog>> = emptyList()) : RowSource()
    data object Static : RowSource()
    /** kind: "resume", "nextup" or "latest" (with a library id). */
    data class Emby(val kind: String, val libraryId: String? = null) : RowSource()
    data object NewEpisodes : RowSource()
    /** kind: "watchlist" or "recs" */
    data class Trakt(val kind: String) : RowSource()
    /** Netflix-style Top 10 for "movie" or "series". */
    data class Top10(val type: String) : RowSource()
    /** "Because you watched …": titles like one you watched recently. */
    data class Because(val type: String, val id: String) : RowSource()
}

data class RowModel(val key: String, val title: String, val source: RowSource)

/** Everything a browse page needs to look the same when you come back to it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
class BrowseState(val type: String?, val withContinue: Boolean, val sportsOnly: Boolean = false) {
    val columnState = LazyListState()
    val rowStates = HashMap<String, LazyListState>()
    val rows = mutableStateListOf<RowModel>()
    val rowItems = mutableStateMapOf<String, List<CardItem>>()
    val loaded = mutableStateMapOf<String, Boolean>()
    val requested = HashSet<String>()
    private val skips = HashMap<String, Int>()
    private val loadingMore = HashSet<String>()
    var hero by mutableStateOf<CardItem?>(null)
    var heroMeta by mutableStateOf<MetaItem?>(null)
    var heroJob: Job? = null
    var lastFocusKey: String? = null
    var pendingFocusKey by mutableStateOf<String?>(null)
    /** Focus handles of the cards on screen, by "row#index", so focus coming in from the menu lands on the right one. */
    val cardRequesters = HashMap<String, androidx.compose.ui.focus.FocusRequester>()
    var signature = ""

    /**
     * Each row builds its first 4 posters ahead of time, in idle moments while the row above is on
     * screen, so moving down never has to build a whole row at once.
     */
    fun rowState(key: String) = rowStates.getOrPut(key) {
        LazyListState(prefetchStrategy = androidx.compose.foundation.lazy.LazyListPrefetchStrategy(nestedPrefetchItemCount = 4))
    }

    fun firstKey(): String? = rows.firstOrNull { !rowItems[it.key].isNullOrEmpty() }?.let { "${it.key}#0" }

    /**
     * The top row that has titles, but only once every row above it has finished loading
     * (rows load in parallel; a lower row answering first mustn't grab the starting focus).
     */
    fun firstReadyRow(): RowModel? {
        for (r in rows) {
            val items = rowItems[r.key]
            if (!items.isNullOrEmpty()) return r
            if (loaded[r.key] != true) return null
        }
        return null
    }

    fun reset(defs: List<RowModel>) {
        rows.clear(); rows.addAll(defs)
        rowItems.clear(); loaded.clear(); rowStates.clear(); skips.clear(); loadingMore.clear(); requested.clear()
        lastFocusKey = null; pendingFocusKey = null
    }

    fun beginMore(key: String) = loadingMore.add(key)
    fun endMore(key: String) = loadingMore.remove(key)
    fun nextSkip(key: String) = skips[key] ?: (rowItems[key]?.size ?: 0)
    fun setSkip(key: String, v: Int) { skips[key] = v }
}

class AppModel(private val activity: MainActivity) {
    private val scope get() = activity.lifecycleScope

    var loggedIn by mutableStateOf(Store.authKey != null)
    var tab by mutableStateOf(Tab.HOME)
    val stack = mutableStateListOf<Screen>()
    var library by mutableStateOf<List<LibEntry>>(emptyList())
    var localVersion by mutableIntStateOf(0)
    var loading by mutableStateOf<PlayReq?>(null)
    var contentFocusTick by mutableIntStateOf(0)
    var addonCount by mutableIntStateOf(0)
    var syncing by mutableStateOf(false)
    var embyLibraries by mutableStateOf<List<com.maxim.marquee.data.Emby.Library>>(emptyList())
    private var embyEntries: List<com.maxim.marquee.data.Emby.Entry> = emptyList()
    private var newEpisodes: List<CardItem> = emptyList()
    private var newEpisodesAt = 0L
    var traktCode by mutableStateOf<com.maxim.marquee.data.Trakt.DeviceCode?>(null)
    var traktStatus by mutableStateOf<String?>(null)
    private var traktJob: Job? = null
    var railFocused = false
    var cardMenu by mutableStateOf<CardMenu?>(null)
    var updateStatus by mutableStateOf<String?>(null)
    var updateProgress by mutableStateOf(-1f)
    private var updating = false
    private var lastUpdateCheck = 0L
    /** Whether the TV can show Dolby Vision; DV-only sources are avoided when it can't. */
    var supportsDV = false
    /** Tallest resolution the TV can display (1080 on a 1080p set, 2160 on 4K). */
    var displayMaxHeight = 2160
    /** AV1 only plays smoothly with a hardware decoder. */
    var av1Hardware = false
    var handledTick = 0

    // ---- profiles ----
    /** The profile this screen was built for (switching rebuilds the activity). */
    val loadedProfileId = Store.activeProfileId
    var profiles by mutableStateOf(Store.profiles)
    var profileGate by mutableStateOf(ProfileGate.NONE)
    /** Profile being edited; id -1 = a new one. */
    var profileEdit by mutableStateOf<com.maxim.marquee.data.Profile?>(null)
    /** After signing a new profile in to an account with no stream addons: offer to copy another profile's. */
    var offerCopyFrom by mutableStateOf<com.maxim.marquee.data.Profile?>(null)
    var started = false
        private set

    // ---- network / problems ----
    var online by mutableStateOf(true)
    var addonError by mutableStateOf<String?>(null)

    // ---- Top 10 and poster ribbons ----
    /** Title id → rank (1-10) for the "TOP 10" corner badge. */
    var top10 by mutableStateOf<Map<String, Int>>(emptyMap())
    /** Title id → "New Episode" / "New Season" / … */
    var ribbons by mutableStateOf<Map<String, String>>(emptyMap())

    val browse = mapOf(
        Tab.HOME to BrowseState(null, true),
        Tab.SERIES to BrowseState("series", false),
        Tab.MOVIES to BrowseState("movie", false),
        Tab.SPORTS to BrowseState(null, false, sportsOnly = true),
    )
    /** The Sports tab only shows up when your addons have sports or live TV catalogs. */
    var hasSports by mutableStateOf(false)

    /** Side-menu entries, in order. */
    val tabs: List<Tab> get() = Tab.entries.filter { it != Tab.SPORTS || hasSports }
    val search = BrowseState(null, false)
    var searchQuery by mutableStateOf("")
    var searchedQuery = ""
    val myListGrid = LazyGridState()
    var myListFocus = -1

    private var trailerPlayer by mutableStateOf<TrailerPlayer?>(null)
    /** Created while Home loads, only if background trailers are on. */
    val trailer: TrailerPlayer get() = trailerPlayer ?: TrailerPlayer(activity).also { trailerPlayer = it }
    /** The trailer player if it already exists (doesn't create it). */
    val trailerIfReady: TrailerPlayer? get() = trailerPlayer
    fun pauseTrailer() = trailerPlayer?.onPause()
    fun resumeTrailer() = trailerPlayer?.onResume()
    fun stopTrailer() = trailerPlayer?.stop()
    fun destroy() {
        trailerPlayer?.destroy()
        trailerPlayer = null
    }

    private var playJob: Job? = null
    private var pending: Pending? = null
    private var lastLibraryLoad = 0L

    private data class Pending(
        val req: PlayReq,
        val streams: List<StreamItem>,
        val index: Int,
        val auto: Boolean,
        val attempts: Int,
        val addonSubs: List<SubTrack>,
        val external: Boolean = false,
        val autoChain: Int = 0,
        /** HTTP errors seen while trying sources (401/403 usually mean the service refused). */
        val httpCodes: List<Int> = emptyList(),
    )

    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    // ---------------- session ----------------

    fun start() {
        started = true
        if (!loggedIn) return
        online = Net.online(activity)
        scope.launch {
            // Show last session's addons, rows and Continue Watching straight away (no network),
            // then refresh everything in the background.
            Repo.loadCachedAddonsAsync()
            addonCount = Repo.addons.size
            if (embyLibraries.isEmpty()) embyLibraries = savedEmbyLibraries()
            rebuildRows()
            if (library.isEmpty()) {
                val saved = Repo.cachedLibrary()
                if (library.isEmpty() && saved.isNotEmpty()) {
                    library = saved
                    refreshContinue()
                }
            }
            syncing = true
            addonError = null
            // Set up the trailer player while Home is still loading, not in the middle of browsing.
            // (Its web engine was already loaded in the background when the app opened.)
            if (Store.trailerMode != 0) scope.launch { delay(1_200); trailer }
            try {
                Repo.refreshAddons()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Repo.addons.isEmpty()) addonError = e.message ?: "Stremio didn't answer"
            }
            addonCount = Repo.addons.size
            loadEmbyLibraries()
            rebuildRows()
            loadLibrary()
            syncing = false
        }
    }

    private suspend fun loadEmbyLibraries() {
        val emby = com.maxim.marquee.data.Emby
        if (!emby.connected) {
            embyLibraries = emptyList(); return
        }
        val libs = emby.libraries()
        // An unreachable server answers nothing: keep the rows we already have.
        if (libs.isNotEmpty() || embyLibraries.isEmpty()) {
            embyLibraries = libs
            Store.embyLibrariesJson = org.json.JSONArray().apply {
                libs.forEach { put(org.json.JSONObject().put("id", it.id).put("name", it.name).put("type", it.type ?: "")) }
            }.toString()
        }
        emby.buildIndex(force = true)
    }

    /** Emby libraries as they were last time, so the first layout of Home already has their rows. */
    private fun savedEmbyLibraries(): List<com.maxim.marquee.data.Emby.Library> {
        if (!com.maxim.marquee.data.Emby.connected) return emptyList()
        val json = Store.embyLibrariesJson ?: return emptyList()
        return com.maxim.marquee.data.safe {
            org.json.JSONArray(json).let { a ->
                (0 until a.length()).map { i ->
                    val o = a.getJSONObject(i)
                    com.maxim.marquee.data.Emby.Library(o.getString("id"), o.getString("name"), o.optString("type").ifEmpty { null })
                }
            }
        } ?: emptyList()
    }

    fun embyLogin(address: String, user: String, password: String, onError: (String) -> Unit) {
        scope.launch {
            try {
                com.maxim.marquee.data.Emby.login(address, user, password)
                loadEmbyLibraries()
                rebuildRows(force = true)
                loadLibrary()
                toast("Connected to Emby")
                back()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Couldn't connect")
            }
        }
    }

    fun embySignOut() {
        Store.embySignOut()
        embyLibraries = emptyList()
        embyEntries = emptyList()
        rebuildRows(force = true)
        refreshContinue()
    }

    fun login(email: String, password: String, onError: (String) -> Unit) {
        scope.launch {
            try {
                Store.authKey = StremioApi.login(email.trim(), password)
                Store.email = email.trim()
                loggedIn = true
                start()
                offerAddonCopy()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(e.message ?: "Sign in failed")
            }
        }
    }

    fun signOut() {
        Store.signOut()
        browse.values.forEach { it.reset(emptyList()); it.signature = "" }
        stack.clear()
        library = emptyList()
        tab = Tab.HOME
        loggedIn = false
    }

    private var poller: Job? = null
    private var pausedAt = 0L

    fun onPause() {
        pausedAt = System.currentTimeMillis()
    }

    /** Back after a long break (the TV kept the app in memory): reload everything. */
    fun refreshAll() {
        scope.launch {
            online = Net.online(activity)
            addonError = null
            syncing = true
            try {
                Repo.refreshAddons()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Repo.addons.isEmpty()) addonError = e.message ?: "Stremio didn't answer"
            }
            addonCount = Repo.addons.size
            loadEmbyLibraries()
            rebuildRows(force = true)
            loadLibrary()
            syncing = false
        }
    }

    /** Connection lost or back. Coming back reloads whatever failed while offline. */
    fun onNetwork(available: Boolean) {
        val was = online
        online = available
        if (available && !was && started && loggedIn) refreshAll()
    }

    fun onResume() {
        // Back after a long time away: ask who's watching, like Netflix.
        if (started && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 4 * 3_600_000L &&
            profiles.size > 1 && profileGate == ProfileGate.NONE
        ) {
            profileGate = ProfileGate.PICK
        }
        if (loggedIn && pausedAt > 0 && System.currentTimeMillis() - pausedAt > 30 * 60_000 && stack.isEmpty()) {
            pausedAt = 0
            refreshAll()
            return
        }
        if (loggedIn && System.currentTimeMillis() - lastLibraryLoad > 15_000) scope.launch { loadLibrary() }
        // Keep Continue Watching in step with other devices while the app is open.
        if (poller == null) {
            poller = scope.launch {
                while (true) {
                    delay(120_000)
                    if (loggedIn && activity.isForeground) loadLibrary()
                }
            }
        }
    }

    private suspend fun loadLibrary() {
        lastLibraryLoad = System.currentTimeMillis()
        Repo.library()?.let { library = it }
        refreshContinue()
        scope.launch { loadNewEpisodes() }
        if (!remindersChecked) {
            remindersChecked = true
            scope.launch { checkReminders() }
        }
        if (com.maxim.marquee.data.Emby.connected) {
            com.maxim.marquee.data.Emby.buildIndex()
            embyEntries = com.maxim.marquee.data.Emby.resume() + com.maxim.marquee.data.Emby.nextUp()
            refreshContinue()
        } else if (embyEntries.isNotEmpty()) {
            embyEntries = emptyList()
            refreshContinue()
        }
    }

    // ---------------- watchlist reminders ----------------

    /** "New episode" banner on opening the app: episodes out today or yesterday you haven't seen. */
    data class Reminder(val lines: List<String>, val more: Int)
    var reminder by mutableStateOf<Reminder?>(null)
    private var remindersChecked = false

    private suspend fun checkReminders() {
        delay(6_000) // let Home settle first
        loadCalendar()
        var waited = 0
        while (calendar == null && waited < 90) { delay(1_000); waited++ }
        val days = calendar ?: return
        val today = calendarToday()
        val shown = Store.remindedEpisodes()
        val fresh = days.filter { it.day == today || it.day == today - 1 }
            .flatMap { d -> d.entries.map { d.day to it } }
            .filter { (_, e) -> !e.watched && e.ep.id !in shown }
        if (fresh.isEmpty()) return
        Store.addRemindedEpisodes(fresh.map { it.second.ep.id })
        val lines = fresh.take(3).map { (day, e) ->
            "${e.meta.name} · ${e.ep.label} ${e.ep.title}" + if (day == today) "" else " (yesterday)"
        }
        reminder = Reminder(lines, fresh.size - lines.size)
        delay(12_000)
        reminder = null
    }

    // ---------------- background refresh ----------------

    /** When you last pressed a button (for refreshing quietly while the app sits idle). */
    var lastInputAt = System.currentTimeMillis()
    private var lastQuietRefresh = System.currentTimeMillis()

    /** Runs every minute while the app is open: after 10 idle minutes, freshens everything (at most twice an hour). */
    fun idleTick() {
        val now = System.currentTimeMillis()
        if (now - lastInputAt < 10 * 60_000L || now - lastQuietRefresh < 30 * 60_000L || stack.isNotEmpty() || loading != null) return
        lastQuietRefresh = now
        scope.launch { refreshQuietly() }
    }

    /** New catalog contents, Continue Watching, new episodes and the calendar, without touching where you are. */
    private suspend fun refreshQuietly() {
        safeRun { loadLibrary() }
        safeRun { loadNewEpisodes(force = true) }
        if (calendar != null) loadCalendar(force = true)
        val gate = kotlinx.coroutines.sync.Semaphore(3)
        browse.values.forEach { b ->
            b.rows.filter { it.source is RowSource.Cat && b.loaded[it.key] == true }.map { row ->
                scope.async {
                    gate.acquire()
                    try {
                        val src = row.source as RowSource.Cat
                        val parts = listOf(src.addon to src.catalog) + src.more
                        val fresh = parts.map { (a, c) -> safeRun { Repo.catalog(a, c) } ?: emptyList() }.flatten().distinctBy { it.id }
                        val browsingRow = b.lastFocusKey?.startsWith(row.key + "#") == true
                        if (fresh.isNotEmpty() && !browsingRow && fresh.map { it.id } != b.rowItems[row.key]?.map { it.meta.id }) {
                            b.rowItems[row.key] = fresh.map { CardItem(it) }
                        }
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun <T> safeRun(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    // ---------------- browse rows ----------------

    private fun rowTitle(c: Catalog): String {
        val n = c.name.trim()
        val lower = n.lowercase()
        if (lower.contains("movie") || lower.contains("series") || lower.contains("show") || lower.contains("film")) return n
        return "$n ${typeLabel(c.type)}"
    }

    private fun rebuildRows(force: Boolean = false) {
        val sig = Repo.signature + "|" + embyLibraries.joinToString(",") { it.id } + "|" + Store.embyRows
        browse.values.forEach { b ->
            if (!force && b.signature == sig && b.rows.isNotEmpty()) return@forEach
            val defs = mutableListOf<RowModel>()
            if (b.withContinue) {
                defs += RowModel("continue", "Continue Watching", RowSource.Continue)
                defs += RowModel("new-eps", "New Episodes", RowSource.NewEpisodes)
                if (com.maxim.marquee.data.Trakt.connected) {
                    defs += RowModel("trakt-watchlist", "My Trakt Watchlist", RowSource.Trakt("watchlist"))
                    defs += RowModel("trakt-recs", "Recommended for You", RowSource.Trakt("recs"))
                }
            }
            // Sports and live TV live only on their own tab.
            val hidden = Store.hiddenRows
            val recaps = Store.showRecaps
            fun shown(a: com.maxim.marquee.data.Addon, c: Catalog) =
                (recaps || !Repo.isRecap(c)) && rowTitle(c).lowercase() !in hidden
            val sportsRows = Repo.boardCatalogs(null)
                .filter { (a, c) -> Repo.isSports(a, c) && shown(a, c) }
                .groupBy { (_, c) -> rowTitle(c).lowercase() }
                .values
                .mapIndexed { i, group ->
                    val (a, c) = group.first()
                    RowModel("sports|${a.transportUrl.hashCode()}|${c.type}|${c.id}|$i", rowTitle(c), RowSource.Cat(a, c, group.drop(1)))
                }
            hasSports = sportsRows.isNotEmpty()
            if (b.sportsOnly) {
                b.reset(sportsRows)
                b.signature = sig
                return@forEach
            }
            // Your server's "New in …" rows: off by default (its titles still show up in Continue
            // Watching, search and as a source); Settings can add them to the tabs or everywhere.
            val embyRows = mutableListOf<RowModel>()
            val embyMode = Store.embyRows
            if (com.maxim.marquee.data.Emby.connected && (embyMode == 2 || (embyMode == 1 && b.type != null))) {
                embyLibraries.filter { lib ->
                    when (b.type) {
                        "series" -> lib.type == "tvshows"
                        "movie" -> lib.type == "movies"
                        else -> lib.type == "tvshows" || lib.type == "movies"
                    }
                }.forEach { lib ->
                    embyRows += RowModel("emby-latest-${lib.id}", "New in ${lib.name}", RowSource.Emby("latest", lib.id))
                }
            }
            // Rows with the same name from different addons (e.g. two "Popular Movies") become one row.
            val catalogRows = Repo.boardCatalogs(b.type)
                // Sports only on the Sports tab; hidden rows and recaps stay hidden.
                .filter { (a, c) -> shown(a, c) && !Repo.isSports(a, c) }
                .groupBy { (_, c) -> rowTitle(c).lowercase() }
                .values
                .mapIndexed { i, group ->
                    val (a, c) = group.first()
                    RowModel("${a.transportUrl.hashCode()}|${c.type}|${c.id}|$i", rowTitle(c), RowSource.Cat(a, c, group.drop(1)))
                }
                .toMutableList()
            // Top 10 rows a little way down, like Netflix.
            val today = if (com.maxim.marquee.data.Trakt.hasKeys) " Today" else ""
            fun top10Row(type: String) = RowModel(
                "top10-$type", if (type == "series") "Top 10 Shows$today" else "Top 10 Movies$today", RowSource.Top10(type),
            )
            when (b.type) {
                null -> {
                    catalogRows.add(minOf(5, catalogRows.size), top10Row("movie"))
                    catalogRows.add(minOf(2, catalogRows.size), top10Row("series"))
                }
                else -> catalogRows.add(minOf(1, catalogRows.size), top10Row(b.type))
            }
            // "Because you watched …" for the last couple of titles you watched, spread down the page.
            becauseSeeds(b.type).forEachIndexed { k, seed ->
                catalogRows.add(
                    minOf(if (k == 0) 1 else 4, catalogRows.size),
                    RowModel("because-${seed.type}-${seed.id}", "Because you watched ${seed.name}", RowSource.Because(seed.type, seed.id)),
                )
            }
            if (Repo.addons.isNotEmpty()) defs += catalogRows
            // After your usual rows, not in front of them.
            defs += embyRows
            b.reset(defs.filter { it.title.lowercase() !in hidden || it.source is RowSource.Continue })
            b.signature = sig
            if (b.withContinue) {
                b.rowItems["continue"] = continueItems()
                b.loaded["continue"] = true
            }
        }
    }

    /**
     * Loads a row when it's about to show. Also loads the next couple of rows ahead of time (and
     * their posters), so scrolling down never waits on the network.
     */
    fun loadRow(b: BrowseState, row: RowModel, readAhead: Boolean = true) {
        if (readAhead) {
            val i = b.rows.indexOfFirst { it.key == row.key }
            if (i >= 0) b.rows.drop(i + 1).take(2).forEach { loadRow(b, it, readAhead = false) }
        }
        if (b.loaded[row.key] == true || !b.requested.add(row.key)) return
        val preloadPosters = !readAhead
        when (val src = row.source) {
            is RowSource.Continue -> {
                b.rowItems[row.key] = continueItems(); b.loaded[row.key] = true; afterRowLoaded(b)
            }
            is RowSource.Static -> b.loaded[row.key] = true
            is RowSource.Because -> {
                scope.launch {
                    val seen = HashSet<String>()
                    library.filter { it.lastWatched > 0 || it.timeOffset > 0 }.forEach { seen += it.id }
                    Store.allProgress().forEach { seen += it.metaId }
                    val meta = Repo.meta(src.type, src.id)
                    val items = meta?.let { relatedFor(it) }.orEmpty()
                        // Not things you've already watched or started.
                        .filter { it.id !in seen }
                        .map { CardItem(it) }
                    b.rowItems[row.key] = items
                    b.loaded[row.key] = true
                    afterRowLoaded(b)
                }
            }
            is RowSource.NewEpisodes -> {
                b.rowItems[row.key] = newEpisodes; b.loaded[row.key] = true; afterRowLoaded(b)
            }
            is RowSource.Top10 -> {
                scope.launch {
                    val items = top10For(src.type).map { CardItem(it) }
                    b.rowItems[row.key] = items
                    b.loaded[row.key] = true
                    top10 = top10 + items.mapIndexed { i, c -> c.meta.id to (i + 1) }
                    afterRowLoaded(b)
                }
            }
            is RowSource.Trakt -> {
                scope.launch {
                    val items = when (src.kind) {
                        "watchlist" -> com.maxim.marquee.data.Trakt.watchlist()
                        else -> com.maxim.marquee.data.Trakt.recommendations()
                    }.map { CardItem(it) }
                    b.rowItems[row.key] = items
                    b.loaded[row.key] = true
                    afterRowLoaded(b)
                }
            }
            is RowSource.Emby -> {
                scope.launch {
                    val items: List<CardItem> = when (src.kind) {
                        "resume" -> com.maxim.marquee.data.Emby.resume().map { it.card }
                        "nextup" -> com.maxim.marquee.data.Emby.nextUp().map { it.card }
                        else -> src.libraryId?.let { com.maxim.marquee.data.Emby.latest(it) } ?: emptyList()
                    }
                    b.rowItems[row.key] = items
                    b.loaded[row.key] = true
                    afterRowLoaded(b)
                }
            }
            is RowSource.Cat -> {
                scope.launch {
                    val parts = listOf(src.addon to src.catalog) + src.more
                    // 1. Last session's copy from disk: the row appears instantly.
                    val cached = parts.map { (a, c) -> async { Repo.catalog(a, c, cachedOnly = true) } }.awaitAll()
                        .flatten().distinctBy { it.id }
                    if (cached.isNotEmpty() && b.loaded[row.key] != true) {
                        b.rowItems[row.key] = cached.map { CardItem(it) }
                        b.loaded[row.key] = true
                        afterRowLoaded(b)
                        if (preloadPosters) preloadPosters(cached)
                    }
                    // 2. Fresh copy from the addon. Swapped in unless you're browsing that row right now.
                    // A slow addon gets 20 seconds; after that the row gives up instead of loading forever.
                    val requests = parts.map { (a, c) -> AppScope.async { Repo.catalog(a, c) } }
                    val fresh = requests.map { d -> withTimeoutOrNull(20_000) { d.await() } ?: emptyList() }
                        .flatten().distinctBy { it.id }
                    val browsingRow = b.lastFocusKey?.startsWith(row.key + "#") == true
                    if (fresh.isNotEmpty() && fresh.map { it.id } != cached.map { it.id } && (cached.isEmpty() || !browsingRow)) {
                        b.rowItems[row.key] = fresh.map { CardItem(it) }
                        if (preloadPosters) preloadPosters(fresh)
                    }
                    if (b.loaded[row.key] != true) {
                        b.rowItems[row.key] = fresh.map { CardItem(it) }
                        b.loaded[row.key] = true
                    }
                    afterRowLoaded(b)
                }
            }
        }
    }

    fun loadMore(b: BrowseState, row: RowModel) {
        val src = row.source as? RowSource.Cat ?: return
        if (!src.catalog.supportsSkip || !b.beginMore(row.key)) return
        scope.launch {
            val current = b.rowItems[row.key] ?: emptyList()
            val skip = b.nextSkip(row.key)
            val more = Repo.catalog(src.addon, src.catalog, skip)
            val seen = current.map { it.meta.id }.toHashSet()
            val fresh = more.filter { seen.add(it.id) }.map { CardItem(it) }
            if (fresh.isNotEmpty()) {
                b.rowItems[row.key] = current + fresh
                b.setSkip(row.key, skip + more.size)
                b.endMore(row.key)
            } // else: leave it marked busy so we stop asking
        }
    }

    private val top10Cache = HashMap<String, Pair<Long, List<MetaItem>>>()

    /** Trending on Trakt when you have Trakt keys, otherwise the top of your most "popular" catalog. */
    private suspend fun top10For(type: String): List<MetaItem> {
        top10Cache[type]?.let { (t, list) -> if (System.currentTimeMillis() - t < 3 * 3_600_000L && list.isNotEmpty()) return list }
        var list = com.maxim.marquee.data.Trakt.trending(type)
        if (list.size < 10) {
            list = Repo.popularCatalog(type)?.let { (a, c) -> Repo.catalog(a, c) } ?: emptyList()
        }
        list = list.filter { it.poster != null }.distinctBy { it.id }.take(10)
        if (list.isNotEmpty()) top10Cache[type] = System.currentTimeMillis() to list
        return list
    }

    /** Red ribbon on a poster: new episodes of a show you follow, or recently added to your server. */
    fun ribbonFor(meta: MetaItem): String? {
        ribbons[meta.id]?.let { return it }
        if (meta.added > 0 && System.currentTimeMillis() - meta.added < 14L * 24 * 3_600_000) return "Recently Added"
        return null
    }

    /** True after the first remote press: from then on, rows arriving never move your focus. */
    var userNavigated = false

    private fun afterRowLoaded(b: BrowseState) {
        val first = b.firstReadyRow() ?: return
        // Nothing focused yet, or the app just opened and a row above has filled in (Continue
        // Watching arrives after the catalogs): start on the first title of the top row.
        if (b.lastFocusKey == null || (!userNavigated && b.lastFocusKey != "${first.key}#0")) {
            b.pendingFocusKey = "${first.key}#0"
            b.rowItems[first.key]?.firstOrNull()?.let { setHero(b, it) }
        }
        if (b.hero == null) b.rowItems[first.key]?.firstOrNull()?.let { setHero(b, it) }
    }

    private var heroDebounce: Job? = null

    private val imageLoader by lazy { coil.Coil.imageLoader(activity) }

    /** Downloads the first posters of a row that isn't on screen yet, at the size they'll be shown. */
    private fun preloadPosters(items: List<MetaItem>) {
        val d = activity.resources.displayMetrics.density
        val w = (118 * d).toInt(); val h = (177 * d).toInt()
        items.take(8).forEach { m ->
            val url = m.poster ?: return@forEach
            imageLoader.enqueue(coil.request.ImageRequest.Builder(activity).data(url).size(w, h).build())
        }
    }

    /** Get the details and backdrops of the next titles in a row ready, so the billboard switches instantly. */
    private fun prefetchAround(b: BrowseState, key: String) {
        val rowKey = key.substringBeforeLast('#')
        val i = key.substringAfterLast('#').toIntOrNull() ?: return
        val items = b.rowItems[rowKey] ?: return
        items.drop(i + 1).take(2).forEach { c ->
            val m = c.meta
            scope.launch {
                val full = Repo.cachedMeta(m.type, m.id) ?: Repo.meta(m.type, m.id) ?: return@launch
                (full.background ?: m.backdropUrl)?.let { bg -> imageLoader.enqueue(com.maxim.marquee.ui.backdropRequest(activity, bg)) }
            }
        }
    }

    fun onCardFocus(b: BrowseState, key: String, item: CardItem) {
        b.lastFocusKey = key
        // Moving on: the trailer stops at once, so it doesn't play (and cost frames) during the scroll.
        if (b.hero?.meta?.id != item.meta.id) stopTrailer()
        heroDebounce?.cancel()
        heroDebounce = scope.launch {
            delay(140)
            setHero(b, item)
            delay(250)
            prefetchAround(b, key)
            // Resting on a Continue Watching tile: get its sources ready.
            val vid = item.videoId
            if (vid != null && !(item.meta.type == "series" && vid == item.meta.id)) {
                delay(450)
                prefetch(item.meta.type, vid)
            }
        }
    }

    private fun setHero(b: BrowseState, item: CardItem) {
        if (b.hero?.meta?.id == item.meta.id && b.hero?.badge == item.badge) return
        b.hero = item
        b.heroJob?.cancel()
        val cached = Repo.cachedMeta(item.meta.type, item.meta.id)
        b.heroMeta = cached
        if (cached == null) {
            b.heroJob = scope.launch {
                val m = Repo.meta(item.meta.type, item.meta.id)
                if (b.hero?.meta?.id == item.meta.id) b.heroMeta = m
            }
        }
    }

    fun selectTab(t: Tab) {
        if (t == Tab.PROFILE) {
            openProfiles(); return
        }
        tab = t
        contentFocusTick++
    }

    fun openDetail(m: MetaItem) {
        stack.add(Screen.Detail(m.type, m.id, m))
    }

    fun back(): Boolean {
        if (stack.isEmpty()) return false
        stack.removeAt(stack.lastIndex)
        return true
    }

    // ---------------- progress / continue watching ----------------

    private val SAME_SESSION = 15 * 60_000L

    private data class Cand(
        val metaId: String, val type: String, val name: String, val poster: String?,
        val videoId: String, val pos: Long, val dur: Long, val time: Long, val shape: String? = null,
        val badge: String? = null,
    )

    /**
     * Same rule as Stremio's own Continue Watching: anything in the library with progress
     * (type != other, not removed unless temporary, offset > 0), newest change first.
     * Titles watched only in this app are merged in from local progress.
     */
    /**
     * When a library title was last watched. Not its "modified" time: that also changes when it's
     * synced, added to a list or edited elsewhere, which pushed shows you weren't watching to the
     * front of Continue Watching.
     */
    private fun watchedAt(e: com.maxim.marquee.data.LibEntry): Long = if (e.lastWatched > 0) e.lastWatched else e.mtime

    fun continueItems(): List<CardItem> {
        val byId = HashMap<String, Cand>()
        library.forEach { e ->
            if (e.type != "other" && (!e.removed || e.temp) && e.timeOffset > 0) {
                byId[e.id] = Cand(
                    e.id, e.type, e.name, e.poster, e.videoId ?: e.id, e.timeOffset, e.duration,
                    watchedAt(e), e.posterShape,
                )
            }
        }
        val inLibrary = library.associateBy { it.id }
        // Played from a show page that came from your Emby server: same show (by IMDb id) when it's known.
        Store.allProgress().groupBy { com.maxim.marquee.data.Emby.sharedId(it.metaId) }.forEach { (metaId, list) ->
            val p = list.maxByOrNull { it.updated } ?: return@forEach
            val lib = inLibrary[metaId]
            val libTime = lib?.let { watchedAt(it) } ?: -1
            val libShows = lib != null && (!lib.removed || lib.temp) && lib.timeOffset > 0
            if (p.updated <= libTime && libShows) return@forEach // Stremio has newer info (and it's in the row)
            // Taken out of your library after you last played it here: stays out.
            if (lib != null && lib.removed && !lib.temp && libTime > p.updated + 60_000) return@forEach
            val finished = p.duration > 0 && p.position >= p.duration * 0.95
            if (finished || p.position <= 0) {
                byId.remove(metaId)
            } else {
                byId[metaId] = Cand(metaId, p.type, p.name, p.poster ?: lib?.poster, p.videoId, p.position, p.duration, p.updated, lib?.posterShape)
            }
        }
        // Emby: in-progress and next-up episodes join the same row; the newest position wins.
        embyEntries.forEach { e ->
            val m = e.card.meta
            val existing = byId[m.id]
            // Playing from your Emby server in Marquee updates both Marquee's record and the
            // server's (a moment apart, and the server may number episodes differently): ours is
            // kept unless the server's is clearly later (watched in another app).
            if (existing != null && e.time > existing.time && e.time - existing.time < SAME_SESSION) {
                byId[m.id] = existing.copy(time = e.time)
            } else if (existing == null || e.time > existing.time) {
                byId[m.id] = Cand(
                    m.id, m.type, m.name, m.poster ?: existing?.poster, e.card.videoId ?: m.id,
                    e.position, e.duration, e.time, m.posterShape ?: existing?.shape, e.card.badge,
                )
            }
        }
        val hidden = Store.continueHidden
        return byId.values
            // Taken off by hand: stays off until you watch it again.
            .filter { c -> maxOf(hidden[c.metaId] ?: -1, hidden[nameKey(c.type, c.name)] ?: -1) < c.time }
            // "Up next" pointing at an episode that hasn't aired yet (lined up by an older version
            // or another app): the show is finished for now.
            .filterNot { c -> c.pos == 1L && c.type == "series" && notAiredYet(c.metaId, c.videoId) }
            // The same show from Stremio and from your Emby server (when the server doesn't know its
            // IMDb id, the two can't be matched by id): keep only the most recent one.
            .groupBy { nameKey(it.type, it.name) }
            .map { (_, same) ->
                val newest = same.maxBy { it.time }
                val ours = same.filterNot { com.maxim.marquee.data.Emby.isEmby(it.metaId) }.maxByOrNull { it.time }
                if (ours != null && newest !== ours && newest.time - ours.time < SAME_SESSION) ours.copy(time = newest.time) else newest
            }
            .sortedByDescending { it.time }
            .take(50)
            .map { c ->
                CardItem(
                    meta = MetaItem(id = c.metaId, type = c.type, name = c.name, poster = c.poster, posterShape = c.shape),
                    videoId = c.videoId,
                    progress = if (c.dur > 0 && c.pos > 1) (c.pos.toFloat() / c.dur).coerceIn(0.02f, 1f) else null,
                    badge = if (c.type == "series") {
                        (episodeLabel(c.videoId) ?: com.maxim.marquee.data.Emby.introKeys[c.videoId]?.let { episodeLabel(it) })
                            ?.let { if (c.pos == 1L) "NEXT · $it" else it } ?: c.badge
                    } else null,
                )
            }
    }

    /**
     * New episodes of shows you follow (in your Stremio library, notifications on): anything
     * that aired since you last watched the show, within the last 30 days. Checked every 3 hours.
     */
    private suspend fun loadNewEpisodes(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - newEpisodesAt < 3 * 3_600_000L) return
        // First check of the session waits until Home has loaded, so it doesn't compete with it.
        if (newEpisodesAt == 0L) {
            newEpisodesAt = System.currentTimeMillis()
            delay(5_000)
        }
        val now = System.currentTimeMillis()
        newEpisodesAt = now
        val followed = library
            .filter { it.type == "series" && !it.removed && !it.temp && !it.noNotif }
            .sortedByDescending { maxOf(it.mtime, it.lastWatched) }
            .take(60)
        if (followed.isEmpty()) return
        val cutoff = now - 30L * 24 * 3_600_000
        val gate = kotlinx.coroutines.sync.Semaphore(4)
        val found = followed.map { e ->
            scope.async {
                gate.acquire()
                try {
                    val meta = Repo.meta("series", e.id, fresh = true) ?: return@async null
                    val since = maxOf(e.lastWatched, cutoff)
                    val fresh = sortedEpisodes(meta).filter {
                        it.season > 0 && it.released in (since + 1)..now && !watched(meta.id, it.id)
                    }
                    val first = fresh.firstOrNull() ?: return@async null
                    Triple(meta, first, fresh)
                } finally {
                    gate.release()
                }
            }
        }.awaitAll().filterNotNull()
        ribbons = found.associate { (meta, first, fresh) ->
            meta.id to when {
                first.episode == 1 && first.season > 1 -> "New Season"
                fresh.size > 1 -> "New Episodes"
                else -> "New Episode"
            }
        }
        newEpisodes = found
            .sortedByDescending { (_, _, fresh) -> fresh.maxOf { it.released } }
            .map { (meta, first, fresh) ->
                CardItem(
                    meta = meta,
                    videoId = first.id,
                    badge = if (fresh.size > 1) "${fresh.size} NEW EPISODES" else "NEW · ${first.label}",
                )
            }
        browse.values.filter { it.withContinue }.forEach { b ->
            b.rowItems["new-eps"] = newEpisodes
            b.loaded["new-eps"] = true
        }
        // Show details are fresh now: drops any "up next" that hasn't aired (no change = no redraw).
        refreshContinue()
    }

    fun refreshContinue() {
        localVersion++
        val items = continueItems()
        browse.values.filter { it.withContinue }.forEach { b ->
            // Same as before (the usual case for the 2-minute sync): leave the row alone, so it
            // doesn't rebuild under you while you're scrolling.
            if (b.rowItems["continue"] != items) b.rowItems["continue"] = items
            if (b.loaded["continue"] != true) b.loaded["continue"] = true
            afterRowLoaded(b)
        }
    }

    /** (position, duration) for a specific video, newest of local vs library. */
    private fun positionOf(metaId: String, videoId: String): Pair<Long, Long>? {
        val local = Store.progress(videoId)
        if (local == null && com.maxim.marquee.data.Emby.isEmby(videoId)) {
            com.maxim.marquee.data.Emby.userData[videoId]?.let { u ->
                return if (u.played && u.position <= 0) u.duration to u.duration else u.position to u.duration
            }
        }
        val lib = library.firstOrNull { it.id == metaId && (it.videoId ?: it.id) == videoId }
        val localTime = local?.updated ?: -1
        val libTime = lib?.lastWatched ?: -1
        return when {
            local == null && lib == null -> null
            localTime >= libTime && local != null -> local.position to local.duration
            lib != null -> lib.timeOffset to lib.duration
            else -> null
        }
    }

    /**
     * What you set by hand for this episode (or the whole show): true = watched, false = unwatched,
     * null = nothing, or you've played it since (then how far you got decides).
     */
    private fun manualState(metaId: String, videoId: String): Boolean? {
        var best = -1L
        var state: Boolean? = null
        fun consider(t: Long?, v: Boolean) {
            if (t != null && t > best) { best = t; state = v }
        }
        consider(Store.markedWatched[metaId], true)
        consider(Store.markedUnwatched[metaId], false)
        consider(Store.episodesWatched[videoId], true)
        consider(Store.episodesUnwatched[videoId], false)
        if (state == null) return null
        return if ((Store.progress(videoId)?.updated ?: -1) > best) null else state
    }

    /** Marked as watched by hand, and not played again since. */
    private fun markedDone(metaId: String, videoId: String): Boolean = manualState(metaId, videoId) == true

    fun progressFor(metaId: String, videoId: String): Float? {
        if (manualState(metaId, videoId) != null) return null
        val (pos, dur) = positionOf(metaId, videoId) ?: return null
        if (dur <= 0 || pos < 5_000) return null
        val f = pos.toFloat() / dur
        return if (f >= 0.95f) null else f
    }

    fun watched(metaId: String, videoId: String): Boolean {
        manualState(metaId, videoId)?.let { return it }
        val (pos, dur) = positionOf(metaId, videoId) ?: return false
        return dur > 0 && pos >= dur * 0.95
    }

    private fun resumeMs(req: PlayReq): Long {
        if (req.startOver) return 0
        val (pos, dur) = positionOf(req.metaId, req.videoId) ?: return 0
        if (pos < 10_000 || (dur > 0 && pos >= dur * 0.95)) return 0
        return (pos - 3_000).coerceAtLeast(0)
    }

    /** The episode to offer on the Play button: last one watched, or the first one. */
    fun lastVideoFor(metaId: String): String? {
        val local = Store.allProgress().filter { it.metaId == metaId }.maxByOrNull { it.updated }
        val lib = library.firstOrNull { it.id == metaId }
        return if ((local?.updated ?: -1) >= (lib?.lastWatched ?: -1)) local?.videoId ?: lib?.videoId else lib?.videoId
    }

    fun sortedEpisodes(meta: MetaItem): List<Video> =
        meta.videos.sortedWith(compareBy<Video>({ if (it.season == 0) Int.MAX_VALUE else it.season }, { it.episode }))

    fun resumeTarget(meta: MetaItem): Video? {
        val eps = sortedEpisodes(meta)
        val last = lastVideoFor(meta.id)
        val lastEp = eps.firstOrNull { it.id == last }
        if (lastEp != null && watched(meta.id, lastEp.id)) {
            val next = nextVideo(meta, lastEp.id)
            if (next != null) return next
        }
        return lastEp ?: eps.firstOrNull { it.season > 0 } ?: eps.firstOrNull()
    }

    /**
     * The episode after [videoId], if it has aired. (Addons list upcoming episodes too: lining one
     * of those up would keep a finished show in Continue Watching until it airs.)
     */
    fun nextVideo(meta: MetaItem, videoId: String): Video? {
        val eps = sortedEpisodes(meta).filter { it.season > 0 }
        val i = eps.indexOfFirst { it.id == videoId }
        if (i < 0) return null
        val cur = eps[i]
        // Strictly after this one: never the same episode again under another entry.
        val next = eps.drop(i + 1).firstOrNull { it.season > cur.season || (it.season == cur.season && it.episode > cur.episode) }
            ?: return null
        // A day's grace for time zones (air dates are midnight UTC).
        return if (next.released > System.currentTimeMillis() + 24 * 3_600_000L) null else next
    }

    fun reqFor(meta: MetaItem, video: Video?): PlayReq = PlayReq(
        type = meta.type,
        metaId = meta.id,
        videoId = video?.id ?: meta.defaultVideoId ?: meta.id,
        name = meta.name,
        poster = meta.poster,
        background = meta.background,
        episode = video?.let { "${it.label}  ${it.title}" },
    )

    // ---------------- playback ----------------

    /** Continue Watching tile: jump straight back in. */
    fun resume(item: CardItem, startOver: Boolean = false, chooseSource: Boolean = false) {
        val vid = item.videoId
        if (vid == null || (item.meta.type == "series" && vid == item.meta.id)) {
            openDetail(item.meta); return
        }
        scope.launch {
            val meta = Repo.meta(item.meta.type, item.meta.id) ?: item.meta
            val video = meta.videos.firstOrNull { it.id == vid }
            play(
                PlayReq(
                    type = item.meta.type, metaId = item.meta.id, videoId = vid, name = meta.name,
                    poster = meta.poster ?: item.meta.poster, background = meta.background,
                    episode = video?.let { "${it.label}  ${it.title}" } ?: episodeLabel(vid),
                    startOver = startOver,
                ),
                chooseSource = chooseSource,
            )
        }
    }

    /**
     * Marks episodes watched or unwatched (just those, never the whole show). Continue Watching
     * follows: a watched episode moves the show on to the next one.
     */
    fun setEpisodesWatched(meta: MetaItem, eps: List<Video>, watched: Boolean) {
        if (eps.isEmpty()) return
        val ids = eps.map { it.id }
        Store.markEpisodes(ids, watched)
        if (!watched) Store.forgetProgress(ids)
        val req = PlayReq(meta.type, meta.id, ids.last(), meta.name, meta.poster, meta.background)
        if (watched && meta.isSeries) {
            // Up next moves on only if these are where you're at (or past it), not for an old episode.
            val order = sortedEpisodes(meta).map { it.id }
            val last = order.lastOrNull { it in ids }
            val current = Store.allProgress().filter { it.metaId == meta.id }.maxByOrNull { it.updated }?.videoId
            if (last != null && (current == null || order.indexOf(last) >= order.indexOf(current))) {
                nextVideo(meta, last)?.takeIf { !watched(meta.id, it.id) }?.let { markUpNext(req, it) }
            }
        }
        refreshContinue()
        toast(
            when {
                eps.size == 1 -> "${eps[0].label} marked ${if (watched) "watched" else "unwatched"}"
                else -> "${eps.size} episodes marked ${if (watched) "watched" else "unwatched"}"
            },
        )
        scope.launch {
            eps.take(30).forEach { ep -> safeRun { com.maxim.marquee.data.Emby.setPlayed(meta.type, meta.id, ep.id, watched) } }
        }
    }

    /** The whole show (or movie) back to unwatched: progress cleared everywhere. */
    fun markUnwatched(meta: MetaItem) {
        val id = meta.id
        Store.markUnwatched(id)
        Store.dismiss(id)
        library = library.map { if (it.id == id) it.copy(timeOffset = 0) else it }
        refreshContinue()
        toast(if (meta.isSeries) "Marked the whole show as unwatched" else "Marked as unwatched")
        scope.launch {
            Repo.markUnwatched(id)
            safeRun { com.maxim.marquee.data.Emby.setPlayed(meta.type, id, null, false) }
        }
    }

    /** True when every aired episode (or the movie) counts as watched. */
    fun allWatched(meta: MetaItem): Boolean {
        if (!meta.isSeries) return watched(meta.id, meta.defaultVideoId ?: meta.id)
        val now = System.currentTimeMillis()
        val eps = meta.videos.filter { it.season > 0 && (it.released == 0L || it.released < now) }
        return eps.isNotEmpty() && eps.all { watched(meta.id, it.id) }
    }

    /** From a Continue Watching card: the episode after this one. */
    fun playNextOf(item: CardItem) {
        val vid = item.videoId ?: return
        scope.launch {
            val meta = Repo.meta(item.meta.type, item.meta.id) ?: return@launch
            val next = nextVideo(meta, vid) ?: run { toast("That was the last episode"); return@launch }
            play(reqFor(meta, next))
        }
    }

    /** "I've seen this one": the episode counts as watched and the card moves on to the next. */
    fun markEpisodeWatched(item: CardItem) {
        val vid = item.videoId ?: return
        scope.launch {
            val meta = Repo.meta(item.meta.type, item.meta.id) ?: item.meta
            val req = PlayReq(item.meta.type, item.meta.id, vid, meta.name, meta.poster ?: item.meta.poster, meta.background)
            Store.markEpisodes(listOf(vid), true)
            Store.saveProgress(LocalProgress(req.metaId, req.type, req.name, req.poster, vid, 1, 1, System.currentTimeMillis() - 1))
            val next = if (meta.type == "series") nextVideo(meta, vid) else null
            next?.let { markUpNext(req, it) }
            refreshContinue()
            toast(if (next != null) "Marked watched · up next ${next.label}" else "Marked as watched")
            if (Store.syncProgress) Repo.syncProgress(req.type, req.metaId, vid, 1, 1, true, next?.id, req.name, req.poster)
        }
    }

    /** Puts the sources the TV handles best first (respecting the max-quality setting). */
    private fun rank(streams: List<StreamItem>, req: PlayReq? = null): List<StreamItem> {
        // A source whose file is clearly another episode (numbering differs between services)
        // goes to the very end: only used if nothing else plays.
        val meta = req?.takeIf { it.type == "series" }?.let { Repo.cachedMeta(it.type, it.metaId) }
        val video = meta?.videos?.firstOrNull { it.id == req.videoId }
        val (right, wrong) = if (meta != null && video != null) {
            streams.partition { !com.maxim.marquee.data.Episodes.mismatch(it, meta.videos, video) }
        } else streams to emptyList()
        if (wrong.isNotEmpty()) android.util.Log.i("Marquee", "${wrong.size} sources skipped: wrong episode for ${req?.videoId}")
        val runtimeMin = (runtimeMs((meta ?: req?.let { Repo.cachedMeta(it.type, it.metaId) })?.runtime) / 60_000)
            .takeIf { it > 0 } ?: if (req?.type == "movie") 110L else 45L
        return rankQuality(right, runtimeMin) + rankQuality(wrong, runtimeMin)
    }

    private fun rankQuality(streams: List<StreamItem>, runtimeMin: Long = 60): List<StreamItem> {
        // "Best available" still never auto-picks above what the TV can show.
        val cap = if (Store.maxQuality > 0) Store.maxQuality else displayMaxHeight
        fun ok(s: StreamItem): Boolean {
            val q = s.quality
            if (q.res > cap && s.embyId == null) return false
            if (q.dvOnly && !supportsDV) return false
            if (q.av1 && !av1Hardware) return false
            return true
        }
        val (good, rest) = streams.partition { ok(it) }
        // Smarter picking: a release that failed or kept buffering here recently, or one whose
        // bitrate is more than your connection carries (measured while playing), goes after the
        // ones that will play smoothly. Your server's copies are on your own network: no limit.
        val bad = Store.badSources()
        val kbps = Store.bandwidthKbps
        fun smooth(s: StreamItem): Boolean {
            if (s.sourceKey in bad) return false
            if (s.embyId != null || kbps <= 0) return true
            val mb = s.quality.sizeMb ?: return true
            val needKbps = mb * 8 * 1024 / (runtimeMin * 60)
            return needKbps <= kbps * 0.75
        }
        fun ordered(l: List<StreamItem>): List<StreamItem> {
            val (fine, heavy) = l.partition { smooth(it) }
            return fine + heavy
        }
        // Your own server's copy first, then the addons' (each group keeps its own order).
        fun serverFirst(l: List<StreamItem>) = l.sortedBy { if (it.embyId != null) 0 else 1 }
        return serverFirst(ordered(good)) + serverFirst(ordered(rest))
    }

    private val streamCache = HashMap<String, Pair<Long, Deferred<List<StreamItem>>>>()
    private val subsCache = HashMap<String, Pair<Long, Deferred<List<SubTrack>>>>()

    private fun streamsFor(type: String, videoId: String): Deferred<List<StreamItem>> {
        val k = "$type/$videoId"
        val now = System.currentTimeMillis()
        streamCache[k]?.let { (t, d) -> if (now - t < 10 * 60_000 && !d.isCancelled) return d }
        val d = scope.async {
            try {
                Repo.bestStreams(type, videoId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        }
        streamCache[k] = now to d
        return d
    }

    private fun subsFor(type: String, videoId: String): Deferred<List<SubTrack>> {
        val k = "$type/$videoId"
        val now = System.currentTimeMillis()
        subsCache[k]?.let { (t, d) -> if (now - t < 10 * 60_000 && !d.isCancelled) return d }
        val d = scope.async { withTimeoutOrNull(6_000) { Repo.subtitles(type, videoId) } ?: emptyList() }
        subsCache[k] = now to d
        return d
    }

    /** Start looking up sources before you press Play, so playback starts almost instantly. */
    fun prefetch(type: String, videoId: String) {
        if (com.maxim.marquee.data.Emby.connected) {
            // Also loads Emby's resume points for this title, so episode progress shows up.
            val d = streamsFor(type, videoId)
            scope.launch { runCatching { d.await() }; localVersion++ }
        } else if (Store.autoplay) {
            streamsFor(type, videoId)
        }
        if (Store.autoplay) subsFor(type, videoId)
    }

    fun play(req: PlayReq, chooseSource: Boolean = false, autoChain: Int = 0) {
        if (chooseSource || !Store.autoplay) {
            stack.add(Screen.Streams(req)); return
        }
        playJob?.cancel()
        loading = req
        playJob = scope.launch {
            val subs = subsFor(req.type, req.videoId)
            val all = streamsFor(req.type, req.videoId).await()
            // Addons report problems (expired debrid, bad key…) as fake "streams": don't play those.
            val (errors, real) = all.partition { it.looksLikeError() }
            val streams = rank(real, req)
            if (streams.isEmpty()) {
                streamCache.remove("${req.type}/${req.videoId}")
                loading = null
                showProblem(
                    when {
                        !Net.online(activity) -> Screen.Problem.Kind.OFFLINE
                        errors.isNotEmpty() -> Screen.Problem.Kind.SERVICE
                        else -> Screen.Problem.Kind.NO_SOURCES
                    },
                    errors.firstOrNull()?.let { e -> listOf(e.name, e.title).joinToString("\n") { it.trim() }.trim() },
                    req,
                )
                return@launch
            }
            launchStream(Pending(req, streams, 0, auto = true, attempts = 1, addonSubs = subs.await(), autoChain = autoChain))
        }
    }

    fun playFromList(req: PlayReq, streams: List<StreamItem>, index: Int) {
        playJob?.cancel()
        loading = req
        playJob = scope.launch {
            val subs = subsFor(req.type, req.videoId).await()
            launchStream(Pending(req, streams, index, auto = false, attempts = 1, addonSubs = subs))
        }
    }

    fun showProblem(kind: Screen.Problem.Kind, message: String? = null, req: PlayReq? = null) {
        if (stack.lastOrNull() is Screen.Problem) stack.removeAt(stack.lastIndex)
        stack.add(Screen.Problem(kind, message, req))
    }

    /** "Try again" on a problem screen: look the sources up fresh. */
    fun retry(p: Screen.Problem) {
        back()
        val req = p.req
        if (req == null) {
            refreshAll(); return
        }
        streamCache.remove("${req.type}/${req.videoId}")
        play(req)
    }

    fun cancelLoading() {
        playJob?.cancel()
        loading = null
    }

    private suspend fun launchStream(p: Pending) {
        val s = p.streams[p.index]
        // Your language first (this show's choice, else the general one), then English, then a few
        // others, so the player's subtitle menu has real choices.
        val want = Store.normLang((Store.showPrefs(p.req.metaId)?.subLang ?: Store.subLang).ifBlank { "eng" })
        val all = (s.subtitles + p.addonSubs).distinctBy { it.url }
        val mine = all.filter { Store.normLang(it.lang) == want }.take(8)
        val english = if (want == "eng") emptyList() else all.filter { Store.normLang(it.lang) == "eng" }.take(3)
        val others = all.filter { it !in mine && it !in english }.distinctBy { Store.normLang(it.lang) }.take(10)
        val subs = mine + english + others
        val start = resumeMs(p.req)
        // Episode list + next episode for the player's Episodes panel and "Next Episode" card.
        val meta = if (p.req.type == "series") Repo.meta(p.req.type, p.req.metaId) else null
        val current = meta?.videos?.firstOrNull { it.id == p.req.videoId }
        // A double-episode file (e.g. S04E03-E04 as one hour): part 2 starts halfway in, and the
        // next episode is the one after the whole file.
        val span = if (meta != null && current != null) com.maxim.marquee.data.Episodes.span(s, meta.videos, current) else null
        val part = span?.let { current!!.episode - it.first } ?: 0
        val parts = span?.let { it.last - it.first + 1 } ?: 1
        val next = meta?.let { m -> nextVideo(m, lastOfSpan(m, current, span) ?: p.req.videoId) }
        // Part 1 of a split story ("Money (1)", "Money (2)"): the file may well be the whole story,
        // which the player can tell from its length; it then lines up what comes after it.
        val group = if (span == null && meta != null && current != null) {
            com.maxim.marquee.data.Episodes.partGroup(meta.videos, current).takeIf { it.size > 1 && it.first().id == current.id }
        } else null
        val groupNext = group?.let { g -> nextVideo(meta!!, g.last().id) }
        val partLen = runtimeMs(meta?.runtime)
        val season = meta?.let { m -> sortedEpisodes(m).filter { it.season == (current?.season ?: it.season) } } ?: emptyList()
        if (!coroutineContext.isActive) return
        loading = null
        if (Store.externalPlayer) {
            pending = p.copy(external = true)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(s.url), "video/*")
                putExtra("title", p.req.name)
                putExtra("position", start.toInt())
                putExtra("return_result", true)
                if (s.headers.isNotEmpty()) {
                    putExtra("headers", s.headers.flatMap { listOf(it.key, it.value) }.toTypedArray())
                }
            }
            try {
                activity.playerLauncher.launch(intent)
            } catch (e: ActivityNotFoundException) {
                toast("No video player app installed — switch to the built-in player in Settings")
            }
            return
        }
        pending = p
        activity.playerLauncher.launch(
            PlayerActivity.intent(activity, p.req, s, subs, start, next, season, p.autoChain)
                .putExtra("part", part).putExtra("parts", parts).putExtra("runtime", partLen)
                .putExtra("sourceKey", s.sourceKey)
                .apply {
                    if (group != null && partLen > 0) {
                        putExtra("groupNextKnown", true).putExtra("groupParts", group.size).putExtra("partLen", partLen)
                        groupNext?.let { putExtra("groupNextId", it.id).putExtra("groupNextLabel", it.label).putExtra("groupNextTitle", it.title) }
                    }
                },
        )
    }

    /** "22 min", "1h 5min", "45" → milliseconds; 0 if unknown. */
    private fun runtimeMs(r: String?): Long {
        if (r.isNullOrBlank()) return 0
        val h = Regex("(\\d+)\\s*h").find(r)?.groupValues?.get(1)?.toLongOrNull() ?: 0
        val m = Regex("(\\d+)\\s*m").find(r)?.groupValues?.get(1)?.toLongOrNull()
            ?: if (h == 0L) Regex("(\\d+)").find(r)?.groupValues?.get(1)?.toLongOrNull() ?: 0 else 0
        return (h * 60 + m) * 60_000L
    }

    /** The id of the last episode a double-episode file covers (same season), else null. */
    private fun lastOfSpan(meta: MetaItem, current: Video?, span: IntRange?): String? {
        if (current == null || span == null) return null
        return meta.videos.firstOrNull { it.season == current.season && it.episode == span.last }?.id
    }

    /** After an episode finishes, Continue Watching points at the next one ("Up Next"). */
    private fun markUpNext(req: PlayReq, next: Video) {
        Store.saveProgress(LocalProgress(req.metaId, req.type, req.name, req.poster, next.id, 1, 0, System.currentTimeMillis()))
    }

    fun onPlayerResult(data: Intent?) {
        pausedAt = 0 // time spent watching isn't "away"; keep Home exactly where it was
        val p = pending ?: return
        pending = null
        var outcome = data?.getStringExtra("outcome") ?: "exit"
        val httpCode = data?.getIntExtra("httpCode", 0) ?: 0
        val reason = data?.getStringExtra("reason")
        val outcomeVideo = data?.getStringExtra("videoId")
        val auto = data?.getBooleanExtra("auto", false) ?: false
        val merged = data?.getBooleanExtra("merged", false) ?: false

        if (p.external) {
            val pos = data?.getIntExtra("position", -1) ?: -1
            val dur = data?.getIntExtra("duration", -1) ?: -1
            if (pos > 0 && dur > 0) {
                val finished = pos >= dur * 0.92
                Store.saveProgress(
                    LocalProgress(p.req.metaId, p.req.type, p.req.name, p.req.poster, p.req.videoId,
                        if (finished) dur.toLong() else pos.toLong(), dur.toLong(), System.currentTimeMillis()),
                )
                if (Store.syncProgress) AppScope.launch {
                    Repo.syncProgress(
                        p.req.type, p.req.metaId, p.req.videoId, pos.toLong(), dur.toLong(), finished,
                        null, p.req.name, p.req.poster,
                    )
                }
            }
            if (data?.getStringExtra("end_by") == "playback_completion") outcome = "ended"
        }

        scope.launch {
            // Finished an episode? Line up the next one in Continue Watching.
            if (p.req.type == "series" && (outcome == "ended" || outcome == "next" || watched(p.req.metaId, p.req.videoId))) {
                Repo.meta(p.req.type, p.req.metaId)?.let { m ->
                    // A double-episode file: every part counts as watched, up next is after the file.
                    val cur = m.videos.firstOrNull { it.id == p.req.videoId }
                    val span = p.streams.getOrNull(p.index)?.let { st -> cur?.let { com.maxim.marquee.data.Episodes.span(st, m.videos, it) } }
                        ?: cur?.takeIf { merged }?.let { c -> com.maxim.marquee.data.Episodes.partGroup(m.videos, c).let { g -> g.first().episode..g.last().episode } }
                    if (span != null && cur != null) {
                        m.videos.filter { it.season == cur.season && it.episode in span && it.episode > cur.episode }.forEach { v ->
                            Store.saveProgress(LocalProgress(p.req.metaId, p.req.type, p.req.name, p.req.poster, v.id, 1, 1, System.currentTimeMillis() - 1))
                        }
                    }
                    nextVideo(m, lastOfSpan(m, cur, span) ?: p.req.videoId)?.let { markUpNext(p.req, it) }
                }
            }
            refreshContinue()
            delay(1500)
            loadLibrary()
        }

        when (outcome) {
            "error" -> {
                p.streams.getOrNull(p.index)?.let { Store.markSourceBad(it.sourceKey) }
                val codes = if (httpCode > 0) p.httpCodes + httpCode else p.httpCodes
                if (p.auto && p.index + 1 < p.streams.size && p.attempts < 5) {
                    toast("That source didn't work — trying the next one")
                    loading = p.req
                    playJob = scope.launch { launchStream(p.copy(index = p.index + 1, attempts = p.attempts + 1, httpCodes = codes)) }
                } else {
                    val refused = codes.any { it == 401 || it == 402 || it == 403 }
                    showProblem(
                        when {
                            !Net.online(activity) -> Screen.Problem.Kind.OFFLINE
                            refused -> Screen.Problem.Kind.SERVICE
                            else -> Screen.Problem.Kind.PLAYBACK
                        },
                        when {
                            refused -> "The video server answered \"access denied\" (HTTP ${codes.last { it in 401..403 }})."
                            reason == "video" -> "The last source's video format can't be decoded by this TV box. Pick a different source (H.264 / HEVC work best)."
                            reason == "audio" -> "The last source's audio format couldn't be played. Pick a different source."
                            reason == "decoder" -> "The TV box's video decoder couldn't handle the last source. Pick a lower quality or a different codec."
                            else -> null
                        },
                        p.req,
                    )
                }
            }
            "next", "episode" -> scope.launch {
                val meta = Repo.meta(p.req.type, p.req.metaId) ?: return@launch
                val target = outcomeVideo?.let { id -> meta.videos.firstOrNull { it.id == id } }
                    ?: nextVideo(meta, p.req.videoId) ?: return@launch
                play(reqFor(meta, target), autoChain = if (outcome == "next" && auto) p.autoChain + 1 else 0)
            }
            "ended" -> if (Store.autoNext && p.req.type == "series" && p.external) {
                scope.launch {
                    val meta = Repo.meta(p.req.type, p.req.metaId) ?: return@launch
                    val next = nextVideo(meta, p.req.videoId) ?: return@launch
                    play(reqFor(meta, next))
                }
            }
        }
    }

    // ---------------- My List / Continue Watching management ----------------

    fun inMyList(metaId: String): Boolean = library.any { it.id == metaId && it.inMyList }

    fun toggleMyList(meta: MetaItem) {
        val add = !inMyList(meta.id)
        // Optimistic update so the button flips immediately.
        val now = System.currentTimeMillis()
        val existing = library.firstOrNull { it.id == meta.id }
        library = if (existing != null) {
            library.map { if (it.id == meta.id) it.copy(removed = !add, temp = false, mtime = now) else it }
        } else if (add) {
            library + LibEntry(meta.id, meta.type, meta.name, meta.poster, false, false, now, 0, null, 0, 0)
        } else library
        toast(if (add) "Added to My List" else "Removed from My List")
        scope.launch {
            if (!Repo.setInLibrary(meta, add)) toast("Couldn't update your Stremio library")
            delay(800)
            loadLibrary()
        }
    }

    private fun notAiredYet(metaId: String, videoId: String): Boolean {
        val v = Repo.cachedMeta("series", metaId)?.videos?.firstOrNull { it.id == videoId } ?: return false
        return v.released > System.currentTimeMillis() + 24 * 3_600_000L
    }

    /** A title's name, simplified, so "The Office (US)" from one source matches "The Office" from another. */
    private fun nameKey(type: String, name: String): String =
        "name:" + (if (type == "movie") "movie" else "series") + "|" +
            name.lowercase().replace(PARENS, "").replace(NON_WORD, "")

    private val PARENS = Regex("\\([^)]*\\)")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    fun dismissContinue(item: CardItem) {
        val id = item.meta.id
        Store.dismiss(id)
        Store.hideFromContinue(id)
        Store.hideFromContinue(nameKey(item.meta.type, item.meta.name))
        library = library.map { if (it.id == id) it.copy(timeOffset = 0) else it }
        refreshContinue()
        toast("Removed from Continue Watching")
        scope.launch { Repo.rewind(id) }
    }

    /**
     * "Mark as watched" for a whole show (or a movie): every episode counts as watched, it leaves
     * Continue Watching, and Stremio and your Emby server are told too. Watching it again later
     * brings it back as usual.
     */
    fun markWatched(meta: MetaItem) {
        val id = meta.id
        Store.dismiss(id)
        Store.markWatched(id)
        Store.hideFromContinue(id)
        Store.hideFromContinue(nameKey(meta.type, meta.name))
        library = library.map { if (it.id == id) it.copy(timeOffset = 0) else it }
        refreshContinue()
        toast(if (meta.type == "series") "Marked the whole show as watched" else "Marked as watched")
        scope.launch {
            Repo.markWatched(id)
            if (com.maxim.marquee.data.Emby.markPlayed(id)) {
                embyEntries = embyEntries.filter { it.card.meta.id != id }
                refreshContinue()
            }
        }
    }

    fun openCardMenu(b: BrowseState?, item: CardItem, fromContinue: Boolean, rowTitle: String? = null) {
        // If OK is still held, its release must not "click" the menu's first option.
        activity.swallowOkUp = activity.okHeld
        cardMenu = CardMenu(item, b, fromContinue, rowTitle)
    }

    /** "Hide this row": gone from every page now and next time (undo in Settings). */
    fun hideRow(title: String) {
        Store.hiddenRows = Store.hiddenRows + title.lowercase()
        browse.values.forEach { b ->
            val i = b.rows.indexOfFirst { it.title.equals(title, true) }
            if (i >= 0) {
                b.rows.removeAt(i)
                b.lastFocusKey = null
                b.pendingFocusKey = b.firstKey()
            }
        }
        toast("Row hidden · Settings → Hidden rows to bring it back")
    }

    fun showHiddenRows() {
        Store.hiddenRows = emptySet()
        rebuildRows(force = true)
    }

    fun setEmbyRows(mode: Int) {
        Store.embyRows = mode
        rebuildRows(force = true)
    }

    fun setShowRecaps(on: Boolean) {
        Store.showRecaps = on
        rebuildRows(force = true)
    }

    fun closeCardMenu() {
        val m = cardMenu ?: return
        cardMenu = null
        m.browse?.let { b -> b.pendingFocusKey = b.lastFocusKey }
    }

    /** Runs whenever the app is opened: finds, downloads and installs a newer build. */
    fun checkForUpdate(manual: Boolean = false) {
        if (updating) return
        val now = System.currentTimeMillis()
        if (!manual && now - lastUpdateCheck < 30 * 60_000) return
        lastUpdateCheck = now
        scope.launch {
            val r = Updater.check(activity)
            if (r == null) {
                if (manual) toast("You're up to date (build ${Updater.currentBuild(activity)})")
                return@launch
            }
            if (!Store.autoUpdate && !manual) {
                updateStatus = "Update available (build ${r.build}) · Settings → Check for updates"
                delay(8_000); updateStatus = null
                return@launch
            }
            if (!Updater.canInstall(activity)) {
                updateStatus = "Allow Marquee to install apps so it can update itself"
                lastUpdateCheck = 0 // check again as soon as you come back
                Updater.openInstallPermission(activity)
                delay(6_000); updateStatus = null
                return@launch
            }
            updating = true
            updateProgress = 0f
            updateStatus = "Downloading update · build ${r.build}"
            try {
                val apk = Updater.download(activity, r) { updateProgress = it }
                updateProgress = 1f
                updateStatus = "Installing update…"
                Updater.install(activity, apk)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Update failed: ${e.message}")
                lastUpdateCheck = 0
            } finally {
                updating = false
                delay(5_000)
                updateStatus = null
                updateProgress = -1f
            }
        }
    }

    // ---------------- Trakt sign-in ----------------

    fun startTraktAuth() {
        traktJob?.cancel()
        traktCode = null
        traktStatus = "Getting a code from Trakt…"
        traktJob = scope.launch {
            val dc = com.maxim.marquee.data.Trakt.deviceCode()
            if (dc == null) {
                traktStatus = "Couldn't reach Trakt. Check the Client ID and Secret (Settings → Set up from your phone)."
                return@launch
            }
            traktCode = dc
            traktStatus = "Waiting for you to approve…"
            var interval = dc.interval.coerceAtLeast(2)
            val end = System.currentTimeMillis() + dc.expiresIn * 1000L
            while (System.currentTimeMillis() < end) {
                delay(interval * 1000L)
                when (com.maxim.marquee.data.Trakt.pollDevice(dc)) {
                    "ok" -> {
                        traktStatus = "Connected"
                        toast("Trakt connected")
                        rebuildRows(force = true)
                        if (stack.lastOrNull() is Screen.TraktActivate) back()
                        return@launch
                    }
                    "slow" -> interval += 1
                    "pending" -> Unit
                    "denied" -> { traktStatus = "Access was denied on Trakt."; return@launch }
                    "expired" -> { traktStatus = "The code expired. Go back and try again."; return@launch }
                    else -> { traktStatus = "Trakt rejected the request. Check the Client Secret."; return@launch }
                }
            }
            traktStatus = "The code expired. Go back and try again."
        }
    }

    fun cancelTraktAuth() {
        traktJob?.cancel()
    }

    fun traktSignOut() {
        Store.traktSignOut()
        rebuildRows(force = true)
    }

    // ---------------- phone setup ----------------

    /** Applies what was entered on the phone; returns a summary shown on the phone. */
    suspend fun applySetup(form: Map<String, String>): String {
        val lines = mutableListOf<String>()
        fun v(k: String) = form[k]?.trim()?.takeIf { it.isNotEmpty() }
        val email = v("stremio_email"); val pw = form["stremio_password"]
        if (email != null && !pw.isNullOrEmpty()) {
            try {
                Store.authKey = StremioApi.login(email, pw)
                Store.email = email
                loggedIn = true
                start()
                scope.launch { offerAddonCopy() }
                lines += "✓ Stremio: signed in as $email"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lines += "✗ Stremio: ${e.message}"
            }
        }
        val server = v("emby_server"); val user = v("emby_user")
        if (user != null) {
            try {
                com.maxim.marquee.data.Emby.login(server ?: "", user, form["emby_password"] ?: "")
                loadEmbyLibraries()
                rebuildRows(force = true)
                if (loggedIn) loadLibrary()
                // Signed in from the phone while the TV shows the Emby screen: leave it.
                withContext(Dispatchers.Main) { if (stack.lastOrNull() == Screen.EmbyLogin) back() }
                lines += "✓ Emby: connected as $user"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lines += "✗ Emby: ${e.message}"
            }
        }
        v("tmdb_key")?.let { Store.tmdbKey = it; lines += "✓ TMDB key saved (collections on movie pages)" }
        val tid = v("trakt_id"); val tsec = v("trakt_secret")
        if (tid != null && tsec != null) {
            Store.traktClientId = tid
            Store.traktSecret = tsec
            lines += "✓ Trakt keys saved. Approve the code now showing on your TV."
            if (loggedIn) {
                stack.add(Screen.TraktActivate)
                startTraktAuth()
            }
        }
        if (lines.isEmpty()) lines += "Nothing to set up: fill in at least one section."
        return lines.joinToString("\n")
    }

    // ---------------- profiles ----------------

    fun openProfiles() {
        profiles = Store.profiles
        profileGate = ProfileGate.PICK
    }

    fun pickProfile(p: com.maxim.marquee.data.Profile) {
        if (p.id == loadedProfileId) {
            profileGate = ProfileGate.NONE
            if (!started) start()
            return
        }
        switchProfile(p.id)
    }

    private fun switchProfile(id: Int) {
        stopTrailer()
        Store.switchProfile(id)
        Repo.reset()
        com.maxim.marquee.data.Emby.reset()
        MainActivity.profileChosen = true
        activity.recreate()
    }

    fun saveProfile(name: String, color: Int) {
        val e = profileEdit ?: return
        if (e.id < 0) Store.addProfile(name, color) else Store.updateProfile(e.copy(name = name.trim().ifEmpty { e.name }, color = color))
        profiles = Store.profiles
        profileEdit = null
    }

    fun deleteProfile(id: Int) {
        Store.deleteProfile(id)
        profiles = Store.profiles
        profileEdit = null
        if (profiles.size <= 1) profileGate = ProfileGate.PICK
    }

    /** Back on the profile screen: close it if the app is already showing, otherwise leave. */
    fun profileBack(): Boolean {
        if (profileEdit != null) { profileEdit = null; return true }
        if (profileGate == ProfileGate.MANAGE) { profileGate = ProfileGate.PICK; return true }
        if (started && Store.activeProfileId == loadedProfileId) { profileGate = ProfileGate.NONE; return true }
        return false
    }

    /** Other profiles whose Stremio account has addons this one could copy. */
    fun copySources(): List<com.maxim.marquee.data.Profile> = Store.profiles.filter { p ->
        p.id != loadedProfileId && Store.addonsJsonOf(p.id) != null && Store.authKeyOf(p.id) != Store.authKey
    }

    /** A fresh account with no stream addons: offer the first profile's (Duck Streams etc.). */
    private suspend fun offerAddonCopy() {
        delay(2_500)
        val hasStreams = Repo.addons.any { a -> a.resources.any { it.name == "stream" } && a.id != "com.linvo.cinemeta" }
        if (!hasStreams) copySources().firstOrNull()?.let { offerCopyFrom = it }
    }

    fun copyAddonsFrom(p: com.maxim.marquee.data.Profile) {
        offerCopyFrom = null
        val json = Store.addonsJsonOf(p.id) ?: return
        val key = Store.authKey ?: return
        scope.launch {
            try {
                StremioApi.setAddonCollection(key, org.json.JSONArray(json))
                toast("Copied ${p.name}'s addons")
                refreshAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast("Couldn't copy addons: ${e.message}")
            }
        }
    }

    // ---------------- More Like This / collections ----------------

    private data class Seed(val type: String, val id: String, val name: String)

    /** The last titles you watched (shows and films from your addons), newest first, for "Because you watched". */
    private fun becauseSeeds(type: String?): List<Seed> {
        val times = HashMap<String, Pair<Long, Seed>>()
        fun add(t: String, id: String, name: String, time: Long) {
            if (com.maxim.marquee.data.Emby.isEmby(id) || !id.startsWith("tt") || name.isBlank()) return
            if (t != "movie" && t != "series") return
            if (type != null && t != type) return
            val old = times[id]
            if (old == null || time > old.first) times[id] = time to Seed(t, id, name)
        }
        library.forEach { e -> if (e.lastWatched > 0) add(e.type, e.id, e.name, e.lastWatched) }
        Store.allProgress().forEach { p -> add(p.type, p.metaId, p.name, p.updated) }
        return times.values.sortedByDescending { it.first }.map { it.second }.take(2)
    }

    // ---------------- Calendar ----------------

    data class CalEntry(val meta: MetaItem, val ep: Video, val watched: Boolean)
    /** One day of the calendar. [day] counts days since 1970 (air dates are calendar dates). */
    data class CalDay(val day: Long, val entries: List<CalEntry>)

    /** Episodes of your shows from a week ago to six weeks ahead; null while it's being worked out. */
    var calendar by mutableStateOf<List<CalDay>?>(null)
        private set
    private var calendarAt = 0L

    fun loadCalendar(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && calendar != null && now - calendarAt < 3_600_000L) return
        calendarAt = now
        scope.launch {
            // Shows you follow or have watched, most recent first.
            val ids = LinkedHashMap<String, Long>()
            library.filter { it.type == "series" && (!it.removed || it.temp) && !it.noNotif }
                .forEach { ids[it.id] = maxOf(ids[it.id] ?: 0, watchedAt(it)) }
            Store.allProgress().filter { it.type == "series" }.forEach { ids[it.metaId] = maxOf(ids[it.metaId] ?: 0, it.updated) }
            val shows = ids.entries.filter { !com.maxim.marquee.data.Emby.isEmby(it.key) }
                .sortedByDescending { it.value }.take(80).map { it.key }
            val dayMs = 86_400_000L
            val today = now / dayMs
            val from = (today - 7) * dayMs
            val to = (today + 43) * dayMs
            val gate = kotlinx.coroutines.sync.Semaphore(4)
            val entries = shows.map { id ->
                scope.async {
                    gate.acquire()
                    try {
                        val meta = Repo.meta("series", id) ?: return@async emptyList()
                        meta.videos.filter { it.season > 0 && it.released in from until to }
                            .map { CalEntry(meta, it, watched(meta.id, it.id)) }
                    } catch (e: Exception) {
                        emptyList()
                    } finally {
                        gate.release()
                    }
                }
            }.awaitAll().flatten()
            calendar = entries.groupBy { it.ep.released / dayMs }
                .map { (d, l) -> CalDay(d, l.sortedWith(compareBy({ it.meta.name }, { it.ep.season }, { it.ep.episode }))) }
                .sortedBy { it.day }
        }
    }

    /** "Today", "Tomorrow", "Yesterday" or "Wednesday, Oct 29". */
    fun calendarLabel(day: Long): String {
        val local = java.util.Calendar.getInstance()
        val todayLocal = java.time.LocalDate.of(local.get(java.util.Calendar.YEAR), local.get(java.util.Calendar.MONTH) + 1, local.get(java.util.Calendar.DAY_OF_MONTH)).toEpochDay()
        return when (day - todayLocal) {
            0L -> "Today"
            1L -> "Tomorrow"
            -1L -> "Yesterday"
            else -> java.time.LocalDate.ofEpochDay(day).format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", java.util.Locale.getDefault()))
        }
    }

    /** Today's date in the same "days since 1970" count the calendar uses. */
    fun calendarToday(): Long {
        val c = java.util.Calendar.getInstance()
        return java.time.LocalDate.of(c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH)).toEpochDay()
    }

    suspend fun relatedFor(meta: MetaItem): List<MetaItem> {
        if (com.maxim.marquee.data.Emby.isEmby(meta.id)) return emptyList()
        val fromTrakt = com.maxim.marquee.data.Trakt.related(meta.type, meta.id)
        val list = if (fromTrakt.isNotEmpty()) fromTrakt else {
            // Without Trakt: titles from its top two genres, the ones sharing the most genres first.
            val genres = meta.genres.take(2)
            if (genres.isEmpty()) return emptyList()
            val lists = coroutineScope { genres.map { g -> async { Repo.genreCatalog(meta.type, g) } }.awaitAll() }
            val inBoth = if (lists.size > 1) lists[0].map { it.id }.toSet() intersect lists[1].map { it.id }.toSet() else emptySet()
            lists.flatten().distinctBy { it.id }
                .sortedByDescending { m -> m.genres.count { it in meta.genres } + (if (m.id in inBoth) 2 else 0) }
        }
        return list.filter { it.id != meta.id }.distinctBy { it.id }.take(24)
    }

    fun voiceSearch() {
        val i = Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(android.speech.RecognizerIntent.EXTRA_PROMPT, "Search movies and shows")
        try {
            activity.voiceLauncher.launch(i)
        } catch (e: ActivityNotFoundException) {
            toast("Voice search isn't available on this device")
        }
    }

    fun onVoiceResult(data: Intent?) {
        val text = data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
        searchQuery = text
        // Spoken from anywhere in the app: show the results.
        stack.clear()
        tab = Tab.SEARCH
    }

    /** The remote's search / microphone button inside the app: straight to voice search. */
    fun voiceFromRemote() {
        if (loading != null) return
        voiceSearch()
    }

    fun openTrailer(title: String, ids: List<String>) {
        if (ids.isEmpty()) return
        stack.add(Screen.Trailer(title, ids))
    }

    fun playTrailer(ytId: String) {
        val app = Intent(Intent.ACTION_VIEW, Uri.parse("vnd.youtube:$ytId"))
        val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$ytId"))
        try {
            activity.startActivity(app)
        } catch (e: ActivityNotFoundException) {
            try {
                activity.startActivity(web)
            } catch (e2: ActivityNotFoundException) {
                toast("Install YouTube to watch trailers")
            }
        }
    }
}
