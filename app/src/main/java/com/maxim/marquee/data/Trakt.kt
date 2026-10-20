package com.maxim.marquee.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Trakt: sign in with a device code, scrobble what you watch, and pull your watchlist,
 * recommendations and "related" titles. Access tokens last 24 hours (since 2025), so they
 * are refreshed automatically before every call that needs them.
 */
object Trakt {
    private const val API = "https://api.trakt.tv"
    private const val OOB = "urn:ietf:wg:oauth:2.0:oob"

    val hasKeys: Boolean get() = !Store.traktClientId.isNullOrBlank() && !Store.traktSecret.isNullOrBlank()
    val connected: Boolean get() = hasKeys && Store.traktRefresh != null

    data class DeviceCode(val deviceCode: String, val userCode: String, val url: String, val interval: Int, val expiresIn: Int)

    private val JSON = "application/json".toMediaType()

    /** Returns (HTTP status, body) or null on network failure. */
    private suspend fun call(path: String, body: JSONObject? = null, auth: Boolean = false): Pair<Int, String>? =
        withContext(Dispatchers.IO) {
            safe {
                val b = Request.Builder().url(API + path)
                    .header("Content-Type", "application/json")
                    .header("trakt-api-version", "2")
                    .header("trakt-api-key", Store.traktClientId ?: "")
                    .header("User-Agent", USER_AGENT)
                if (auth) Store.traktAccess?.let { b.header("Authorization", "Bearer $it") }
                if (body != null) b.post(body.toString().toRequestBody(JSON))
                Http.client.newCall(b.build()).execute().use { r -> r.code to (r.body?.string() ?: "") }
            }
        }

    private fun saveTokens(o: JSONObject) {
        Store.traktAccess = o.str("access_token")
        o.str("refresh_token")?.let { Store.traktRefresh = it }
        val created = o.long("created_at") ?: (System.currentTimeMillis() / 1000)
        val expires = o.long("expires_in") ?: 86_400
        Store.traktExpires = (created + expires) * 1000
    }

    // ---------------------------------------------------------------- sign in

    suspend fun deviceCode(): DeviceCode? {
        val (code, text) = call("/oauth/device/code", JSONObject().put("client_id", Store.traktClientId)) ?: return null
        if (code !in 200..299) return null
        val o = safe { JSONObject(text) } ?: return null
        return DeviceCode(
            o.str("device_code") ?: return null,
            o.str("user_code") ?: return null,
            o.str("verification_url") ?: "https://trakt.tv/activate",
            o.int("interval") ?: 5,
            o.int("expires_in") ?: 600,
        )
    }

    /** "ok", "pending", "slow", or a terminal reason ("expired", "denied", "error"). */
    suspend fun pollDevice(dc: DeviceCode): String {
        val body = JSONObject().put("code", dc.deviceCode).put("client_id", Store.traktClientId).put("client_secret", Store.traktSecret)
        val (code, text) = call("/oauth/device/token", body) ?: return "pending"
        return when (code) {
            200 -> {
                safe { saveTokens(JSONObject(text)) } ?: return "error"
                "ok"
            }
            400 -> "pending"
            429 -> "slow"
            410 -> "expired"
            418 -> "denied"
            else -> "error"
        }
    }

    /** Makes sure the access token is valid (refreshing it if it's about to expire). */
    private suspend fun ensureToken(): Boolean {
        if (!connected) return false
        if (Store.traktAccess != null && System.currentTimeMillis() < Store.traktExpires - 10 * 60_000) return true
        val body = JSONObject()
            .put("refresh_token", Store.traktRefresh).put("client_id", Store.traktClientId)
            .put("client_secret", Store.traktSecret).put("redirect_uri", OOB).put("grant_type", "refresh_token")
        val (code, text) = call("/oauth/token", body) ?: return Store.traktAccess != null
        if (code in 200..299) {
            return safe { saveTokens(JSONObject(text)); true } ?: false
        }
        if (code == 400 || code == 401) Store.traktSignOut() // refresh token revoked
        return false
    }

    private suspend fun authed(path: String, body: JSONObject? = null): Pair<Int, String>? {
        if (!ensureToken()) return null
        val r = call(path, body, auth = true)
        if (r?.first == 401) {
            Store.traktExpires = 0
            if (!ensureToken()) return null
            return call(path, body, auth = true)
        }
        return r
    }

