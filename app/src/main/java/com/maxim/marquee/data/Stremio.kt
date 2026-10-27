package com.maxim.marquee.data

import com.maxim.marquee.AppScope
import android.util.LruCache
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/** Stremio account API: login, installed addons, library. */
object StremioApi {
    private const val BASE = "https://api.strem.io/api/"

    private suspend fun call(method: String, body: JSONObject): JSONObject {
        val res = Http.postJson(BASE + method, body)
        when (val err = res.opt("error")) {
            is JSONObject -> throw IOException(err.optString("message", "Request failed"))
            is String -> throw IOException(err)
        }
        return res
    }

    suspend fun login(email: String, password: String): String {
        val r = call(
            "login",
            JSONObject().put("type", "Login").put("email", email)
                .put("password", password).put("facebook", false),
        )
        return r.obj("result")?.str("authKey") ?: throw IOException("Sign in failed")
    }

    suspend fun addonCollection(authKey: String): JSONArray =
        call(
            "addonCollectionGet",
            JSONObject().put("type", "AddonCollectionGet").put("authKey", authKey).put("update", true),
        ).obj("result")?.arr("addons") ?: JSONArray()

    suspend fun library(authKey: String): List<JSONObject> =
        call(
            "datastoreGet",
            JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                .put("ids", JSONArray()).put("all", true),
        ).arr("result").objects()

    suspend fun libraryItem(authKey: String, id: String): JSONObject? =
        call(
            "datastoreGet",
            JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                .put("ids", JSONArray().put(id)).put("all", false),
        ).arr("result").objects().firstOrNull()

    /** Replaces the account's addon list (used to copy addons into a new profile's account). */
    suspend fun setAddonCollection(authKey: String, addons: JSONArray) {
        call("addonCollectionSet", JSONObject().put("type", "AddonCollectionSet").put("authKey", authKey).put("addons", addons))
    }

    suspend fun putLibraryItem(authKey: String, item: JSONObject) {
        call(
            "datastorePut",
            JSONObject().put("authKey", authKey).put("collection", "libraryItem")
                .put("changes", JSONArray().put(item)),
        )
    }
}

/** Talks to the user's installed addons, in the same order Stremio uses them. */
object Repo {
    @Volatile
    var addons: List<Addon> = emptyList()
        private set

    private val metaCache = LruCache<String, MetaItem>(400)

    val signature: String get() = addons.joinToString("|") { it.transportUrl }

    private fun parseAddons(arr: JSONArray) =
        arr.objects().mapNotNull { Addon.parse(it) }.filterNot { it.isLocal }

    fun loadCachedAddons() {
        val json = Store.addonsJson ?: run { addons = emptyList(); return }
        safe { addons = parseAddons(JSONArray(json)) }
    }

    /** Forget the previous profile's addons. */
    fun reset() {
        addons = emptyList()
    }

    /**
     * The catalog that best represents "what's popular right now" for a type: a trending /
     * popular / top catalog, preferring Cinemeta's. Used for the Top 10 rows.
     */
    fun popularCatalog(type: String): Pair<Addon, Catalog>? {
        val all = boardCatalogs(type)
        fun score(a: Addon, c: Catalog): Int {
            val id = c.id.lowercase(); val n = c.name.lowercase()
            var s = when {
                id.contains("trending") || n.contains("trending") -> 30
                id == "top" || n == "popular" || n.startsWith("popular") -> 25
                id.contains("popular") || n.contains("popular") -> 20
                id.contains("top") || n.startsWith("top") -> 10
                else -> 0
            }
            if (s > 0 && a.id == "com.linvo.cinemeta") s += 5
            return s
        }
        return all.map { (a, c) -> Triple(a, c, score(a, c)) }.filter { it.third > 0 }.maxByOrNull { it.third }?.let { it.first to it.second }
    }

    suspend fun refreshAddons() {
        val key = Store.authKey ?: return
        val arr = StremioApi.addonCollection(key)
        Store.addonsJson = arr.toString()
        addons = parseAddons(arr)
    }

    private val SPORTS = Regex(
        "sport|\\blive\\b|ppv|\\bnba\\b|\\bnfl\\b|\\bnhl\\b|\\bmlb\\b|\\bufc\\b|\\bmma\\b|\\bf1\\b|formula|football|soccer|" +
            "boxing|wrestling|\\bwwe\\b|tennis|golf|cricket|rugby|hockey|basketball|baseball|motogp|nascar|match|game day",
        RegexOption.IGNORE_CASE,
    )

