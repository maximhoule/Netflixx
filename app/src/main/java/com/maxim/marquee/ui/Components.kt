package com.maxim.marquee.ui

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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
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
    onFocusChange: ((Boolean) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    content: @Composable BoxScope.(focused: Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) focusedScale else 1f, tween(110, easing = FastOutSlowInEasing), label = "tileScale")
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
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
    onClick: () -> Unit,
) {
    val baseW = if (shape == CardShape.POSTER) width else shape.width
    val h = baseW / shape.aspect
    val canExpand = expandable && shape == CardShape.POSTER
    var focused by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    if (canExpand) {
        LaunchedEffect(focused) {
            if (focused) {
                kotlinx.coroutines.delay(900)
                expanded = true
            } else {
                expanded = false
            }
        }
    }
    val w by animateDpAsState(if (expanded) h * 16f / 9f else baseW, tween(230, easing = FastOutSlowInEasing), label = "cardWidth")
    Tile(
        modifier = Modifier.width(w).height(h),
        focusedScale = if (canExpand) 1.05f else 1.08f,
        focusRequester = focusRequester,
        onFocus = onFocus,
        onFocusChange = { focused = it },
        onLongClick = onLongClick,
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
                onSuccess = { st ->
                    val sz = st.painter.intrinsicSize
                    if (onImageAspect != null && sz.width > 0f && sz.height > 0f) onImageAspect(sz.width / sz.height)
                },
                modifier = Modifier.width(baseW).fillMaxHeight(),
            )
        }
        if (canExpand && (expanded || w > baseW + 1.dp)) {
            ExpandedPreview(item, Modifier.fillMaxSize().graphicsLayer { alpha = ((w - baseW) / (h * 16f / 9f - baseW)).coerceIn(0f, 1f) })
        }
        if (!expanded) {
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
        val bg = m.background ?: m0.background
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
fun Backdrop(url: String?, modifier: Modifier = Modifier, video: (@Composable () -> Unit)? = null) {
    Box(modifier) {
        Crossfade(targetState = url, animationSpec = tween(220), label = "backdrop") { u ->
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
        AsyncImage(
            model = url,
            contentDescription = meta.name,
            contentScale = ContentScale.Fit,
            alignment = Alignment.BottomStart,
            onError = { attempt++ },
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
    rememberInfiniteTransition(label = "shimmer").animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerX",
    )

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

/** Keeps the focused item pinned at a fixed offset from the start of a list (Netflix-style rows). */
@OptIn(ExperimentalFoundationApi::class)
class PivotSpec(private val leadingPx: Float) : BringIntoViewSpec {
    // Short, decisive scroll (the default spring feels floaty with a remote).
    override val scrollAnimationSpec: AnimationSpec<Float> = tween(170, easing = FastOutSlowInEasing)

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
    val Trailer by lazy {
        vectorIcon("trailer", "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V4h-4z")
    }
    val Restart by lazy {
        vectorIcon("restart", "M12,5V1L7,6l5,5V7c3.31,0 6,2.69 6,6s-2.69,6 -6,6 -6,-2.69 -6,-6H4c0,4.42 3.58,8 8,8s8,-3.58 8,-8 -3.58,-8 -8,-8z")
    }
}
