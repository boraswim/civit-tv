package com.tvenhance.home

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

/**
 * Remote "mouse mode" for Apple Music (a phone app with no TV interface).
 *
 * The pointer appears by itself whenever Apple Music is in front and disappears everywhere else.
 * The arrows move it, OK taps, holding OK long-presses, pushing against a screen edge scrolls,
 * and MENU turns it off/on. Apple Music itself is untouched: we only draw an overlay and inject
 * taps through accessibility.
 */
class MouseService : AccessibilityService() {
    private lateinit var wm: WindowManager
    private lateinit var cursor: CursorView
    private var ezanNotice: EzanNotice? = null
    private val handler = Handler(Looper.getMainLooper())
    private val screen = Point()

    private var foreground: String? = null
    private var pointerOn = true          // reset to on each time an eligible app comes forward
    private var x = 0f
    private var y = 0f
    private var okDown = false
    private var longPressed = false
    private var scrolling = false

    // Arrow keys currently held down. Android synthesises key repeats only after the
    // accessibility filter, so holds are timed here: one nudge on press, then a smooth glide.
    private val heldKeys = LinkedHashSet<Int>()
    private var heldSince = 0L

    private val glide = object : Runnable {
        override fun run() {
            if (heldKeys.isEmpty()) return
            val held = SystemClock.uptimeMillis() - heldSince
            // Starts slow for aiming, reaches full speed after about a second.
            val speed = GLIDE_MIN + (GLIDE_MAX - GLIDE_MIN) * (held / 1000f).coerceAtMost(1f)
            val (dx, dy) = heldDirection()
            move(dx, dy, speed)
            handler.postDelayed(this, FRAME_MS)
        }
    }

    private val longPress = Runnable {
        if (okDown && !longPressed) {
            longPressed = true
            gesture(Path().apply { moveTo(x, y) }, 900)
        }
    }

    override fun onServiceConnected() {
        // Android caches the XML config across app updates, so subscribe to window changes here too.
        serviceInfo = serviceInfo?.apply { eventTypes = eventTypes or AccessibilityEvent.TYPE_WINDOWS_CHANGED }
        wm = getSystemService(WindowManager::class.java)
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealSize(screen)
        x = screen.x / 2f
        y = screen.y / 2f
        cursor = CursorView(this).apply { visibility = View.INVISIBLE }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        wm.addView(cursor, lp)
        ezanNotice = EzanNotice(this, wm).also { it.start() }
        instance = this
        // The service can (re)start while Apple Music is already open.
        activeApp()?.let(::foregroundChanged)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        ezanNotice?.stop()
        handler.removeCallbacksAndMessages(null)
        if (::cursor.isInitialized) runCatching { wm.removeView(cursor) }
        super.onDestroy()
    }

    // Looks at the active window a few times while an app opens. Android's window-state event for
    // a newly opened app can be missing (task brought back from the background, trampolines, a
    // splash screen), so this is what reliably brings the pointer up on entering Apple Music.
    private var rechecks = 0
    private val recheck = object : Runnable {
        override fun run() {
            activeApp()?.let(::foregroundChanged)
            if (++rechecks < RECHECK_TIMES) handler.postDelayed(this, RECHECK_MS)
        }
    }

    private fun recheckSoon() {
        rechecks = 0
        handler.removeCallbacks(recheck)
        handler.postDelayed(recheck, RECHECK_MS)
    }