    /** Sports and live TV: live-channel catalog types, or a sports/live name on the catalog or its addon. */
    fun isSports(a: Addon, c: Catalog): Boolean =
        c.type.lowercase() in setOf("tv", "channel", "channels", "events", "event", "sports", "sport", "live") ||
            SPORTS.containsMatchIn(c.name) || SPORTS.containsMatchIn(c.id) || SPORTS.containsMatchIn(a.name)

    private val RECAP = Regex("recap|highlight|replay|full match|condensed|re-?watch|vod", RegexOption.IGNORE_CASE)

    /** Recaps and highlights of past games (not live). */
    fun isRecap(c: Catalog): Boolean = RECAP.containsMatchIn(c.name) || RECAP.containsMatchIn(c.id)

    /** Catalogs that show on the board without needing a search term or genre. */
    fun boardCatalogs(type: String?): List<Pair<Addon, Catalog>> =
        addons.flatMap { a ->
            a.catalogs.filter { it.browsable && (type == null || it.type == type) }.map { a to it }
        }

    /** A catalog page. [cachedOnly] returns the copy saved last time (instantly, no network) or nothing. */
    suspend fun catalog(a: Addon, c: Catalog, skip: Int = 0, cachedOnly: Boolean = false): List<MetaItem> {
        val extra = if (skip > 0) "/skip=$skip" else ""
        val o = Http.getJson("${a.base}/catalog/${c.type}/${enc(c.id)}$extra.json", if (cachedOnly) Fetch.CACHED_ONLY else Fetch.NORMAL)
            ?: return emptyList()
        return withContext(Dispatchers.Default) { o.arr("metas").objects().mapNotNull { MetaItem.parse(it, c.type) } }
    }

    /** Titles from the first catalog that can filter by this genre (used for "More Like This"). */
    suspend fun genreCatalog(type: String, genre: String): List<MetaItem> {
        for (a in addons) {
            val c = a.catalogs.firstOrNull { c ->
                c.type == type && c.extras.any { it.name == "genre" } && c.extras.none { it.isRequired && it.name != "genre" }
            } ?: continue
            val items = Http.getMapped("${a.base}/catalog/${c.type}/${enc(c.id)}/genre=${enc(genre).replace(":", "%3A")}.json") { o ->
                o.arr("metas").objects().mapNotNull { MetaItem.parse(it, c.type) }
            } ?: continue
            if (items.isNotEmpty()) return items
        }
        return emptyList()
    }

    fun cachedMeta(type: String, id: String): MetaItem? = metaCache.get("$type/$id")

    /**
     * Full details for a title. Uses a stored copy up to 12 hours old for speed; [fresh] always asks
     * the addon (used when checking for new episodes).
     */
    suspend fun meta(type: String, id: String, fresh: Boolean = false): MetaItem? {
        if (!fresh) cachedMeta(type, id)?.let { return it }
        if (Emby.isEmby(id)) {
            return Emby.meta(id)?.also { metaCache.put("$type/$id", it) }
        }
        for (a in addons.filter { it.supports("meta", type, id) }) {
            val o = Http.getJson("${a.base}/meta/$type/${enc(id)}.json", if (fresh) Fetch.NORMAL else Fetch.STALE_OK)?.obj("meta") ?: continue
            val m = withContext(Dispatchers.Default) { MetaItem.parse(o, type) } ?: continue
            metaCache.put("$type/$id", m)
            return m
        }
        // Not described by any addon but on your Emby server: use Emby's details.
        if (Emby.connected) Emby.metaForImdb(id)?.let { metaCache.put("$type/$id", it); return it }
        return null
    }

    private suspend fun fetchStreams(a: Addon, type: String, videoId: String): List<StreamItem> =
        withTimeoutOrNull(25_000) {
            Http.getMapped("${a.base}/stream/$type/${enc(videoId)}.json", Fetch.NETWORK_ONLY) { o ->
                o.arr("streams").objects().mapNotNull { StreamItem.parse(it, a.name) }
            }
        } ?: emptyList()

    private fun streamAddons(type: String, videoId: String) =
        addons.filter { it.supports("stream", type, videoId) }

