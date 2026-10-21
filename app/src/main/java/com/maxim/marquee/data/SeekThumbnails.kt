package com.maxim.marquee.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/** Preview pictures shown above the seek bar while you skip through something from your server. */
sealed class SeekThumbnails {
    /** The picture for a position, or null if there isn't one. May download (call off the main thread). */
    abstract suspend fun frame(positionMs: Long): Bitmap?

    /** Frees what the previews hold (called when the player closes). */
    open fun release() {}

    /**
     * For any video file (Stremio sources): pictures taken from the video itself, at the nearest
     * keyframe, while you skip. Only the latest position is worked on (pressing ahead cancels the
     * rest), each 10-second step is remembered, and nothing runs unless you're skipping.
     */
    class Extracted(private val url: String, private val headers: Map<String, String>) : SeekThumbnails() {
        private val cache = LruCache<Long, Bitmap>(40)
        private val lock = kotlinx.coroutines.sync.Mutex()
        private var retriever: android.media.MediaMetadataRetriever? = null
        @Volatile private var failed = false

        override suspend fun frame(positionMs: Long): Bitmap? {
            if (failed) return null
            val step = positionMs / 10_000
            cache.get(step)?.let { return it }
            // Still pressing? Wait a moment: only the position you stop at gets a picture.
            kotlinx.coroutines.delay(180)
            return lock.withLock {
                cache.get(step) ?: withContext(Dispatchers.IO) { grab(step * 10_000) }?.also { cache.put(step, it) }
            }
        }

        private fun grab(ms: Long): Bitmap? = try {
            val r = retriever ?: android.media.MediaMetadataRetriever().also {
                it.setDataSource(url, headers)
                retriever = it
            }
            val us = ms * 1000
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216)
            } else {
                r.getFrameAtTime(us, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { b ->
                    val h = 216; val w = (b.width * h / b.height.coerceAtLeast(1)).coerceAtLeast(1)
                    Bitmap.createScaledBitmap(b, w, h, true).also { if (it != b) b.recycle() }
                }
            }
        } catch (e: Throwable) {
            // Not a format the TV can open this way (e.g. a live stream): no previews for this video.
            android.util.Log.w("Marquee", "seek previews unavailable: ${e.javaClass.simpleName}")
            failed = true
            null
        }

        override fun release() {
            val r = retriever
            retriever = null
            if (r != null) Thread { runCatching { r.release() } }.start()
        }
    }

    /** Emby's BIF file: every thumbnail in one download, decoded on demand. */
    class Bif private constructor(
        private val data: ByteArray,
        private val times: LongArray,
        private val offsets: IntArray,
    ) : SeekThumbnails() {
        private val cache = LruCache<Int, Bitmap>(12)

        override suspend fun frame(positionMs: Long): Bitmap? {
            if (times.isEmpty()) return null
            // Last thumbnail at or before the position.
            var lo = 0; var hi = times.size - 1; var idx = 0
            while (lo <= hi) {
                val mid = (lo + hi) ushr 1
                if (times[mid] <= positionMs) { idx = mid; lo = mid + 1 } else hi = mid - 1
            }
            cache.get(idx)?.let { return it }
            val start = offsets[idx]; val end = offsets[idx + 1]
            if (start < 0 || end > data.size || end <= start) return null
            return withContext(Dispatchers.Default) {
                safe { BitmapFactory.decodeByteArray(data, start, end - start) }
            }?.also { cache.put(idx, it) }
        }

        companion object {
            private val MAGIC = byteArrayOf(0x89.toByte(), 0x42, 0x49, 0x46, 0x0d, 0x0a, 0x1a, 0x0a)

            private fun u32(b: ByteArray, at: Int): Long =
                (b[at].toLong() and 0xff) or ((b[at + 1].toLong() and 0xff) shl 8) or
                    ((b[at + 2].toLong() and 0xff) shl 16) or ((b[at + 3].toLong() and 0xff) shl 24)

            /** Roku BIF format: 64-byte header, then (timestamp, offset) pairs ending with 0xFFFFFFFF. */
            fun parse(b: ByteArray): Bif? {
                if (b.size < 72 || !MAGIC.indices.all { b[it] == MAGIC[it] }) return null
                val count = u32(b, 12).toInt()
                if (count <= 0 || 64 + (count + 1) * 8 > b.size) return null
                val mult = u32(b, 16).takeIf { it > 0 } ?: 1000L
                val times = LongArray(count)
                val offsets = IntArray(count + 1)
                for (i in 0..count) {
                    val at = 64 + i * 8
                    if (i < count) times[i] = u32(b, at) * mult
                    offsets[i] = u32(b, at + 4).toInt()
                }
                return Bif(b, times, offsets)
            }
        }
    }

    /** Jellyfin trickplay: grids of thumbnails, one image per grid. */
    class Tiles(
        private val interval: Long,
        private val count: Int,
        private val cols: Int,
        private val rows: Int,
        private val width: Int,
        private val height: Int,
        private val tileUrl: (Int) -> String,
    ) : SeekThumbnails() {
        private val tiles = LruCache<Int, Bitmap>(4)

        override suspend fun frame(positionMs: Long): Bitmap? {
            if (count <= 0 || interval <= 0) return null
            val idx = (positionMs / interval).toInt().coerceIn(0, count - 1)
            val perTile = (cols * rows).coerceAtLeast(1)
            val tileIndex = idx / perTile
            val inTile = idx % perTile
            val tile = tiles.get(tileIndex) ?: withContext(Dispatchers.IO) {
                safe {
                    Http.client.newCall(Request.Builder().url(tileUrl(tileIndex)).build()).execute().use { r ->
                        if (r.isSuccessful) r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } else null
                    }
                }
            }?.also { tiles.put(tileIndex, it) } ?: return null
            val x = (inTile % cols) * width
            val y = (inTile / cols) * height
            if (x + width > tile.width || y + height > tile.height) return null
            return withContext(Dispatchers.Default) { safe { Bitmap.createBitmap(tile, x, y, width, height) } }
        }
    }
}
