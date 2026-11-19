package com.maxim.marquee.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.maxim.marquee.data.StreamItem
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * Marquee's own picture-and-sound badges: a small symbol and a label, in one style, so a source
 * reads at a glance (disc = Blu-ray, globe = streaming service, sun = HDR, speaker = sound…).
 * The symbols are drawn here; they aren't anyone's trademark.
 */
enum class BadgeGlyph { DISC, GLOBE, ANTENNA, CAMERA, SCREEN, SUN, SPEAKER, FRAME, FILM, CHIP, SERVER, NONE }

enum class BadgeTone { BRIGHT, GOLD, PLAIN }

@Composable
fun QualityBadge(label: String, glyph: BadgeGlyph = BadgeGlyph.NONE, tone: BadgeTone = BadgeTone.PLAIN, sub: String? = null, caps: Boolean = true) {
    val fg = if (tone == BadgeTone.PLAIN) Color.White else Color.Black
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .height(22.dp)
            .then(
                when (tone) {
                    BadgeTone.BRIGHT -> Modifier.background(Color(0xFFEDEDED), shape)
                    BadgeTone.GOLD -> Modifier.background(Color(0xFFF5C518), shape)
                    BadgeTone.PLAIN -> Modifier.background(Color(0x1FFFFFFF), shape).border(1.dp, Color(0x59FFFFFF), shape)
                },
            )
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (glyph != BadgeGlyph.NONE) {
            Canvas(Modifier.size(13.dp)) { glyph(glyph, fg) }
            Spacer(Modifier.width(5.dp))
        }
        Text(if (caps) label.uppercase() else label, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 0.3.sp, maxLines = 1)
        if (sub != null) {
            Spacer(Modifier.width(4.dp))
            Text(sub.uppercase(), color = fg.copy(alpha = 0.62f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp, maxLines = 1)
        }
    }
}