    /** Every playable stream, best first (addon order, then the addon's own ranking). */
    suspend fun allStreams(type: String, videoId: String): List<StreamItem> = coroutineScope {
        streamAddons(type, videoId).map { a -> async { fetchStreams(a, type, videoId) } }.awaitAll().flatten()
    }

    /**
     * Streams for playback, fast: returns as soon as the highest-priority addon that has
     * results has answered (and every addon above it in your order has finished), instead
     * of waiting for the slowest addon. Hard cap of 15 seconds.
     */
    suspend fun bestStreams(type: String, videoId: String): List<StreamItem> {
        if (Emby.isEmby(videoId)) return Emby.streams(videoId)
        // Your own server's copy (if any) is looked up in parallel and always listed first.
        val emby: Deferred<List<StreamItem>>? = if (Emby.connected) {
            AppScope.async { safe { Emby.streamsForTitle(type, videoId) } ?: emptyList<StreamItem>() }
        } else null
        val fromAddons = addonStreams(type, videoId)
        val local: List<StreamItem> = if (emby != null) withTimeoutOrNull(6_000) { emby.await() } ?: emptyList() else emptyList()
        return local + fromAddons
    }

    private suspend fun addonStreams(type: String, videoId: String): List<StreamItem> {
        val list = streamAddons(type, videoId)
        if (list.isEmpty()) return emptyList()
        // Detached, so a slow addon never holds up playback; it just finishes in the background.
        val pending = list.map { a -> AppScope.async { fetchStreams(a, type, videoId) } }
        val deadline = System.currentTimeMillis() + 15_000
        val out = ArrayList<List<StreamItem>>()
        for (d in pending) {
            val left = deadline - System.currentTimeMillis()
            val r = if (left > 0) withTimeoutOrNull(left) { d.await() } else null
            if (r == null) break
            out += r
            if (r.isNotEmpty()) break
        }
        // Lower-priority addons that already answered become fallbacks.
        pending.drop(out.size).forEach { d ->
            if (d.isCompleted && !d.isCancelled) runCatching { out += d.await() }
        }
        return out.flatten()
    }

    /** Emits (addonIndex, addon, streams) as each addon answers. */
    private val embyAddon = Addon("emby://", "emby", "Emby", emptyList(), emptyList())

    fun streamsFlow(type: String, videoId: String): Flow<Triple<Int, Addon, List<StreamItem>>> = channelFlow {
        if (Emby.isEmby(videoId)) {
            send(Triple(0, embyAddon, Emby.streams(videoId)))
            return@channelFlow
        }
        if (Emby.connected) launch { send(Triple(0, embyAddon, safe { Emby.streamsForTitle(type, videoId) } ?: emptyList())) }
        streamAddons(type, videoId).forEachIndexed { i, a ->
            launch { send(Triple(i + 1, a, fetchStreams(a, type, videoId))) }
        }
    }

    suspend fun subtitles(type: String, videoId: String): List<SubTrack> = coroutineScope {
        if (Emby.isEmby(videoId)) return@coroutineScope emptyList()
        addons.filter { it.supports("subtitles", type, videoId) }.map { a ->
            async {
                withTimeoutOrNull(6_000) {
                    Http.getMapped("${a.base}/subtitles/$type/${enc(videoId)}.json") { o ->
                        o.arr("subtitles").objects().mapNotNull { SubTrack.parse(it, a.name) }
                    }
                } ?: emptyList()
            }
        }.awaitAll().flatten()
    }

    suspend fun search(query: String): List<Pair<String, List<MetaItem>>> = coroutineScope {
        val q = enc(query).replace(":", "%3A")
        val emby: Deferred<List<MetaItem>>? = if (Emby.connected) {
            async { safe { Emby.search(query) } ?: emptyList<MetaItem>() }
        } else null
        val stremio = addons.flatMap { a -> a.catalogs.filter { it.supportsSearch }.map { a to it } }
            .map { (a, c) ->
                async {
                    Http.getMapped("${a.base}/catalog/${c.type}/${enc(c.id)}/search=$q.json") { o ->
                        o.arr("metas").objects().mapNotNull { MetaItem.parse(it, c.type) }
                    } ?: emptyList()
                }
            }.awaitAll().flatten()
        val all = stremio + (emby?.await() ?: emptyList())
        android.util.Log.i("Marquee", "search '$query': ${addons.size} addons, ${stremio.size} from catalogs, ${all.size} total")
        // One row per type ("Movies", "Series", …), each title once.
        val order = listOf("movie", "series")
        all.groupBy { it.type }
            .toList()
            .sortedBy { (t, _) -> order.indexOf(t).let { if (it < 0) 99 else it } }
            .map { (t, items) -> typeLabel(t) to items.distinctBy { it.id } }
            .filter { it.second.isNotEmpty() }
    }

