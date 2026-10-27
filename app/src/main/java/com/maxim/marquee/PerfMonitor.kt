package com.maxim.marquee

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.maxim.marquee.data.Store
import kotlinx.coroutines.delay

/**
 * Measures how long the TV really takes to draw each frame (Android's own frame timings, so it
 * includes the graphics chip), for the on-screen performance overlay in Settings. Costs nothing
 * while the overlay is off: nothing is attached then.
 */
object PerfMonitor {
    var enabled by mutableStateOf(false)
        private set

    private val thread by lazy { HandlerThread("marquee-frames").apply { start() } }
    private val handler by lazy { Handler(thread.looper) }

    // Last 1,200 frames: when each finished (ms) and how long it took (ns).
    private const val N = 1200
    private val ends = LongArray(N)
    private val durs = LongArray(N)
    private var next = 0
    private var count = 0
    private val lock = Any()

    private var current: Pair<Window, Window.OnFrameMetricsAvailableListener>? = null

    fun init() {
        enabled = Store.perfOverlay
    }

    fun setEnabled(activity: Activity, on: Boolean) {
        Store.perfOverlay = on
        enabled = on
        if (on) attach(activity) else detach()
    }

    fun attach(activity: Activity) {
        if (!enabled) return
        detach()
        val l = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val d = m.getMetric(FrameMetrics.TOTAL_DURATION)
            synchronized(lock) {
                ends[next] = System.currentTimeMillis()
                durs[next] = d
                next = (next + 1) % N
                if (count < N) count++
            }
        }
        try {
            activity.window.addOnFrameMetricsAvailableListener(l, handler)
            current = activity.window to l
        } catch (e: Exception) {
            android.util.Log.w("Marquee", "frame timings unavailable: $e")
        }
    }

    fun detach() {
        current?.let { (w, l) -> runCatching { w.removeOnFrameMetricsAvailableListener(l) } }
        current = null
    }

    data class Stats(
        val frames: Int,
        val seconds: Int,
        val slowPct: Int,
        val janky: Int,
        val p50: Double,
        val p90: Double,
        val p99: Double,
        val worst: Double,
        val memMb: Long,
        val maxMb: Long,
    )

    /** Frames drawn in the last [seconds] (an idle screen draws none, which is good). */
    fun stats(seconds: Int = 5): Stats {
        val since = System.currentTimeMillis() - seconds * 1000L
        val list = ArrayList<Long>()
        synchronized(lock) {
            for (i in 0 until count) {
                val k = (next - 1 - i + N) % N
                if (ends[k] < since) break
                list += durs[k]
            }
        }
        val ms = list.map { it / 1_000_000.0 }.sorted()
        fun pct(p: Double) = if (ms.isEmpty()) 0.0 else ms[((ms.size - 1) * p).toInt()]
        val rt = Runtime.getRuntime()
        return Stats(
            frames = ms.size,
            seconds = seconds,
            slowPct = if (ms.isEmpty()) 0 else ms.count { it > 16.7 } * 100 / ms.size,
            janky = ms.count { it > 33.4 },
            p50 = pct(0.5), p90 = pct(0.9), p99 = pct(0.99), worst = ms.lastOrNull() ?: 0.0,
            memMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576,
            maxMb = rt.maxMemory() / 1_048_576,
        )
    }
}

/** The small box in the corner with live frame timings. Updates once a second. */
@Composable
fun PerfOverlay(modifier: Modifier = Modifier) {
    if (!PerfMonitor.enabled) return
    var s by remember { mutableStateOf(PerfMonitor.stats()) }
    LaunchedEffect(Unit) {
        var n = 0
        while (true) {
            delay(1_000)
            s = PerfMonitor.stats()
            if (++n % 10 == 0) {
                android.util.Log.i(
                    "MarqueePerf",
                    "frames ${s.frames}/5s slow ${s.slowPct}% janky ${s.janky} p50 ${"%.1f".format(s.p50)} p90 ${"%.1f".format(s.p90)} " +
                        "p99 ${"%.1f".format(s.p99)} worst ${"%.0f".format(s.worst)} mem ${s.memMb}/${s.maxMb}MB",
                )
            }
        }
    }
    val good = Color(0xFF46D369)
    val warn = Color(0xFFF5C518)
    val bad = Color(0xFFFF5A5A)
    fun col(v: Double, ok: Double, meh: Double) = if (v <= ok) good else if (v <= meh) warn else bad
    Box(modifier.padding(top = 64.dp, end = 16.dp)) {
        Column(
            Modifier
                .align(Alignment.TopEnd)
                .background(Color(0xCC000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            val mono = FontFamily.Monospace
            if (s.frames == 0) {
                Text("idle (nothing redrawing)", color = good, fontSize = 11.sp, fontFamily = mono)
            } else {
                Text(
                    "${s.frames / s.seconds} fps · ${s.frames} frames/${s.seconds}s",
                    color = Color.White, fontSize = 11.sp, fontFamily = mono, fontWeight = FontWeight.Bold,
                )
                Text(
                    "slow >16ms ${s.slowPct}% · janky >33ms ${s.janky}",
                    color = col(s.slowPct.toDouble(), 5.0, 20.0), fontSize = 11.sp, fontFamily = mono,
                )
                Text(
                    "p50 ${"%.1f".format(s.p50)} · p90 ${"%.1f".format(s.p90)} · p99 ${"%.1f".format(s.p99)} ms",
                    color = col(s.p90, 16.7, 33.4), fontSize = 11.sp, fontFamily = mono,
                )
                Text("worst ${"%.0f".format(s.worst)} ms", color = col(s.worst, 33.4, 100.0), fontSize = 11.sp, fontFamily = mono)
            }
            Text("memory ${s.memMb} / ${s.maxMb} MB", color = Color(0xFFBDBDBD), fontSize = 11.sp, fontFamily = mono)
        }
    }
}
