package com.maxim.marquee.data

import android.util.LruCache

/**
 * TVmaze (free, no key): episode pictures, summaries and running times for shows, used where
 * the episode list has none of its own.
 */
object TvMaze {
    data class Ep(val season: Int, val number: Int, val name: String, val image: String?, val summary: String?, val runtimeMin: Int?)

    private val cache = LruCache<String, List<Ep>>(40)
    private val tags = Regex("<[^>]+>")

    /** Every episode of the show with this IMDb id; empty if TVmaze doesn't know it. */
    suspend fun episodes(imdbId: String): List<Ep> {
        val tt = imdbId.substringBefore(':')
        if (!tt.startsWith("tt")) return emptyList()
        cache.get(tt)?.let { return it }
        val show = Http.getJson("https://api.tvmaze.com/lookup/shows?imdb=$tt", Fetch.STALE_OK) ?: return emptyList()
        val id = show.long("id") ?: return emptyList()
        val list = Http.getMapped("https://api.tvmaze.com/shows/$id?embed=episodes", Fetch.STALE_OK) { o ->
            o.obj("_embedded")?.arr("episodes").objects().mapNotNull { e ->
                val s = e.int("season") ?: return@mapNotNull null
                val n = e.int("number") ?: return@mapNotNull null
                Ep(
                    season = s,
                    number = n,
                    name = e.str("name") ?: "",
                    image = e.obj("image")?.let { it.str("original") ?: it.str("medium") }?.replace("http://", "https://"),
                    summary = e.str("summary")?.replace(tags, "")?.trim()?.takeIf { it.isNotEmpty() },
                    runtimeMin = e.int("runtime"),
                )
            }
        } ?: return emptyList()
        cache.put(tt, list)
        return list
    }

    /**
     * The TVmaze episode that is [v]: same title in the same season first (numbering can differ
     * between services, e.g. two-part episodes), else the same season and number.
     */
    fun match(all: List<Ep>, v: Video): Ep? {
        val base = Episodes.baseTitle(v.title)
        val season = all.filter { it.season == v.season }
        if (base.length >= 4) {
            season.filter { Episodes.baseTitle(it.name) == base }.let { same ->
                if (same.size == 1) return same[0]
                if (same.size > 1) {
                    // Two parts with the same name: the one with the matching part number.
                    return same.firstOrNull { it.name.equals(v.title, true) } ?: same.minBy { kotlin.math.abs(it.number - v.episode) }
                }
            }
        }
        return season.firstOrNull { it.number == v.episode }
    }
}
