package com.maxim.marquee.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.nio.charset.Charset

/**
 * Subtitle files from addons (OpenSubtitles etc.), read by the app itself instead of the video
 * player, so their timing can be nudged earlier or later while you watch.
 * Understands SRT, WebVTT and ASS/SSA.
 */
object Subtitles {
    /** One line of dialogue. [text] may contain <i>…</i> for italics. */
    data class Cue(val startMs: Long, val endMs: Long, val text: String)

    /** Downloads and parses a subtitle file; null if it can't be read. */
    suspend fun load(url: String): List<Cue>? = withContext(Dispatchers.IO) {
        val bytes = safe {
            Http.client.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        } ?: return@withContext null
        val cues = withContext(Dispatchers.Default) { parse(decode(bytes)) }
        cues.takeIf { it.isNotEmpty() }
    }

    /** UTF-8 (with or without BOM), UTF-16, or the Windows Latin encoding older files use. */
    fun decode(b: ByteArray): String {
        if (b.size >= 2 && b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()) return String(b, Charsets.UTF_16LE).drop(1)
        if (b.size >= 2 && b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) return String(b, Charsets.UTF_16BE).drop(1)
        val utf8 = String(b, Charsets.UTF_8).removePrefix("﻿")
        val broken = utf8.count { it == '�' }
        return if (broken > 3) String(b, Charset.forName("windows-1252")) else utf8
    }

    fun parse(text: String): List<Cue> {
        val t = text.replace("\r\n", "\n").replace('\r', '\n')
        val cues = when {
            t.contains("[Events]", ignoreCase = true) && t.contains("Dialogue:") -> parseAss(t)
            else -> parseSrtOrVtt(t)
        }
        return cues.filter { it.endMs > it.startMs && it.text.isNotBlank() }.sortedBy { it.startMs }
    }

    private val TIME = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})[.,](\d{1,3})""")

    private fun time(s: String): Long? {
        val m = TIME.find(s) ?: return null
        val (h, mi, se, ms) = m.destructured
        val frac = ms.padEnd(3, '0').take(3).toLong()
        return ((h.ifEmpty { "0" }.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + frac
    }

    private fun parseSrtOrVtt(t: String): List<Cue> {
        val out = ArrayList<Cue>()
        val lines = t.split('\n')
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val arrow = line.indexOf("-->")
            if (arrow < 0) { i++; continue }
            val start = time(line.substring(0, arrow))
            val end = time(line.substring(arrow + 3))
            i++
            val body = StringBuilder()
            while (i < lines.size && lines[i].isNotBlank()) {
                if (lines[i].contains("-->")) break
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (start != null && end != null) out += Cue(start, end, clean(body.toString()))
        }
        return out
    }

    private fun parseAss(t: String): List<Cue> {
        val out = ArrayList<Cue>()
        var fields = listOf("layer", "start", "end", "style", "name", "marginl", "marginr", "marginv", "effect", "text")
        var inEvents = false
        for (line in t.split('\n')) {
            val l = line.trim()
            if (l.startsWith("[")) { inEvents = l.equals("[Events]", true); continue }
            if (!inEvents) continue
            if (l.startsWith("Format:", true)) {
                fields = l.substringAfter(':').split(',').map { it.trim().lowercase() }
                continue
            }
            if (!l.startsWith("Dialogue:", true)) continue
            val parts = l.substringAfter(':').split(',', limit = fields.size)
            fun f(name: String) = fields.indexOf(name).takeIf { it >= 0 }?.let { parts.getOrNull(it)?.trim() }
            val start = f("start")?.let { time(it) } ?: continue
            val end = f("end")?.let { time(it) } ?: continue
            val raw = f("text") ?: continue
            out += Cue(start, end, clean(raw.replace("\\N", "\n").replace("\\n", "\n").replace("\\h", " ")))
        }
        // Same text shown on several layers (karaoke/effects) only once.
        return out.distinctBy { Triple(it.startMs, it.endMs, it.text) }
    }

    /** Keeps line breaks and italics; drops styling tags, positioning codes and HTML entities. */
    private fun clean(s: String): String {
        var x = s.replace(Regex("""\{\\i1\}"""), "<i>").replace(Regex("""\{\\i0\}"""), "</i>")
        x = x.replace(Regex("""\{[^}]*\}"""), "")
        x = x.replace(Regex("""<(?!/?i>)[^>]*>""", RegexOption.IGNORE_CASE), "")
        x = x.replace(Regex("<(/?)I>"), "<$1i>")
        x = x.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&quot;", "\"")
        return x.lines().joinToString("\n") { it.trim() }.trim()
    }

    /** The cue showing at [positionMs], or null. Cues must be sorted by start time. */
    fun at(cues: List<Cue>, positionMs: Long): Cue? {
        var lo = 0
        var hi = cues.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].startMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        // Overlapping cues: look back a little for one that's still on screen.
        var i = found
        var seen = 0
        while (i >= 0 && seen < 8) {
            val c = cues[i]
            if (positionMs < c.endMs) return c
            i--; seen++
        }
        return null
    }
}