    // ---------------------------------------------------------------- scrobble

    /** action: "start", "pause" or "stop". videoId: "tt123" (movie) or "tt123:S:E" (episode). */
    suspend fun scrobble(action: String, type: String, videoId: String, progressPct: Float) {
        if (!connected) return
        val p = videoId.split(":")
        if (!p[0].startsWith("tt")) return
        val body = JSONObject().put("progress", progressPct.coerceIn(0f, 100f).toDouble())
        if (type == "series" && p.size >= 3) {
            val s = p[p.size - 2].toIntOrNull() ?: return
            val e = p[p.size - 1].toIntOrNull() ?: return
            body.put("show", JSONObject().put("ids", JSONObject().put("imdb", p[0])))
            body.put("episode", JSONObject().put("season", s).put("number", e))
        } else {
            body.put("movie", JSONObject().put("ids", JSONObject().put("imdb", p[0])))
        }
        authed("/scrobble/$action", body)
    }

    // ---------------------------------------------------------------- lists

    private fun poster(tt: String) = "https://images.metahub.space/poster/medium/$tt/img"
    private fun background(tt: String) = "https://images.metahub.space/background/medium/$tt/img"

    private fun toMeta(o: JSONObject, type: String): MetaItem? {
        val tt = o.obj("ids")?.str("imdb") ?: return null
        return MetaItem(
            id = tt, type = type, name = o.str("title") ?: return null,
            poster = poster(tt), background = background(tt), releaseInfo = o.str("year"),
        )
    }

    private fun parseList(text: String, wrapperKey: String?, type: String): List<MetaItem> {
        val arr = safe { JSONArray(text) } ?: return emptyList()
        return arr.objects().mapNotNull { o -> (if (wrapperKey != null) o.obj(wrapperKey) else o)?.let { toMeta(it, type) } }
    }

    suspend fun watchlist(): List<MetaItem> {
        val movies = authed("/sync/watchlist/movies/added")?.takeIf { it.first == 200 }?.second
        val shows = authed("/sync/watchlist/shows/added")?.takeIf { it.first == 200 }?.second
        return withContext(Dispatchers.Default) {
            ((movies?.let { parseList(it, "movie", "movie") } ?: emptyList()) +
                (shows?.let { parseList(it, "show", "series") } ?: emptyList())).distinctBy { it.id }
        }
    }

    suspend fun recommendations(): List<MetaItem> {
        val movies = authed("/recommendations/movies?limit=25&ignore_collected=true")?.takeIf { it.first == 200 }?.second
        val shows = authed("/recommendations/shows?limit=25&ignore_collected=true")?.takeIf { it.first == 200 }?.second
        val m = movies?.let { parseList(it, null, "movie") } ?: emptyList()
        val s = shows?.let { parseList(it, null, "series") } ?: emptyList()
        // Interleave movies and shows.
        return (0 until maxOf(m.size, s.size)).flatMap { i -> listOfNotNull(m.getOrNull(i), s.getOrNull(i)) }
    }

    /** What most people are watching right now. Needs only the Client ID (no sign-in). */
    suspend fun trending(type: String, limit: Int = 10): List<MetaItem> {
        if (Store.traktClientId.isNullOrBlank()) return emptyList()
        val kind = if (type == "series") "shows" else "movies"
        val (code, text) = call("/$kind/trending?limit=$limit") ?: return emptyList()
        if (code != 200) return emptyList()
        return withContext(Dispatchers.Default) { parseList(text, if (type == "series") "show" else "movie", type) }
    }

    /** "More like this". Needs only the Client ID (no sign-in). */
    suspend fun related(type: String, imdbId: String): List<MetaItem> {
        if (Store.traktClientId.isNullOrBlank() || !imdbId.startsWith("tt")) return emptyList()
        val kind = if (type == "series") "shows" else "movies"
        val (code, text) = call("/$kind/$imdbId/related?limit=24") ?: return emptyList()
        if (code != 200) return emptyList()
        return withContext(Dispatchers.Default) { parseList(text, null, type) }
    }
}
