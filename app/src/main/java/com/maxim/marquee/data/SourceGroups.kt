package com.maxim.marquee.data

import kotlin.math.roundToInt

/**
 * Turns a long list of sources into a few clear choices: the same file offered several times is
 * shown once, and what's left is sorted into quality sections (best for this TV first).
 */
object SourceGroups {
    /** [tier] is the quality the filter buttons go by ("4k", "1080"…); "" = always shown. */
    class Group(val key: String, val title: String, val note: String?, val items: List<StreamItem>, val tier: String)

    private val FILE_LIKE = Regex("""\d{3,4}p|\.mkv|\.mp4|s\d{1,2}e\d{1,3}|\b(19|20)\d\d\b""")

    /** The same file listed more than once (by several addons, or several servers) is kept once. */
    fun dedupe(list: List<StreamItem>): List<StreamItem> {
        val seen = HashSet<String>()
        return list.filter { s ->
            if (s.embyId != null) return@filter true
            val q = s.quality
            val size = q.sizeMb?.let { (it / 10).roundToInt() }
            // Only a real file name identifies a file (some addons put the show's title there).
            val byName = !FILE_LIKE.containsMatchIn(s.sourceKey) || seen.add("n|${s.sourceKey}|$size")
            // Same release group, quality and size (to within 10 MB): the same file under another name.
            val g = Episodes.releaseGroup(s)
            val bySize = if (size == null || g.isEmpty()) true else seen.add("s|$g|${q.res}|${q.dv}|${q.hdr}|$size")
            byName && bySize
        }
    }

    private fun tier(s: StreamItem): Pair<String, String> {
        val q = s.quality
        return when {
            q.res >= 2160 -> "4k" to "4K"
            q.res >= 1080 -> "1080" to "1080p"
            q.res >= 720 -> "720" to "720p"
            q.res > 0 -> "sd" to "SD"
            else -> "other" to "Unknown quality"
        }
    }

    private val TIERS = listOf("4k", "1080", "720", "sd", "other")
    private val KINDS = listOf("Blu-ray Remux", "Blu-ray", "WEB", "TV recording", null, "Cinema recording")

    /**
     * Sections a person can scan: the best pick alone on top, your server's copies, then one
     * section per quality and kind of release ("4K · Blu-ray Remux", "4K · WEB", "1080p · Blu-ray"…),
     * always in the same order, biggest file first inside each. [ranked] is the order the TV
     * handles best (its first one is the best pick); [wrong] ones (another episode, by their
     * name) go in a last section of their own.
     */
    fun group(ranked: List<StreamItem>, wrong: Set<StreamItem> = emptySet()): List<Group> {
        val clean = dedupe(ranked)
        val groups = ArrayList<Group>()
        clean.firstOrNull { it !in wrong }?.let { groups += Group("best", "Best match", "What Play picks by itself", listOf(it), "") }
        clean.filter { it.embyId != null && it !in wrong }.takeIf { it.isNotEmpty() }?.let {
            groups += Group("server", "On your server", null, it, "")
        }
        val rest = clean.filter { it.embyId == null && it !in wrong }
        val byTier = rest.groupBy { tier(it) }
        for (t in TIERS) {
            val (key, list) = byTier.entries.firstOrNull { it.key.first == t } ?: continue
            val byKind = list.groupBy { kind(it) }
            for (k in KINDS) {
                val items = byKind[k] ?: continue
                groups += Group(
                    "$t|$k", key.second + "  ·  " + (k ?: "Other"), null,
                    items.sortedByDescending { it.quality.sizeMb ?: -1.0 }, t,
                )
            }
        }
        val w = clean.filter { it in wrong }
        if (w.isNotEmpty()) groups += Group("wrong", "Probably another episode", "Their file names point to a different episode", w, "")
        return groups
    }

    private val TAGS = listOf(
        "imax" to "IMAX", "extended" to "Extended", "directors cut" to "Director's Cut", "director's cut" to "Director's Cut",
        "hybrid" to "Hybrid", "remastered" to "Remastered", "open matte" to "Open Matte", "unrated" to "Unrated",
        "theatrical" to "Theatrical", "3d" to "3D",
    )

    private fun words(s: StreamItem) =
        java.text.Normalizer.normalize("${s.fileName ?: ""} ${s.title} ${s.name}", java.text.Normalizer.Form.NFKC).lowercase().replace('.', ' ')

    private fun has(t: String, k: String) = Regex("(^|[^a-z0-9])" + Regex.escape(k) + "($|[^a-z0-9])").containsMatchIn(t)

    /** The cut or version, when the name says: IMAX, Extended, Hybrid… */
    fun tags(s: StreamItem): List<String> {
        val t = words(s)
        return TAGS.filter { (k, _) -> has(t, k) }.map { it.second }.distinct()
    }

    /** HEVC / H.264 / AV1, when the name says. */
    fun codec(s: StreamItem): String? {
        val t = words(s)
        return when {
            has(t, "av1") -> "AV1"
            has(t, "hevc") || has(t, "x265") || has(t, "h265") || has(t, "h 265") -> "HEVC"
            has(t, "x264") || has(t, "h264") || has(t, "h 264") || has(t, "avc") -> "H.264"
            else -> null
        }
    }

    private val KIND = listOf(
        "remux" to "Blu-ray Remux", "bluray" to "Blu-ray", "blu-ray" to "Blu-ray", "blu ray" to "Blu-ray", "bdrip" to "Blu-ray", "brrip" to "Blu-ray",
        "web-dl" to "WEB", "webdl" to "WEB", "web dl" to "WEB", "webrip" to "WEB", "web" to "WEB", "uhd" to "Blu-ray", "bd" to "Blu-ray", "hdtv" to "TV recording",
        "telesync" to "Cinema recording", "hdcam" to "Cinema recording", "cam" to "Cinema recording",
    )

    /** Where the file comes from, in plain words ("Blu-ray Remux", "WEB"…), or null. */
    fun kind(s: StreamItem): String? {
        val t = words(s)
        return KIND.firstOrNull { (k, _) -> has(t, k) }?.second
    }
}
