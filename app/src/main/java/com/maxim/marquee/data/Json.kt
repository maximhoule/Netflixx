package com.maxim.marquee.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.coroutines.cancellation.CancellationException

fun JSONObject.str(key: String): String? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    if (v is JSONObject || v is JSONArray) return null
    return v?.toString()?.takeIf { it.isNotBlank() }
}

fun JSONObject.long(key: String): Long? {
    if (!has(key) || isNull(key)) return null
    return when (val v = opt(key)) {
        is Number -> v.toLong()
        is String -> v.toDoubleOrNull()?.toLong()
        else -> null
    }
}

fun JSONObject.int(key: String): Int? = long(key)?.toInt()
fun JSONObject.obj(key: String): JSONObject? = optJSONObject(key)
fun JSONObject.arr(key: String): JSONArray? = optJSONArray(key)

fun JSONArray?.objects(): List<JSONObject> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it) }
}

fun JSONArray?.strings(): List<String> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { i ->
        val v = opt(i)
        if (v == null || v == JSONObject.NULL || v is JSONObject || v is JSONArray) null
        else v.toString().takeIf { it.isNotBlank() }
    }
}

/** Runs [block], returning null on any failure but never swallowing coroutine cancellation. */
inline fun <T> safe(block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}

/** Encodes an id for an addon URL path. Colons stay readable, matching what Stremio sends. */
fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20").replace("%3A", ":")

private const val ISO = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
private const val ISO_NO_MS = "yyyy-MM-dd'T'HH:mm:ss'Z'"

private fun fmt(p: String) = SimpleDateFormat(p, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

fun parseIso(s: String?): Long {
    if (s.isNullOrBlank()) return 0
    val clean = s.replace(Regex("\\.(\\d{3})\\d*"), ".\$1")
    for (p in listOf(ISO, ISO_NO_MS)) {
        try {
            val d = fmt(p).parse(clean)
            if (d != null) return d.time
        } catch (e: Exception) {
            // try next format
        }
    }
    return 0
}

fun isoNow(): String = fmt(ISO).format(Date())

fun typeLabel(type: String): String = when (type) {
    "movie" -> "Movies"
    "series" -> "Series"
    "tv" -> "Live TV"
    "channel" -> "Channels"
    "anime" -> "Anime"
    else -> type.replaceFirstChar { it.uppercase() }
}

/** "tt123:2:5" -> "S2:E5" */
fun episodeLabel(videoId: String?): String? {
    if (videoId == null) return null
    val parts = videoId.split(":")
    if (parts.size < 3) return null
    val s = parts[parts.size - 2].toIntOrNull() ?: return null
    val e = parts[parts.size - 1].toIntOrNull() ?: return null
    return "S$s:E$e"
}
