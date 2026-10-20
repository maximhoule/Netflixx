package com.maxim.marquee.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder

/**
 * Emby (and Jellyfin) server client. Emby items appear in the app with ids prefixed "emby:"
 * so the rest of the app routes metadata, playback and progress here instead of to Stremio.
 */
object Emby {
    const val PREFIX = "emby:"

    data class Library(val id: String, val name: String, val type: String?)

    val connected: Boolean get() = Store.embyToken != null && Store.embyServer != null && Store.embyUserId != null

    fun isEmby(id: String?) = id?.startsWith(PREFIX) == true
    fun rawId(id: String) = id.removePrefix(PREFIX)

    private val server get() = Store.embyServer ?: ""
    private val token get() = Store.embyToken ?: ""
    private val userId get() = Store.embyUserId ?: ""

    private fun authHeader(): String =
        """Emby Client="Marquee", Device="Android TV", DeviceId="${Store.deviceId}", Version="1.0""""

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    // ---------------------------------------------------------------- HTTP

    private suspend fun request(path: String, body: JSONObject? = null, base: String = server): String? =
        withContext(Dispatchers.IO) {
            safe {
                val b = Request.Builder()
                    .url(base.trimEnd('/') + path)
                    .header("X-Emby-Authorization", authHeader())
                    .header("Accept", "application/json")
                if (token.isNotEmpty()) b.header("X-Emby-Token", token)
                if (body != null) b.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                Http.client.newCall(b.build()).execute().use { r -> if (r.isSuccessful) (r.body?.string() ?: "") else null }
            }
        }

    private suspend fun getObj(path: String): JSONObject? =
        request(path)?.let { t -> withContext(Dispatchers.Default) { safe { JSONObject(t) } } }

    private suspend fun getArr(path: String): JSONArray? =
        request(path)?.let { t -> withContext(Dispatchers.Default) { safe { JSONArray(t) } } }

    // ---------------------------------------------------------------- sign in

    /** Signs in and remembers the server. Accepts "192.168.1.10:8096", "http://…", or ".../emby". */
    suspend fun login(address: String, username: String, password: String) {
        var a = address.trim().trimEnd('/')
        if (a.isEmpty()) throw IOException("Enter your server address")
        if (!a.startsWith("http://") && !a.startsWith("https://")) a = "http://$a"
        if (!Regex("^https?://[^/]+:\\d+").containsMatchIn(a) && !a.startsWith("https://") && a.count { it == '/' } <= 2) {
            a = "$a:8096" // Emby's default port
        }
        val body = JSONObject().put("Username", username).put("Pw", password).put("Password", password)
        val candidates = if (a.endsWith("/emby")) listOf(a) else listOf(a, "$a/emby")
        for (base in candidates) {
            val res = withContext(Dispatchers.IO) {
                safe {
                    val req = Request.Builder()
                        .url("$base/Users/AuthenticateByName")
                        .header("X-Emby-Authorization", authHeader())
                        .header("Accept", "application/json")
                        .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    Http.client.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") }
                }
            } ?: continue
            if (res.first == 401) throw IOException("Wrong username or password")
            if (res.first !in 200..299) continue
            val o = safe { JSONObject(res.second) } ?: continue
            val tok = o.str("AccessToken") ?: continue
            val uid = o.obj("User")?.str("Id") ?: continue
            Store.embyServer = base
            Store.embyToken = tok
            Store.embyUserId = uid
            Store.embyUserName = o.obj("User")?.str("Name") ?: username
            return
        }
        throw IOException("Couldn't reach an Emby server at $a")
    }

    // ---------------------------------------------------------------- images

    private fun img(itemId: String?, type: String, tag: String?, size: String): String? {
        if (itemId == null || tag == null) return null
        return "$server/Items/$itemId/Images/$type?$size&quality=90&tag=$tag&api_key=$token"
    }

