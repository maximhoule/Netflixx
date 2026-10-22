package com.maxim.marquee.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
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

        private var blackInARow = 0

        /** Mostly black (a fade, or a TV box decoder that can't hand pictures back). */
        private fun isBlack(b: Bitmap): Boolean {
            var bright = 0
            var n = 0
            val stepX = (b.width / 12).coerceAtLeast(1)
            val stepY = (b.height / 8).coerceAtLeast(1)
            var y = stepY / 2
            while (y < b.height) {
                var x = stepX / 2
                while (x < b.width) {
                    val c = b.getPixel(x, y)
                    val l = (android.graphics.Color.red(c) * 3 + android.graphics.Color.green(c) * 6 + android.graphics.Color.blue(c)) / 10
                    if (l > 24) bright++
                    n++
                    x += stepX
                }
                y += stepY
            }
            return n > 0 && bright * 10 < n // under 10% of the samples lit
        }

        private fun frameAt(r: android.media.MediaMetadataRetriever, us: Long, option: Int): Bitmap? =
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(us, option, 384, 216)
            } else {
                r.getFrameAtTime(us, option)?.let { b ->
                    val h = 216; val w = (b.width * h / b.height.coerceAtLeast(1)).coerceAtLeast(1)
                    Bitmap.createScaledBitmap(b, w, h, true).also { if (it != b) b.recycle() }
                }
            }

        /** Our own decoder (software, so the TV box's hardware decoder can't hand back black). */
        private var soft: SoftFrames? = null
        private var softFailed = false

        private fun grab(ms: Long): Bitmap? {
            if (!softFailed) {
                try {
                    val d = soft ?: SoftFrames(url, headers).also { soft = it }
                    var b = d.frameAt(ms * 1000)
                    // A fade to black: the next keyframe a few seconds on.
                    if (b != null && isBlack(b)) b = d.frameAt(ms * 1000 + 4_000_000) ?: b
                    if (b != null) return b
                } catch (e: Throwable) {
                    android.util.Log.w("Marquee", "seek previews: own decoder unavailable (${e.javaClass.simpleName}: ${e.message?.take(80)}), trying the system one")
                    softFailed = true
                    runCatching { soft?.release() }
                    soft = null
                }
            }
            return grabSystem(ms)
        }

        private fun grabSystem(ms: Long): Bitmap? = try {
            val r = retriever ?: android.media.MediaMetadataRetriever().also {
                it.setDataSource(url, headers)
                retriever = it
            }
            val us = ms * 1000
            val M = android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            // A black picture: try the next keyframe a few seconds on (a scene fade), and never
            // show a black box. Black every time = this box can't make previews for this video.
            var b = frameAt(r, us, M)
            if (b != null && isBlack(b)) b = frameAt(r, us + 4_000_000, android.media.MediaMetadataRetriever.OPTION_NEXT_SYNC)
            if (b != null && isBlack(b)) {
                if (++blackInARow >= 3) {
                    android.util.Log.w("Marquee", "seek previews: frames come back black; turned off for this video")
                    failed = true
                }
                null
            } else {
                if (b != null) blackInARow = 0
                b
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
            val d = soft
            soft = null
            Thread {
                runCatching { r?.release() }
                runCatching { d?.release() }
            }.start()
        }
    }

    /**
     * Decodes single frames of a video file with a software decoder: seek to the keyframe before a
     * position, decode it, scale it down to a 384-pixel-wide picture. Kept open between requests.
     */
    private class SoftFrames(url: String, headers: Map<String, String>) {
        private val extractor = android.media.MediaExtractor()
        private val codec: android.media.MediaCodec
        private var started = false

        init {
            extractor.setDataSource(url, headers)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("no video track")
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(android.media.MediaFormat.KEY_MIME)!!
            val info = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { ci ->
                !ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } &&
                    if (android.os.Build.VERSION.SDK_INT >= 29) ci.isSoftwareOnly
                    else ci.name.startsWith("OMX.google.") || ci.name.startsWith("c2.android.")
            } ?: throw IllegalStateException("no software decoder for $mime")
            format.setInteger(
                android.media.MediaFormat.KEY_COLOR_FORMAT,
                android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            codec = android.media.MediaCodec.createByCodecName(info.name)
            try {
                codec.configure(format, null, null, 0)
                codec.start()
            } catch (e: Throwable) {
                codec.release(); extractor.release(); throw e
            }
        }

        fun frameAt(us: Long): Bitmap? {
            extractor.seekTo(us, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            if (started) codec.flush()
            started = true
            val info = android.media.MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = System.currentTimeMillis() + 6_000
            while (System.currentTimeMillis() < deadline) {
                if (!inputDone) {
                    val i = codec.dequeueInputBuffer(5_000)
                    if (i >= 0) {
                        val buf = codec.getInputBuffer(i)!!
                        val n = extractor.readSampleData(buf, 0)
                        if (n < 0) {
                            codec.queueInputBuffer(i, 0, 0, 0, android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(i, 0, n, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = codec.dequeueOutputBuffer(info, 5_000)
                if (o >= 0) {
                    val img = codec.getOutputImage(o)
                    val bmp = try { img?.let { toBitmap(it) } } finally { img?.close() }
                    codec.releaseOutputBuffer(o, false)
                    if (bmp != null) return bmp
                    // e.g. 10-bit HDR pictures: not something this converter reads.
                    if (img != null) throw IllegalStateException("picture format ${img.format}")
                    if (info.flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return null
                }
            }
            return null
        }

        /** YUV 4:2:0 → a small RGB picture (samples the pixels it needs; no full-size copy). */
        private fun toBitmap(img: android.media.Image): Bitmap? {
            if (img.format != android.graphics.ImageFormat.YUV_420_888 || img.planes.size < 3) return null
            val crop = img.cropRect
            val w = crop.width().coerceAtLeast(1)
            val h = crop.height().coerceAtLeast(1)
            val tw = 384
            val th = (tw * h / w).coerceIn(1, 384)
            val (yP, uP, vP) = Triple(img.planes[0], img.planes[1], img.planes[2])
            val yb = yP.buffer; val ub = uP.buffer; val vb = vP.buffer
            val px = IntArray(tw * th)
            for (ty in 0 until th) {
                val sy = crop.top + ty * h / th
                for (tx in 0 until tw) {
                    val sx = crop.left + tx * w / tw
                    val y = (yb.get(sy * yP.rowStride + sx * yP.pixelStride).toInt() and 0xff) - 16
                    val ci = (sy / 2) * uP.rowStride + (sx / 2) * uP.pixelStride
                    val u = (ub.get(ci).toInt() and 0xff) - 128
                    val v = (vb.get((sy / 2) * vP.rowStride + (sx / 2) * vP.pixelStride).toInt() and 0xff) - 128
                    val c = 298 * y.coerceAtLeast(0)
                    val r = ((c + 409 * v + 128) shr 8).coerceIn(0, 255)
                    val g = ((c - 100 * u - 208 * v + 128) shr 8).coerceIn(0, 255)
                    val b = ((c + 516 * u + 128) shr 8).coerceIn(0, 255)
                    px[ty * tw + tx] = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            return Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888)
        }

        fun release() {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            runCatching { extractor.release() }
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
