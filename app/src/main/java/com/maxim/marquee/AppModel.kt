package com.maxim.marquee

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
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
import com.maxim.marquee.data.typeLabel
import com.maxim.marquee.ui.MovieIcon
import com.maxim.marquee.ui.TvIcon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

enum class Tab(val label: String, val icon: ImageVector) {
    SEARCH("Search", Icons.Filled.Search),
    HOME("Home", Icons.Filled.Home),
    SERIES("Series", TvIcon),
    MOVIES("Movies", MovieIcon),
    MYLIST("My List", Icons.Filled.Add),
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

data class CardMenu(val item: CardItem, val browse: BrowseState?, val fromContinue: Boolean)

sealed class Screen {
    data class Detail(val type: String, val id: String, val preview: MetaItem) : Screen()
    data class Streams(val req: PlayReq) : Screen()
    data object EmbyLogin : Screen()
}

sealed class RowSource {
    data object Continue : RowSource()
    /** One catalog, plus any other addons' catalogs with the same name (merged, no duplicates). */
    data class Cat(val addon: Addon, val catalog: Catalog, val more: List<Pair<Addon, Catalog>> = emptyList()) : RowSource()
    data object Static : RowSource()
    /** kind: "resume", "nextup" or "latest" (with a library id). */
    data class Emby(val kind: String, val libraryId: String? = null) : RowSource()
}

data class RowModel(val key: String, val title: String, val source: RowSource)

/** Everything a browse page needs to look the same when you come back to it. */
class BrowseState(val type: String?, val withContinue: Boolean) {
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
    var signature = ""

    fun rowState(key: String) = rowStates.getOrPut(key) { LazyListState() }

    fun firstKey(): String? = rows.firstOrNull { !rowItems[it.key].isNullOrEmpty() }?.let { "${it.key}#0" }

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

    val browse = mapOf(
        Tab.HOME to BrowseState(null, true),
        Tab.SERIES to BrowseState("series", false),
        Tab.MOVIES to BrowseState("movie", false),
    )
    val search = BrowseState(null, false)
    var searchQuery by mutableStateOf("")
    var searchedQuery = ""
    val myListGrid = LazyGridState()
    var myListFocus = -1

    private var trailerPlayer: TrailerPlayer? = null
    /** Created on first use, only if background trailers are enabled. */
    val trailer: TrailerPlayer get() = trailerPlayer ?: TrailerPlayer(activity).also { trailerPlayer = it }
    fun pauseTrailer() = trailerPlayer?.onPause()
    fun resumeTrailer() = trailerPlayer?.onResume()
    fun stopTrailer() = trailerPlayer?.stop()

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
    )

    fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    // ---------------- session ----------------

    fun start() {
        if (!loggedIn) return
        Repo.loadCachedAddons()
        addonCount = Repo.addons.size
        rebuildRows()
        scope.launch {
            syncing = true
            try {
                Repo.refreshAddons()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (Repo.addons.isEmpty()) toast("Couldn't load your addons: ${e.message}")
            }
            addonCount = Repo.addons.size
            loadEmbyLibraries()
            rebuildRows()
            loadLibrary()
            syncing = false
        }
    }

