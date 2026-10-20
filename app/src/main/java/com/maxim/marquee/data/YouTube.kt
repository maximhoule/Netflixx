package com.maxim.marquee.data

import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.YoutubeStreamHelper
import java.util.concurrent.TimeUnit

/**
 * Finds a direct stream for a YouTube trailer so the app's own video player can play it (sharp,
 * no YouTube logo or buttons, no web page). Uses NewPipe's YouTube client, which asks for the
 * video the way YouTube's Apple Vision app does: that answer includes an HLS stream up to 1080p.
 */
object YouTube {
    private const val TTL_MS = 3 * 60 * 60 * 1000L // stream links expire after ~6 h

    private const val REFUSED_TTL_MS = 20 * 60 * 1000L

    private class Hit(val url: String?, val at: Long) {
        fun fresh() = System.currentTimeMillis() - at < if (url != null) TTL_MS else REFUSED_TTL_MS
    }

    private val US = ContentCountry("US")
    private val EN = Localization("en", "US")

    private val cache = HashMap<String, Hit>()

    @Volatile
    private var ready = false

    private fun init() {
        if (ready) return
        synchronized(this) {
            if (!ready) {
                NewPipe.init(Fetcher, EN, US)
                ready = true
            }
        }
    }

    /** Cached result only; doesn't touch the network. */
    fun cached(id: String): String? = synchronized(cache) {
        cache[id]?.takeIf { it.fresh() }?.url
    }

    /** The HLS address for a YouTube video, or null if YouTube won't give one. Blocking: call off the main thread. */
    fun hlsUrl(id: String): String? {
        synchronized(cache) {
            cache[id]?.let { if (it.fresh()) return it.url }
        }
        var reachable = false
        var url: String? = null
        // Apple Vision client first, then the iPhone one: both answer with an HLS stream.
        for (client in listOf("visionOS", "iOS")) {
            url = try {
                init()
                val cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
                val r = if (client == "visionOS") {
                    YoutubeStreamHelper.getVisionOsPlayerResponse(US, EN, id, cpn)
                } else {
                    YoutubeStreamHelper.getIosPlayerResponse(US, EN, id, cpn, null)
                }
                reachable = true
                val status = r.getObject("playabilityStatus")
                val st = status.getString("status")
                if (st != null && !st.equals("OK", ignoreCase = true)) {
                    android.util.Log.w("Marquee", "trailer $id ($client): YouTube says $st (${status.getString("reason")})")
                    null
                } else {
                    r.getObject("streamingData").getString("hlsManifestUrl").takeUnless { it.isNullOrEmpty() }
                        .also { if (it == null) android.util.Log.w("Marquee", "trailer $id ($client): no stream offered") }
                }
            } catch (e: Throwable) {
                android.util.Log.w("Marquee", "trailer $id ($client): couldn't reach YouTube (${e.javaClass.simpleName}: ${e.message?.take(160)})")
                null
            }
            if (url != null) break
        }
        // Network trouble isn't remembered (try again next time); a refusal is, for a while.
        if (!reachable) return null
        synchronized(cache) { cache[id] = Hit(url, System.currentTimeMillis()) }
        return url
    }

    /** NewPipe's network calls, made with the app's own HTTP client (shared connections, no disk cache). */
    private object Fetcher : Downloader() {
        private val client by lazy {
            Http.client.newBuilder()
                .cache(null)
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }

        override fun execute(request: Request): Response {
            val method = request.httpMethod()
            val data = request.dataToSend()
            val body = when {
                data != null -> data.toRequestBody()
                method == "POST" || method == "PUT" -> ByteArray(0).toRequestBody()
                else -> null
            }
            val b = okhttp3.Request.Builder().url(request.url()).method(method, body)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0")
            request.headers().forEach { (name, values) ->
                b.removeHeader(name)
                values.forEach { b.addHeader(name, it) }
            }
            client.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("YouTube asked for a captcha", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }
}
