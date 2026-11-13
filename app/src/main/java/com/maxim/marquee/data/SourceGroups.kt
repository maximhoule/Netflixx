package com.maxim.marquee.data

import kotlin.math.roundToInt

/**
 * Turns a long list of sources into a few clear choices: the same file offered several times is
 * shown once, and what's left is sorted into quality sections (best for this TV first).
 */
object SourceGroups {
    class Group(val key: String, val title: String, val note: String?, val items: List<StreamItem>)

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
            s.embyId != null -> "server" to "On your server"
            q.res >= 2160 && (q.dv || q.hdr != null) -> "4khdr" to "4K HDR"
            q.res >= 2160 -> "4k" to "4K"
            q.res >= 1080 -> "1080" to "1080p"
            q.res >= 720 -> "720" to "720p"
            q.res > 0 -> "sd" to "SD"
            else -> "other" to "Other"
        }
    }

    /**
     * [ranked] is already in the order the TV handles best. Sections keep that order (the first
     * section holds the best pick); [wrong] ones (another episode, by their name) go in a last
     * section of their own.
     */
    fun group(ranked: List<StreamItem>, wrong: Set<StreamItem> = emptySet()): List<Group> {
        val clean = dedupe(ranked)
        val out = LinkedHashMap<String, Pair<String, MutableList<StreamItem>>>()
        for (s in clean) {
            if (s in wrong) continue
            val (k, title) = tier(s)
            out.getOrPut(k) { title to ArrayList() }.second += s
        }
        val groups = out.map { (k, v) -> Group(k, v.first, null, v.second) }.toMutableList()
        // Your own copy always leads.
        groups.sortBy { if (it.key == "server") 0 else 1 }
        val w = clean.filter { it in wrong }
        if (w.isNotEmpty()) groups += Group("wrong", "Probably another episode", "Their file names point to a different episode", w)
        return groups
    }

    private val KIND = listOf(
        "remux" to "Blu-ray Remux", "bluray" to "Blu-ray", "blu-ray" to "Blu-ray", "bdrip" to "Blu-ray", "brrip" to "Blu-ray",
        "web-dl" to "WEB", "webdl" to "WEB", "webrip" to "WEB", "web" to "WEB", "hdtv" to "TV recording",
        "telesync" to "Cinema recording", "hdcam" to "Cinema recording", "cam" to "Cinema recording",
    )

    /** Where the file comes from, in plain words ("Blu-ray Remux", "WEB"…), or null. */
    fun kind(s: StreamItem): String? {
        val t = ("${s.fileName ?: ""} ${s.title} ${s.name}").lowercase()
        return KIND.firstOrNull { (k, _) -> Regex("(^|[^a-z0-9])" + Regex.escape(k) + "($|[^a-z0-9])").containsMatchIn(t) }?.second
    }
}
