package com.tvenhance.home

import android.content.Intent
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Density
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date

private const val COLUMNS = 5
internal val SidePad = 56.dp
private val Gap = 24.dp
private val TileShape = RoundedCornerShape(16.dp)
private val DockInset = 22.dp // space between the dock panel's edge and its first/last tile

// The first screen is 960 x 540 dp. TVs such as Samsungs crop ("overscan") a few percent of each
// edge, so everything stays inside the TV safe area: >= 48 dp from the sides, >= 27 dp top/bottom.
// Vertical budget: shelf 280 + dock 148 + info strip 82 = 510 dp, leaving 30 dp at the bottom.
private val ShelfHeight = 280.dp
private val Bg = Color(0xFF0B0B10)

/** Marks a background image to be shown sharp and full-screen rather than blurred. */
private data class Hero(val model: Any)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    apps: List<AppEntry>,
    homeSignal: Int,
    repo: AppRepository,
    onLaunch: (AppEntry) -> Unit,
    onAppInfo: (AppEntry) -> Unit,
    onReload: () -> Unit,
) {
    var items by remember(apps) { mutableStateOf(apps) }
    var shelfPkg by remember { mutableStateOf<String?>(null) }
    var moving by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<AppEntry?>(null) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }
    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(pkg: String) = requesters.getOrPut(pkg) { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    // Like tvOS, the top shelf follows whichever top-row app last had focus.
    val shelf = items.find { it.pkg == shelfPkg } ?: items.firstOrNull()

    val context = LocalContext.current
    val source = remember {
        ShelfSource(
            context,
            Tmdb(BuildConfig.TMDB_KEY, context.cacheDir),
            TvPlusChannels(context.cacheDir),
        )
    }
    val history by rememberPlayHistory()
    // The shelf for the focused dock app; kept with its package so a stale shelf never shows under another app.
    val loadedShelf by produceState<Pair<String?, Shelf?>>(null to null, shelf?.pkg, history) {
        val app = shelf ?: return@produceState
        value = app.pkg to withContext(Dispatchers.IO) { source.cached(app, history) }
        delay(350) // don't hit the network while the user is flicking along the dock
        value = app.pkg to withContext(Dispatchers.IO) { source.load(app, history) }
    }
    val shelfContent = loadedShelf.second?.takeIf { loadedShelf.first == shelf?.pkg }
    var focusedItem by remember(shelf?.pkg) { mutableStateOf<ShelfItem?>(null) }
    val nowPlaying by rememberNowPlaying()
    val weather by rememberWeather()
    fun openItem(item: ShelfItem) {
        val pkg = shelf?.pkg ?: return
        UiSounds.select()
        val opened = item.intent(pkg)?.let { runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess } == true
        if (!opened) shelf?.let(onLaunch)
        // TV+ doesn't report what it plays, so remember the channel ourselves for "last watched".
        if (opened && pkg == TV_PLUS) {
            MediaWatcher.opened(
                Played(pkg, item.title, context.getString(R.string.live), item.image as? String, item.link, System.currentTimeMillis())
            )
        }
    }

    fun openNowPlaying(np: NowPlaying) {
        UiSounds.select()
        val sent = np.open?.let { runCatching { it.send() }.isSuccess } == true
        val linked = !sent && playedIntent(np.item)?.let {
            runCatching { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        } == true
        if (!sent && !linked) items.find { it.pkg == np.item.pkg }?.let(onLaunch)
    }

    fun goTop() {
        scope.launch { scroll.animateScrollTo(0) }
        pendingFocus = items.firstOrNull()?.pkg
    }

    LaunchedEffect(homeSignal, items.isEmpty()) { if (items.isNotEmpty()) goTop() }

    LaunchedEffect(pendingFocus, items) {
        val pkg = pendingFocus ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(pkg).requestFocus() }
        pendingFocus = null
    }

    fun finishMove() {
        pendingFocus = moving
        moving = null
        repo.saveOrder(items.map { it.pkg })
    }

    fun moveBy(delta: Int) {
        val i = items.indexOfFirst { it.pkg == moving }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, items.lastIndex)
        if (j == i) return
        items = items.toMutableList().apply { add(j, removeAt(i)) }
        pendingFocus = moving
    }

    val moveKeys: (KeyEvent) -> Boolean = { e ->
        when {
            e.key.isSelect() -> {
                if (e.type == KeyEventType.KeyUp) finishMove()
                true
            }
            e.type != KeyEventType.KeyDown -> false
            e.key == Key.DirectionLeft -> { moveBy(-1); true }
            e.key == Key.DirectionRight -> { moveBy(1); true }
            e.key == Key.DirectionUp -> { moveBy(-COLUMNS); true }
            e.key == Key.DirectionDown -> { moveBy(COLUMNS); true }
            else -> false
        }
    }

    BackHandler { if (moving != null) finishMove() else goTop() }

    Box(Modifier.fillMaxSize().background(Bg)) {
        // Background, tvOS style: films, series and videos fill the screen sharply behind everything;
        // music and channels get a soft blurred wash of their artwork; otherwise the app's colours.
        val focusArt = focusedItem ?: shelfContent?.items?.firstOrNull()
        val layout = shelfContent?.layout
        val heroArt = focusArt?.wideImage?.takeIf { layout == ShelfLayout.Featured || layout == ShelfLayout.Wide }
        val washArt = (focusArt?.wideImage ?: focusArt?.image).takeIf { heroArt == null && layout != ShelfLayout.Channels }
        val backgroundKey: Any? = heroArt?.let { Hero(it) } ?: washArt ?: shelf?.pkg
        Crossfade(backgroundKey, animationSpec = tween(700), label = "background") { key ->
            val app = items.find { it.pkg == key }
            when {
                key is Hero -> AsyncImage(
                    model = (key.model as? String)?.replace("/w780/", "/w1280/") ?: key.model,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
                app != null -> Image(
                    app.ambient, null, Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
                )
                key != null -> AsyncImage(
                    model = ImageRequest.Builder(context)
                        // Only a small image is needed for a blurred background.
                        .data(if (key is String) key.replace("/w780/", "/w300/") else key)
                        .transformations(AmbientBlur())
                        .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Scrims keep text readable: darker on the left (where titles sit) and towards the bottom.
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = 0.80f),
                    0.45f to Color.Black.copy(alpha = 0.35f),
                    1f to Color.Black.copy(alpha = 0.10f),
                )
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.45f),
                    0.22f to Color.Transparent,
                    0.50f to Color.Black.copy(alpha = 0.30f),
                    0.75f to Color.Black.copy(alpha = 0.80f),
                    1f to Color.Black.copy(alpha = 0.95f),
                )
            )
        )
        val dim by remember { derivedStateOf { (scroll.value / 500f).coerceIn(0f, 0.6f) } }
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = dim }.background(Bg))

        val density = LocalDensity.current
        val bringIntoView = remember(density) { ShelfBringIntoViewSpec(scroll, density) }
        CompositionLocalProvider(LocalBringIntoViewSpec provides bringIntoView) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            TopShelf(
                app = shelf,
                lastLaunched = shelf?.let { repo.lastLaunched(it.pkg) } ?: 0L,
                content = shelfContent,
                onOpen = ::openItem,
                onFocusItem = { focusedItem = it },
            )
            items.chunked(COLUMNS).forEachIndexed { r, row ->
                val isDock = r == 0
                // Favourites (the top row) sit on a rounded glass dock whose edges line up with the
                // shelf and the app rows (SidePad); the tiles sit DockInset inside it.
                Box(
                    if (isDock) {
                        Modifier
                            .fillMaxWidth()
                            .padding(start = SidePad, end = SidePad, top = 6.dp, bottom = 4.dp)
                            .dockGlass()
                            // Equal space above and below: room for the focused tile to grow.
                            .padding(vertical = 18.dp)
                    } else {
                        Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 10.dp)
                    }
                ) {
                    val rowPad = if (isDock) DockInset else SidePad
                    Row(Modifier.padding(horizontal = rowPad), horizontalArrangement = Arrangement.spacedBy(Gap)) {
                        row.forEach { app ->
                            key(app.pkg) {
                                AppTile(
                                    app = app,
                                    focusRequester = requester(app.pkg),
                                    isMoving = moving == app.pkg,
                                    onFocused = { if (isDock) shelfPkg = app.pkg },
                                    onClick = { onLaunch(app) },
                                    onLongPress = { menuFor = app },
                                    onMoveKey = moveKeys,
                                    // Favourites are recognisable by their logos; no names under them.
                                    showLabel = !isDock,
                                )
                            }
                        }
                        repeat(COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (isDock) {
                    InfoStrip(
                        nowPlaying = nowPlaying,
                        weather = weather,
                        onOpenNowPlaying = ::openNowPlaying,
                    )
                    Spacer(Modifier.height(26.dp))
                }
            }
            Spacer(Modifier.height(48.dp))
        }
        }

        val clockAlpha by remember { derivedStateOf { 1f - (scroll.value / 150f).coerceIn(0f, 1f) } }
        Wordmark(Modifier.align(Alignment.TopStart).padding(top = 25.dp, start = SidePad).graphicsLayer { alpha = clockAlpha })
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 28.dp, end = SidePad).graphicsLayer { alpha = clockAlpha },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopWeather(weather, Modifier.padding(end = 18.dp))
            Clock(Modifier.padding(end = 20.dp))
            SettingsButton {
                UiSounds.select()
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }

        if (moving != null) {
            BasicText(
                stringResource(R.string.move_hint),
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.14f))
                    .padding(horizontal = 22.dp, vertical = 10.dp),
                style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            )
        }

        menuFor?.let { app ->
            AppMenu(
                app = app,
                hiddenCount = repo.hiddenCount(),
                onDismiss = { menuFor = null; pendingFocus = app.pkg },
                onMove = { menuFor = null; moving = app.pkg; pendingFocus = app.pkg },
                onHide = {
                    val idx = items.indexOfFirst { it.pkg == app.pkg }
                    repo.hide(app.pkg)
                    items = items.filter { it.pkg != app.pkg }
                    menuFor = null
                    pendingFocus = items.getOrNull(idx.coerceAtMost(items.lastIndex))?.pkg
                },
                onInfo = { menuFor = null; pendingFocus = app.pkg; onAppInfo(app) },
                onRemove = {
                    menuFor = null
                    pendingFocus = app.pkg
                    // Android's own uninstall confirmation; the grid refreshes once it's gone.
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_DELETE, android.net.Uri.fromParts("package", app.pkg, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
                onUnhideAll = { repo.unhideAll(); menuFor = null; pendingFocus = app.pkg; onReload() },
            )
        }
    }
}

