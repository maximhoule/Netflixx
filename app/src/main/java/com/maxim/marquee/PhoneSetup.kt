package com.maxim.marquee

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder

/**
 * "Set up from your phone": while the setup screen is open, the TV serves a small web page on
 * your home network. Scan the QR code, fill in the form on your phone, and the TV signs in.
 * Nothing leaves your network; the server stops as soon as you leave the screen.
 */
class PhoneSetup(private val ctx: android.content.Context, private val onSubmit: suspend (Map<String, String>) -> String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null
    private var job: Job? = null

    /** http://<tv-ip>:<port>/ or null if the TV has no network address. */
    var url: String? = null
        private set

    fun start(): String? {
        if (server != null) return url
        val ip = localIp(ctx) ?: return null
        val socket = (8899..8910).firstNotNullOfOrNull { p -> runCatching { ServerSocket(p) }.getOrNull() } ?: return null
        socket.soTimeout = 1_000
        server = socket
        url = "http://$ip:${socket.localPort}/"
        job = scope.launch {
            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (e: SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    break
                }
                scope.launch { handle(client) }
            }
        }
        return url
    }

    fun stop() {
        job?.cancel()
        runCatching { server?.close() }
        server = null
    }

    private suspend fun handle(c: Socket) {
        c.use { s ->
            s.soTimeout = 10_000
            val input = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val requestLine = input.readLine() ?: return
            var length = 0
            while (true) {
                val h = input.readLine() ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val method = requestLine.substringBefore(' ')
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            val html = if (method == "POST") {
                val buf = CharArray(length.coerceAtMost(64_000))
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                val form = parseForm(String(buf, 0, read))
                val done = CompletableDeferred<String>()
                // Run the sign-ins on the app side and report back to the phone.
                CoroutineScope(Dispatchers.Main).launch {
                    done.complete(runCatching { onSubmit(form) }.getOrElse { "Something went wrong: ${it.message}" })
                }
                val result = withTimeoutOrNull(45_000) { done.await() } ?: "Still working… check the TV."
                page(result)
            } else if (path.startsWith("/favicon")) {
                ""
            } else {
                page(null)
            }
            val body = html.toByteArray(Charsets.UTF_8)
            val out = s.getOutputStream()
            out.write(
                ("HTTP/1.1 ${if (html.isEmpty()) "404 Not Found" else "200 OK"}\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n")
                    .toByteArray(),
            )
            out.write(body)
            out.flush()
        }
    }

    private fun parseForm(s: String): Map<String, String> =
        s.split('&').mapNotNull { kv ->
            val k = kv.substringBefore('=', "")
            if (k.isEmpty()) return@mapNotNull null
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(kv.substringAfter('=', ""), "UTF-8")
        }.toMap()

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun page(result: String?): String = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Marquee setup</title>
<style>
body{font-family:-apple-system,system-ui,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:20px;max-width:560px;margin:auto}
h1{color:#e21b2d;font-size:28px;margin:8px 0 4px}h2{font-size:18px;margin:26px 0 6px}
p,li{color:#aaa;font-size:14px;line-height:1.45}label{display:block;font-size:13px;color:#bbb;margin:10px 0 4px}
input{width:100%;box-sizing:border-box;padding:12px;border-radius:8px;border:1px solid #444;background:#1c1c1c;color:#fff;font-size:16px}
button{margin:28px 0 40px;width:100%;padding:15px;border:0;border-radius:8px;background:#e21b2d;color:#fff;font-size:17px;font-weight:600}
.card{background:#1a1a1a;border-radius:10px;padding:4px 16px 14px;margin-top:12px}.ok{background:#16351c;color:#bfe8c4;padding:14px;border-radius:8px;white-space:pre-line}
a{color:#7ab7ff}
</style></head><body>
<h1>MARQUEE</h1><p>Fill in only what you want to set up; leave the rest empty.</p>
${if (result != null) "<div class=\"ok\">${esc(result)}</div>" else ""}
<form method="post">
<div class="card"><h2>Stremio account</h2>
<label>Email</label><input name="stremio_email" type="email" autocomplete="username">
<label>Password</label><input name="stremio_password" type="password" autocomplete="current-password"></div>
<div class="card"><h2>Emby / Jellyfin server</h2>
<label>Server address (e.g. 192.168.1.20:8096)</label><input name="emby_server" autocapitalize="off">
<label>Username</label><input name="emby_user" autocapitalize="off">
<label>Password</label><input name="emby_password" type="password"></div>
<div class="card"><h2>Trakt</h2>
<p>Create a free API app at <a href="https://trakt.tv/oauth/applications/new" target="_blank">trakt.tv/oauth/applications/new</a>:
any name, Redirect URI <b>urn:ietf:wg:oauth:2.0:oob</b>. Paste its keys here; the TV will then show a code to approve.</p>
<label>Client ID</label><input name="trakt_id" autocapitalize="off">
<label>Client Secret</label><input name="trakt_secret" autocapitalize="off"></div>
<div class="card"><h2>TMDB (for collections)</h2>
<p>Free key from <a href="https://www.themoviedb.org/settings/api" target="_blank">themoviedb.org/settings/api</a> (API Key or Read Access Token).</p>
<label>TMDB key</label><input name="tmdb_key" autocapitalize="off"></div>
<button type="submit">Send to TV</button></form></body></html>"""

    companion object {
        /**
         * The TV's address on your home network. Asks Android's connectivity service first (works on
         * every version); the interface scan is only a fallback (newer Android restricts it).
         */
        fun localIp(ctx: android.content.Context): String? {
            runCatching {
                val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks.toList()
                for (n in nets.distinct()) {
                    val lp = cm.getLinkProperties(n) ?: continue
                    lp.linkAddresses.map { it.address }
                        .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
                        ?.hostAddress?.let { return it }
                }
            }
            return scanInterfaces()
        }

        private fun scanInterfaces(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .sortedBy { if (it.name.startsWith("eth")) 0 else if (it.name.startsWith("wlan")) 1 else 2 }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && !it.isLoopbackAddress && it.isSiteLocalAddress }
                ?.hostAddress
        }.getOrNull()

        fun qr(text: String, size: Int = 512): Bitmap {
            val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
            val px = IntArray(size * size) { i -> if (m.get(i % size, i / size)) Color.BLACK else Color.WHITE }
            return Bitmap.createBitmap(px, size, size, Bitmap.Config.RGB_565)
        }
    }
}