private fun DrawScope.glyph(g: BadgeGlyph, c: Color) {
    val w = size.width
    val h = size.height
    val cx = w / 2
    val cy = h / 2
    val sw = w * 0.11f
    val line = Stroke(sw, cap = StrokeCap.Round)
    when (g) {
        // A disc: ring, hole, and a glint.
        BadgeGlyph.DISC -> {
            drawCircle(c, w * 0.44f, style = Stroke(sw))
            drawCircle(c, w * 0.11f)
            drawArc(c, 200f, 50f, false, Offset(w * 0.24f, h * 0.24f), Size(w * 0.52f, h * 0.52f), style = line)
        }
        // A globe: streamed from a service.
        BadgeGlyph.GLOBE -> {
            drawCircle(c, w * 0.44f, style = Stroke(sw))
            drawOval(c, Offset(cx - w * 0.19f, cy - h * 0.44f), Size(w * 0.38f, h * 0.88f), style = Stroke(sw * 0.8f))
            drawLine(c, Offset(w * 0.08f, cy), Offset(w * 0.92f, cy), sw * 0.8f)
        }
        // Recorded from broadcast.
        BadgeGlyph.ANTENNA -> {
            drawLine(c, Offset(cx, h * 0.42f), Offset(cx, h * 0.95f), sw, StrokeCap.Round)
            drawCircle(c, w * 0.1f, Offset(cx, h * 0.36f))
            drawArc(c, 215f, 110f, false, Offset(w * 0.2f, h * 0.06f), Size(w * 0.6f, h * 0.6f), style = line)
            drawArc(c, 215f, 110f, false, Offset(w * 0.02f, -h * 0.12f), Size(w * 0.96f, h * 0.96f), style = line)
        }
        // Filmed off a cinema screen.
        BadgeGlyph.CAMERA -> {
            drawRoundRect(c, Offset(w * 0.04f, h * 0.28f), Size(w * 0.6f, h * 0.48f), CornerRadius(w * 0.08f))
            val p = Path().apply { moveTo(w * 0.68f, cy); lineTo(w * 0.97f, h * 0.3f); lineTo(w * 0.97f, h * 0.74f); close() }
            drawPath(p, c)
        }
        // Picture size.
        BadgeGlyph.SCREEN -> {
            drawRoundRect(c, Offset(w * 0.05f, h * 0.14f), Size(w * 0.9f, h * 0.56f), CornerRadius(w * 0.08f), style = Stroke(sw))
            drawLine(c, Offset(w * 0.3f, h * 0.9f), Offset(w * 0.7f, h * 0.9f), sw, StrokeCap.Round)
        }
        // Brighter picture (HDR): a sun.
        BadgeGlyph.SUN -> {
            drawCircle(c, w * 0.2f)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                drawLine(
                    c, Offset(cx + cos(a).toFloat() * w * 0.33f, cy + sin(a).toFloat() * h * 0.33f),
                    Offset(cx + cos(a).toFloat() * w * 0.47f, cy + sin(a).toFloat() * h * 0.47f), sw, StrokeCap.Round,
                )
            }
        }
        // Sound.
        BadgeGlyph.SPEAKER -> {
            val p = Path().apply {
                moveTo(w * 0.04f, h * 0.36f); lineTo(w * 0.24f, h * 0.36f); lineTo(w * 0.5f, h * 0.12f)
                lineTo(w * 0.5f, h * 0.88f); lineTo(w * 0.24f, h * 0.64f); lineTo(w * 0.04f, h * 0.64f); close()
            }
            drawPath(p, c)
            drawArc(c, -45f, 90f, false, Offset(w * 0.38f, h * 0.28f), Size(w * 0.44f, h * 0.44f), style = line)
            drawArc(c, -45f, 90f, false, Offset(w * 0.22f, h * 0.1f), Size(w * 0.76f, h * 0.8f), style = line)
        }
        // A taller picture: frame with arrows up and down.
        BadgeGlyph.FRAME -> {
            drawRoundRect(c, Offset(w * 0.08f, h * 0.06f), Size(w * 0.84f, h * 0.88f), CornerRadius(w * 0.08f), style = Stroke(sw))
            val up = Path().apply { moveTo(cx, h * 0.2f); lineTo(cx - w * 0.16f, h * 0.4f); lineTo(cx + w * 0.16f, h * 0.4f); close() }
            val down = Path().apply { moveTo(cx, h * 0.8f); lineTo(cx - w * 0.16f, h * 0.6f); lineTo(cx + w * 0.16f, h * 0.6f); close() }
            drawPath(up, c); drawPath(down, c)
        }
        // A different cut of the film.
        BadgeGlyph.FILM -> {
            drawRoundRect(c, Offset(w * 0.06f, h * 0.1f), Size(w * 0.88f, h * 0.8f), CornerRadius(w * 0.06f), style = Stroke(sw))
            for (i in 0..2) {
                val y = h * (0.26f + i * 0.24f)
                drawCircle(c, w * 0.055f, Offset(w * 0.22f, y))
                drawCircle(c, w * 0.055f, Offset(w * 0.78f, y))
            }
        }
        // How the video is packed.
        BadgeGlyph.CHIP -> {
            drawRoundRect(c, Offset(w * 0.22f, h * 0.22f), Size(w * 0.56f, h * 0.56f), CornerRadius(w * 0.06f), style = Stroke(sw))
            for (i in 0..1) {
                val t = 0.38f + i * 0.24f
                drawLine(c, Offset(w * t, h * 0.04f), Offset(w * t, h * 0.2f), sw * 0.8f)
                drawLine(c, Offset(w * t, h * 0.8f), Offset(w * t, h * 0.96f), sw * 0.8f)
                drawLine(c, Offset(w * 0.04f, h * t), Offset(w * 0.2f, h * t), sw * 0.8f)
                drawLine(c, Offset(w * 0.8f, h * t), Offset(w * 0.96f, h * t), sw * 0.8f)
            }
        }
        // Your own server.
        BadgeGlyph.SERVER -> {
            for (i in 0..1) {
                val y = h * (0.12f + i * 0.44f)
                drawRoundRect(c, Offset(w * 0.06f, y), Size(w * 0.88f, h * 0.32f), CornerRadius(w * 0.07f), style = Stroke(sw))
                drawCircle(c, w * 0.06f, Offset(w * 0.26f, y + h * 0.16f))
            }
        }
        BadgeGlyph.NONE -> Unit
    }
}

