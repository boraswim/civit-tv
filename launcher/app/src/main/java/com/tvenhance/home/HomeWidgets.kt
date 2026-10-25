package com.tvenhance.home

import android.os.SystemClock
import android.text.format.DateFormat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import java.io.File
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Focus ring for everything you can navigate to: ÇivitTV's cobalt blue. */
internal val FocusBlue = Color(0xFF4F97F2)
internal val FocusWidth = 4.5.dp

private val CardShape = RoundedCornerShape(16.dp)
private val GlassShape = RoundedCornerShape(20.dp)
private val Glass = Color.White.copy(alpha = 0.07f)
private val GlassEdge = Color.White.copy(alpha = 0.10f)
private val Placeholder = Color.White.copy(alpha = 0.06f)

/** Top shelf for a focused dock app: popular titles, top songs, or recently played items. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfView(app: AppEntry, shelf: Shelf, onOpen: (ShelfItem) -> Unit, onFocusItem: (ShelfItem) -> Unit) {
    var current by remember(shelf.pkg, shelf.items.firstOrNull()) { mutableStateOf(shelf.items.first()) }
    // Card heights per layout, sized so the whole screen stays inside the TV safe area (see
    // ShelfHeight). Up to 10 cards; the row scrolls sideways when they don't all fit.
    val height = when (shelf.layout) {
        ShelfLayout.Featured -> 126.dp
        ShelfLayout.Squares, ShelfLayout.Channels -> 124.dp
        ShelfLayout.Wide -> 114.dp
    }
    val density = LocalDensity.current
    val rowScroll = rememberScrollState()
    val rowSpec = remember(density) { EdgeBringIntoViewSpec(with(density) { 40.dp.toPx() }) }
    // Starts below the header (logo + wordmark), which ends around 55 dp.
    Column(Modifier.fillMaxSize().padding(top = 64.dp)) {
        BasicText(
            shelf.heading.uppercase(LocalContext.current.resources.configuration.locales[0]),
            Modifier.padding(horizontal = SidePad),
            style = TextStyle(
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.4.sp,
            ),
        )
        Spacer(Modifier.height(12.dp))
        // The row runs edge to edge (cards line up with SidePad at rest) so scrolled or enlarged
        // cards are never clipped at the content margins.
        CompositionLocalProvider(LocalBringIntoViewSpec provides rowSpec) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rowScroll).padding(horizontal = SidePad),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            shelf.items.take(SHELF_CARDS).forEachIndexed { i, item ->
                val aspect = when (shelf.layout) {
                    ShelfLayout.Featured -> if (i == 0) 16f / 9f else 2f / 3f
                    ShelfLayout.Squares, ShelfLayout.Channels -> 1f
                    ShelfLayout.Wide -> 16f / 9f
                }
                ShelfCard(
                    item = item,
                    model = if (aspect > 1f) item.wideImage else item.image,
                    logo = shelf.layout == ShelfLayout.Channels,
                    fallback = app,
                    height = height,
                    aspect = aspect,
                    onFocused = { current = item; onFocusItem(item) },
                    onClick = { onOpen(item) },
                )
            }
        }
        }
        Spacer(Modifier.height(12.dp))
        BasicText(
            current.title,
            Modifier.padding(horizontal = SidePad),
            style = TextStyle(color = Color.White, fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        BasicText(
            current.meta,
            Modifier.padding(horizontal = SidePad),
            style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private const val SHELF_CARDS = 10

/** Scrolls a shelf row just enough to show the focused card plus a margin, and no more. */
@OptIn(ExperimentalFoundationApi::class)
private class EdgeBringIntoViewSpec(private val margin: Float) : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val start = offset - margin
        val end = offset + size + margin
        return when {
            start >= 0 && end <= containerSize -> 0f
            start < 0 -> start
            else -> end - containerSize
        }
    }
}