    /** Your Stremio library; null if it couldn't be loaded (keep what you have). Saved for instant start next time. */
    suspend fun library(): List<LibEntry>? {
        val key = Store.authKey ?: return emptyList()
        return safe {
            val raw = StremioApi.library(key)
            withContext(Dispatchers.Default) {
                Store.saveLibraryCache(JSONArray().apply { raw.forEach { put(it) } }.toString())
                raw.mapNotNull { LibEntry.parse(it) }
            }
        }
    }

    /** The library as it was last time, from disk (no network). */
    suspend fun cachedLibrary(): List<LibEntry> = withContext(Dispatchers.IO) {
        val text = Store.loadLibraryCache() ?: return@withContext emptyList()
        safe { JSONArray(text).objects().mapNotNull { LibEntry.parse(it) } } ?: emptyList()
    }

    /** Parses the saved addon list off the main thread. */
    suspend fun loadCachedAddonsAsync() = withContext(Dispatchers.Default) { loadCachedAddons() }

    /**
     * Writes playback progress back to the Stremio library so Continue Watching matches
     * on every device. Only updates items that already exist, and only the progress fields,
     * following the same rules Stremio's own player uses:
     * - finished movie: marked watched, progress cleared
     * - finished episode: Continue Watching moves on to the next episode ("Up Next")
     */
    suspend fun syncProgress(
        type: String, metaId: String, videoId: String, pos: Long, dur: Long, finished: Boolean,
        nextVideoId: String? = null, name: String? = null, poster: String? = null, stopped: Boolean = false,
    ) {
        if (Emby.isEmby(videoId)) {
            safe { Emby.report(videoId, pos, finished, stopped) }
            return
        }
        val key = Store.authKey ?: return
        safe {
            // Not in the library yet: create a temporary entry, exactly like Stremio does when you
            // press play, so it shows in Continue Watching on your other devices.
            val item = StremioApi.libraryItem(key, metaId)
                ?: if (name != null) newItem(metaId, type, name, poster, removed = true, temp = true) else return
            val state = item.obj("state") ?: return
            val now = isoNow()
            // Not in your list: kept as a "temporary" entry, like Stremio does, so it shows in Continue Watching.
            if (item.optBoolean("removed")) item.put("temp", true)
            state.put("lastWatched", now)
            state.put("duration", dur)
            if (!finished) {
                state.put("timeOffset", pos)
                state.put("video_id", videoId)
            } else {
                state.put("timesWatched", (state.long("timesWatched") ?: 0) + 1)
                if (type == "series" && nextVideoId != null) {
                    state.put("overallTimeWatched", (state.long("overallTimeWatched") ?: 0) + (state.long("timeWatched") ?: 0))
                    state.put("timeWatched", 0L)
                    state.put("flaggedWatched", 0)
                    state.put("video_id", nextVideoId)
                    state.put("timeOffset", 1L)
                } else {
                    state.put("video_id", videoId)
                    state.put("timeOffset", 0L)
                    if (type == "movie") state.put("flaggedWatched", 1)
                }
            }
            item.put("_mtime", now)
            StremioApi.putLibraryItem(key, item)
        }
    }