    private fun poster(o: JSONObject): String? {
        val type = o.str("Type")
        if (type == "Episode") {
            return img(o.str("SeriesId"), "Primary", o.str("SeriesPrimaryImageTag"), "maxHeight=600")
                ?: img(o.str("Id"), "Primary", o.obj("ImageTags")?.str("Primary"), "maxHeight=600")
        }
        return img(o.str("Id"), "Primary", o.obj("ImageTags")?.str("Primary"), "maxHeight=600")
    }

    private fun backdrop(o: JSONObject): String? {
        o.arr("BackdropImageTags").strings().firstOrNull()?.let {
            return "$server/Items/${o.str("Id")}/Images/Backdrop/0?maxWidth=1920&quality=85&tag=$it&api_key=$token"
        }
        val pid = o.str("ParentBackdropItemId")
        o.arr("ParentBackdropImageTags").strings().firstOrNull()?.let {
            return "$server/Items/$pid/Images/Backdrop/0?maxWidth=1920&quality=85&tag=$it&api_key=$token"
        }
        return null
    }

    private fun logo(o: JSONObject): String? =
        img(o.str("Id"), "Logo", o.obj("ImageTags")?.str("Logo"), "maxWidth=700")
            ?: img(o.str("ParentLogoItemId"), "Logo", o.str("ParentLogoImageTag"), "maxWidth=700")

    private fun thumb(o: JSONObject): String? =
        img(o.str("Id"), "Primary", o.obj("ImageTags")?.str("Primary"), "maxWidth=500")

    private fun ms(ticks: Long?) = (ticks ?: 0) / 10_000

    private fun youtubeId(url: String?): String? {
        if (url == null) return null
        Regex("[?&]v=([A-Za-z0-9_-]{6,})").find(url)?.let { return it.groupValues[1] }
        Regex("youtu\\.be/([A-Za-z0-9_-]{6,})").find(url)?.let { return it.groupValues[1] }
        return null
    }

    private fun appType(o: JSONObject) = when (o.str("Type")) {
        "Series", "Season", "Episode" -> "series"
        else -> "movie"
    }

    /** Title-level item (series or movie) shown as a tile. */
    private fun toMeta(o: JSONObject): MetaItem? {
        val id = o.str("Id") ?: return null
        val type = o.str("Type")
        val isEp = type == "Episode"
        val metaId = if (isEp) (o.str("SeriesId") ?: id) else id
        val runtime = ms(o.long("RunTimeTicks")).takeIf { it > 0 }?.let { "${it / 60_000} min" }
        val primaryAspect = o.optDouble("PrimaryImageAspectRatio", 0.0)
        return MetaItem(
            id = PREFIX + metaId,
            type = appType(o),
            name = (if (isEp) o.str("SeriesName") else null) ?: o.str("Name") ?: return null,
            poster = poster(o),
            background = backdrop(o),
            logo = logo(o),
            description = o.str("Overview"),
            releaseInfo = o.str("ProductionYear"),
            imdbRating = o.opt("CommunityRating")?.let { r -> (r as? Number)?.let { String.format(java.util.Locale.US, "%.1f", it.toDouble()) } },
            runtime = runtime,
            genres = o.arr("Genres").strings(),
            cast = o.arr("People").objects().filter { it.str("Type") == "Actor" }.mapNotNull { it.str("Name") },
            trailer = o.arr("RemoteTrailers").objects().firstNotNullOfOrNull { youtubeId(it.str("Url")) },
            posterShape = if (primaryAspect > 1.2) "landscape" else null,
            defaultVideoId = if (appType(o) == "movie") PREFIX + id else null,
        )
    }

    // ---------------------------------------------------------------- user data cache

    /** Emby's own resume points, so Resume works for things watched in other Emby apps. */
    data class UserPos(val position: Long, val duration: Long, val played: Boolean)
    val userData = HashMap<String, UserPos>()
    /** IMDb-style id ("tt123:1:2") per Emby episode, for skip-intro lookups. */
    val introKeys = HashMap<String, String>()