@Composable
private fun TopShelf(
    app: AppEntry?,
    lastLaunched: Long,
    content: Shelf?,
    onOpen: (ShelfItem) -> Unit,
    onFocusItem: (ShelfItem) -> Unit,
) {
    Crossfade(app to content, Modifier.fillMaxWidth().height(ShelfHeight), animationSpec = tween(450), label = "shelf") { (a, s) ->
        if (a == null) return@Crossfade
        if (s != null && s.items.isNotEmpty()) {
            ShelfView(a, s, onOpen, onFocusItem)
            return@Crossfade
        }
        val subtitle = if (lastLaunched > 0) {
            stringResource(
                R.string.opened_ago,
                DateUtils.getRelativeTimeSpanString(lastLaunched, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS),
            )
        } else {
            stringResource(R.string.press_ok)
        }
        Row(
            Modifier.fillMaxSize().padding(start = SidePad, end = SidePad, top = 70.dp, bottom = 18.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f).padding(end = 32.dp, bottom = 8.dp)) {
                BasicText(
                    a.label,
                    style = TextStyle(color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                BasicText(subtitle, style = TextStyle(color = Color.White.copy(alpha = 0.6f), fontSize = 17.sp))
            }
            Image(
                a.banner, null,
                Modifier
                    .fillMaxHeight()
                    .aspectRatio(16f / 9f)
                    .graphicsLayer {
                        shadowElevation = 30.dp.toPx()
                        shape = RoundedCornerShape(18.dp)
                        clip = true
                    },
                contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.High,
            )
        }
    }
}

@Composable
private fun RowScope.AppTile(
    app: AppEntry,
    focusRequester: FocusRequester,
    isMoving: Boolean,
    onFocused: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onMoveKey: (KeyEvent) -> Boolean,
    showLabel: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var longFired by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        if (focused) 1.14f else 1f,
        spring(dampingRatio = 0.65f, stiffness = 380f),
        label = "scale",
    )
    val labelAlpha by animateFloatAsState(if (focused) 1f else 0f, tween(200), label = "label")
    val sheen = remember { Animatable(-1f) }
    LaunchedEffect(focused) {
        if (focused) {
            sheen.snapTo(-0.6f)
            sheen.animateTo(1.6f, tween(1100, easing = FastOutSlowInEasing))
        }
    }
    val wiggle = if (isMoving) {
        rememberInfiniteTransition(label = "wiggle")
            .animateFloat(-1.6f, 1.6f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "angle")
    } else {
        null
    }

    Column(Modifier.weight(1f).zIndex(if (focused) 1f else 0f), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationY = if (focused) -4.dp.toPx() else 0f
                    rotationZ = wiggle?.value ?: 0f
                    shadowElevation = if (focused) 24.dp.toPx() else 2.dp.toPx()
                    shape = TileShape
                    clip = true
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    focused = it.isFocused
                    if (it.isFocused) {
                        UiSounds.tick()
                        onFocused()
                    }
                }
                .onPreviewKeyEvent { e ->
                    if (isMoving) return@onPreviewKeyEvent onMoveKey(e)
                    when {
                        e.key.isSelect() -> {
                            // OK opens the app; holding OK (key repeat kicks in after ~0.5s) opens the menu.
                            if (e.type == KeyEventType.KeyDown) {
                                if (e.nativeKeyEvent.repeatCount == 0) {
                                    pressed = true
                                    longFired = false
                                } else if (pressed && !longFired) {
                                    longFired = true
                                    onLongPress()
                                }
                            } else if (e.type == KeyEventType.KeyUp) {
                                if (pressed && !longFired) onClick()
                                pressed = false
                            }
                            true
                        }
                        e.key == Key.Menu && e.type == KeyEventType.KeyDown -> { onLongPress(); true }
                        else -> false
                    }
                }
                .focusable()
        ) {
            Image(
                app.banner, app.label, Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop, filterQuality = FilterQuality.High,
            )
            if (focused) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.16f), 0.5f to Color.Transparent))
                            val x = sheen.value * size.width
                            drawRect(
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent),
                                    start = Offset(x - size.width * 0.35f, 0f),
                                    end = Offset(x + size.width * 0.05f, size.height),
                                )
                            )
                        }
                        .border(FocusWidth, FocusBlue, TileShape)
                )
            }
        }
        if (!showLabel) return@Column
        Spacer(Modifier.height(12.dp))
        BasicText(
            app.label,
            Modifier.graphicsLayer { alpha = labelAlpha },
            style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Wordmark(modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        BirdLogo(Modifier.size(30.dp))
        Spacer(Modifier.width(9.dp))
        BasicText(
            buildAnnotatedString {
                // "Çivit" in the logo budgie's cobalt blue.
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color(0xFF4F97F2))) { append("Çivit") }
                withStyle(SpanStyle(fontWeight = FontWeight.Light, color = Color.White.copy(alpha = 0.7f))) { append("TV") }
            },
            style = TextStyle(color = Color.White, fontSize = 24.sp, letterSpacing = (-0.3).sp),
        )
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    WhileVisible {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - now % 60_000)
        }
    }
    BasicText(
        DateFormat.getTimeFormat(context).format(Date(now)),
        modifier,
        style = TextStyle(color = Color.White.copy(alpha = 0.9f), fontSize = 22.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
private fun AppMenu(
    app: AppEntry,
    hiddenCount: Int,
    onDismiss: () -> Unit,
    onMove: () -> Unit,
    onHide: () -> Unit,
    onInfo: () -> Unit,
    onRemove: () -> Unit,
    onUnhideAll: () -> Unit,
) {
    val first = remember { FocusRequester() }
    // The menu opens while OK is still held (long press). That hold keeps sending OK repeats and
    // then a release, which would "click" the first option. So OK only counts once a fresh press
    // starts inside the menu; the held press and its release are swallowed.
    var freshPress by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .width(380.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xF2242429))
                .onPreviewKeyEvent { e ->
                    if (!e.key.isSelect()) return@onPreviewKeyEvent false
                    if (e.type == KeyEventType.KeyDown && e.nativeKeyEvent.repeatCount == 0) freshPress = true
                    !freshPress
                }
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    app.banner, null,
                    Modifier.height(54.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(14.dp))
                BasicText(
                    app.label,
                    style = TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(6.dp))
            MenuButton(stringResource(R.string.menu_move), onMove, Modifier.focusRequester(first))
            if (!app.isBuiltIn) {
                MenuButton(stringResource(R.string.menu_hide), onHide)
                MenuButton(stringResource(R.string.menu_info), onInfo)
                if (app.removable) MenuButton(stringResource(R.string.menu_remove), onRemove)
            }
            if (hiddenCount > 0) MenuButton(stringResource(R.string.menu_unhide, hiddenCount), onUnhideAll)
            MenuButton(stringResource(R.string.menu_cancel), onDismiss)
        }
    }
    LaunchedEffect(Unit) {
        // The dialog window attaches a frame or two later; retry until it takes focus.
        repeat(10) {
            withFrameNanos { }
            if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
}

@Composable
private fun MenuButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.06f))
            .then(if (focused) Modifier.border(FocusWidth, FocusBlue, RoundedCornerShape(12.dp)) else Modifier)
            .onFocusChanged { focused = it.isFocused; if (it.isFocused) UiSounds.tick() }
            .clickable(interactionSource = interaction, indication = null) { UiSounds.select(); onClick() }
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicText(
            text,
            style = TextStyle(
                color = if (focused) Color.Black else Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium,
            ),
        )
    }
}

// Compose's TV default pins the focused item ~30% down the screen, which pushes the top shelf
// off-screen. Instead: anything in the shelf/dock region scrolls fully to the top (as on tvOS),
// and lower rows scroll just enough to show the tile plus its label.
@OptIn(ExperimentalFoundationApi::class)
private class ShelfBringIntoViewSpec(private val scroll: ScrollState, density: Density) : BringIntoViewSpec {
    // Shelf, dock and the info strip below it all live on the first screen.
    private val dockBottom = with(density) { 520.dp.toPx() }
    private val margin = with(density) { 48.dp.toPx() }

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        if (offset + scroll.value < dockBottom) return -scroll.value.toFloat()
        val top = offset - margin
        val bottom = offset + size + margin
        return when {
            top >= 0 && bottom <= containerSize -> 0f
            top < 0 -> top
            else -> bottom - containerSize
        }
    }
}

private fun Key.isSelect() = this == Key.DirectionCenter || this == Key.Enter || this == Key.NumPadEnter
