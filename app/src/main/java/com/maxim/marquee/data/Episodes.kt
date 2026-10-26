package com.maxim.marquee.data

/**
 * Making sure the file that plays is the episode you picked, whatever the show.
 *
 * Episode numbers don't always agree between where the episode list comes from (Cinemeta / TVDB)
 * and where the video comes from (an addon's file, your Emby server): two-part episodes are often
 * one hour-long file ("Dunder Mifflin Infinity (1)" + "(2)" = one file), and some services number
 * seasons differently. These helpers read what a file really is and line it up with the list.
 */
object Episodes {
    private val SXE = Regex("(?i)\\bS(\\d{1,2})[ ._]?E(\\d{1,3})")
    private val PART = Regex(
        "(?i)\\s*(\\(\\s*(part\\s*)?(\\d+|one|two|three|i{1,3})\\s*\\)|[,:\\-–]?\\s*part\\s*(\\d+|one|two|three|i{1,3})|[,:\\-–]?\\s*pt\\.?\\s*\\d+)\\s*$",
    )
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** "Money (1), Money (2)" / "Name (1) / Name (2)": one entry standing for several parts. */
    private fun combined(t: String) = t.contains(" / ") || (t.contains(", ") && t.split(", ").count { hasPart(it) } >= 2)

    /**
     * The episode list without duplicates: the same id twice, or two entries for one episode
     * number (a merged "Money (1), Money (2)" next to "Money (1)", a missing-episode placeholder
     * next to the real file). [worse] scores entries to drop first (e.g. a server's placeholders).
     */
    fun clean(videos: List<Video>, worse: (Video) -> Int = { 0 }): List<Video> {
        val unique = videos.distinctBy { it.id }
        val keep = unique.filter { it.season > 0 && it.episode > 0 }
            .groupBy { it.season to it.episode }
            .filterValues { it.size > 1 }
            .mapValues { (_, l) -> l.minBy { worse(it) * 10 + (if (combined(it.title)) 1 else 0) } }
        if (keep.isEmpty()) return unique
        return unique.filter { v -> keep[v.season to v.episode]?.let { it === v } ?: true }
    }

    /** "Season 4 · Episode 3" read from a file / source name, if it says. */
    fun fileEpisode(s: StreamItem): Pair<Int, Int>? =
        SXE.find("${s.name} ${s.title} ${s.fileName ?: ""}")?.let {
            val sn = it.groupValues[1].toIntOrNull() ?: return null
            val en = it.groupValues[2].toIntOrNull() ?: return null
            sn to en
        }

    /** A title without its part marker: "Dunder Mifflin Infinity (2)" → "dundermifflininfinity". */
    fun baseTitle(t: String): String = t.replace(PART, "").lowercase().replace(NON_WORD, "")

    fun hasPart(t: String) = PART.containsMatchIn(t)

    /**
     * The parts of a multi-part story [v] belongs to, in the same season, in order
     * ("Name (1)", "Name (2)"…). Just [v] when it isn't one.
     */
    fun partGroup(all: List<Video>, v: Video): List<Video> {
        if (!hasPart(v.title)) return listOf(v)
        val base = baseTitle(v.title)
        if (base.isEmpty()) return listOf(v)
        val season = all.filter { it.season == v.season }.sortedBy { it.episode }
        val i = season.indexOfFirst { it.id == v.id }
        if (i < 0) return listOf(v)
        var a = i
        var b = i
        while (a > 0 && hasPart(season[a - 1].title) && baseTitle(season[a - 1].title) == base && season[a - 1].episode == season[a].episode - 1) a--
        while (b < season.size - 1 && hasPart(season[b + 1].title) && baseTitle(season[b + 1].title) == base && season[b + 1].episode == season[b].episode + 1) b++
        return season.subList(a, b + 1)
    }

    private val GENERIC = Regex("^(episode|chapter|part|ep)?\\d*$")

    /** A title specific enough to look for in a file name ("launchparty", not "episode3"). */
    private fun usable(base: String) = base.length >= 4 && !GENERIC.matches(base)

    private fun text(s: StreamItem) = "${s.name} ${s.title} ${s.fileName ?: ""}".lowercase().replace(NON_WORD, "")

    /** The file names the episode you picked ("…S04E03.Launch.Party…" for "Launch Party (1)"). */
    fun namesEpisode(s: StreamItem, v: Video): Boolean {
        val base = baseTitle(v.title)
        return usable(base) && text(s).contains(base)
    }

    /** The file names another episode of the same season instead (DVD vs TV numbering). */
    private fun namesOther(s: StreamItem, all: List<Video>, v: Video): Boolean {
        val mine = baseTitle(v.title)
        val t = text(s)
        return all.any { o ->
            if (o.season != v.season || o.id == v.id) return@any false
            val b = baseTitle(o.title)
            b != mine && b.length >= 5 && usable(b) && !mine.contains(b) && t.contains(b)
        }
    }

    /**
     * The episodes [s] holds, for playing [v]: from the file name or the server ("S04E03-E04"),
     * or worked out when the file is one merged hour for a story the list splits in parts (its
     * number is the first part's, or it carries the story's title under another number).
     */
    fun span(s: StreamItem, all: List<Video>, v: Video): IntRange? {
        s.episodeSpan?.let { return it.takeIf { r -> v.episode in r } }
        val fe = fileEpisode(s) ?: return null
        if (fe.second == v.episode && fe.first == v.season) return null
        val group = partGroup(all, v)
        if (group.size < 2) return null
        val range = group.first().episode..group.last().episode
        if (fe.first == v.season && fe.second == group.first().episode) return range
        if (namesEpisode(s, v)) return range
        return null
    }

    /** True when [s] clearly is a different episode than [v] (its name says another one). */
    fun mismatch(s: StreamItem, all: List<Video>, v: Video): Boolean {
        if (s.embyId != null) return false // your server already matched it by number and title
        if (namesEpisode(s, v)) return false
        if (namesOther(s, all, v)) return true
        val fe = fileEpisode(s) ?: return false
        if (fe.first != v.season) return true
        if (fe.second == v.episode) return false
        return span(s, all, v) == null
    }
}
