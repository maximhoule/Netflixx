package com.maxim.marquee.ui

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * The main colour of a title's artwork, as a deep page tint ([shade]) and a slightly lighter
 * panel colour ([panel]). Grey or black artwork gives none (the page stays black).
 */
class Accent(val shade: Color, val panel: Color) {
    companion object {
        private val cache = ConcurrentHashMap<String, Accent>()
        private val none = ConcurrentHashMap.newKeySet<String>()

        fun cached(url: String?): Accent? = url?.let { cache[it] }

        suspend fun of(ctx: Context, url: String?): Accent? {
            if (url == null || url in none) return null
            cache[url]?.let { return it }
            return try {
                val req = ImageRequest.Builder(ctx).data(url).size(64, 36).allowHardware(false).build()
                val bmp = ((ctx.imageLoader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return null
                withContext(Dispatchers.Default) {
                    val hsv = FloatArray(3)
                    var x = 0.0
                    var y = 0.0
                    var sat = 0.0
                    var weight = 0.0
                    val w = bmp.width
                    val h = bmp.height
                    for (j in 0 until h step 2) for (i in 0 until w step 2) {
                        android.graphics.Color.colorToHSV(bmp.getPixel(i, j), hsv)
                        // Vivid, reasonably bright pixels decide the colour; greys and shadows don't.
                        val wt = (hsv[1] * hsv[2]).toDouble().let { it * it }
                        if (wt <= 0.0) continue
                        val a = Math.toRadians(hsv[0].toDouble())
                        x += Math.cos(a) * wt
                        y += Math.sin(a) * wt
                        sat += hsv[1] * wt
                        weight += wt
                    }
                    val n = (w / 2) * (h / 2)
                    if (weight / n < 0.01) {
                        none += url
                        return@withContext null
                    }
                    val hue = ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360).toFloat()
                    val s = (sat / weight).toFloat().coerceIn(0.25f, 0.7f)
                    Accent(
                        shade = Color.hsv(hue, s, 0.13f),
                        panel = Color.hsv(hue, s * 0.6f, 0.11f).copy(alpha = 0.92f),
                    ).also { cache[url] = it }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }
}