@Composable
private fun ShelfCard(
    item: ShelfItem,
    model: Any?,
    logo: Boolean,
    fallback: AppEntry,
    height: Dp,
    aspect: Float,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, spring(dampingRatio = 0.7f, stiffness = 400f), label = "card")
    Box(
        Modifier
            .height(height)
            .aspectRatio(aspect)
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                shadowElevation = if (focused) 22.dp.toPx() else 4.dp.toPx()
                shape = CardShape
                clip = true
            }
            .background(Placeholder)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused) {
                    UiSounds.tick()
                    onFocused()
                }
            }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
    ) {
        if (model != null && logo) {
            // Channel logos are small transparent marks: show them whole on a soft tile.
            AsyncImage(
                model = model,
                contentDescription = item.title,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.10f)).padding(26.dp),
            )
        } else if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // No artwork (some players don't share any): the app's banner with the title on top.
            Image(fallback.banner, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))
                )
            )
            BasicText(
                item.title,
                Modifier.align(Alignment.BottomStart).padding(10.dp),
                style = TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // A soft top highlight and the blue focus ring, like light catching a tvOS card.
        if (focused) {
            Box(
                Modifier.fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.14f), 0.45f to Color.Transparent))
                    .border(FocusWidth, FocusBlue, CardShape)
            )
        }
    }
}

/** Frosted-glass panel used by the cards under the dock. */
private fun Modifier.glass(focused: Boolean = false) = this
    .clip(GlassShape)
    .background(if (focused) Color.White.copy(alpha = 0.18f) else Glass)
    .border(if (focused) FocusWidth else 1.dp, if (focused) FocusBlue else GlassEdge, GlassShape)

private val DockShape = RoundedCornerShape(30.dp)

/**
 * The favourites dock: the same frosted glass as the cards below, a little lighter and rounder
 * since it's a bigger surface. Not clipped, so focused tiles can grow past its edges.
 */
fun Modifier.dockGlass() = this
    .background(
        Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 1f to Color.White.copy(alpha = 0.05f)),
        DockShape,
    )
    .border(1.dp, GlassEdge, DockShape)

/** The strip under the dock: what's playing, the weather, and the next ezan. */
@Composable
fun InfoStrip(
    nowPlaying: NowPlaying?,
    weather: WeatherNow?,
    onOpenNowPlaying: (NowPlaying) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = SidePad).padding(top = 10.dp).height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        NowPlayingCard(nowPlaying, Modifier.width(276.dp).fillMaxHeight(), onOpenNowPlaying)
        WeatherCard(weather, Modifier.weight(1f).fillMaxHeight())
        EzanCard(Modifier.width(206.dp).fillMaxHeight())
    }
}

