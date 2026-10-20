package com.maxim.marquee.data

import android.net.Uri
import org.json.JSONObject

data class Resource(val name: String, val types: List<String>, val idPrefixes: List<String>?)

data class Extra(val name: String, val isRequired: Boolean)

data class Catalog(val type: String, val id: String, val name: String, val extras: List<Extra>) {
    val browsable get() = extras.none { it.isRequired }
    val supportsSearch get() = extras.any { it.name == "search" } &&
        extras.none { it.isRequired && it.name != "search" }
    val supportsSkip get() = extras.any { it.name == "skip" }
}

data class Addon(
    val transportUrl: String,
    val id: String,
    val name: String,
    val resources: List<Resource>,
    val catalogs: List<Catalog>,
) {
    val base: String = transportUrl
        .replaceFirst("stremio://", "https://")
        .substringBefore("/manifest.json")
        .trimEnd('/')

    val isLocal: Boolean get() {
        val host = safe { Uri.parse(base).host } ?: return false
        return host == "127.0.0.1" || host == "localhost"
    }

    fun supports(resource: String, type: String, id: String): Boolean = resources.any { r ->
        r.name == resource &&
            (r.types.isEmpty() || type in r.types) &&
            (r.idPrefixes.isNullOrEmpty() || r.idPrefixes.any { id.startsWith(it) })
    }

    companion object {
        fun parse(o: JSONObject): Addon? {
            val url = o.str("transportUrl") ?: return null
            val m = o.obj("manifest") ?: return null
            val types = m.arr("types").strings()
            val prefixes = m.arr("idPrefixes")?.strings()
            val resArr = m.arr("resources")
            val resources = mutableListOf<Resource>()
            if (resArr != null) {
                for (i in 0 until resArr.length()) {
                    when (val r = resArr.opt(i)) {
                        is String -> resources += Resource(r, types, prefixes)
                        is JSONObject -> {
                            val name = r.str("name") ?: continue
                            resources += Resource(
                                name,
                                r.arr("types")?.strings() ?: types,
                                r.arr("idPrefixes")?.strings() ?: prefixes,
                            )
                        }
                    }
                }
            }
            val catalogs = m.arr("catalogs").objects().mapNotNull { c ->
                val type = c.str("type") ?: return@mapNotNull null
                val id = c.str("id") ?: return@mapNotNull null
                val extras = mutableListOf<Extra>()
                c.arr("extra").objects().forEach { e ->
                    val n = e.str("name") ?: return@forEach
                    extras += Extra(n, e.optBoolean("isRequired", false))
                }
                // Legacy manifest fields.
                c.arr("extraRequired").strings().forEach { n ->
                    if (extras.none { it.name == n }) extras += Extra(n, true)
                }
                c.arr("extraSupported").strings().forEach { n ->
                    if (extras.none { it.name == n }) extras += Extra(n, false)
                }
                Catalog(type, id, c.str("name") ?: id, extras)
            }
            return Addon(
                transportUrl = url,
                id = m.str("id") ?: url,
                name = m.str("name") ?: "Addon",
                resources = resources,
                catalogs = catalogs,
            )
        }
    }
}

data class Video(
    val id: String,
    val title: String,
    val season: Int,
    val episode: Int,
    val thumbnail: String?,
    val overview: String?,
) {
    val label get() = "S$season:E$episode"

    companion object {
        fun parse(o: JSONObject): Video? {
            val id = o.str("id") ?: return null
            val ep = o.int("episode") ?: o.int("number") ?: 0
            return Video(
                id = id,
                title = o.str("title") ?: o.str("name") ?: "Episode $ep",
                season = o.int("season") ?: 0,
                episode = ep,
                thumbnail = o.str("thumbnail"),
                overview = o.str("overview") ?: o.str("description"),
            )
        }
    }
}

data class MetaItem(
    val id: String,
    val type: String,
    val name: String,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val releaseInfo: String? = null,
    val imdbRating: String? = null,
    val runtime: String? = null,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val videos: List<Video> = emptyList(),
    val defaultVideoId: String? = null,
    /** YouTube id of a trailer, when the metadata addon provides one. */
    val trailer: String? = null,
    /** "poster" (2:3), "landscape" (16:9) or "square", as declared by the addon. */
    val posterShape: String? = null,
) {
    val isSeries get() = type != "movie" && videos.isNotEmpty()

    companion object {
        fun parse(o: JSONObject, fallbackType: String? = null): MetaItem? {
            val id = o.str("id") ?: o.str("imdb_id") ?: return null
            val name = o.str("name") ?: o.str("title") ?: return null
            return MetaItem(
                id = id,
                type = o.str("type") ?: fallbackType ?: "movie",
                name = name,
                poster = o.str("poster"),
                background = o.str("background"),
                logo = o.str("logo"),
                description = o.str("description"),
                releaseInfo = o.str("releaseInfo") ?: o.str("year"),
                imdbRating = o.str("imdbRating"),
                runtime = o.str("runtime"),
                genres = (o.arr("genres") ?: o.arr("genre")).strings(),
                cast = o.arr("cast").strings(),
                videos = o.arr("videos").objects().mapNotNull { Video.parse(it) },
                defaultVideoId = o.obj("behaviorHints")?.str("defaultVideoId"),
                trailer = o.arr("trailerStreams").objects().firstNotNullOfOrNull { it.str("ytId") }
                    ?: o.arr("trailers").objects().firstOrNull { (it.str("type") ?: "Trailer") == "Trailer" }?.str("source"),
                posterShape = o.str("posterShape"),
            )
        }
    }
}

