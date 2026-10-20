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

    /**
     * Every address worth trying for what you typed: a full server URL ("https://emby.example.com",
     * "http://myserver.duckdns.org:8096"), a link copied from the Emby web app ("…/web/index.html#!/home"),
     * or a plain address ("192.168.1.20:8096", "emby.example.com"). With and without "/emby".
     */
    fun candidates(input: String): List<String> {
        var a = input.trim().trimEnd('/')
        if (a.isEmpty()) return emptyList()
        // Links copied from the browser: drop the web-app part, query and #fragment.
        a = a.substringBefore('#').substringBefore('?')
        Regex("(?i)/web(/.*)?$").find(a)?.let { a = a.substring(0, it.range.first) }
        a = a.trimEnd('/')
        val hasScheme = a.startsWith("http://", true) || a.startsWith("https://", true)
        val hostPart = a.substringAfter("://").substringBefore('/')
        val hasPort = Regex(":\\d+$").containsMatchIn(hostPart)
        val path = a.substringAfter("://", a).let { if (it.contains('/')) "/" + it.substringAfter('/') else "" }
        val host = hostPart
        val bases = mutableListOf<String>()
        when {
            hasScheme && hasPort -> bases += a
            hasScheme -> {
                bases += a
                // Server URL without a port: also try Emby's own default ports.
                if (a.startsWith("https://", true)) bases += "https://$host:8920$path" else bases += "http://$host:8096$path"
            }
            hasPort -> {
                bases += "http://$host$path"
                bases += "https://$host$path"
            }
            else -> {
                // A bare name: a domain usually sits behind HTTPS; a local IP usually on port 8096.
                val looksLocal = Regex("^(\\d+\\.){3}\\d+$").matches(host) || !host.contains('.')
                if (looksLocal) {
                    bases += "http://$host:8096$path"
                    bases += "http://$host$path"
                } else {
                    bases += "https://$host$path"
                    bases += "http://$host:8096$path"
                    bases += "http://$host$path"
                    bases += "https://$host:8920$path"
                }
            }
        }
        return bases.flatMap { b -> if (b.endsWith("/emby", true)) listOf(b) else listOf(b, "$b/emby") }.distinct()
    }

    /** Why one address didn't work, in words. Higher [rank] = more telling (shown to you first). */
    private class Miss(val rank: Int, val why: String)

    private fun describe(e: Throwable, base: String): Miss {
        val host = base.substringAfter("://").substringBefore('/')
        return when (e) {
            is java.net.UnknownHostException -> Miss(1, "couldn't find $host (check the spelling, or that the TV has internet)")
            is javax.net.ssl.SSLException, is java.security.cert.CertificateException ->
                Miss(3, "$host answered, but its HTTPS certificate isn't trusted (self-signed?). Try http:// with port 8096")
            is java.net.SocketTimeoutException -> Miss(2, "$host didn't answer in time (is the server on, and reachable from the TV's network?)")
            is java.net.ConnectException -> Miss(2, "$host refused the connection (wrong port, or the server is off)")
            else -> Miss(1, "$host: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private val signInClient by lazy {
        // Short timeouts per try, so a wrong guess doesn't keep you waiting.
        Http.client.newBuilder().cache(null)
            .connectTimeout(6, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** One sign-in call: (HTTP code, body), or the network error. */
    private suspend fun call(req: Request): Result<Pair<Int, String>> = withContext(Dispatchers.IO) {
        runCatching { signInClient.newCall(req).execute().use { r -> r.code to (r.body?.string() ?: "") } }
    }

    private fun save(base: String, token: String, userId: String, name: String) {
        Store.embyServer = base
        Store.embyToken = token
        Store.embyUserId = userId
        Store.embyUserName = name
    }

    /**
     * Signs in and remembers the server. Accepts a server URL or an address (see [candidates]).
     * With no address, or when the server rejects an email address, signs in through Emby Connect
     * (your emby.media account), which also finds the server for you.
     */
    suspend fun login(address: String, username: String, password: String) {
        val looksLikeEmail = username.contains('@')
        if (address.isBlank()) {
            if (username.isBlank()) throw IOException("Enter your Emby Connect email, or your server URL and username")
            connectLogin(username, password, null)
            return
        }
        val tries = candidates(address)
        val body = JSONObject().put("Username", username).put("Pw", password).put("Password", password)
        var wrongPassword = false
        var best: Miss? = null
        fun miss(m: Miss) { if (best == null || m.rank > best!!.rank) best = m }
        for (base in tries) {
            val req = Request.Builder()
                .url("$base/Users/AuthenticateByName")
                .header("X-Emby-Authorization", authHeader())
                .header("Accept", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            val res = call(req).getOrElse { miss(describe(it, base)); null } ?: continue
            if (res.first == 401) { wrongPassword = true; continue }
            if (res.first !in 200..299) {
                // 404 just means "not at this path" (we try a few); anything else is worth showing.
                miss(Miss(if (res.first == 404) 1 else 4, "${base.substringAfter("://").substringBefore('/')} answered HTTP ${res.first}"))
                continue
            }
            val o = safe { JSONObject(res.second) }
            val tok = o?.str("AccessToken")
            val uid = o?.obj("User")?.str("Id")
            if (tok == null || uid == null) { miss(Miss(1, "$base isn't an Emby server")); continue }
            save(base, tok, uid, o?.obj("User")?.str("Name") ?: username)
            return
        }
        if (wrongPassword && looksLikeEmail) {
            // An Emby Connect email isn't a server username: sign in through Emby Connect instead.
            connectLogin(username, password, address)
            return
        }
        if (wrongPassword) throw IOException("Wrong username or password. Use your Emby server username (not your email), or leave Server URL empty and sign in with your Emby Connect email.")
        throw IOException("Couldn't connect to ${input(address)}: ${best?.why ?: "no answer"}.")
    }

    /**
     * Emby Connect: sign in with your emby.media account, list the servers linked to it, and
     * exchange the server's key for a sign-in on that server. Tries [preferred] first, then the
     * server's local address, then its remote one.
     */
    private suspend fun connectLogin(email: String, password: String, preferred: String?) {
        val app = "Marquee/1.0"
        val form = okhttp3.FormBody.Builder().add("nameOrEmail", email).add("rawpw", password).build()
        val auth = call(
            Request.Builder().url("https://connect.emby.media/service/user/authenticate")
                .header("X-Application", app).header("Accept", "application/json").post(form).build(),
        ).getOrElse { throw IOException("Couldn't reach Emby Connect (${describe(it, "https://connect.emby.media").why})") }
        if (auth.first == 401 || auth.first == 400) throw IOException("Wrong Emby Connect email or password")
        if (auth.first !in 200..299) throw IOException("Emby Connect answered HTTP ${auth.first}. Try again in a minute.")
        val a = safe { JSONObject(auth.second) } ?: throw IOException("Emby Connect sent an unexpected answer")
        val connectToken = a.str("AccessToken") ?: a.str("ConnectAccessToken") ?: throw IOException("Wrong Emby Connect email or password")
        val connectUser = a.obj("User")?.str("Id") ?: a.str("ConnectUserId") ?: throw IOException("Emby Connect sent an unexpected answer")
        val connectName = a.obj("User")?.str("Name") ?: email

        val list = call(
            Request.Builder().url("https://connect.emby.media/service/servers?userId=${enc(connectUser)}")
                .header("X-Application", app).header("X-Connect-UserToken", connectToken)
                .header("Accept", "application/json").build(),
        ).getOrElse { throw IOException("Couldn't get your servers from Emby Connect (${describe(it, "https://connect.emby.media").why})") }
        val servers = safe { JSONArray(list.second) }?.objects().orEmpty()
        if (servers.isEmpty()) {
            throw IOException(
                "No server is linked to this Emby Connect account. On your server: Settings → Users → your user → " +
                    "Emby Connect, enter $email, then try again.",
            )
        }
        var best: Miss? = null
        fun miss(m: Miss) { if (best == null || m.rank > best!!.rank) best = m }
        for (srv in servers) {
            val key = srv.str("AccessKey") ?: continue
            val addresses = (preferred?.let { candidates(it) }.orEmpty() +
                listOfNotNull(srv.str("LocalAddress"), srv.str("Url")).flatMap { candidates(it) }).distinct()
            for (base in addresses) {
                val res = call(
                    Request.Builder().url("$base/Connect/Exchange?format=json&ConnectUserId=${enc(connectUser)}")
                        .header("X-Emby-Token", key).header("X-Emby-Authorization", authHeader())
                        .header("Accept", "application/json").build(),
                ).getOrElse { miss(describe(it, base)); null } ?: continue
                if (res.first !in 200..299) {
                    miss(Miss(if (res.first == 404) 1 else 4, "${base.substringAfter("://").substringBefore('/')} answered HTTP ${res.first}"))
                    continue
                }
                val o = safe { JSONObject(res.second) } ?: continue
                val tok = o.str("AccessToken") ?: continue
                val uid = o.str("LocalUserId") ?: continue
                save(base, tok, uid, connectName)
                return
            }
        }
        val names = servers.mapNotNull { it.str("Name") }.joinToString(", ")
        throw IOException("Signed in to Emby Connect, but couldn't reach your server${if (names.isNotEmpty()) " ($names)" else ""}: ${best?.why ?: "no address worked"}.")
    }

    private fun input(a: String) = a.trim().ifEmpty { "that address" }

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
            trailers = o.arr("RemoteTrailers").objects().mapNotNull { youtubeId(it.str("Url")) }.distinct(),
            posterShape = if (primaryAspect > 1.2) "landscape" else null,
            defaultVideoId = if (appType(o) == "movie") PREFIX + id else null,
            certification = o.str("OfficialRating"),
            added = parseIso(o.str("DateCreated")),
        )
    }

    // ---------------------------------------------------------------- one identity per title

    /** IMDb id ⇄ Emby id for every movie and series on the server. */
    private val imdbToEmby = HashMap<String, String>()
    private val embyToImdb = HashMap<String, String>()
    private var indexedAt = 0L

    private fun imdbOf(o: JSONObject): String? {
        val p = o.obj("ProviderIds") ?: return null
        return (p.str("Imdb") ?: p.str("IMDB") ?: p.str("imdb"))?.takeIf { it.startsWith("tt") }
    }

    /** Lightweight scan of the library (ids only). Refreshed at most every 30 minutes. */
    suspend fun buildIndex(force: Boolean = false) {
        if (!connected) return
        if (!force && System.currentTimeMillis() - indexedAt < 30 * 60_000 && imdbToEmby.isNotEmpty()) return
        val items = getObj(
            "/Users/$userId/Items?Recursive=true&IncludeItemTypes=Movie,Series&Fields=ProviderIds" +
                "&EnableImages=false&EnableUserData=false&Limit=20000",
        )?.arr("Items").objects()
        if (items.isEmpty()) return
        withContext(Dispatchers.Default) {
            val a = HashMap<String, String>(); val b = HashMap<String, String>()
            items.forEach { o ->
                val id = o.str("Id") ?: return@forEach
                val tt = imdbOf(o) ?: return@forEach
                a[tt] = id; b[id] = tt
            }
            synchronized(imdbToEmby) {
                imdbToEmby.clear(); imdbToEmby.putAll(a)
                embyToImdb.clear(); embyToImdb.putAll(b)
            }
        }
        indexedAt = System.currentTimeMillis()
    }

    /** True when this Stremio title (tt…) is also on your Emby server. */
    fun hasTitle(imdbId: String): Boolean = synchronized(imdbToEmby) { imdbToEmby.containsKey(imdbId.substringBefore(':')) }

    private fun imdbForEmby(embyId: String?): String? = embyId?.let { synchronized(imdbToEmby) { embyToImdb[it] } }

    /** The server's item id for an app id ("emby:123", or a merged "tt…" one), if it's on your server. */
    fun serverIdFor(metaId: String): String? =
        if (isEmby(metaId)) rawId(metaId) else synchronized(imdbToEmby) { imdbToEmby[metaId.substringBefore(':')] }

    /** Marks a whole show (every episode) or a movie as watched on your server, so it leaves Resume and Next Up there too. */
    suspend fun markPlayed(metaId: String): Boolean {
        if (!connected) return false
        val id = serverIdFor(metaId) ?: return false
        return request("/Users/$userId/PlayedItems/$id", JSONObject()) != null
    }

    /** Re-labels an Emby title with its IMDb id so it merges with the same title from Stremio. */
    private fun unify(m: MetaItem, embyId: String): MetaItem {
        val tt = imdbForEmby(embyId) ?: return m
        return m.copy(id = tt, defaultVideoId = if (m.type == "movie") tt else null)
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
        val pos = UserPos(ms(ud.long("PlaybackPositionTicks")), ms(o.long("RunTimeTicks")), ud.optBoolean("Played"))
        userData[PREFIX + id] = pos
        unifiedVideoId(o)?.let { userData[it] = pos }
    }

    /** "tt123:1:2" for an Emby episode, or "tt456" for a movie, when the IMDb id is known. */
    private fun unifiedVideoId(o: JSONObject): String? = when (o.str("Type")) {
        "Episode" -> {
            val tt = imdbForEmby(o.str("SeriesId"))
            val sn = o.int("ParentIndexNumber"); val en = o.int("IndexNumber")
            if (tt != null && sn != null && en != null) "$tt:$sn:$en" else null
        }
        "Movie" -> imdbForEmby(o.str("Id"))
        else -> null
    }

    private const val FIELDS = "Overview,Genres,PrimaryImageAspectRatio,ProviderIds,People,RemoteTrailers,CommunityRating,ProductionYear,RunTimeTicks,MediaSources,OfficialRating,DateCreated"

    // ---------------------------------------------------------------- rows

    suspend fun libraries(): List<Library> =
        getObj("/Users/$userId/Views")?.arr("Items").objects().mapNotNull { o ->
            val id = o.str("Id") ?: return@mapNotNull null
            val type = o.str("CollectionType")
            if (type != null && type !in listOf("movies", "tvshows", "homevideos", "mixed")) return@mapNotNull null
            Library(id, o.str("Name") ?: "Library", type)
        }

    /** A Continue Watching entry from Emby, with when it was last watched (for merging). */
    data class Entry(val card: CardItem, val time: Long, val position: Long, val duration: Long)

    /** Episodes/movies in progress, as tiles that resume directly. */
    suspend fun resume(): List<Entry> =
        getObj("/Users/$userId/Items/Resume?Limit=40&Recursive=true&MediaTypes=Video&Fields=$FIELDS&EnableImageTypes=Primary,Backdrop,Logo,Thumb")
            ?.arr("Items").objects().mapNotNull { o ->
                val c = episodeCard(o, resumeBadge = false) ?: return@mapNotNull null
                Entry(c, parseIso(o.obj("UserData")?.str("LastPlayedDate")), ms(o.obj("UserData")?.long("PlaybackPositionTicks")), ms(o.long("RunTimeTicks")))
            }

    /** Next unwatched episode of shows you're watching. Ranked after anything in progress. */
    suspend fun nextUp(): List<Entry> =
        getObj("/Shows/NextUp?UserId=$userId&Limit=40&Fields=$FIELDS&EnableImageTypes=Primary,Backdrop,Logo,Thumb")
            ?.arr("Items").objects().mapIndexedNotNull { i, o ->
                val c = episodeCard(o, resumeBadge = true) ?: return@mapIndexedNotNull null
                Entry(c, parseIso(o.obj("UserData")?.str("LastPlayedDate")).takeIf { it > 0 } ?: (1_000_000L - i), 1, 0)
            }

    private fun episodeCard(o: JSONObject, resumeBadge: Boolean): CardItem? {
        remember(o)
        val raw = toMeta(o) ?: return null
        val embyMetaId = if (o.str("Type") == "Episode") o.str("SeriesId") ?: o.str("Id") else o.str("Id")
        val meta = unify(raw, embyMetaId ?: "")
        val vid = unifiedVideoId(o) ?: (PREFIX + (o.str("Id") ?: return null))
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
            ?.objects()?.mapNotNull { o ->
                val id = if (o.str("Type") == "Episode") o.str("SeriesId") else o.str("Id")
                toMeta(o)?.let { CardItem(unify(it, id ?: "")) }
            }?.distinctBy { it.meta.id } ?: emptyList()

    suspend fun search(query: String): List<MetaItem> =
        getObj("/Users/$userId/Items?SearchTerm=${enc(query)}&IncludeItemTypes=Movie,Series&Recursive=true&Limit=40&Fields=$FIELDS")
            ?.arr("Items").objects().mapNotNull { o -> toMeta(o)?.let { unify(it, o.str("Id") ?: "") } }

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

    /** Emby's own metadata for a Stremio title, used if no addon can describe it. */
    suspend fun metaForImdb(imdbId: String): MetaItem? {
        val eid = synchronized(imdbToEmby) { imdbToEmby[imdbId] } ?: return null
        return meta(PREFIX + eid)
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
                name = "Emby · Your server\n" + (src.str("Name") ?: "Direct play"),
                title = parts.joinToString(" · "),
                url = "$server/Videos/$raw/stream?static=true&MediaSourceId=$sid&api_key=$token",
                headers = emptyMap(),
                subtitles = subs,
                embyId = PREFIX + raw,
            )
        }
    }

    /** Emby episode ids per series, keyed "season:episode". */
    private val episodeIndex = HashMap<String, Map<String, String>>()

    suspend fun streamsForTitle(type: String, videoId: String): List<StreamItem> {
        if (!connected) return emptyList()
        if (isEmby(videoId)) return streams(videoId)
        val p = videoId.split(":")
        val embyTitle = synchronized(imdbToEmby) { imdbToEmby[p[0]] } ?: return emptyList()
        if (type == "movie" || p.size < 3) return streams(PREFIX + embyTitle)
        val key = "${p[p.size - 2]}:${p[p.size - 1]}"
        val eps = episodeIndex[embyTitle] ?: run {
            val list = getObj("/Shows/$embyTitle/Episodes?UserId=$userId&Fields=RunTimeTicks")?.arr("Items").objects()
            val m = HashMap<String, String>()
            list.forEach { e ->
                remember(e)
                val sn = e.int("ParentIndexNumber"); val en = e.int("IndexNumber"); val id = e.str("Id")
                if (sn != null && en != null && id != null) m["$sn:$en"] = id
            }
            episodeIndex[embyTitle] = m
            m
        }
        val epId = eps[key] ?: return emptyList()
        return streams(PREFIX + epId)
    }

    /** Forget everything about the previous profile's server. */
    fun reset() {
        synchronized(imdbToEmby) { imdbToEmby.clear(); embyToImdb.clear() }
        indexedAt = 0
        userData.clear(); introKeys.clear(); episodeIndex.clear(); started.clear()
    }

    // ---------------------------------------------------------------- seek thumbnails

    private suspend fun bytes(path: String): ByteArray? = withContext(Dispatchers.IO) {
        safe {
            val req = Request.Builder().url(server.trimEnd('/') + path)
                .header("X-Emby-Authorization", authHeader())
                .header("X-Emby-Token", token)
                .build()
            Http.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }
    }

    /**
     * Preview pictures for the seek bar, if the server has made them: Jellyfin "trickplay"
     * tiles, or Emby's thumbnail (BIF) files. Null when the library has none.
     */
    suspend fun seekThumbnails(videoId: String): SeekThumbnails? {
        if (!connected) return null
        val raw = rawId(videoId)
        // Jellyfin 10.9+: per media source, per width: tile grid info.
        val item = getObj("/Users/$userId/Items/$raw?Fields=Trickplay,MediaSources")
        val tp = item?.obj("Trickplay")
        if (tp != null) {
            val sourceId: String? = tp.keys().asSequence().firstOrNull()
            val byWidth: JSONObject? = sourceId?.let { tp.obj(it) }
            val width: Int? = byWidth?.keys()?.asSequence()?.mapNotNull { it.toIntOrNull() }?.minByOrNull { kotlin.math.abs(it - 320) }
            val info: JSONObject? = if (width != null) byWidth?.obj(width.toString()) else null
            if (info != null && sourceId != null && width != null) {
                val w = info.int("Width") ?: width
                val h = info.int("Height") ?: (w * 9 / 16)
                return SeekThumbnails.Tiles(
                    interval = info.long("Interval") ?: 10_000,
                    count = info.int("ThumbnailCount") ?: 0,
                    cols = info.int("TileWidth") ?: 10,
                    rows = info.int("TileHeight") ?: 10,
                    width = w, height = h,
                    tileUrl = { index -> "$server/Videos/$raw/Trickplay/$width/$index.jpg?MediaSourceId=$sourceId&api_key=$token" },
                )
            }
        }
        // Emby: one BIF file with every thumbnail.
        val sid = item?.arr("MediaSources").objects().firstOrNull()?.str("Id")
        val bif = (if (sid != null) bytes("/Videos/$raw/index.bif?width=320&MediaSourceId=$sid") else null)
            ?: bytes("/Videos/$raw/index.bif?width=320")
            ?: return null
        return withContext(Dispatchers.Default) { SeekThumbnails.Bif.parse(bif) }
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
