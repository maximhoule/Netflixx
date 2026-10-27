package com.maxim.marquee.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.focus.focusProperties

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import coil.compose.AsyncImage
import coil.imageLoader
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

/** Left margin of the tab screens (the menu is a bar along the top). */
val RAIL = 32.dp
/** Height of the menu bar along the top. */
val TOP_BAR = 56.dp

/** Focusable, clickable box with the TV "grow + outline on focus" treatment. */
@Composable
fun Tile(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    focusedScale: Float = 1.08f,
    border: Boolean = true,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onFocusChange: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(110, easing = FastOutSlowInEasing), label = "tileScale")
    val interaction = remember { MutableInteractionSource() }
    val gate = LocalFocusGate.current
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            // A screen kept in the background (see KeepAlive) can't take focus.
            .focusProperties { canFocus = gate() }
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged {
                val f = it.isFocused
                if (f && !focused) onFocus()
                if (f != focused) onFocusChange?.invoke(f)
                focused = f
            }
            .onPreviewKeyEvent { e ->
                // Holding OK opens the long-press action instead of clicking.
                if (onLongClick == null) return@onPreviewKeyEvent false
                val ok = e.key == Key.DirectionCenter || e.key == Key.Enter || e.key == Key.NumPadEnter
                if (!ok) return@onPreviewKeyEvent false
                val ne = e.nativeKeyEvent
                when (e.type) {
                    KeyEventType.KeyDown -> when {
                        ne.repeatCount == 0 -> { longFired = false; false }
                        !longFired -> { longFired = true; onLongClick(); true }
                        else -> true
                    }
                    KeyEventType.KeyUp -> when {
                        longFired -> { longFired = false; true }
                        // Remotes that don't auto-repeat: decide by how long OK was held.
                        ne.eventTime - ne.downTime >= 450 -> { onLongClick(); true }
                        else -> false
                    }
                    else -> false
                }
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

/** Tile shapes, matching Stremio's posterShape values. */
enum class CardShape(val aspect: Float, val width: Dp) {
    POSTER(2f / 3f, 118.dp),
    LANDSCAPE(16f / 9f, 236.dp),
    SQUARE(1f, 150.dp);

    companion object {
        fun of(s: String?): CardShape? = when (s?.lowercase()) {
            "landscape" -> LANDSCAPE
            "square" -> SQUARE
            "poster", "regular" -> POSTER
            else -> null
        }

        /** Shape that fits an image's real width/height ratio. */
        fun forAspect(a: Float): CardShape = when {
            a > 1.25f -> LANDSCAPE
            a > 0.85f -> SQUARE
            else -> POSTER
        }
    }
}

@Composable
fun PosterCard(
    item: CardItem,
    width: Dp,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
    shape: CardShape = CardShape.POSTER,
    onImageAspect: ((Float) -> Unit)? = null,
    /** Red label along the bottom ("New Episode", "Recently Added"…). */
    ribbon: String? = null,
    /** Shows the red TOP 10 corner badge. */
    topRank: Int? = null,
    /** Widen into a landscape preview after resting on the card (portrait cards only). */
    expandable: Boolean = false,
    /** Show the IMDb score in the corner (catalog rows). */
    showRating: Boolean = false,
    onClick: () -> Unit,
) {
    val baseW = if (shape == CardShape.POSTER) width else shape.width
    val h = baseW / shape.aspect
    val canExpand = expandable && shape == CardShape.POSTER
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    // Only the focused card runs a timer (not one per card on screen).
    if (canExpand && focused) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(900)
            expanded = true
        }
    }
    // Opens smoothly, closes instantly: a row sliding back while the screen scrolls looks sluggish.
    val open = expanded && focused
    val w by animateDpAsState(
        if (open) h * 16f / 9f else baseW,
        if (open) tween(230, easing = FastOutSlowInEasing) else androidx.compose.animation.core.snap(),
        label = "cardWidth",
    )
    Tile(
        modifier = Modifier.width(w).height(h),
        focusedScale = if (canExpand) 1.05f else 1.08f,
        focusRequester = focusRequester,
        onFocus = onFocus,
        onFocusChange = { focused = it; if (!it) expanded = false },
        onLongClick = onLongClick,
        onClick = onClick,
    ) {
        Box(Modifier.fillMaxSize().background(C.Surface))
        // The title as text only when there's no artwork (laying out text for every card costs time).
        var imageFailed by remember(item.meta.poster) { mutableStateOf(false) }
        if (item.meta.poster == null || imageFailed) {
            Text(
                item.meta.name,
                color = C.Dim,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.Center).padding(8.dp),
            )
        }
        if (item.meta.poster != null) {
            AsyncImage(
                model = item.meta.poster,
                contentDescription = item.meta.name,
                contentScale = ContentScale.Crop,
                onSuccess = { st ->
                    val sz = st.painter.intrinsicSize
                    if (onImageAspect != null && sz.width > 0f && sz.height > 0f) onImageAspect(sz.width / sz.height)
                },
                onError = { imageFailed = true },
                modifier = Modifier.width(baseW).fillMaxHeight(),
            )
        }
        if (canExpand && (open || w > baseW + 1.dp)) {
            ExpandedPreview(item, Modifier.fillMaxSize().graphicsLayer { alpha = ((w - baseW) / (h * 16f / 9f - baseW)).coerceIn(0f, 1f) })
        }
        if (!open) {
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
            if (item.badge == null && showRating) item.meta.imdbRating?.takeIf { it.isNotBlank() && it != "0" && it != "0.0" }?.let {
                ImdbBadge(it, Modifier.align(Alignment.TopStart).padding(6.dp))
            }
            if (topRank != null) TopBadge(Modifier.align(Alignment.TopEnd))
            if (ribbon != null && item.progress == null) Ribbon(ribbon, Modifier.align(Alignment.BottomCenter))
        }
        item.progress?.let {
            ProgressLine(it, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(8.dp))
        }
    }
}