    /** A library entry in Stremio's exact format (see stremio-core LibraryItem). */
    private fun newItem(
        id: String, type: String, name: String, poster: String?,
        removed: Boolean, temp: Boolean, defaultVideoId: String? = null,
    ): JSONObject {
        val now = isoNow()
        return JSONObject()
            .put("_id", id).put("name", name).put("type", type)
            .put("poster", poster ?: JSONObject.NULL).put("posterShape", "poster")
            .put("removed", removed).put("temp", temp)
            .put("_ctime", now).put("_mtime", now)
            .put(
                "state",
                JSONObject()
                    .put("lastWatched", now).put("timeWatched", 0L).put("timeOffset", 0L)
                    .put("overallTimeWatched", 0L).put("timesWatched", 0).put("flaggedWatched", 0)
                    .put("duration", 0L).put("video_id", JSONObject.NULL).put("watched", JSONObject.NULL)
                    .put("noNotif", false),
            )
            .put(
                "behaviorHints",
                JSONObject().put("defaultVideoId", defaultVideoId ?: JSONObject.NULL)
                    .put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false),
            )
    }

    /** Removes a title from Continue Watching everywhere (Stremio's "rewind"). */
    suspend fun rewind(metaId: String) {
        val key = Store.authKey ?: return
        safe {
            val item = StremioApi.libraryItem(key, metaId) ?: return
            item.obj("state")?.put("timeOffset", 0L) ?: return
            item.put("_mtime", isoNow())
            StremioApi.putLibraryItem(key, item)
        }
    }

    /** Marks a title watched in your Stremio library and takes it off Continue Watching everywhere. */
    suspend fun markWatched(metaId: String) {
        val key = Store.authKey ?: return
        safe {
            val item = StremioApi.libraryItem(key, metaId) ?: return
            val state = item.obj("state") ?: return
            state.put("timeOffset", 0L)
            state.put("flaggedWatched", 1)
            state.put("timesWatched", (state.long("timesWatched") ?: 0) + 1)
            item.put("_mtime", isoNow())
            StremioApi.putLibraryItem(key, item)
        }
    }

    /** Marked unwatched: no watched flag, no resume point. */
    suspend fun markUnwatched(metaId: String) {
        val key = Store.authKey ?: return
        safe {
            val item = StremioApi.libraryItem(key, metaId) ?: return
            val state = item.obj("state") ?: return
            state.put("timeOffset", 0L)
            state.put("flaggedWatched", 0)
            state.put("timesWatched", 0)
            item.put("_mtime", isoNow())
            StremioApi.putLibraryItem(key, item)
        }
    }

    /** Add to / remove from My List (the Stremio library), exactly like Stremio does it. */
    suspend fun setInLibrary(meta: MetaItem, add: Boolean): Boolean {
        val key = Store.authKey ?: return false
        return safe {
            val now = isoNow()
            val existing = StremioApi.libraryItem(key, meta.id)
            val item = if (existing != null) {
                existing.put("removed", !add).put("temp", false).put("_mtime", now)
            } else {
                if (!add) return true
                newItem(meta.id, meta.type, meta.name, meta.poster, removed = false, temp = false, defaultVideoId = meta.defaultVideoId)
            }
            StremioApi.putLibraryItem(key, item)
            true
        } ?: false
    }

    /** [sure]: timing trusted enough to skip by itself (several matching submissions, or from your own server). */
    data class Segment(val startMs: Long, val endMs: Long, val sure: Boolean = true)
    data class Segments(val intro: Segment?, val recap: Segment?, val outro: Segment?)

    /** Intro / recap / credits timestamps from IntroDB (community data, no key needed). */
    suspend fun segments(videoId: String): Segments? {
        if (Emby.isEmby(videoId)) return Emby.introKeys[videoId]?.let { segments(it) }
        val p = videoId.split(":")
        if (p.size < 3 || !p[0].startsWith("tt")) return null
        val season = p[p.size - 2].toIntOrNull() ?: return null
        val episode = p[p.size - 1].toIntOrNull() ?: return null
        val o = Http.getJson("https://api.introdb.app/segments?imdb_id=${p[0]}&season=$season&episode=$episode") ?: return null
        fun seg(k: String): Segment? {
            val s = o.obj(k) ?: return null
            val start = s.long("start_ms") ?: s.long("start_sec")?.times(1000) ?: return null
            val end = s.long("end_ms") ?: s.long("end_sec")?.times(1000) ?: return null
            if (end <= start) return null
            // Community timings: a single or low-confidence submission is often for a different
            // release (an extra recap or logo shifts everything). Too unsure = not offered at all;
            // somewhat sure = a button you can choose to press; sure = may skip by itself.
            val confidence = s.optDouble("confidence", Double.NaN)
            val count = s.int("submission_count")
            if (!confidence.isNaN() && confidence < 0.5) return null
            val len = end - start
            if (k != "outro" && (len < 5_000 || len > 240_000 || start > 25 * 60_000L)) return null
            val sure = (confidence.isNaN() || confidence >= 0.8) && (count == null || count >= 2)
            return Segment(start, end, sure)
        }
        return Segments(seg("intro"), seg("recap"), seg("outro"))
    }
}
