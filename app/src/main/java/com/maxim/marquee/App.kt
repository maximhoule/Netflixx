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
        Store.init(this)
        com.maxim.marquee.data.Http.init(cacheDir)
        // Read saved progress and profiles before the first screen needs them.
        AppScope.launch { Store.warmUp() }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .crossfade(160)
        .allowRgb565(true)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.2).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(300L * 1024 * 1024).build() }
        // Same connections as the rest of the app (images have their own cache, so no HTTP cache here).
        .okHttpClient { com.maxim.marquee.data.Http.client.newBuilder().cache(null).build() }
        .build()
}