    private fun remember(o: JSONObject) {
        val id = o.str("Id") ?: return
        val ud = o.obj("UserData") ?: return
        userData[PREFIX + id] = UserPos(ms(ud.long("PlaybackPositionTicks")), ms(o.long("RunTimeTicks")), ud.optBoolean("Played"))
    }

    private const val FIELDS = "Overview,Genres,PrimaryImageAspectRatio,ProviderIds,People,RemoteTrailers,CommunityRating,ProductionYear,RunTimeTicks,MediaSources"

    // ---------------------------------------------------------------- rows

    suspend fun libraries(): List<Library> =
        getObj("/Users/$userId/Views")?.arr("Items").objects().mapNotNull { o ->
            val id = o.str("Id") ?: return@mapNotNull null
            val type = o.str("CollectionType")
            if (type != null && type !in listOf("movies", "tvshows", "homevideos", "mixed")) return@mapNotNull null
            Library(id, o.str("Name") ?: "Library", type)
        }

    /** Episodes/movies in progress, as tiles that resume directly. */
    suspend fun resume(): List<CardItem> =
        getObj("/Users/$userId/Items/Resume?Limit=40&Recursive=true&MediaTypes=Video&Fields=$FIELDS&EnableImageTypes=Primary,Backdrop,Logo,Thumb")
            ?.arr("Items").objects().mapNotNull { o -> episodeCard(o, resumeBadge = false) }

    suspend fun nextUp(): List<CardItem> =
        getObj("/Shows/NextUp?UserId=$userId&Limit=40&Fields=$FIELDS&EnableImageTypes=Primary,Backdrop,Logo,Thumb")
            ?.arr("Items").objects().mapNotNull { o -> episodeCard(o, resumeBadge = true) }

    private fun episodeCard(o: JSONObject, resumeBadge: Boolean): CardItem? {
        remember(o)
        val meta = toMeta(o) ?: return null
        val vid = PREFIX + (o.str("Id") ?: return null)
        val pos = ms(o.obj("UserData")?.long("PlaybackPositionTicks"))
        val dur = ms(o.long("RunTimeTicks"))
        val label = if (o.str("Type") == "Episode") {
            val s = o.int("ParentIndexNumber"); val e = o.int("IndexNumber")
            if (s != null && e != null) "S$s:E$e" else null
        } else null
        return CardItem(
            meta = meta,
            videoId = vid,
            progress = if (dur > 0 && pos > 0) (pos.toFloat() / dur).coerceIn(0.02f, 1f) else null,
            badge = label?.let { if (resumeBadge && pos <= 0) "NEXT · $it" else it },
        )
    }

    suspend fun latest(libraryId: String): List<CardItem> =
        getArr("/Users/$userId/Items/Latest?ParentId=$libraryId&Limit=40&Fields=$FIELDS&EnableImageTypes=Primary,Backdrop,Logo")
            ?.objects()?.mapNotNull { o -> toMeta(o)?.let { CardItem(it) } } ?: emptyList()

    suspend fun search(query: String): List<MetaItem> =
        getObj("/Users/$userId/Items?SearchTerm=${enc(query)}&IncludeItemTypes=Movie,Series&Recursive=true&Limit=40&Fields=$FIELDS")
            ?.arr("Items").objects().mapNotNull { toMeta(it) }

    // ---------------------------------------------------------------- detail