/** The wide card: backdrop, logo and a line of facts. */
@Composable
private fun ExpandedPreview(item: CardItem, modifier: Modifier) {
    val m0 = item.meta
    var full by remember(m0.id) { mutableStateOf(com.maxim.marquee.data.Repo.cachedMeta(m0.type, m0.id)) }
    LaunchedEffect(m0.id) {
        if (full == null) full = com.maxim.marquee.data.Repo.meta(m0.type, m0.id)
    }
    val m = full ?: m0
    Box(modifier.background(C.Surface)) {
        val bg = m.background ?: m0.backdropUrl
        if (bg != null) {
            AsyncImage(bg, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color(0xE6000000))))
        Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 12.dp, vertical = 10.dp)) {
            TitleArt(m, small = true)
            Spacer(Modifier.height(6.dp))
            val facts = listOfNotNull(
                m.imdbRating?.let { "★ $it" },
                m.releaseInfo,
                if (m.isSeries && m.seasonCount > 0) seasonsLabel(m.seasonCount) else formatRuntime(m.runtime),
                m.genres.firstOrNull(),
            )
            Text(facts.joinToString("  ·  "), color = Color(0xFFE6E6E6), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            item.badge?.let { Text(it, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
        }
    }
}

/** Netflix's little red "TOP 10" square. */
@Composable
fun TopBadge(modifier: Modifier = Modifier) {
    Column(
        modifier.background(C.Red, RoundedCornerShape(bottomStart = 4.dp)).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("TOP", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Bold, lineHeight = 8.sp)
        Text("10", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, lineHeight = 12.sp)
    }
}

@Composable
fun Ribbon(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = Color.White,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        modifier = modifier
            .padding(bottom = 8.dp)
            .background(C.Red, RoundedCornerShape(3.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** A Top 10 entry: the big outlined number with the poster tucked against it. */
@Composable
fun Top10Card(
    rank: Int,
    item: CardItem,
    focusRequester: FocusRequester?,
    onFocus: () -> Unit,
    onLongClick: () -> Unit,
    onClick: () -> Unit,
) {
    val h = CardShape.POSTER.width / CardShape.POSTER.aspect
    Row(verticalAlignment = Alignment.Bottom) {
        Box(Modifier.height(h).width(if (rank >= 10) 112.dp else 70.dp), contentAlignment = Alignment.BottomEnd) {
            Text(
                rank.toString(),
                style = TextStyle(
                    color = Color(0xFF6B6B6B),
                    fontSize = 132.sp,
                    lineHeight = 132.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-6).sp,
                    drawStyle = Stroke(width = 5f),
                ),
                maxLines = 1,
                modifier = Modifier.offset(x = 14.dp, y = 14.dp),
            )
        }
        PosterCard(
            item = item,
            width = CardShape.POSTER.width,
            focusRequester = focusRequester,
            onFocus = onFocus,
            onLongClick = onLongClick,
            onClick = onClick,
        )
    }
}

fun seasonsLabel(n: Int) = if (n == 1) "1 Season" else "$n Seasons"

/** "142 min" → "2h 22m". Leaves anything it doesn't understand as it is. */
fun formatRuntime(r: String?): String? {
    if (r.isNullOrBlank()) return null
    val mins = Regex("""^\s*(\d+)\s*(min|m|minutes)?\s*$""", RegexOption.IGNORE_CASE).find(r)?.groupValues?.get(1)?.toIntOrNull()
        ?: return r
    return when {
        mins >= 60 && mins % 60 == 0 -> "${mins / 60}h"
        mins >= 60 -> "${mins / 60}h ${mins % 60}m"
        else -> "${mins}m"
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
fun Backdrop(url: String?, modifier: Modifier = Modifier, wide: Boolean = false, dim: Float = 0f, video: (@Composable () -> Unit)? = null) {
    // Keep showing the current picture until the next one is decoded, then crossfade:
    // no flash of black between titles.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var shown by remember { mutableStateOf(url) }
    LaunchedEffect(url) {
        if (url != null && url != shown) {
            safeLoad { ctx.imageLoader.execute(backdropRequest(ctx, url)) }
        }
        shown = url
    }
    Box(modifier) {
        Crossfade(targetState = shown, animationSpec = tween(260), label = "backdrop") { u ->
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
        video?.invoke()
        if (wide) {
            // Full-screen picture: a soft shade on the left for the text, the top for the menu,
            // and a fade into black at the bottom. Each shade only covers the part of the screen
            // it darkens (less for the TV's graphics chip to blend on every frame).
            Box(
                Modifier.matchParentSize().drawWithCache {
                    val w = size.width
                    val h = size.height
                    val left = Brush.horizontalGradient(
                        0f to C.Bg.copy(alpha = 0.88f), 0.54f to C.Bg.copy(alpha = 0.55f), 1f to Color.Transparent,
                        startX = 0f, endX = w * 0.7f,
                    )
                    val top = Brush.verticalGradient(0f to C.Bg.copy(alpha = 0.55f), 1f to Color.Transparent, startY = 0f, endY = h * 0.16f)
                    val bottom = Brush.verticalGradient(0f to Color.Transparent, 1f to C.Bg, startY = h * 0.6f, endY = h)
                    onDrawBehind {
                        if (dim > 0f) drawRect(C.Bg.copy(alpha = dim))
                        drawRect(left, size = androidx.compose.ui.geometry.Size(w * 0.7f, h))
                        drawRect(top, size = androidx.compose.ui.geometry.Size(w, h * 0.16f))
                        drawRect(bottom, topLeft = Offset(0f, h * 0.6f), size = androidx.compose.ui.geometry.Size(w, h * 0.4f))
                    }
                },
            )
        } else {
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
}

/** Backdrops are decoded once at a size that fits every place they appear (billboard and details). */
fun backdropRequest(ctx: android.content.Context, url: String): coil.request.ImageRequest {
    val d = ctx.resources.displayMetrics
    return coil.request.ImageRequest.Builder(ctx).data(url).size((d.widthPixels * 0.8f).toInt(), d.heightPixels).build()
}

private suspend fun safeLoad(block: suspend () -> Unit) {
    try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        // show it anyway; AsyncImage will retry
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

/**
 * Netflix-style facts row: IMDb score, year, boxed age rating, seasons or runtime, genres.
 * [cert] overrides the title's own age rating (e.g. from TMDB).
 */
@Composable
fun InfoRow(m: MetaItem, cert: String? = null, leading: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val gap = Modifier.width(12.dp)
        leading?.let {
            Text(it, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(gap)
        }
        m.imdbRating?.let { r ->
            Text(
                "IMDb", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(3.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(r, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(gap)
        }
        m.releaseInfo?.let {
            Text(it, color = C.Dim, fontSize = 15.sp)
            Spacer(gap)
        }
        (cert ?: m.certification)?.let {
            Text(
                it, color = Color(0xFFDDDDDD), fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.border(1.dp, Color(0xFF9A9A9A), RoundedCornerShape(2.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
            Spacer(gap)
        }
        val length = if (m.isSeries && m.seasonCount > 0) seasonsLabel(m.seasonCount) else formatRuntime(m.runtime)
        length?.let {
            Text(it, color = C.Dim, fontSize = 15.sp)
            Spacer(gap)
        }
        if (m.genres.isNotEmpty()) {
            Text(m.genres.take(3).joinToString(" • "), color = C.Dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The title as logo artwork when there is one (the addon's, TMDB's, or the community artwork
 * for IMDb titles), falling back to text. [extraLogo] is tried after the addon's own logo.
 */
@Composable
fun TitleArt(meta: MetaItem, modifier: Modifier = Modifier, big: Boolean = true, small: Boolean = false, extraLogo: String? = null) {
    val candidates = remember(meta.id, meta.logo, extraLogo) { listOfNotNull(meta.logo, extraLogo, meta.logoUrl).distinct() }
    var attempt by remember(candidates) { mutableIntStateOf(0) }
    val url = candidates.getOrNull(attempt)
    val (maxW, maxH) = when {
        small -> 170.dp to 44.dp
        big -> 420.dp to 120.dp
        else -> 340.dp to 84.dp
    }
    if (url != null) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        // Some logos are black (made for light pages): those are shown white, like on Netflix.
        var dark by remember(url) { mutableStateOf(darkLogos[url] == true) }
        val request = remember(url) {
            coil.request.ImageRequest.Builder(ctx).data(url).allowHardware(false).build()
        }
        AsyncImage(
            model = request,
            contentDescription = meta.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { attempt++ },
            onSuccess = { st ->
                val known = darkLogos[url]
                if (known == null) {
                    val bmp = (st.result.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
                    val isDark = bmp != null && isDarkLogo(bmp)
                    darkLogos[url] = isDark
                    dark = isDark
                }
            },
            colorFilter = if (dark) androidx.compose.ui.graphics.ColorFilter.tint(Color.White) else null,
            modifier = modifier.height(maxH).width(maxW),
        )
    } else {
        Text(
            meta.name,
            color = Color.White,
            fontSize = if (small) 17.sp else if (big) 40.sp else 32.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = if (small) 20.sp else if (big) 44.sp else 36.sp,
            modifier = modifier,
        )
    }
}

// ---------------------------------------------------------------- loading placeholders

@Composable
fun rememberShimmer(): State<Float> =
    if (!LocalScreenVisible.current) {
        // On a screen kept alive in the background: no animation (it would keep the TV redrawing).
        remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    } else {
        rememberInfiniteTransition(label = "shimmer").animateFloat(
            initialValue = -0.6f,
            targetValue = 1.6f,
            animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
            label = "shimmerX",
        )
    }

/** Grey box with a soft light sweeping across it. Animates in the draw phase only (cheap). */
fun Modifier.shimmer(progress: State<Float>, shape: Shape = RoundedCornerShape(6.dp)): Modifier =
    this.clip(shape).drawBehind {
        drawRect(Color(0xFF1C1C1C))
        val x = progress.value * size.width
        val band = size.width * 0.6f
        drawRect(
            Brush.linearGradient(
                listOf(Color.Transparent, Color(0x14FFFFFF), Color.Transparent),
                start = Offset(x - band, 0f),
                end = Offset(x, size.height * 0.3f),
            ),
        )
    }

@Composable
fun SkeletonRow(shape: CardShape = CardShape.POSTER, withTitle: Boolean = false) {
    val p = rememberShimmer()
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        if (withTitle) Box(Modifier.padding(start = 14.dp, top = 10.dp).width(180.dp).height(16.dp).shimmer(p, RoundedCornerShape(4.dp)))
        Row(Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            repeat(if (shape == CardShape.POSTER) 8 else 4) {
                Box(Modifier.width(shape.width).height(shape.width / shape.aspect).shimmer(p))
            }
        }
    }
}

/** Billboard placeholder until the first title is ready. */
@Composable
fun SkeletonHero(modifier: Modifier = Modifier) {
    val p = rememberShimmer()
    Column(modifier) {
        Box(Modifier.width(360.dp).height(70.dp).shimmer(p))
        Spacer(Modifier.height(14.dp))
        Box(Modifier.width(300.dp).height(14.dp).shimmer(p, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(10.dp))
        Box(Modifier.width(520.dp).height(14.dp).shimmer(p, RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(8.dp))
        Box(Modifier.width(460.dp).height(14.dp).shimmer(p, RoundedCornerShape(4.dp)))
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

/** Set while a direction key is being held down (auto-repeat). */
object Nav {
    @Volatile
    var keyHeld = false
}

/** Keeps the focused item pinned at a fixed offset from the start of a list (Netflix-style rows). */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val leadingPx: Float) : BringIntoViewSpec {
    // A stiff, non-bouncy spring: a single press settles in about an eighth of a second, and when
    // a direction is held each new step folds into the running motion (keeping its speed), so the
    // row glides continuously with the remote instead of jumping card by card.
    override val scrollAnimationSpec: AnimationSpec<Float>
        get() = if (Nav.keyHeld) HELD else QUICK

    private val QUICK: AnimationSpec<Float> = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 1800f)
    private val HELD: AnimationSpec<Float> = androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 2600f)

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        offset - leadingPx
}

fun vectorIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White))
        .build()

val MovieIcon: ImageVector by lazy {
    vectorIcon(
        "movie",
        "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z",
    )
}

/** Trophy, for the Sports tab. */
val SportsIcon: ImageVector by lazy {
    vectorIcon(
        "sports",
        "M19,5h-2V3H7v2H5C3.9,5 3,5.9 3,7v1c0,2.55 1.92,4.63 4.39,4.94c0.63,1.5 1.98,2.63 3.61,2.96V19H7v2h10v-2h-4v-3.1" +
            "c1.63,-0.33 2.98,-1.46 3.61,-2.96C19.08,12.63 21,10.55 21,8V7C21,5.9 20.1,5 19,5zM5,8V7h2v3.82C5.84,10.4 5,9.3 5,8z" +
            "M19,8c0,1.3 -0.84,2.4 -2,2.82V7h2V8z",
    )
}

val TvIcon: ImageVector by lazy {
    vectorIcon(
        "tv",
        "M21,3H3c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h5v2h8v-2h5c1.1,0 1.99,-0.9 1.99,-2L23,5c0,-1.1 -0.9,-2 -2,-2zM21,17H3V5h18v12z",
    )
}

object PlayerIcons {
    val Pause by lazy { vectorIcon("pause", "M6,19h4V5H6v14zm8,-14v14h4V5h-4z") }
    val Play by lazy { vectorIcon("play", "M8,5v14l11,-7z") }
    val Replay by lazy {
        vectorIcon("replay", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    }
    val Forward by lazy {
        vectorIcon("forward", "M12,5V1l5,5 -5,5V7c-3.31,0 -6,2.69 -6,6s2.69,6 6,6 6,-2.69 6,-6h2c0,4.42 -3.58,8 -8,8s-8,-3.58 -8,-8 3.58,-8 8,-8z")
    }
    val Subtitles by lazy {
        vectorIcon(
            "cc",
            "M19,4H5c-1.11,0 -2,0.9 -2,2v12c0,1.1 0.89,2 2,2h14c1.1,0 2,-0.9 2,-2V6c0,-1.1 -0.9,-2 -2,-2zM11,11H9.5v-0.5h-2v3h2V13H11v1c0,0.55 -0.45,1 -1,1H7c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1zM18,11h-1.5v-0.5h-2v3h2V13H18v1c0,0.55 -0.45,1 -1,1h-3c-0.55,0 -1,-0.45 -1,-1v-4c0,-0.55 0.45,-1 1,-1h3c0.55,0 1,0.45 1,1v1z",
        )
    }
    val Speed by lazy {
        vectorIcon(
            "speed",
            "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58H5.07A8,8 0,0 1,15.58 6.85l1.85,-1.23A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44zM10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z",
        )
    }
    val Episodes by lazy {
        vectorIcon(
            "episodes",
            "M4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6zM20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2zM12,14.5v-9l6,4.5 -6,4.5z",
        )
    }
    val Picture by lazy {
        vectorIcon(
            "aspect",
            "M19,12h-2v3h-3v2h5v-5zM7,9h3V7H5v5h2V9zM21,3H3c-1.1,0 -2,0.9 -2,2v14c0,1.1 0.9,2 2,2h18c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM21,19.01H3V4.99h18v14.02z",
        )
    }
    val Next by lazy { vectorIcon("next", "M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val Check by lazy { vectorIcon("check", "M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }
    val DoneAll by lazy {
        vectorIcon(
            "done_all",
            "M18,7l-1.41,-1.41 -6.34,6.34 1.41,1.41L18,7zM22.24,5.59L11.66,16.17 7.48,12l-1.41,1.41L11.66,19l12,-12 -1.42,-1.41zM0.41,13.41L6,19l1.41,-1.41L1.83,12 0.41,13.41z",
        )
    }
    val Trailer by lazy {
        vectorIcon("trailer", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z")
    }
    val Restart by lazy {
        vectorIcon("restart", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    }
}


/** False inside a screen that's kept alive but hidden: looping animations pause there. */
val LocalScreenVisible = androidx.compose.runtime.compositionLocalOf { true }

/** Whether the screen something belongs to may take focus. Screens kept alive in the background can't. */
val LocalFocusGate = staticCompositionLocalOf<() -> Boolean> { { true } }

/** Content that can take focus only while [enabled] (and while its enclosing screen can). */
@Composable
fun FocusGated(enabled: () -> Boolean, content: @Composable () -> Unit) {
    val parent = LocalFocusGate.current
    CompositionLocalProvider(LocalFocusGate provides { parent() && enabled() }, content = content)
}

/**
 * Keeps [content] composed while it's hidden (not measured, placed or drawn: it costs nothing per
 * frame), so coming back to it is instant instead of building the whole screen again.
 */
@Composable
fun KeepAlive(visible: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val parentVisible = LocalScreenVisible.current
    Layout({ CompositionLocalProvider(LocalScreenVisible provides (visible && parentVisible), content = content) }, modifier) { measurables, constraints ->
        if (!visible) {
            layout(0, 0) {}
        } else {
            val placeables = measurables.map { it.measure(constraints) }
            val w = constraints.constrainWidth(placeables.maxOfOrNull { it.width } ?: 0)
            val h = constraints.constrainHeight(placeables.maxOfOrNull { it.height } ?: 0)
            layout(w, h) { placeables.forEach { it.place(0, 0) } }
        }
    }
}

/** "IMDb 7.5" corner badge. */
@Composable
fun ImdbBadge(rating: String, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color(0xD9000000), RoundedCornerShape(3.dp)).padding(start = 2.dp, end = 5.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "IMDb", color = Color.Black, fontSize = 8.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.background(Color(0xFFF5C518), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(rating, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * A button with a ring around it while focused: [primary] is solid white (Play), the other kind
 * is translucent grey; both keep their look and get the ring, so focus is always clear.
 */
@Composable
fun PillButton(
    text: String,
    icon: ImageVector? = null,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onFocus: () -> Unit = {},
    onClick: () -> Unit,
) {
    Tile(
        modifier = modifier.height(50.dp),
        shape = RoundedCornerShape(10.dp),
        focusedScale = 1.04f,
        border = false,
        focusRequester = focusRequester,
        onFocus = onFocus,
        onClick = onClick,
    ) { f ->
        val bg = when {
            primary -> Color.White
            f -> Color.White
            else -> Color(0x806D6D6E)
        }
        val fg = if (primary || f) Color.Black else Color.White
        Row(
            Modifier
                .fillMaxHeight()
                .border(2.dp, if (f) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
                .padding(4.dp)
                .background(bg, RoundedCornerShape(6.dp))
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, tint = fg, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(9.dp))
            }
            Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** A round outlined icon button (white when focused); its name is shown by the screen. */
@Composable
fun RoundAction(icon: ImageVector, label: String, onFocus: () -> Unit = {}, onClick: () -> Unit) {
    Tile(
        modifier = Modifier.size(46.dp),
        shape = RoundedCornerShape(23.dp),
        focusedScale = 1.08f,
        border = false,
        onFocus = onFocus,
        onClick = onClick,
    ) { f ->
        Box(
            Modifier.fillMaxSize()
                .background(if (f) Color.White else Color(0x26FFFFFF), RoundedCornerShape(23.dp))
                .border(1.5.dp, if (f) Color.White else Color(0x80FFFFFF), RoundedCornerShape(23.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, label, tint = if (f) Color.Black else Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

/** Logos already checked: true = mostly black artwork. */
private val darkLogos = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

/** Average brightness of the logo's visible pixels (a 24×24 sample) is low. */
private fun isDarkLogo(b: android.graphics.Bitmap): Boolean = try {
    var sum = 0.0
    var n = 0
    val w = b.width
    val h = b.height
    for (y in 0 until 24) for (x in 0 until 24) {
        val c = b.getPixel(x * (w - 1) / 23, y * (h - 1) / 23)
        if (android.graphics.Color.alpha(c) < 140) continue
        sum += 0.299 * android.graphics.Color.red(c) + 0.587 * android.graphics.Color.green(c) + 0.114 * android.graphics.Color.blue(c)
        n++
    }
    n >= 12 && sum / n < 60
} catch (e: Exception) {
    false
}
