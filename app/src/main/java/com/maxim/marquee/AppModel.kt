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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
}

sealed class RowSource {
    data object Continue : RowSource()
    data class Cat(val addon: Addon, val catalog: Catalog) : RowSource()
    data object Static : RowSource()
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
    var railFocused = false
    var cardMenu by mutableStateOf<CardMenu?>(null)
    /** Whether the TV can show Dolby Vision; DV-only sources are avoided when it can't. */
    var supportsDV = false
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
            rebuildRows()
            loadLibrary()
            syncing = false
        }
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

    fun onResume() {
        if (loggedIn && System.currentTimeMillis() - lastLibraryLoad > 60_000) scope.launch { loadLibrary() }
    }

    private suspend fun loadLibrary() {
        lastLibraryLoad = System.currentTimeMillis()
        library = Repo.library()
        refreshContinue()
    }

    // ---------------- browse rows ----------------

    private fun rowTitle(c: Catalog): String {
        val n = c.name.trim()
        val lower = n.lowercase()
        if (lower.contains("movie") || lower.contains("series") || lower.contains("show") || lower.contains("film")) return n
        return "$n ${typeLabel(c.type)}"
    }

    private fun rebuildRows() {
        val sig = Repo.signature
        browse.values.forEach { b ->
            if (b.signature == sig && b.rows.isNotEmpty()) return@forEach
            val defs = mutableListOf<RowModel>()
            if (b.withContinue) defs += RowModel("continue", "Continue Watching", RowSource.Continue)
            Repo.boardCatalogs(b.type).forEachIndexed { i, (a, c) ->
                defs += RowModel("${a.transportUrl.hashCode()}|${c.type}|${c.id}|$i", rowTitle(c), RowSource.Cat(a, c))
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
            is RowSource.Cat -> {
                scope.launch {
                    val items = Repo.catalog(src.addon, src.catalog).distinctBy { it.id }.map { CardItem(it) }
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

    fun onCardFocus(b: BrowseState, key: String, item: CardItem) {
        b.lastFocusKey = key
        setHero(b, item)
    }

    private fun setHero(b: BrowseState, item: CardItem) {
        if (b.hero?.meta?.id == item.meta.id && b.hero?.badge == item.badge) return
        b.hero = item
        b.heroJob?.cancel()
        val cached = Repo.cachedMeta(item.meta.type, item.meta.id)
        b.heroMeta = cached
        if (cached == null) {
            b.heroJob = scope.launch {
                delay(300)
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
        val videoId: String, val pos: Long, val dur: Long, val time: Long, val hidden: Boolean,
    )

    fun continueItems(): List<CardItem> {
        val cands = mutableListOf<Cand>()
        library.filter { it.type != "other" && it.lastWatched > 0 }.forEach { e ->
            cands += Cand(
                e.id, e.type, e.name, e.poster, e.videoId ?: e.id, e.timeOffset, e.duration, e.lastWatched,
                hidden = e.removed && !e.temp,
            )
        }
        Store.allProgress().forEach { p ->
            cands += Cand(p.metaId, p.type, p.name, p.poster, p.videoId, p.position, p.duration, p.updated, false)
        }
        return cands.groupBy { it.metaId }
            .mapNotNull { (_, list) -> list.maxByOrNull { it.time } }
            .filter { c -> !c.hidden && (c.pos == 1L || c.pos > 5_000) && (c.dur <= 0 || c.pos < c.dur * 0.95) }
            .sortedByDescending { it.time }
            .take(30)
            .map { c ->
                CardItem(
                    meta = MetaItem(id = c.metaId, type = c.type, name = c.name, poster = c.poster),
                    videoId = c.videoId,
                    progress = if (c.dur > 0 && c.pos > 1) c.pos.toFloat() / c.dur else null,
                    badge = if (c.type == "series") {
                        episodeLabel(c.videoId)?.let { if (c.pos == 1L) "NEXT · $it" else it }
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
        val cap = Store.maxQuality
        fun ok(s: StreamItem): Boolean {
            val q = s.quality
            if (cap > 0 && q.res > cap) return false
            if (q.dvOnly && !supportsDV) return false
            return true
        }
        val (good, rest) = streams.partition { ok(it) }
        return good + rest
    }

    fun play(req: PlayReq, chooseSource: Boolean = false, autoChain: Int = 0) {
        if (chooseSource || !Store.autoplay) {
            stack.add(Screen.Streams(req)); return
        }
        playJob?.cancel()
        loading = req
        playJob = scope.launch {
            val subs = async { withTimeoutOrNull(6_000) { Repo.subtitles(req.type, req.videoId) } ?: emptyList() }
            val streams = try {
                rank(Repo.allStreams(req.type, req.videoId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
            if (streams.isEmpty()) {
                loading = null
                subs.cancel()
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
            val subs = withTimeoutOrNull(6_000) { Repo.subtitles(req.type, req.videoId) } ?: emptyList()
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
                    Repo.syncProgress(p.req.type, p.req.metaId, p.req.videoId, pos.toLong(), dur.toLong(), finished)
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
        cardMenu = CardMenu(item, b, fromContinue)
    }

    fun closeCardMenu() {
        val m = cardMenu ?: return
        cardMenu = null
        m.browse?.let { b -> b.pendingFocusKey = b.lastFocusKey }
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
