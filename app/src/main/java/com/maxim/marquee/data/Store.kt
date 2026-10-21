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

data class Profile(val id: Int, val name: String, val color: Int)

object Store {
    /** The active profile's settings, account and progress. Profile 0 keeps the original file. */
    private lateinit var prefs: SharedPreferences
    /** Shared by every profile: the profile list and device-wide settings. */
    private lateinit var global: SharedPreferences
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        global = ctx.getSharedPreferences("marquee_global", Context.MODE_PRIVATE)
        prefs = prefsFor(activeProfileId)
    }

    private fun prefsFor(id: Int): SharedPreferences =
        appCtx.getSharedPreferences(if (id == 0) "marquee" else "marquee_p$id", Context.MODE_PRIVATE)

    // ---- profiles ----

    /** Avatar colours, Netflix-style. */
    val profileColors = listOf(0xFFE21B2D, 0xFF2E7DF6, 0xFFF5B50E, 0xFF22A35A, 0xFF8E44D9, 0xFFEE6A1F, 0xFF16A6B8).map { it.toInt() }

    @Volatile private var profilesCache: List<Profile>? = null

    val profiles: List<Profile>
        get() = profilesCache ?: readProfiles().also { profilesCache = it }

    private fun readProfiles(): List<Profile> {
        run {
            val arr = safe { org.json.JSONArray(global.getString("profiles", null) ?: "[]") } ?: org.json.JSONArray()
            val list = arr.objects().mapNotNull { o ->
                val id = o.int("id") ?: return@mapNotNull null
                Profile(id, o.str("name") ?: "Profile", o.optInt("color", profileColors[0]))
            }
            return list.ifEmpty { listOf(Profile(0, defaultName(), profileColors[0])) }
        }
    }

    private fun defaultName(): String = "Me"

    private fun saveProfiles(list: List<Profile>) {
        val arr = org.json.JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)) }
        global.edit().putString("profiles", arr.toString()).apply()
        profilesCache = list
    }

    var activeProfileId: Int
        get() = global.getInt("activeProfile", 0)
        private set(v) = global.edit().putInt("activeProfile", v).apply()

    val activeProfile: Profile get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()

    fun addProfile(name: String, color: Int): Profile {
        val list = profiles
        val p = Profile((list.maxOfOrNull { it.id } ?: 0) + 1, name.trim().ifEmpty { "Profile" }, color)
        saveProfiles(list + p)
        return p
    }

    fun updateProfile(p: Profile) = saveProfiles(profiles.map { if (it.id == p.id) p else it })

    /** Deletes a profile and everything it stored. The last remaining profile can't be deleted. */
    fun deleteProfile(id: Int) {
        val list = profiles
        if (list.size <= 1) return
        saveProfiles(list.filterNot { it.id == id })
        prefsFor(id).edit().clear().apply()
        synchronized(timeMaps) { timeMaps.keys.removeAll { it.startsWith("$id/") } }
        if (activeProfileId == id) switchProfile(list.first { it.id != id }.id)
    }

    fun switchProfile(id: Int) {
        activeProfileId = id
        prefs = prefsFor(id)
        synchronized(lock) { cache = null }
    }

    // ---- last known Stremio library, so Continue Watching shows instantly on launch ----

    private fun libraryFile() = java.io.File(appCtx.filesDir, "library_$activeProfileId.json")

    fun saveLibraryCache(json: String) {
        safe {
            val f = libraryFile()
            val tmp = java.io.File(f.path + ".tmp")
            tmp.writeText(json)
            tmp.renameTo(f)
        }
    }

    fun loadLibraryCache(): String? = safe { libraryFile().takeIf { it.exists() }?.readText() }

    /** Reads saved progress into memory ahead of time (off the main thread). */
    fun warmUp() {
        synchronized(lock) { loaded() }
        profiles
    }

    /** Raw addon list stored by another profile (for "copy addons"). */
    fun addonsJsonOf(id: Int): String? = prefsFor(id).getString("addons", null)
    fun authKeyOf(id: Int): String? = prefsFor(id).getString("authKey", null)

    /** Device-wide value, falling back to what older builds stored in the first profile. */
    private fun globalString(k: String): String? = global.getString(k, null) ?: prefsFor(0).getString(k, null)
    private fun setGlobalString(k: String, v: String?) = global.edit().putString(k, v).apply()

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
        get() = global.getBoolean("autoUpdate", prefsFor(0).getBoolean("autoUpdate", true))
        set(v) = global.edit().putBoolean("autoUpdate", v).apply()

    /** Netflix-style cards that widen into a preview when you rest on them. */
    var expandCards: Boolean
        get() = global.getBoolean("expandCards", true)
        set(v) = global.edit().putBoolean("expandCards", v).apply()

    /** Background trailers on the home billboard: 0 off, 1 muted, 2 with sound. */
    var trailerMode: Int
        get() = prefs.getInt("trailerMode", 1)
        set(v) = prefs.edit().putInt("trailerMode", v).apply()

    /** Row titles you chose to hide (lower case). */
    var hiddenRows: Set<String>
        get() = safe { org.json.JSONArray(string("hiddenRows") ?: "[]").strings().toSet() } ?: emptySet()
        set(v) = setString("hiddenRows", org.json.JSONArray(v.toList()).toString())

    // Read often (every episode tile asks), so kept in memory per profile.
    private val timeMaps = HashMap<String, Map<String, Long>>()

    private fun timeMap(key: String): Map<String, Long> = synchronized(timeMaps) {
        timeMaps.getOrPut("$activeProfileId/$key") {
            safe {
                val o = org.json.JSONObject(string(key) ?: "{}")
                o.keys().asSequence().associateWith { o.optLong(it) }
            } ?: emptyMap()
        }
    }

    private fun putTime(key: String, id: String, t: Long) = synchronized(timeMaps) {
        // Keep the newest 300 so the list can't grow forever.
        val m = (timeMap(key) + (id to t)).entries.sortedByDescending { it.value }.take(300).associate { it.key to it.value }
        timeMaps["$activeProfileId/$key"] = m
        setString(key, org.json.JSONObject().apply { m.forEach { put(it.key, it.value) } }.toString())
    }

    /** Titles taken off Continue Watching, and when: they come back only if you watch them again after that. */
    val continueHidden: Map<String, Long> get() = timeMap("continueHidden")
    fun hideFromContinue(metaId: String) = putTime("continueHidden", metaId, System.currentTimeMillis())

    /** Shows/movies you marked as watched, and when: every episode counts as watched unless played again after. */
    val markedWatched: Map<String, Long> get() = timeMap("markedWatched")
    fun markWatched(metaId: String) = putTime("markedWatched", metaId, System.currentTimeMillis())

    /** Emby "New in …" rows: 0 off (default), 1 on the Series and Movies tabs, 2 everywhere. */
    var embyRows: Int
        get() = prefs.getInt("embyRows", 0)
        set(v) = prefs.edit().putInt("embyRows", v).apply()

    /** Sports recap / highlight rows (hidden unless turned on). */
    var showRecaps: Boolean
        get() = bool("showRecaps", false)
        set(v) = setBool("showRecaps", v)

    /** Send Dolby / DTS audio untouched to the TV or soundbar when it supports it (otherwise decoded here). */
    /**
     * DTS and Dolby TrueHD are converted here (to regular multichannel sound) instead of being sent
     * to the TV, which many TVs accept but can't actually play (silence). On by default.
     */
    var convertDts: Boolean
        get() = bool("convertDts", true)
        set(v) = setBool("convertDts", v)

    var passthrough: Boolean
        get() = bool("passthrough", true)
        set(v) = setBool("passthrough", v)

    /** Night mode: evens out loud and quiet scenes and lifts voices. */
    var nightMode: Boolean
        get() = bool("nightMode", false)
        set(v) = setBool("nightMode", v)

    /** Audio and subtitle choices remembered per show. */
    data class ShowPrefs(val audioLang: String?, val subLang: String?, val subsOn: Boolean?)

    fun showPrefs(metaId: String): ShowPrefs? = safe {
        val o = JSONObject(prefs.getString("showPrefs", null) ?: return@safe null).optJSONObject(metaId) ?: return@safe null
        ShowPrefs(o.str("audio"), o.str("sub"), if (o.has("subsOn")) o.optBoolean("subsOn") else null)
    }

    fun saveShowPrefs(metaId: String, audioLang: String? = null, subLang: String? = null, subsOn: Boolean? = null) {
        safe {
            val all = JSONObject(prefs.getString("showPrefs", null) ?: "{}")
            val o = all.optJSONObject(metaId) ?: JSONObject()
            audioLang?.let { o.put("audio", it) }
            subLang?.let { o.put("sub", it) }
            subsOn?.let { o.put("subsOn", it) }
            o.put("t", System.currentTimeMillis())
            all.put(metaId, o)
            // Keep the 300 most recent shows.
            if (all.length() > 300) {
                val oldest = all.keys().asSequence().toList().sortedBy { all.optJSONObject(it)?.optLong("t") ?: 0 }
                oldest.take(all.length() - 300).forEach { all.remove(it) }
            }
            prefs.edit().putString("showPrefs", all.toString()).apply()
        }
    }

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
    /** Last known Emby libraries (so Home's rows don't rebuild when the server answers). */
    var embyLibrariesJson: String?
        get() = string("embyLibraries")
        set(v) = setString("embyLibraries", v)
    val deviceId: String
        get() = globalString("deviceId") ?: java.util.UUID.randomUUID().toString().also { setGlobalString("deviceId", it) }

    // ---- Trakt / TMDB ----
    // The Trakt API app keys are shared; each profile approves its own Trakt account.
    var traktClientId: String?
        get() = globalString("traktClientId")
        set(v) = setGlobalString("traktClientId", v)
    var traktSecret: String?
        get() = globalString("traktSecret")
        set(v) = setGlobalString("traktSecret", v)
    var traktAccess: String?
        get() = string("traktAccess")
        set(v) = setString("traktAccess", v)
    var traktRefresh: String?
        get() = string("traktRefresh")
        set(v) = setString("traktRefresh", v)
    var traktExpires: Long
        get() = prefs.getLong("traktExpires", 0)
        set(v) = prefs.edit().putLong("traktExpires", v).apply()
    var tmdbKey: String?
        get() = globalString("tmdbKey")
        set(v) = setGlobalString("tmdbKey", v)

    fun traktSignOut() {
        prefs.edit().remove("traktAccess").remove("traktRefresh").remove("traktExpires").apply()
    }

    fun embySignOut() {
        prefs.edit().remove("embyServer").remove("embyToken").remove("embyUserId").remove("embyUserName").remove("embyLibraries").apply()
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
