package com.maxim.marquee.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import com.maxim.marquee.data.CardItem
import com.maxim.marquee.data.MetaItem

object C {
    val Bg = Color(0xFF000000)
    val Surface = Color(0xFF1A1A1A)
    val Red = Color(0xFFE21B2D)
    val Text = Color(0xFFFFFFFF)
    val Dim = Color(0xFFB3B3B3)
    val Faint = Color(0x40FFFFFF)
    val Green = Color(0xFF46D369)
}

val RAIL = 72.dp

/** Focusable, clickable box with the TV "grow + outline on focus" treatment. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    focusedScale: Float = 1.08f,
    border: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, label = "tileScale")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val f = it.isFocused
                if (f && !focused) onFocus()
                focused = f
            }
            .clip(shape)
            .then(if (focused && border) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        content(focused)
    }
}

@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).background(C.Faint)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(C.Red))
    }
}

@Composable
fun PosterCard(
    item: CardItem,
    width: Dp,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Tile(
        modifier = Modifier.width(width).aspectRatio(2f / 3f),
        focusRequester = focusRequester,
        onFocus = onFocus,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize().background(C.Surface))
        Text(
            item.meta.name,
            color = C.Dim,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.Center).padding(8.dp),
        )
        if (item.meta.poster != null) {
            AsyncImage(
                model = item.meta.poster,
                contentDescription = item.meta.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        item.badge?.let {
            Text(
                it,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .background(Color(0xCC000000), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            )
        }
        item.progress?.let {
            ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
        }
    }
}

/** Netflix-TV style button: translucent at rest, solid white when focused. */
@Composable
fun ActionButton(
    text: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Tile(
        modifier = modifier.height(46.dp),
        shape = RoundedCornerShape(6.dp),
        focusedScale = 1.05f,
        border = false,
        focusRequester = focusRequester,
        onFocus = onFocus,
        onClick = onClick,
    ) { f ->
        Row(
            Modifier
                .fillMaxHeight()
                .background(if (f) Color.White else Color(0x4DFFFFFF))
                .padding(horizontal = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = if (f) Color.Black else Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Full-bleed artwork that fades into black on the left and bottom. */
@Composable
fun Backdrop(url: String?, modifier: Modifier = Modifier) {
    Box(modifier) {
        Crossfade(targetState = url, label = "backdrop") { u ->
            if (u != null) {
                AsyncImage(
                    model = u,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = Alignment.TopCenter,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(0f to C.Bg, 0.3f to C.Bg.copy(alpha = 0.75f), 0.65f to Color.Transparent),
            ),
        )
        Box(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(0.45f to Color.Transparent, 1f to C.Bg),
            ),
        )
    }
}

/** "2023 · ★ 7.9 · 2h 10min · Drama • Thriller" */
fun infoLine(m: MetaItem): String {
    val parts = mutableListOf<String>()
    m.releaseInfo?.let { parts += it }
    m.imdbRating?.let { parts += "★ $it" }
    m.runtime?.let { parts += it }
    if (m.genres.isNotEmpty()) parts += m.genres.take(3).joinToString(" • ")
    return parts.joinToString("   ·   ")
}

@Composable
fun TitleArt(meta: MetaItem, modifier: Modifier = Modifier, big: Boolean = true) {
    var logoFailed by remember(meta.logo) { mutableStateOf(false) }
    if (meta.logo != null && !logoFailed) {
        AsyncImage(
            model = meta.logo,
            contentDescription = meta.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart,
            onError = { logoFailed = true },
            modifier = modifier.height(if (big) 100.dp else 80.dp).width(380.dp),
        )
    } else {
        Text(
            meta.name,
            color = Color.White,
            fontSize = if (big) 40.sp else 32.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = if (big) 44.sp else 36.sp,
            modifier = modifier,
        )
    }
}

@Composable
fun Centered(text: String, sub: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            if (sub != null) Text(sub, color = C.Dim, fontSize = 15.sp, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** Keeps the focused item pinned at a fixed offset from the start of a list (Netflix-style rows). */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val leadingPx: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - leadingPx
}

private fun icon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
        .build()

val MovieIcon: ImageVector by lazy {
    icon(
        "movie",
        "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z",
    )
}

val TvIcon: ImageVector by lazy {
    icon(
        "tv",
        "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z",
    )
}
