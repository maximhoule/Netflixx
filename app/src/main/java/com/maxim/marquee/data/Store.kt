package com.maxim.marquee.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject
import java.util.Locale

data class LocalProgress(
    val metaId: String,
    val type: String,
    val name: String,
    val poster: String?,
    val videoId: String,
    val position: Long,
    val duration: Long,
    val updated: Long,
)

object Store {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("marquee", Context.MODE_PRIVATE)
    }

    private fun bool(k: String, d: Boolean) = prefs.getBoolean(k, d)
    private fun setBool(k: String, v: Boolean) = prefs.edit().putBoolean(k, v).apply()
    private fun string(k: String) = prefs.getString(k, null)
    private fun setString(k: String, v: String?) = prefs.edit().putString(k, v).apply()

    var authKey: String?
        get() = string("authKey")
        set(v) = setString("authKey", v)

    var email: String?
        get() = string("email")
        set(v) = setString("email", v)

    var addonsJson: String?
        get() = string("addons")
        set(v) = setString("addons", v)

    /** Play the top source immediately instead of showing the source list. */
    var autoplay: Boolean
        get() = bool("autoplay", true)
        set(v) = setBool("autoplay", v)

    var autoNext: Boolean
        get() = bool("autoNext", true)
        set(v) = setBool("autoNext", v)

    var syncProgress: Boolean
        get() = bool("syncProgress", true)
        set(v) = setBool("syncProgress", v)

    var subsOn: Boolean
        get() = bool("subsOn", false)
        set(v) = setBool("subsOn", v)

    var subLang: String
        get() = string("subLang") ?: "eng"
        set(v) = setString("subLang", v)

    var externalPlayer: Boolean
        get() = bool("externalPlayer", false)
        set(v) = setBool("externalPlayer", v)

    /** 0 = best available, otherwise the highest vertical resolution to auto-pick (2160/1080/720). */
    var maxQuality: Int
        get() = prefs.getInt("maxQuality", 0)
        set(v) = prefs.edit().putInt("maxQuality", v).apply()

    var autoSkipIntro: Boolean
        get() = bool("autoSkipIntro", false)
        set(v) = setBool("autoSkipIntro", v)

    var matchFrameRate: Boolean
        get() = bool("matchFrameRate", false)
        set(v) = setBool("matchFrameRate", v)

    /** 0 small, 1 medium, 2 large, 3 extra large */
    var subSize: Int
        get() = prefs.getInt("subSize", 1)
        set(v) = prefs.edit().putInt("subSize", v).apply()

    /** 0 = white with shadow (Netflix), 1 = on a dark box */
    var subStyle: Int
        get() = prefs.getInt("subStyle", 0)
        set(v) = prefs.edit().putInt("subStyle", v).apply()

    var autoUpdate: Boolean
        get() = bool("autoUpdate", true)
        set(v) = setBool("autoUpdate", v)

    /** Background trailers on the home billboard: 0 off, 1 muted, 2 with sound. */
    var trailerMode: Int
        get() = prefs.getInt("trailerMode", 1)
        set(v) = prefs.edit().putInt("trailerMode", v).apply()

    /** Last audio language picked in the player; becomes the default. */
    var audioLang: String?
        get() = string("audioLang")
        set(v) = setString("audioLang", v)

    // ---- Emby server ----
    var embyServer: String?
        get() = string("embyServer")
        set(v) = setString("embyServer", v)
    var embyToken: String?
        get() = string("embyToken")
        set(v) = setString("embyToken", v)
    var embyUserId: String?
        get() = string("embyUserId")
        set(v) = setString("embyUserId", v)
    var embyUserName: String?
        get() = string("embyUserName")
        set(v) = setString("embyUserName", v)
    val deviceId: String
        get() = string("deviceId") ?: java.util.UUID.randomUUID().toString().also { setString("deviceId", it) }

    fun embySignOut() {
        prefs.edit().remove("embyServer").remove("embyToken").remove("embyUserId").remove("embyUserName").apply()
    }

    fun signOut() {
        prefs.edit().remove("authKey").remove("addons").apply()
    }

    // ---- local watch progress (works even for titles not in the Stremio library) ----

    private val lock = Any()

    /** In-memory copy so lookups (one per episode tile) never touch disk. */
    private var cache: LinkedHashMap<String, LocalProgress>? = null

    private fun loaded(): LinkedHashMap<String, LocalProgress> {
        cache?.let { return it }
        val map = LinkedHashMap<String, LocalProgress>()
        val all = safe { JSONObject(prefs.getString("progress", null) ?: "{}") } ?: JSONObject()
        all.keys().forEach { id -> parseProgress(id, all.optJSONObject(id))?.let { map[id] = it } }
        cache = map
        return map
    }

    private fun persist(map: Map<String, LocalProgress>) {
        val all = JSONObject()
        map.values.forEach { p ->
            all.put(
                p.videoId,
                JSONObject()
                    .put("metaId", p.metaId).put("type", p.type).put("name", p.name)
                    .put("poster", p.poster ?: JSONObject.NULL)
                    .put("position", p.position).put("duration", p.duration).put("updated", p.updated),
            )
        }
        prefs.edit().putString("progress", all.toString()).apply()
    }

    fun saveProgress(p: LocalProgress) = synchronized(lock) {
        val map = loaded()
        map[p.videoId] = p
        if (map.size > 400) {
            map.values.sortedBy { it.updated }.take(map.size - 400).forEach { map.remove(it.videoId) }
        }
        persist(map)
    }

    /** Forget local progress for a title (used by "Remove from Continue Watching"). */
    fun dismiss(metaId: String) = synchronized(lock) {
        val map = loaded()
        map.values.filter { it.metaId == metaId }.map { it.videoId }.forEach { map.remove(it) }
        persist(map)
    }

    private fun parseProgress(videoId: String, o: JSONObject?): LocalProgress? {
        if (o == null) return null
        return LocalProgress(
            metaId = o.str("metaId") ?: return null,
            type = o.str("type") ?: "movie",
            name = o.str("name") ?: "",
            poster = o.str("poster"),
            videoId = videoId,
            position = o.optLong("position"),
            duration = o.optLong("duration"),
            updated = o.optLong("updated"),
        )
    }

    fun progress(videoId: String): LocalProgress? = synchronized(lock) { loaded()[videoId] }

    fun allProgress(): List<LocalProgress> = synchronized(lock) { loaded().values.toList() }

    // ---- subtitle language matching ----

    val subtitleLanguages = listOf(
        "eng" to "English", "fre" to "French", "spa" to "Spanish", "ger" to "German",
        "ita" to "Italian", "por" to "Portuguese", "dut" to "Dutch", "pol" to "Polish",
        "rus" to "Russian", "ara" to "Arabic", "tur" to "Turkish", "jpn" to "Japanese",
        "kor" to "Korean", "chi" to "Chinese", "" to "Any language",
    )

    private val bibliographic = mapOf(
        "fre" to "fra", "ger" to "deu", "dut" to "nld", "chi" to "zho", "cze" to "ces",
        "gre" to "ell", "per" to "fas", "rum" to "ron", "slo" to "slk", "alb" to "sqi",
        "arm" to "hye", "baq" to "eus", "geo" to "kat", "ice" to "isl", "mac" to "mkd",
        "may" to "msa", "wel" to "cym", "pob" to "por", "pt-br" to "por",
    )

    fun normLang(code: String): String {
        val c = code.lowercase(Locale.US).trim()
        bibliographic[c]?.let { return it }
        val base = c.substringBefore('-').substringBefore('_')
        bibliographic[base]?.let { return it }
        return safe { Locale.forLanguageTag(base).isO3Language }?.takeIf { it.isNotBlank() } ?: base
    }

    fun wantsSubtitle(lang: String): Boolean {
        val pref = subLang
        return pref.isBlank() || normLang(lang) == normLang(pref)
    }
}