/** Next ezan for Esenyurt and the time left until it, refreshed every 20 seconds. */
@Composable
private fun EzanCard(modifier: Modifier) {
    val context = LocalContext.current
    val ezan = remember { Ezan(context.cacheDir) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    val next by produceState<NextEzan?>(null, now.toLocalDate(), now.hour, now.minute) {
        value = withContext(Dispatchers.IO) { ezan.next(LocalDateTime.now()) }
    }
    WhileVisible {
        while (true) {
            now = LocalDateTime.now()
            delay(20_000)
        }
    }
    // When a prayer time arrives, the card blinks white for 10 seconds showing that prayer.
    // Kept apart from `next`, which already moves on to the following prayer at that moment.
    var arrived by remember { mutableStateOf<NextEzan?>(null) }
    LaunchedEffect(next?.at) {
        val n = next ?: return@LaunchedEffect
        val wait = Duration.between(LocalDateTime.now(), n.at).toMillis()
        if (wait > 0) delay(wait)
        arrived = n
        now = LocalDateTime.now()
    }
    LaunchedEffect(arrived) {
        if (arrived == null) return@LaunchedEffect
        delay(FLASH_MS)
        arrived = null
    }
    // In the last 30 minutes before a prayer the card blinks red for 10 seconds: once when the
    // 30-minute mark passes on screen, and again every time you come back to the home screen.
    var warning by remember { mutableStateOf(false) }
    val currentNext by rememberUpdatedState(next)
    WhileVisible {
        snapshotFlow { currentNext?.at }.filterNotNull().collectLatest { at ->
            val toMark = Duration.between(LocalDateTime.now(), at.minusMinutes(EZAN_SOON_MIN)).toMillis()
            if (toMark > 0) delay(toMark)
            if (LocalDateTime.now() >= at) return@collectLatest
            now = LocalDateTime.now()
            warning = true
            try {
                delay(FLASH_MS)
            } finally {
                warning = false // also when leaving mid-blink, so it never sticks on red
            }
        }
    }
    val flashColor = if (arrived != null) Color.White else EzanRed
    val flash = if (arrived != null || warning) {
        rememberInfiniteTransition(label = "ezanFlash")
            .animateFloat(0.15f, 0.95f, infiniteRepeatable(tween(500), RepeatMode.Reverse), label = "blink")
            .value
    } else {
        0f
    }
    // Text goes dark while the card is white, so it stays readable through the blink.
    val white = arrived != null
    val ink = if (white) lerp(Color.White, Color(0xFF111114), flash) else Color.White
    Column(
        modifier
            .glass()
            .background(flashColor.copy(alpha = flash), GlassShape)
            .padding(horizontal = 16.dp, vertical = 5.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(stringResource(R.string.next_ezan), style = Label.copy(color = lerp(EzanAccent, if (white) Color(0xFF0B7A4B) else Color.White, flash)))
        val n = arrived ?: next
        if (n == null) {
            BasicText("—", style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 15.sp))
        } else {
            BasicText(
                buildAnnotatedString {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stringResource(n.nameRes)) }
                    withStyle(SpanStyle(color = ink.copy(alpha = 0.75f))) {
                        append("  " + n.at.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
                    }
                },
                style = TextStyle(color = ink, fontSize = 14.sp),
                maxLines = 1,
            )
            // The time left is what matters most, so it's the biggest line.
            val mins = Duration.between(now, n.at).toMinutes().coerceAtLeast(0)
            BasicText(
                when {
                    arrived != null || mins < 1 -> stringResource(R.string.ezan_now)
                    mins < 60 -> stringResource(R.string.ezan_left_m, mins)
                    else -> stringResource(R.string.ezan_left_hm, mins / 60, mins % 60)
                },
                // Blue (the launcher's colour) normally; red in the last 30 minutes and at the ezan itself.
                style = TextStyle(
                    // (white while the card itself blinks red, or it would vanish into it)
                    color = when {
                        warning -> lerp(EzanRed, Color.White, flash)
                        arrived != null || mins <= EZAN_SOON_MIN -> EzanRed
                        else -> FocusBlue
                    },
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                ),
                maxLines = 1,
            )
        }
    }
}

private val EzanAccent = Color(0xFF64D2A0)
private val EzanRed = Color(0xFFFF453A)
private const val FLASH_MS = 10_000L
private const val EZAN_SOON_MIN = 30L

/**
 * ÇivitTV's logo: a little budgie ("çivit" is the bird's cobalt blue) on a frosted tile, after the
 * owner's own bird: white face, blue body, black barring on the back of the head, blue cere,
 * yellow-green beak and black throat spots.
 */
