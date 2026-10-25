package com.tvenhance.home

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * A 5-second card at the top of the screen, over whatever app is open, 30 minutes before each
 * ezan and when it arrives. Media that's playing is paused and five seconds of birdsong play. Run by the mouse service: as an accessibility service it is always
 * running and may draw over every app (video players included) without asking for more rights.
 */
class EzanNotice(private val context: Context, private val wm: WindowManager) {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val ezan = Ezan(context.cacheDir)
    private val chime = EzanChime(context)
    private val shown = HashSet<String>() // "<time>/<kind>", so a reschedule never repeats a notice
    private var card: View? = null
    private val pending = ArrayList<Runnable>() // the timed notices, cancelled on every reschedule

    private val refresh = Runnable { schedule() }

    fun start() {
        io.execute { runCatching { chime.prepare() } }
        schedule()
    }

    fun stop() {
        main.removeCallbacksAndMessages(null)
        hide()
        chime.stop()
        io.shutdownNow()
    }

    /** Works out the next ezan (off the main thread: it may download the schedule) and times both notices. */
    private fun schedule() {
        main.removeCallbacks(refresh)
        pending.forEach(main::removeCallbacks)
        pending.clear()
        io.execute {
            val next = runCatching { ezan.next(LocalDateTime.now()) }.getOrNull()
            main.post {
                // Rechecked every 10 minutes too, which absorbs clock changes and late schedule downloads.
                main.postDelayed(refresh, RECHECK_MS)
                next ?: return@post
                val name = context.getString(next.nameRes)
                val time = next.at.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"))
                at(next.at.minusMinutes(SOON_MIN), "${next.at}/soon") {
                    show(context.getString(R.string.next_ezan), context.getString(R.string.ezan_notice_soon, name), time, EZAN_RED)
                }
                at(next.at, "${next.at}/now") {
                    show(context.getString(R.string.ezan_now).uppercase(), context.getString(R.string.ezan_notice_now, name), time, Color.WHITE)
                    // Move on to the following prayer once this one has passed.
                    main.postDelayed(refresh, 60_000)
                }
            }
        }
    }

    private fun at(time: LocalDateTime, key: String, action: () -> Unit) {
        if (key in shown) return
        val wait = Duration.between(LocalDateTime.now(), time).toMillis()
        if (wait < -LATE_MS) return // long past (e.g. the TV was just switched on)
        val r = Runnable { if (shown.add(key)) action() }
        pending += r
        main.postDelayed(r, wait.coerceAtLeast(0))
    }

    private fun show(label: String, title: String, time: String, accent: Int) {
        hide()
        val dp = context.resources.displayMetrics.density
        val view = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * dp).toInt(), (14 * dp).toInt(), (28 * dp).toInt(), (16 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 22 * dp
                setColor(0xE6161618.toInt())
                setStroke((2 * dp).toInt(), accent)
            }
            addView(text(label, 12f, accent, bold = true).apply { letterSpacing = 0.12f })
            addView(text(title, 22f, Color.WHITE, bold = true).apply { setSingleLine() })
            addView(text(time, 16f, 0xB3FFFFFF.toInt(), bold = false))
            alpha = 0f
            translationY = -24 * dp
        }
        // A full-width window with the card centred in it: a wrap-content overlay window gets
        // capped in width by Android and would cut the text off.
        val root = FrameLayout(context).apply {
            addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL))
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (40 * dp).toInt() // inside the TV's safe area
        }
        if (runCatching { wm.addView(root, lp) }.isFailure) return
        card = root
        chime.play() // pauses whatever is playing, then 5 s of birdsong
        view.animate().alpha(1f).translationY(0f).setDuration(250).start()
        main.postDelayed({
            if (card === root) view.animate().alpha(0f).translationY(-24 * dp).setDuration(250).withEndAction { hide() }.start()
        }, SHOW_MS)
    }

    private fun hide() {
        card?.let { runCatching { wm.removeView(it) } }
        card = null
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean) = TextView(context).apply {
        text = s
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) paint.isFakeBoldText = true
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private companion object {
        const val SOON_MIN = 30L
        const val SHOW_MS = 5_000L
        const val RECHECK_MS = 10 * 60_000L
        const val LATE_MS = 60_000L // still show a notice that's up to a minute late
        const val EZAN_RED = 0xFFFF453A.toInt()
    }
}