    suspend fun meta(id: String): MetaItem? {
        val raw = rawId(id)
        val o = getObj("/Users/$userId/Items/$raw?Fields=$FIELDS") ?: return null
        remember(o)
        val base = toMeta(o) ?: return null
        if (o.str("Type") != "Series") return base
        val imdb = o.obj("ProviderIds")?.str("Imdb")
        val eps = getObj("/Shows/$raw/Episodes?UserId=$userId&Fields=Overview,RunTimeTicks&EnableImageTypes=Primary")
            ?.arr("Items").objects() ?: emptyList()
        val videos = eps.mapNotNull { e ->
            remember(e)
            val eid = e.str("Id") ?: return@mapNotNull null
            val season = e.int("ParentIndexNumber") ?: 0
            val number = e.int("IndexNumber") ?: 0
            if (imdb != null && season > 0 && number > 0) introKeys[PREFIX + eid] = "$imdb:$season:$number"
            Video(
                id = PREFIX + eid,
                title = e.str("Name") ?: "Episode $number",
                season = season,
                episode = number,
                thumbnail = thumb(e),
                overview = e.str("Overview"),
            )
        }
        return base.copy(videos = videos)
    }

    // ---------------------------------------------------------------- playback

    /** Direct play of the original file (no transcoding), described so quality badges work. */
    suspend fun streams(videoId: String): List<StreamItem> {
        val raw = rawId(videoId)
        val o = getObj("/Users/$userId/Items/$raw?Fields=MediaSources") ?: return emptyList()
        val sources = o.arr("MediaSources").objects()
        val list = if (sources.isEmpty()) listOf(JSONObject().put("Id", raw)) else sources
        return list.map { src ->
            val sid = src.str("Id") ?: raw
            val streams = src.arr("MediaStreams").objects()
            val v = streams.firstOrNull { it.str("Type") == "Video" }
            val a = streams.firstOrNull { it.str("Type") == "Audio" && it.optBoolean("IsDefault", false) }
                ?: streams.firstOrNull { it.str("Type") == "Audio" }
            val parts = mutableListOf<String>()
            v?.int("Height")?.let { h -> parts += if (h >= 1600) "2160p" else if (h >= 1000) "1080p" else if (h >= 700) "720p" else "${h}p" }
            v?.str("Codec")?.let { parts += it.uppercase() }
            v?.str("VideoRange")?.let { r -> if (!r.equals("SDR", true)) parts += r }
            v?.str("ExtendedVideoType")?.let { t -> if (t.contains("Dolby", true)) parts += "Dolby Vision" }
            a?.str("DisplayTitle")?.let { parts += it }
            src.long("Size")?.let { parts += String.format(java.util.Locale.US, "%.1f GB", it / 1_073_741_824.0) }
            val subs = streams.filter { it.str("Type") == "Subtitle" && it.optBoolean("IsExternal", false) }.mapNotNull { st ->
                val idx = st.int("Index") ?: return@mapNotNull null
                SubTrack(
                    "$server/Videos/$raw/$sid/Subtitles/$idx/Stream.srt?api_key=$token",
                    st.str("Language") ?: "und",
                    (st.str("DisplayTitle") ?: st.str("Language") ?: "Subtitles") + " · Emby",
                )
            }
            StreamItem(
                name = "Emby\n" + (src.str("Name") ?: "Direct play"),
                title = parts.joinToString(" · "),
                url = "$server/Videos/$raw/stream?static=true&MediaSourceId=$sid&api_key=$token",
                headers = emptyMap(),
                subtitles = subs,
            )
        }
    }

    private val started = HashSet<String>()

    /** Tells Emby where you are, so its own apps resume at the same spot and mark episodes watched. */
    suspend fun report(videoId: String, positionMs: Long, finished: Boolean, stopped: Boolean) {
        val raw = rawId(videoId)
        val ticks = positionMs * 10_000
        val body = JSONObject().put("ItemId", raw).put("PositionTicks", ticks).put("IsPaused", false)
            .put("PlayMethod", "DirectPlay").put("CanSeek", true)
        if (started.add(raw)) request("/Sessions/Playing", body)
        if (stopped || finished) {
            request("/Sessions/Playing/Stopped", body)
            started.remove(raw)
            if (finished) request("/Users/$userId/PlayedItems/$raw", JSONObject())
        } else {
            request("/Sessions/Playing/Progress", body)
        }
        userData[videoId] = UserPos(if (finished) 0 else positionMs, userData[videoId]?.duration ?: 0, finished)
    }
}