@Composable
fun BirdLogo(modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.20f), 1f to Color.White.copy(alpha = 0.08f)))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
    ) {
        Canvas(Modifier.fillMaxSize().padding(3.dp)) {
            val s = size.minDimension
            fun p(x: Float, y: Float) = Offset(x * s, y * s)
            // Body: cobalt blue, tucked under and behind the head.
            drawOval(
                Brush.linearGradient(listOf(Color(0xFF5AA2F5), Color(0xFF2C63D6)), start = p(0.3f, 0.45f), end = p(0.8f, 0.95f)),
                topLeft = p(0.26f, 0.44f), size = Size(0.58f * s, 0.50f * s),
            )
            // Head: white, with black barring across its back (left side).
            val head = p(0.44f, 0.40f)
            drawCircle(Color(0xFFF4F6F9), radius = 0.27f * s, center = head)
            for (i in 0..2) {
                val r = (0.24f - i * 0.055f) * s
                drawArc(
                    Color(0xFF1C2230), startAngle = 150f, sweepAngle = 75f, useCenter = false,
                    topLeft = Offset(head.x - r, head.y - r), size = Size(2 * r, 2 * r),
                    style = Stroke(width = 0.035f * s, cap = StrokeCap.Round),
                )
            }
            // Eye with a light ring and a catch-light.
            drawCircle(Color(0xFFD9DDE3), radius = 0.050f * s, center = p(0.52f, 0.35f))
            drawCircle(Color(0xFF0E1015), radius = 0.036f * s, center = p(0.52f, 0.35f))
            drawCircle(Color.White, radius = 0.011f * s, center = p(0.509f, 0.338f))
            // Blue cere and the yellow-green beak curving down under it.
            drawOval(Color(0xFF3D6FE0), topLeft = p(0.585f, 0.355f), size = Size(0.13f * s, 0.085f * s))
            val beak = Path().apply {
                moveTo(0.605f * s, 0.43f * s)
                quadraticTo(0.70f * s, 0.43f * s, 0.67f * s, 0.53f * s)
                quadraticTo(0.62f * s, 0.50f * s, 0.605f * s, 0.43f * s)
                close()
            }
            drawPath(beak, Color(0xFFC9CF4F))
            // Black throat spots.
            drawCircle(Color(0xFF14161C), radius = 0.032f * s, center = p(0.43f, 0.60f))
            drawCircle(Color(0xFF14161C), radius = 0.030f * s, center = p(0.52f, 0.63f))
        }
    }
}

/** Round frosted gear button for the top-right corner; opens Android's settings. */
@Composable
fun SettingsButton(onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.15f else 1f, spring(dampingRatio = 0.6f, stiffness = 420f), label = "gear")
    val ink = if (focused) Color(0xFF111114) else Color.White.copy(alpha = 0.9f)
    Box(
        Modifier
            .size(40.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (focused) 12.dp.toPx() else 0f; shape = CircleShape }
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.12f))
            .border(if (focused) FocusWidth else 1.dp, if (focused) FocusBlue else Color.White.copy(alpha = 0.15f), CircleShape)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) UiSounds.tick() }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val r = size.minDimension / 2f
            // Eight teeth around a ring: a simple, crisp gear at any size.
            repeat(8) { i ->
                rotate(i * 45f) {
                    drawRoundRect(
                        ink,
                        topLeft = Offset(center.x - r * 0.15f, center.y - r),
                        size = Size(r * 0.30f, r * 0.42f),
                        cornerRadius = CornerRadius(r * 0.06f),
                    )
                }
            }
            drawCircle(ink, radius = r * 0.56f, style = Stroke(width = r * 0.30f))
        }
    }
}

