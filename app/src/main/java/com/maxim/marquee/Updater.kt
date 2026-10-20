package com.maxim.marquee

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.maxim.marquee.data.Http
import com.maxim.marquee.data.USER_AGENT
import com.maxim.marquee.data.objects
import com.maxim.marquee.data.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Self-updater: checks the GitHub release that every build publishes, downloads the newer
 * APK and installs it through Android's PackageInstaller. The first self-update needs one
 * confirmation; after that, Android 12+ lets the app install its own updates directly.
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/maximhoule/Netflixx/releases/latest"

    data class Release(val build: Int, val url: String)

    fun currentBuild(ctx: Context): Int = try {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    } catch (e: Exception) {
        0
    }

    /** Newer release than the installed build, or null. */
    suspend fun check(ctx: Context): Release? = withContext(Dispatchers.IO) {
        try {
            val req = Request.Builder().url(LATEST)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .build()
            Http.client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) return@withContext null
                val o = JSONObject(r.body?.string() ?: return@withContext null)
                val build = o.str("tag_name")?.substringAfter("build-")?.toIntOrNull() ?: return@withContext null
                val url = o.optJSONArray("assets").objects()
                    .firstOrNull { it.str("name")?.endsWith(".apk") == true }
                    ?.str("browser_download_url") ?: return@withContext null
                if (build > currentBuild(ctx)) Release(build, url) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    suspend fun download(ctx: Context, r: Release, progress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val file = File(ctx.cacheDir, "update.apk")
        val req = Request.Builder().url(r.url).header("User-Agent", USER_AGENT).build()
        Http.client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Download failed (HTTP ${resp.code})")
            val body = resp.body ?: throw IOException("Empty download")
            val total = body.contentLength()
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var last = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0 && read - last > 256 * 1024) {
                            last = read
                            val f = read.toFloat() / total
                            withContext(Dispatchers.Main) { progress(f) }
                        }
                    }
                }
            }
        }
        file
    }

    /** Android requires the "install unknown apps" permission for Marquee itself. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        try {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Toast.makeText(ctx, "Allow Marquee under Settings → Apps → Security → Unknown sources", Toast.LENGTH_LONG).show()
        }
    }

    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("marquee.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(ctx, sessionId, Intent(ctx, UpdateReceiver::class.java), flags)
            session.commit(pending.intentSender)
        }
    }
}

/** Receives the installer's result; shows Android's confirmation screen when it asks for one. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirm?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(it)
                    } catch (e: Exception) {
                        // ignore
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Update failed"
                Toast.makeText(ctx, "Update didn't install: $msg", Toast.LENGTH_LONG).show()
            }
        }
    }
}
