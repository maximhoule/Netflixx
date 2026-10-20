package com.maxim.marquee.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/** TMDB, used for movie collections ("The Dark Knight Collection"…). Optional; needs a free key. */
object Tmdb {
    private const val API = "https://api.themoviedb.org/3"

    val configured: Boolean get() = !Store.tmdbKey.isNullOrBlank()

    private suspend fun get(path: String): JSONObject? = withContext(Dispatchers.IO) {
        val key = Store.tmdbKey ?: return@withContext null
        safe {
            // Accept either the short v3 API key or the long "Read Access Token".
            val bearer = key.length > 50
            val url = API + path + if (bearer) "" else (if (path.contains('?')) "&" else "?") + "api_key=$key"
            val b = Request.Builder().url(url).header("Accept", "application/json")
            if (bearer) b.header("Authorization", "Bearer $key")
            Http.client.newCall(b.build()).execute().use { r ->
                if (r.isSuccessful) JSONObject(r.body?.string() ?: return@use null) else null
            }
        }
    }

    data class Collection(val name: String, val items: List<MetaItem>)

    /** Age rating and logo artwork for a title. */
    data class Extras(val certification: String?, val logo: String?, val trailers: List<String> = emptyList())

    private val extrasCache = android.util.LruCache<String, Extras>(300)

    fun cachedExtras(imdbId: String): Extras? = extrasCache.get(imdbId)

    /** Age rating for your country (falls back to the US one) and the English logo. */
    suspend fun extras(type: String, imdbId: String): Extras? {
        val tt = imdbId.substringBefore(':')
        if (!configured || !tt.startsWith("tt")) return null
        extrasCache.get(tt)?.let { return it }
        val found = get("/find/$tt?external_source=imdb_id") ?: return null
        val tv = type == "series"
        val id = found.arr(if (tv) "tv_results" else "movie_results").objects().firstOrNull()?.long("id")
            ?: found.arr(if (tv) "movie_results" else "tv_results").objects().firstOrNull()?.long("id")
            ?: return null
        val o = if (tv) get("/tv/$id?append_to_response=content_ratings,images,videos&include_image_language=en,null")
        else get("/movie/$id?append_to_response=release_dates,images,videos&include_image_language=en,null")
        o ?: return null
        val countries = listOf(java.util.Locale.getDefault().country.ifBlank { "US" }, "US").distinct()
        val cert = countries.firstNotNullOfOrNull { cc ->
            if (tv) {
                o.obj("content_ratings")?.arr("results").objects().firstOrNull { it.str("iso_3166_1") == cc }?.str("rating")
            } else {
                o.obj("release_dates")?.arr("results").objects().firstOrNull { it.str("iso_3166_1") == cc }
                    ?.arr("release_dates").objects().mapNotNull { it.str("certification") }.firstOrNull()
            }?.takeIf { it.isNotBlank() }
        }
        val logos = o.obj("images")?.arr("logos").objects()
        val logo = (logos.firstOrNull { it.str("iso_639_1") == "en" } ?: logos.firstOrNull())
            ?.str("file_path")?.let { "https://image.tmdb.org/t/p/w500$it" }
        val videos = o.obj("videos")?.arr("results").objects().filter { it.str("site") == "YouTube" }
        val trailers = (videos.filter { it.str("type") == "Trailer" } + videos.filter { it.str("type") == "Teaser" })
            .mapNotNull { it.str("key") }.distinct()
        return Extras(cert, logo, trailers).also { extrasCache.put(tt, it) }
    }

    /** The franchise a movie belongs to, in release order, or null. */
    suspend fun collectionFor(imdbId: String): Collection? = coroutineScope {
        if (!configured || !imdbId.startsWith("tt")) return@coroutineScope null
        val tmdbId = get("/find/$imdbId?external_source=imdb_id")?.arr("movie_results").objects().firstOrNull()?.long("id")
            ?: return@coroutineScope null
        val coll = get("/movie/$tmdbId")?.obj("belongs_to_collection") ?: return@coroutineScope null
        val cid = coll.long("id") ?: return@coroutineScope null
        val parts = get("/collection/$cid")?.arr("parts").objects()
            .filter { (it.str("release_date") ?: "").isNotEmpty() }
            .sortedBy { it.str("release_date") }
        val metas = parts.map { p ->
            async {
                val pid = p.long("id") ?: return@async null
                val tt = get("/movie/$pid/external_ids")?.str("imdb_id") ?: return@async null
                MetaItem(
                    id = tt, type = "movie", name = p.str("title") ?: return@async null,
                    poster = p.str("poster_path")?.let { "https://image.tmdb.org/t/p/w342$it" },
                    background = p.str("backdrop_path")?.let { "https://image.tmdb.org/t/p/w1280$it" },
                    releaseInfo = p.str("release_date")?.take(4),
                    description = p.str("overview"),
                )
            }
        }.awaitAll().filterNotNull()
        if (metas.size < 2) null else Collection(coll.str("name") ?: "Collection", metas)
    }
}
