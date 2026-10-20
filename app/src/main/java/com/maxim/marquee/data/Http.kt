package com.maxim.marquee.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12; TV) Marquee/1.0"

/** How much a GET may rely on the on-disk HTTP cache. */
enum class Fetch {
    /** Normal HTTP caching rules (uses a stored copy only while the server says it's fresh). */
    NORMAL,
    /** Use a stored copy up to 12 hours old without asking the network (details pages, billboard). */
    STALE_OK,
    /** Only a stored copy, never the network: for showing the last known rows instantly. */
    CACHED_ONLY,
    /** Always the network, and don't store it (stream links expire). */
    NETWORK_ONLY,
}

object Http {
    private var cacheDir: java.io.File? = null

    /** Call once at app start so responses can be cached on disk. */
    fun init(dir: java.io.File) {
        cacheDir = dir
    }

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // Many rows come from the same addon server: let them load side by side.
            .dispatcher(okhttp3.Dispatcher().apply { maxRequests = 64; maxRequestsPerHost = 16 })
            .connectionPool(okhttp3.ConnectionPool(16, 5, TimeUnit.MINUTES))
            .apply { cacheDir?.let { cache(okhttp3.Cache(it.resolve("http"), 60L * 1024 * 1024)) } }
            .build()
    }

    private fun request(url: String, fetch: Fetch): Request {
        val b = Request.Builder().url(url).header("User-Agent", USER_AGENT)
        when (fetch) {
            Fetch.NORMAL -> Unit
            Fetch.STALE_OK -> b.cacheControl(okhttp3.CacheControl.Builder().maxStale(12, TimeUnit.HOURS).build())
            Fetch.CACHED_ONLY -> b.cacheControl(
                okhttp3.CacheControl.Builder().onlyIfCached().maxStale(Int.MAX_VALUE, TimeUnit.SECONDS).build(),
            )
            Fetch.NETWORK_ONLY -> b.cacheControl(okhttp3.CacheControl.Builder().noCache().noStore().build())
        }
        return b.build()
    }

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** GET a JSON object; null on any failure. */
    suspend fun getJson(url: String, fetch: Fetch = Fetch.NORMAL): JSONObject? = withContext(Dispatchers.IO) {
        safe {
            client.newCall(request(url, fetch)).execute().use { r ->
                val text = r.body?.string()
                if (!r.isSuccessful || text.isNullOrBlank()) null else JSONObject(text)
            }
        }
    }

    /** GET + transform, both off the main thread. */
    suspend fun <T> getMapped(url: String, fetch: Fetch = Fetch.NORMAL, map: (JSONObject) -> T): T? = withContext(Dispatchers.IO) {
        safe {
            client.newCall(request(url, fetch)).execute().use { r ->
                val text = r.body?.string()
                if (!r.isSuccessful || text.isNullOrBlank()) null else map(JSONObject(text))
            }
        }
    }

    /** POST JSON; throws IOException on network or parse failure. */
    suspend fun postJson(url: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
        client.newCall(req).execute().use { r ->
            val text = r.body?.string() ?: throw IOException("Empty response (HTTP ${r.code})")
            try {
                JSONObject(text)
            } catch (e: JSONException) {
                throw IOException("Unexpected response (HTTP ${r.code})")
            }
        }
    }
}