private val RES_SUB = mapOf("4K" to "Ultra HD", "1440p" to null, "1080p" to "Full HD", "720p" to "HD", "SD" to null)
private val HDR_NAMES = mapOf("DV" to "Dolby Vision", "HDR" to "HDR", "HDR10" to "HDR10", "HDR10+" to "HDR10+", "HLG" to "HLG")
private val SOUND = setOf("Atmos", "TrueHD", "DTS-HD", "DTS", "DD+", "DD", "AAC", "7.1", "5.1", "2.0")

/**
 * A source's badges, in order: where it comes from, the cut, picture size, HDR, sound and codec.
 * Call it inside a Row or FlowRow; each badge is a separate item there.
 */
@Composable
fun SourceBadges(st: StreamItem, showCodec: Boolean = true) {
    val SG = com.maxim.marquee.data.SourceGroups
    val kind = remember(st) { SG.kind(st) }
    val tags = remember(st) { SG.tags(st) }
    val codec = remember(st) { if (showCodec) SG.codec(st) else null }
    // (size is shown on its own; "REMUX" is already in the kind)
    val badges = remember(st) { st.quality.badges.filterNot { it == "REMUX" || it.endsWith(" GB") || it.endsWith(" MB") || it == "AV1" } }
    // Where it comes from.
    when {
        st.embyId != null -> QualityBadge("Your copy", BadgeGlyph.SERVER, BadgeTone.BRIGHT)
        kind == "Blu-ray Remux" -> QualityBadge("Blu-ray", BadgeGlyph.DISC, BadgeTone.BRIGHT, sub = "Remux")
        kind == "Blu-ray" -> QualityBadge("Blu-ray", BadgeGlyph.DISC)
        kind == "WEB" -> QualityBadge("Web", BadgeGlyph.GLOBE)
        kind == "TV recording" -> QualityBadge("TV", BadgeGlyph.ANTENNA)
        kind == "Cinema recording" -> QualityBadge("Cinema cam", BadgeGlyph.CAMERA)
        else -> Unit
    }
    // Which cut.
    tags.forEach { t -> QualityBadge(t, if (t == "IMAX" || t == "Open Matte") BadgeGlyph.FRAME else BadgeGlyph.FILM, BadgeTone.GOLD) }
    // Picture: size, then HDR kinds together.
    badges.firstOrNull { it in RES_SUB }?.let {
        QualityBadge(it, BadgeGlyph.SCREEN, if (it == "4K") BadgeTone.BRIGHT else BadgeTone.PLAIN, caps = false)
    }
    badges.filter { it in HDR_NAMES }.takeIf { it.isNotEmpty() }?.let { l ->
        QualityBadge(if (l.size == 1) HDR_NAMES.getValue(l[0]) else l.joinToString(" · "), BadgeGlyph.SUN, BadgeTone.BRIGHT)
    }
    // Sound: format and speakers in one.
    badges.filter { it in SOUND }.takeIf { it.isNotEmpty() }?.let { l ->
        QualityBadge(l.joinToString(" "), BadgeGlyph.SPEAKER, if ("Atmos" in l || "TrueHD" in l || "DTS-HD" in l) BadgeTone.BRIGHT else BadgeTone.PLAIN)
    }
    codec?.let { QualityBadge(it, BadgeGlyph.CHIP) }
}
