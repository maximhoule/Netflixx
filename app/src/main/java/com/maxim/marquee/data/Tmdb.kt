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