    private suspend fun loadEmbyLibraries() {
        embyLibraries = if (com.maxim.marquee.data.Emby.connected) com.maxim.marquee.data.Emby.libraries() else emptyList()
        if (com.maxim.marquee.data.Emby.connected) com.maxim.marquee.data.Emby.buildIndex(force = true)
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

    fun onResume() {
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
        library = Repo.library()
        refreshContinue()
        if (com.maxim.marquee.data.Emby.connected) {
            com.maxim.marquee.data.Emby.buildIndex()
            embyEntries = com.maxim.marquee.data.Emby.resume() + com.maxim.marquee.data.Emby.nextUp()
            refreshContinue()
        } else if (embyEntries.isNotEmpty()) {
            embyEntries = emptyList()
            refreshContinue()
        }
    }

    // ---------------- browse rows ----------------

    private fun rowTitle(c: Catalog): String {
        val n = c.name.trim()
        val lower = n.lowercase()
        if (lower.contains("movie") || lower.contains("series") || lower.contains("show") || lower.contains("film")) return n
        return "$n ${typeLabel(c.type)}"
    }

    private fun rebuildRows(force: Boolean = false) {
        val sig = Repo.signature + "|" + embyLibraries.joinToString(",") { it.id }
        browse.values.forEach { b ->
            if (!force && b.signature == sig && b.rows.isNotEmpty()) return@forEach
            val defs = mutableListOf<RowModel>()
            if (b.withContinue) defs += RowModel("continue", "Continue Watching", RowSource.Continue)
            if (com.maxim.marquee.data.Emby.connected) {
                embyLibraries.filter { lib ->
                    when (b.type) {
                        "series" -> lib.type == "tvshows"
                        "movie" -> lib.type == "movies"
                        else -> true
                    }
                }.forEach { lib ->
                    defs += RowModel("emby-latest-${lib.id}", "New in ${lib.name}", RowSource.Emby("latest", lib.id))
                }
            }
            // Rows with the same name from different addons (e.g. two "Popular Movies") become one row.
            Repo.boardCatalogs(b.type)
                .groupBy { (_, c) -> rowTitle(c).lowercase() }
                .values
                .forEachIndexed { i, group ->
                    val (a, c) = group.first()
                    defs += RowModel(
                        "${a.transportUrl.hashCode()}|${c.type}|${c.id}|$i", rowTitle(c),
                        RowSource.Cat(a, c, group.drop(1)),
                    )
                }
            b.reset(defs)
            b.signature = sig
            if (b.withContinue) {
                b.rowItems["continue"] = continueItems()
                b.loaded["continue"] = true
            }
        }
    }

    fun loadRow(b: BrowseState, row: RowModel) {
        if (b.loaded[row.key] == true || !b.requested.add(row.key)) return
        when (val src = row.source) {
            is RowSource.Continue -> {
                b.rowItems[row.key] = continueItems(); b.loaded[row.key] = true; afterRowLoaded(b)
            }
            is RowSource.Static -> b.loaded[row.key] = true
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
                    val items = parts.map { (a, c) -> async { Repo.catalog(a, c) } }.awaitAll()
                        .flatten().distinctBy { it.id }.map { CardItem(it) }
                    b.rowItems[row.key] = items
                    b.loaded[row.key] = true
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

    private fun afterRowLoaded(b: BrowseState) {
        if (b.lastFocusKey == null && b.pendingFocusKey == null) b.pendingFocusKey = b.firstKey()
        if (b.hero == null) {
            b.rows.firstOrNull { !b.rowItems[it.key].isNullOrEmpty() }?.let { r ->
                b.rowItems[r.key]?.firstOrNull()?.let { setHero(b, it) }
            }
        }
    }

    private var heroDebounce: Job? = null

    fun onCardFocus(b: BrowseState, key: String, item: CardItem) {
        b.lastFocusKey = key
        heroDebounce?.cancel()
        heroDebounce = scope.launch {
            delay(140)
            setHero(b, item)
            // Resting on a Continue Watching tile: get its sources ready.
            val vid = item.videoId
            if (vid != null && !(item.meta.type == "series" && vid == item.meta.id)) {
                delay(700)
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
                delay(120)
                val m = Repo.meta(item.meta.type, item.meta.id)
                if (b.hero?.meta?.id == item.meta.id) b.heroMeta = m
            }
        }
    }

    fun selectTab(t: Tab) {
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
    fun continueItems(): List<CardItem> {
        val byId = HashMap<String, Cand>()
        library.forEach { e ->
            if (e.type != "other" && (!e.removed || e.temp) && e.timeOffset > 0) {
                byId[e.id] = Cand(
                    e.id, e.type, e.name, e.poster, e.videoId ?: e.id, e.timeOffset, e.duration,
                    maxOf(e.mtime, e.lastWatched), e.posterShape,
                )
            }
        }
        val inLibrary = library.associateBy { it.id }
        Store.allProgress().filterNot { com.maxim.marquee.data.Emby.isEmby(it.metaId) }.groupBy { it.metaId }.forEach { (metaId, list) ->
            val p = list.maxByOrNull { it.updated } ?: return@forEach
            val lib = inLibrary[metaId]
            val libTime = lib?.let { maxOf(it.mtime, it.lastWatched) } ?: -1
            if (p.updated <= libTime) return@forEach // Stremio has newer info
            if (lib != null && lib.removed && !lib.temp) return@forEach // removed from library on purpose
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
            if (existing == null || e.time > existing.time) {
                byId[m.id] = Cand(
                    m.id, m.type, m.name, m.poster ?: existing?.poster, e.card.videoId ?: m.id,
                    e.position, e.duration, e.time, m.posterShape ?: existing?.shape, e.card.badge,
                )
            }
        }
        return byId.values
            .sortedByDescending { it.time }
            .take(50)
            .map { c ->
                CardItem(
                    meta = MetaItem(id = c.metaId, type = c.type, name = c.name, poster = c.poster, posterShape = c.shape),
                    videoId = c.videoId,
                    progress = if (c.dur > 0 && c.pos > 1) (c.pos.toFloat() / c.dur).coerceIn(0.02f, 1f) else null,
                    badge = if (c.type == "series") {
                        episodeLabel(c.videoId)?.let { if (c.pos == 1L) "NEXT · $it" else it } ?: c.badge
                    } else null,
                )
            }
    }

    fun refreshContinue() {
        localVersion++
        browse.values.filter { it.withContinue }.forEach { b ->
            b.rowItems["continue"] = continueItems()
            b.loaded["continue"] = true
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

    fun progressFor(metaId: String, videoId: String): Float? {
        val (pos, dur) = positionOf(metaId, videoId) ?: return null
        if (dur <= 0 || pos < 5_000) return null
        val f = pos.toFloat() / dur
        return if (f >= 0.95f) null else f
    }

    fun watched(metaId: String, videoId: String): Boolean {
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

    fun nextVideo(meta: MetaItem, videoId: String): Video? {
        val eps = sortedEpisodes(meta).filter { it.season > 0 }
        val i = eps.indexOfFirst { it.id == videoId }
        return if (i >= 0 && i + 1 < eps.size) eps[i + 1] else null
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
    fun resume(item: CardItem) {
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
                ),
            )
        }
    }

    /** Puts the sources the TV handles best first (respecting the max-quality setting). */
    private fun rank(streams: List<StreamItem>): List<StreamItem> {
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
        return good + rest
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
            val streams = rank(streamsFor(req.type, req.videoId).await())
            if (streams.isEmpty()) {
                streamCache.remove("${req.type}/${req.videoId}")
                loading = null
                toast("No playable sources found")
                stack.add(Screen.Streams(req))
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

    fun cancelLoading() {
        playJob?.cancel()
        loading = null
    }

    private suspend fun launchStream(p: Pending) {
        val s = p.streams[p.index]
        val subs = (s.subtitles + p.addonSubs)
            .filter { Store.wantsSubtitle(it.lang) }
            .distinctBy { it.url }
            .take(12)
        val start = resumeMs(p.req)
        // Episode list + next episode for the player's Episodes panel and "Next Episode" card.
        val meta = if (p.req.type == "series") Repo.meta(p.req.type, p.req.metaId) else null
        val current = meta?.videos?.firstOrNull { it.id == p.req.videoId }
        val next = meta?.let { nextVideo(it, p.req.videoId) }
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
        activity.playerLauncher.launch(PlayerActivity.intent(activity, p.req, s, subs, start, next, season, p.autoChain))
    }

    /** After an episode finishes, Continue Watching points at the next one ("Up Next"). */
    private fun markUpNext(req: PlayReq, next: Video) {
        Store.saveProgress(LocalProgress(req.metaId, req.type, req.name, req.poster, next.id, 1, 0, System.currentTimeMillis()))
    }

    fun onPlayerResult(data: Intent?) {
        val p = pending ?: return
        pending = null
        var outcome = data?.getStringExtra("outcome") ?: "exit"
        val outcomeVideo = data?.getStringExtra("videoId")
        val auto = data?.getBooleanExtra("auto", false) ?: false

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
                Repo.meta(p.req.type, p.req.metaId)?.let { m -> nextVideo(m, p.req.videoId)?.let { markUpNext(p.req, it) } }
            }
            refreshContinue()
            delay(1500)
            loadLibrary()
        }

        when (outcome) {
            "error" -> {
                if (p.auto && p.index + 1 < p.streams.size && p.attempts < 5) {
                    toast("That source didn't work — trying the next one")
                    loading = p.req
                    playJob = scope.launch { launchStream(p.copy(index = p.index + 1, attempts = p.attempts + 1)) }
                } else {
                    toast("Couldn't play this source")
                    if (stack.lastOrNull() !is Screen.Streams) stack.add(Screen.Streams(p.req))
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

    fun dismissContinue(item: CardItem) {
        Store.dismiss(item.meta.id)
        library = library.map { if (it.id == item.meta.id) it.copy(timeOffset = 0) else it }
        refreshContinue()
        scope.launch { Repo.rewind(item.meta.id) }
    }

    fun openCardMenu(b: BrowseState?, item: CardItem, fromContinue: Boolean) {
        // If OK is still held, its release must not "click" the menu's first option.
        activity.swallowOkUp = activity.okHeld
        cardMenu = CardMenu(item, b, fromContinue)
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
