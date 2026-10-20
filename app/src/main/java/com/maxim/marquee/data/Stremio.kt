package com.maxim.marquee.data

import android.util.LruCache
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
        val json = Store.addonsJson ?: return
        safe { addons = parseAddons(JSONArray(json)) }
    }

    suspend fun refreshAddons() {
        val key = Store.authKey ?: return
        val arr = StremioApi.addonCollection(key)
        Store.addonsJson = arr.toString()
        addons = parseAddons(arr)
    }

    /** Catalogs that show on the board without needing a search term or genre. */
    fun boardCatalogs(type: String?): List<Pair<Addon, Catalog>> =
        addons.flatMap { a ->
            a.catalogs.filter { it.browsable && (type == null || it.type == type) }.map { a to it }
        }

    suspend fun catalog(a: Addon, c: Catalog, skip: Int = 0): List<MetaItem> {
        val extra = if (skip > 0) "/skip=$skip" else ""
        val o = Http.getJson("${a.base}/catalog/${c.type}/${enc(c.id)}$extra.json") ?: return emptyList()
        return withContext(Dispatchers.Default) { o.arr("metas").objects().mapNotNull { MetaItem.parse(it, c.type) } }
    }

    fun cachedMeta(type: String, id: String): MetaItem? = metaCache.get("$type/$id")

    suspend fun meta(type: String, id: String): MetaItem? {
        cachedMeta(type, id)?.let { return it }
        for (a in addons.filter { it.supports("meta", type, id) }) {
            val o = Http.getJson("${a.base}/meta/$type/${enc(id)}.json")?.obj("meta") ?: continue
            val m = withContext(Dispatchers.Default) { MetaItem.parse(o, type) } ?: continue
            metaCache.put("$type/$id", m)
            return m
        }
        return null
    }

    private suspend fun fetchStreams(a: Addon, type: String, videoId: String): List<StreamItem> =
        withTimeoutOrNull(25_000) {
            Http.getMapped("${a.base}/stream/$type/${enc(videoId)}.json") { o ->
                o.arr("streams").objects().mapNotNull { StreamItem.parse(it, a.name) }
            }
        } ?: emptyList()

    private fun streamAddons(type: String, videoId: String) =
        addons.filter { it.supports("stream", type, videoId) }

    /** Every playable stream, best first (addon order, then the addon's own ranking). */
    suspend fun allStreams(type: String, videoId: String): List<StreamItem> = coroutineScope {
        streamAddons(type, videoId).map { a -> async { fetchStreams(a, type, videoId) } }.awaitAll().flatten()
    }

    /** Emits (addonIndex, addon, streams) as each addon answers. */
    fun streamsFlow(type: String, videoId: String): Flow<Triple<Int, Addon, List<StreamItem>>> = channelFlow {
        streamAddons(type, videoId).forEachIndexed { i, a ->
            launch { send(Triple(i, a, fetchStreams(a, type, videoId))) }
        }
    }

    suspend fun subtitles(type: String, videoId: String): List<SubTrack> = coroutineScope {
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
        addons.flatMap { a -> a.catalogs.filter { it.supportsSearch }.map { a to it } }
            .map { (a, c) ->
                async {
                    val items = Http.getMapped("${a.base}/catalog/${c.type}/${enc(c.id)}/search=$q.json") { o ->
                        o.arr("metas").objects().mapNotNull { MetaItem.parse(it, c.type) }
                    } ?: emptyList()
                    "${typeLabel(c.type)} · ${a.name}" to items
                }
            }.awaitAll().filter { it.second.isNotEmpty() }
    }

    suspend fun library(): List<LibEntry> {
        val key = Store.authKey ?: return emptyList()
        return safe {
            val raw = StremioApi.library(key)
            withContext(Dispatchers.Default) { raw.mapNotNull { LibEntry.parse(it) } }
        } ?: emptyList()
    }

    /**
     * Writes playback progress back to the Stremio library so Continue Watching matches
     * on every device. Only updates items that already exist, and only the progress fields,
     * following the same rules Stremio's own player uses:
     * - finished movie: marked watched, progress cleared
     * - finished episode: Continue Watching moves on to the next episode ("Up Next")
     */
    suspend fun syncProgress(
        type: String, metaId: String, videoId: String, pos: Long, dur: Long, finished: Boolean,
        nextVideoId: String? = null,
    ) {
        val key = Store.authKey ?: return
        safe {
            val item = StremioApi.libraryItem(key, metaId) ?: return
            val state = item.obj("state") ?: return
            val now = isoNow()
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
                JSONObject()
                    .put("_id", meta.id).put("name", meta.name).put("type", meta.type)
                    .put("poster", meta.poster ?: JSONObject.NULL).put("posterShape", "poster")
                    .put("removed", false).put("temp", false)
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
                        JSONObject().put("defaultVideoId", meta.defaultVideoId ?: JSONObject.NULL)
                            .put("featuredVideoId", JSONObject.NULL).put("hasScheduledVideos", false),
                    )
            }
            StremioApi.putLibraryItem(key, item)
            true
        } ?: false
    }

    data class Segment(val startMs: Long, val endMs: Long)
    data class Segments(val intro: Segment?, val recap: Segment?, val outro: Segment?)

    /** Intro / recap / credits timestamps from IntroDB (community data, no key needed). */
    suspend fun segments(videoId: String): Segments? {
        val p = videoId.split(":")
        if (p.size < 3 || !p[0].startsWith("tt")) return null
        val season = p[p.size - 2].toIntOrNull() ?: return null
        val episode = p[p.size - 1].toIntOrNull() ?: return null
        val o = Http.getJson("https://api.introdb.app/segments?imdb_id=${p[0]}&season=$season&episode=$episode") ?: return null
        fun seg(k: String): Segment? {
            val s = o.obj(k) ?: return null
            val start = s.long("start_ms") ?: s.long("start_sec")?.times(1000) ?: return null
            val end = s.long("end_ms") ?: s.long("end_sec")?.times(1000) ?: return null
            return if (end > start) Segment(start, end) else null
        }
        return Segments(seg("intro"), seg("recap"), seg("outro"))
    }
}