@Composable
private fun NowPlayingCard(np: NowPlaying?, modifier: Modifier, onOpen: (NowPlaying) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "np")
    Row(
        modifier
            .zIndex(if (focused) 1f else 0f)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .glass(focused)
            .then(
                if (np == null) Modifier else Modifier
                    .onFocusChanged { focused = it.isFocused; if (it.isFocused) UiSounds.tick() }
                    .clickable(remember { MutableInteractionSource() }, indication = null) { onOpen(np) }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val art = np?.item?.art
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(Placeholder), contentAlignment = Alignment.Center) {
            if (art != null) {
                AsyncImage(
                    model = if (art.startsWith("/")) File(art) else art,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                BasicText("♫", style = TextStyle(color = Color.White.copy(alpha = 0.5f), fontSize = 22.sp))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                stringResource(if (np == null || np.playing) R.string.now_playing else R.string.last_played),
                style = Label.copy(color = EzanAccent), // same green as the ezan card's label
            )
            BasicText(
                np?.item?.title ?: stringResource(R.string.nothing_playing),
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (np != null && np.item.subtitle.isNotBlank()) {
                BasicText(
                    np.item.subtitle,
                    style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (np != null) {
            BasicText(
                if (np.playing) "❚❚" else "▶",
                style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp),
            )
        }
    }
}

/** Today (date, conditions, city) on the left, the next five days on the right. */
@Composable
private fun WeatherCard(weather: WeatherNow?, modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    WhileVisible {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    val locale = context.resources.configuration.locales[0]
    // Short weekday ("24 Ekim Cmt") so the card stays compact next to the ezan card.
    val date = remember(now / 3_600_000, locale) {
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEdMMMM"), locale).format(Date(now))
    }
    Row(
        modifier.glass().padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(
                date,
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            weather?.let { w ->
                val (icon, desc) = Weather.describe(w.code)
                BasicText(
                    "$icon ${w.tempC}°  ${stringResource(desc)}",
                    style = TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(w.place, style = Label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        val days = weather?.days?.drop(1)?.take(3).orEmpty() // index 0 is today
        if (days.isNotEmpty()) {
            Box(Modifier.padding(horizontal = 10.dp).width(1.dp).fillMaxHeight().background(GlassEdge))
            val dayName = remember(locale) { SimpleDateFormat("EEE", locale) }
            val parse = remember { SimpleDateFormat("yyyy-MM-dd", Locale.ROOT) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                days.forEach { d ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        BasicText(
                            runCatching { dayName.format(parse.parse(d.date)!!) }.getOrDefault(d.date.takeLast(2)),
                            style = Label.copy(color = Color.White.copy(alpha = 0.65f)),
                        )
                        BasicText(Weather.describe(d.code).first, style = TextStyle(fontSize = 17.sp))
                        BasicText(
                            buildAnnotatedString {
                                withStyle(SpanStyle(color = Color.White)) { append("${d.maxC}°") }
                                withStyle(SpanStyle(color = Color.White.copy(alpha = 0.45f))) { append(" ${d.minC}°") }
                            },
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                        )
                    }
                }
            }
        }
    }
}

/** Current weather for the configured city, refreshed every 30 minutes while the home screen is up. */
@Composable
fun rememberWeather(): State<WeatherNow?> {
    val weather = remember { mutableStateOf<WeatherNow?>(null) }
    var loadedAt by remember { mutableLongStateOf(0L) }
    WhileVisible {
        while (true) {
            // Coming back to the home screen only refetches once the last forecast is 30 minutes old.
            val wait = loadedAt + 30 * 60_000L - SystemClock.elapsedRealtime()
            if (loadedAt != 0L && wait > 0) delay(wait)
            val fresh = withContext(Dispatchers.IO) { Weather.load(BuildConfig.WEATHER_CITY) }
            if (fresh != null) {
                weather.value = fresh
                loadedAt = SystemClock.elapsedRealtime()
            } else {
                delay(60_000) // no network yet (e.g. just after the TV boots): try again in a minute
            }
        }
    }
    return weather
}

/**
 * Runs [block] only while ÇivitTV is on screen: it's cancelled when an app opens over the home
 * screen and started again from the top on return, so clocks and refresh loops don't tick unseen.
 */
@Composable
fun WhileVisible(block: suspend CoroutineScope.() -> Unit) {
    val owner = LocalLifecycleOwner.current
    LaunchedEffect(owner) { owner.repeatOnLifecycle(Lifecycle.State.STARTED, block) }
}

/** Compact "☁️ 17°" shown next to the clock. */
@Composable
fun TopWeather(weather: WeatherNow?, modifier: Modifier = Modifier) {
    weather ?: return
    BasicText(
        "${Weather.describe(weather.code).first} ${weather.tempC}°",
        modifier,
        style = TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 22.sp, fontWeight = FontWeight.Medium),
    )
}

private val Label = TextStyle(
    color = Color.White.copy(alpha = 0.5f),
    fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold,
    letterSpacing = 0.8.sp,
)