    override fun onInterrupt() = Unit

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // The set of windows changed (an app opened, closed or came back): ask which app is active.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) return recheckSoon()
        val pkg = event.packageName?.toString() ?: return
        val stateChanged = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (pkg == TV_PLUS) {
            // Only the live player shows channel names; skip menus, guides and settings.
            val cls = event.className?.toString()
            if (stateChanged && cls?.endsWith("Activity") == true) inTvPlusPlayer = cls.contains("player", ignoreCase = true)
            if (inTvPlusPlayer) watchTvPlusChannel()
        }
        if (!stateChanged) return
        // Keyboards and system overlays (volume, toasts) don't change which app is in front,
        // and neither does our own pointer overlay; only ÇivitTV's real screens do.
        if (pkg == "com.android.systemui" || pkg in imePackages()) return
        if (pkg == packageName && event.className?.toString()?.endsWith("Activity") != true) return
        foregroundChanged(pkg)
    }

    /** Shows the pointer on entering Apple Music and hides it on leaving. */
    private fun foregroundChanged(pkg: String) {
        if (pkg == foreground) return
        foreground = pkg
        pointerOn = true
        releaseAll()
        if (isEligible(pkg)) {
            // Start in the middle each time, so the pointer is always easy to find.
            x = screen.x / 2f
            y = screen.y / 2f
            showCursor()
        } else {
            cursor.visibility = View.INVISIBLE
        }
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Window events can be missed (service restarts, trampolines), so check the active window
        // on every key press: the pointer must never act outside Apple Music, nor sleep inside it.
        // (Key-ups belong to the press before them, so they skip this window lookup.)
        if (event.action == KeyEvent.ACTION_DOWN) activeApp()?.let(::foregroundChanged)
        val pkg = foreground ?: return false
        if (!isEligible(pkg)) return false

        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                pointerOn = !pointerOn
                releaseAll()
                if (pointerOn) showCursor() else cursor.visibility = View.INVISIBLE
                Toast.makeText(this, if (pointerOn) R.string.mouse_on else R.string.mouse_off, Toast.LENGTH_SHORT).show()
            }
            return true
        }
        // With the pointer off, or while a keyboard is open, the remote works normally.
        if (!pointerOn || imeVisible()) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { arrow(event); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { select(event); true }
            else -> false
        }
    }

    private fun arrow(event: KeyEvent) {
        val code = event.keyCode
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Ignore repeats (some remotes send them) for a key we're already gliding on.
                if (!heldKeys.add(code)) return
                val (dx, dy) = directionOf(code)
                move(dx, dy, NUDGE)
                if (heldKeys.size == 1) {
                    heldSince = SystemClock.uptimeMillis()
                    handler.removeCallbacks(glide)
                    handler.postDelayed(glide, GLIDE_DELAY_MS)
                }
            }
            KeyEvent.ACTION_UP -> {
                heldKeys.remove(code)
                if (heldKeys.isEmpty()) handler.removeCallbacks(glide)
            }
        }
    }

    private fun directionOf(code: Int): Pair<Int, Int> = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> -1 to 0
        KeyEvent.KEYCODE_DPAD_RIGHT -> 1 to 0
        KeyEvent.KEYCODE_DPAD_UP -> 0 to -1
        KeyEvent.KEYCODE_DPAD_DOWN -> 0 to 1
        else -> 0 to 0
    }

    private fun heldDirection(): Pair<Int, Int> {
        var dx = 0
        var dy = 0
        for (code in heldKeys) {
            val (kx, ky) = directionOf(code)
            dx += kx
            dy += ky
        }
        return dx.coerceIn(-1, 1) to dy.coerceIn(-1, 1)
    }

    private fun releaseAll() {
        heldKeys.clear()
        handler.removeCallbacks(glide)
        handler.removeCallbacks(longPress)
        okDown = false
        if (::cursor.isInitialized) cursor.pressed(false)
    }

    private fun move(dx: Int, dy: Int, step: Float) {
        showCursor()
        val nx = (x + dx * step).coerceIn(0f, screen.x - 1f)
        val ny = (y + dy * step).coerceIn(0f, screen.y - 1f)
        // Already against the edge in this direction: scroll the content instead.
        if (nx == x && ny == y) scroll(dx, dy)
        x = nx
        y = ny
        cursor.moveTo(x, y)
    }

    private fun select(event: KeyEvent) {
        showCursor()
        when (event.action) {
            KeyEvent.ACTION_DOWN -> if (!okDown) {
                okDown = true
                longPressed = false
                cursor.pressed(true)
                handler.postDelayed(longPress, LONG_PRESS_MS)
            }
            KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (okDown && !longPressed) gesture(Path().apply { moveTo(x, y) }, 60)
                okDown = false
                cursor.pressed(false)
            }
        }
    }

    private fun scroll(dx: Int, dy: Int) {
        if (scrolling) return
        // A swipe the opposite way moves the content in the direction the arrow points.
        val path = Path()
        if (dy != 0) {
            // Swipe within the middle of the content: phone apps pin headers/section bars near the
            // top and tab bars at the bottom, and a swipe that starts on those doesn't scroll.
            val cx = x.coerceIn(screen.x * 0.1f, screen.x * 0.9f)
            val (from, to) = if (dy > 0) 0.80f to 0.42f else 0.42f to 0.80f
            path.moveTo(cx, screen.y * from)
            path.lineTo(cx, screen.y * to)
        } else {
            val cy = y.coerceIn(screen.y * 0.1f, screen.y * 0.9f)
            val from = if (dx > 0) 0.7f else 0.3f
            path.moveTo(screen.x * from, cy)
            path.lineTo(screen.x * (1f - from), cy)
        }
        scrolling = true
        gesture(path, 280) { scrolling = false }
    }

    private fun gesture(path: Path, duration: Long, done: (() -> Unit)? = null) {
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val callback = object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { done?.invoke() }
            override fun onCancelled(gestureDescription: GestureDescription?) { done?.invoke() }
        }
        if (!dispatchGesture(g, callback, null)) done?.invoke()
    }

    private fun showCursor() {
        cursor.visibility = View.VISIBLE
        cursor.moveTo(x, y)
    }

    private fun isEligible(pkg: String) = pkg in MOUSE_APPS

    // ---- TV+ "last watched" ------------------------------------------------------------------
    // TV+ doesn't report what it plays to Android, but its player shows the channel name
    // ("TRT1 HD") whenever a channel starts or you switch. Matching that on-screen text against
    // the channel list records every channel watched, not only ones opened from the shelf.
    private var inTvPlusPlayer = false
    private var channelsByKey: Map<String, Channel> = emptyMap()
    private var channelsLoadedAt = 0L
    private var lastScan = 0L
    private var lastChannel: String? = null
    private var lastChannelAt = 0L

    @Suppress("DEPRECATION") // recycle() only became a no-op in Android 13; this box runs 9
    private fun watchTvPlusChannel() {
        val now = SystemClock.uptimeMillis()
        if (now - lastScan < 1500) return // content events come in bursts
        lastScan = now
        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != TV_PLUS) return root.recycle()
        if (channelsByKey.isEmpty() || now - channelsLoadedAt > 60 * 60_000L) {
            channelsByKey = TvPlusChannels(cacheDir).cached().associateBy { channelKey(it.name) }
            channelsLoadedAt = now
        }
        if (channelsByKey.isEmpty()) return root.recycle()
        val channel = findChannel(root) ?: return
        // Don't re-record the same channel every time its info bar pops up.
        if (channel.name == lastChannel && now - lastChannelAt < 10 * 60_000L) return
        lastChannel = channel.name
        lastChannelAt = now
        MediaWatcher.opened(
            Played(TV_PLUS, channel.name, getString(R.string.live), channel.logo, channel.link, System.currentTimeMillis())
        )
    }

    /** Breadth-first search of the player's text for a channel name. Recycles every node, root included. */
    @Suppress("DEPRECATION")
    private fun findChannel(root: AccessibilityNodeInfo): Channel? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        var found: Channel? = null
        while (queue.isNotEmpty() && visited < 400 && found == null) {
            val node = queue.removeFirst()
            visited++
            found = node.text?.toString()?.takeIf { it.length in 2..40 }?.let { channelsByKey[channelKey(it)] }
            if (found == null) for (i in 0 until node.childCount) node.getChild(i)?.let(queue::add)
            node.recycle()
        }
        queue.forEach { it.recycle() }
        return found
    }

    private fun imePackages(): Set<String> =
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.mapTo(HashSet()) { it.packageName }

    private fun activeApp(): String? =
        windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?.root?.packageName?.toString()
            ?: rootInActiveWindow?.packageName?.toString()

    private fun imeVisible(): Boolean =
        windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }

    companion object {
        /** The running service (it lives in ÇivitTV's own process), or null when Android hasn't bound it. */
        @Volatile
        var instance: MouseService? = null
            private set

        /** ÇivitTV is handing the screen to another app: find out which one as it opens. */
        fun appOpening() {
            instance?.let { s -> s.handler.post { s.recheckSoon() } }
        }

        // Apps that get the pointer: only Apple Music, the one phone-only app on this TV.
        private val MOUSE_APPS = setOf(APPLE_MUSIC)
        private const val RECHECK_MS = 400L
        private const val RECHECK_TIMES = 6 // ~2.4 s, enough for Apple Music's splash screen
        private const val NUDGE = 14f           // px moved by a single short press, for fine aiming
        private const val GLIDE_DELAY_MS = 220L  // how long a press must last before gliding starts
        private const val FRAME_MS = 16L
        private const val GLIDE_MIN = 5f         // px per frame when gliding starts (~300 px/s)
        private const val GLIDE_MAX = 26f        // px per frame at full speed (~1600 px/s)
        private const val LONG_PRESS_MS = 550L
    }
}

/** Full-screen, touch-transparent overlay that draws an arrow pointer at the hotspot (x, y). */
private class CursorView(context: Context) : View(context) {
    private val scale = context.resources.displayMetrics.density * 1.4f
    private val arrow = Path().apply {
        moveTo(0f, 0f); lineTo(0f, 17f); lineTo(4f, 13f); lineTo(7f, 20f)
        lineTo(9.5f, 19f); lineTo(6.5f, 12f); lineTo(12f, 12f); close()
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val pressedFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF2D55.toInt() }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF111111.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 1.2f
        strokeJoin = Paint.Join.ROUND
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x55000000 }
    private var px = 0f
    private var py = 0f
    private var down = false

    fun moveTo(x: Float, y: Float) {
        px = x
        py = y
        invalidate()
    }

    fun pressed(on: Boolean) {
        down = on
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(px, py)
        canvas.scale(scale, scale)
        canvas.save()
        canvas.translate(1.2f, 1.6f)
        canvas.drawPath(arrow, shadow)
        canvas.restore()
        canvas.drawPath(arrow, if (down) pressedFill else fill)
        canvas.drawPath(arrow, outline)
        canvas.restore()
    }
}
