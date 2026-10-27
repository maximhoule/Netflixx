package com.maxim.marquee

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.maxim.marquee.data.Repo
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeUnit

/**
 * Every few hours, while the TV is on but Marquee isn't open: fetches your Home rows and their
 * first posters into the app's cache, so the next time you open it everything is already there
 * and up to date.
 */
class WarmWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try {
            Repo.loadCachedAddons()
            if (Repo.addons.isEmpty()) return Result.success()
            val catalogs = (Repo.boardCatalogs(null) + Repo.boardCatalogs("movie") + Repo.boardCatalogs("series"))
                .distinctBy { (a, c) -> "${a.transportUrl}|${c.type}|${c.id}" }
                .take(30)
            val d = applicationContext.resources.displayMetrics.density
            val w = (112 * d).toInt()
            val h = (168 * d).toInt()
            val loader = coil.Coil.imageLoader(applicationContext)
            for ((a, c) in catalogs) {
                if (isStopped) break
                val items = withTimeoutOrNull(20_000) { runCatching { Repo.catalog(a, c) }.getOrNull() } ?: continue
                items.take(10).mapNotNull { it.poster }.forEach { url ->
                    loader.enqueue(coil.request.ImageRequest.Builder(applicationContext).data(url).size(w, h).build())
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("Marquee", "background refresh: $e")
        }
        return Result.success()
    }

    companion object {
        fun schedule(ctx: Context) {
            runCatching {
                val req = PeriodicWorkRequestBuilder<WarmWorker>(4, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build()
                WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("warm-home", ExistingPeriodicWorkPolicy.KEEP, req)
            }
        }
    }
}
