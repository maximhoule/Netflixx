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

    fun signOut() {
        prefs.edit().remove("authKey").remove("addons").apply()
    }

    // ---- local watch progress (works even for titles not in the Stremio library) ----

    private val lock = Any()

    private fun progressJson(): JSONObject =
        safe { JSONObject(prefs.getString("progress", null) ?: "{}") } ?: JSONObject()

    fun saveProgress(p: LocalProgress) = synchronized(lock) {
        val all = progressJson()
        all.put(
            p.videoId,
            JSONObject()
                .put("metaId", p.metaId).put("type", p.type).put("name", p.name)
                .put("poster", p.poster ?: JSONObject.NULL)
                .put("position", p.position).put("duration", p.duration).put("updated", p.updated),
        )
        if (all.length() > 400) {
            val oldest = all.keys().asSequence().toList()
                .sortedBy { all.optJSONObject(it)?.optLong("updated") ?: 0 }
                .take(all.length() - 400)
            oldest.forEach { all.remove(it) }
        }
        prefs.edit().putString("progress", all.toString()).apply()
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

    fun progress(videoId: String): LocalProgress? = synchronized(lock) {
        parseProgress(videoId, progressJson().optJSONObject(videoId))
    }

    fun allProgress(): List<LocalProgress> = synchronized(lock) {
        val all = progressJson()
        all.keys().asSequence().mapNotNull { parseProgress(it, all.optJSONObject(it)) }.toList()
    }

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
        return safe { Locale(base).isO3Language }?.takeIf { it.isNotBlank() } ?: base
    }

    fun wantsSubtitle(lang: String): Boolean {
        val pref = subLang
        return pref.isBlank() || normLang(lang) == normLang(pref)
    }
}
