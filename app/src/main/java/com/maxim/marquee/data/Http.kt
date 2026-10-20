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

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    /** GET a JSON object; null on any failure. */
    suspend fun getJson(url: String): JSONObject? = withContext(Dispatchers.IO) {
        safe {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string()
                if (!r.isSuccessful || text.isNullOrBlank()) null else JSONObject(text)
            }
        }
    }

    /** GET + transform, both off the main thread. */
    suspend fun <T> getMapped(url: String, map: (JSONObject) -> T): T? = withContext(Dispatchers.IO) {
        safe {
            val req = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
            client.newCall(req).execute().use { r ->
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
