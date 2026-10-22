package com.maxim.marquee

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.maxim.marquee.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** For work that must outlive a screen, like saving progress after the player closes. */
val AppScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
        Store.init(this)
        com.maxim.marquee.data.Http.init(cacheDir)
        // Read saved progress and profiles before the first screen needs them.
        AppScope.launch { Store.warmUp() }
    }

    /** The TV is short on memory: give back the picture cache (it refills from disk instantly). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            runCatching {
                val cache = coil.Coil.imageLoader(this).memoryCache ?: return@runCatching
                if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) cache.clear()
                else cache.trimMemory(level)
            }
        }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(120)
        // Decode at most two pictures at once: on a 4-core TV box this leaves the other cores to
        // the screen (scrolling stays smooth while a new row's posters come in).
        .bitmapFactoryMaxParallelism(2)
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
        // Same connections as the rest of the app (images have their own cache, so no HTTP cache here).
        .okHttpClient { com.maxim.marquee.data.Http.client.newBuilder().cache(null).build() }
        .build()
}