data class SubTrack(val url: String, val lang: String, val label: String) {
    companion object {
        fun parse(o: JSONObject, source: String): SubTrack? {
            val url = o.str("url") ?: return null
            val lang = o.str("lang") ?: "und"
            return SubTrack(url, lang, "${lang.uppercase()} · $source")
        }
    }
}

/** What a source's name/description says about it: resolution, HDR, audio, size. */
data class Quality(val res: Int, val dv: Boolean, val hdr: String?, val badges: List<String>) {
    /** Dolby Vision without an HDR10 fallback layer: shows wrong colours on TVs without DV. */
    val dvOnly get() = dv && hdr == null

    companion object {
        private val SIZE = Regex("""(\d+(?:[.,]\d+)?)\s?(GB|MB|GiB|MiB)""", RegexOption.IGNORE_CASE)

        fun parse(text: String): Quality {
            val t = text.lowercase()
            fun has(vararg k: String) = k.any { Regex("(^|[^a-z0-9])" + Regex.escape(it) + "($|[^a-z0-9])").containsMatchIn(t) }
            val res = when {
                has("2160p", "4k", "uhd") -> 2160
                has("1440p", "2k") -> 1440
                has("1080p", "fhd") -> 1080
                has("720p") -> 720
                has("576p", "480p", "sd") -> 480
                else -> 0
            }
            val dv = has("dv", "dovi", "dolby vision", "dolbyvision")
            val hdr = when {
                has("hdr10+", "hdr10plus") -> "HDR10+"
                has("hdr10", "hdr") -> "HDR"
                has("hlg") -> "HLG"
                else -> null
            }
            val badges = mutableListOf<String>()
            when (res) {
                2160 -> badges += "4K"
                1440 -> badges += "1440p"
                1080 -> badges += "1080p"
                720 -> badges += "720p"
                480 -> badges += "SD"
            }
            if (dv) badges += "DV"
            hdr?.let { badges += it }
            when {
                has("atmos") -> badges += "Atmos"
                has("truehd") -> badges += "TrueHD"
                has("dts-hd", "dts-x", "dts:x", "dtsx") -> badges += "DTS-HD"
                has("dts") -> badges += "DTS"
            }
            when {
                has("7.1") -> badges += "7.1"
                has("5.1") -> badges += "5.1"
            }
            if (has("remux")) badges += "REMUX"
            SIZE.find(text)?.let { badges += it.groupValues[1].replace(',', '.') + " " + it.groupValues[2].uppercase().replace("I", "") }
            return Quality(res, dv, hdr, badges)
        }
    }
}

data class StreamItem(
    val name: String,
    val title: String,
    val url: String,
    val headers: Map<String, String>,
    val subtitles: List<SubTrack>,
) {
    val quality: Quality by lazy { Quality.parse("$name $title") }

    companion object {
        fun parse(o: JSONObject, addonName: String): StreamItem? {
            val url = o.str("url") ?: return null // torrent-only (infoHash) streams need a debrid addon
            if (!url.startsWith("http")) return null
            val headers = mutableMapOf<String, String>()
            o.obj("behaviorHints")?.obj("proxyHeaders")?.obj("request")?.let { h ->
                h.keys().forEach { k -> h.str(k)?.let { headers[k] = it } }
            }
            return StreamItem(
                name = o.str("name") ?: addonName,
                title = o.str("title") ?: o.str("description") ?: "",
                url = url,
                headers = headers,
                subtitles = o.arr("subtitles").objects().mapNotNull { SubTrack.parse(it, addonName) },
            )
        }
    }
}

data class LibEntry(
    val id: String,
    val type: String,
    val name: String,
    val poster: String?,
    val removed: Boolean,
    val temp: Boolean,
    val mtime: Long,
    val lastWatched: Long,
    val videoId: String?,
    val timeOffset: Long,
    val duration: Long,
    val posterShape: String? = null,
) {
    val inMyList get() = !removed && !temp && (type == "movie" || type == "series")

    companion object {
        fun parse(o: JSONObject): LibEntry? {
            val id = o.str("_id") ?: return null
            val st = o.obj("state")
            return LibEntry(
                id = id,
                type = o.str("type") ?: "other",
                name = o.str("name") ?: "",
                poster = o.str("poster"),
                removed = o.optBoolean("removed", false),
                temp = o.optBoolean("temp", false),
                mtime = parseIso(o.str("_mtime")),
                lastWatched = parseIso(st?.str("lastWatched")),
                videoId = st?.str("video_id"),
                timeOffset = st?.long("timeOffset") ?: 0,
                duration = st?.long("duration") ?: 0,
                posterShape = o.str("posterShape"),
            )
        }
    }
}

/** A tile on screen: a title plus optional resume info. */
data class CardItem(
    val meta: MetaItem,
    val videoId: String? = null,
    val progress: Float? = null,
    val badge: String? = null,
)
