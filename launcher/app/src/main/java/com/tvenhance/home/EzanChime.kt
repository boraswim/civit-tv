package com.tvenhance.home

import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * What goes with each ezan notice: whatever is playing is paused (it stays paused), then
 * five seconds of birdsong play. The birdsong is synthesised once into a small WAV file, like
 * the launcher's other sounds, so no audio ships with the app.
 */
class EzanChime(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA) // follows the TV's volume, like the video it interrupts
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())
    private val finish = Runnable { stop() }
    private var focus: AudioFocusRequest? = null

    fun play() {
        pauseMedia()
        stop()
        // Taking audio focus for good also stops players that don't publish a media session
        // (some live-TV apps), and none of them resume on their own afterwards.
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(attrs).build()
        audio.requestAudioFocus(request)
        focus = request
        player = runCatching {
            MediaPlayer().apply {
                setAudioAttributes(attrs)
                setDataSource(file().path)
                setOnErrorListener { _, _, _ -> stop(); true }
                prepare()
                start()
            }
        }.getOrNull()
        // This box's player doesn't always report completion, so end (and give the focus back) on a timer.
        if (player == null) stop() else main.postDelayed(finish, CLIP_MS + 300)
    }

    fun stop() {
        main.removeCallbacks(finish)
        player?.let { runCatching { it.release() } }
        player = null
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    /** Pauses every app reporting playback, through the media-session access ÇivitTV already has. */
    private fun pauseMedia() {
        val sessions = context.getSystemService(MediaSessionManager::class.java)
        val controllers = runCatching { sessions.getActiveSessions(ComponentName(context, NowPlayingListener::class.java)) }
            .getOrDefault(emptyList())
        for (c in controllers) {
            if (c.playbackState?.state == PlaybackState.STATE_PLAYING) runCatching { c.transportControls.pause() }
        }
    }

    /** Writes the clip ahead of time (call off the main thread) so the first notice plays instantly. */
    fun prepare() {
        file()
    }

    private fun file(): File {
        val f = File(File(context.cacheDir, "ui_sounds").apply { mkdirs() }, "ezan_birds_v1.wav")
        if (!f.exists()) writeWav(f, birdsong())
        return f
    }

    /**
     * Five seconds of a small garden chorus: whistled glides, quick trills and soft double tweets
     * from two "birds" at different pitches, spaced with short rests and faded out at the end.
     */
    private fun birdsong(): ShortArray {
        val out = DoubleArray(RATE * 5)
        val rnd = Random(1453) // fixed seed: the same pleasant pattern every time
        var t = 0.08
        while (t < 4.6) {
            val low = rnd.nextBoolean()
            val base = if (low) 2100.0 else 3000.0
            when (rnd.nextInt(3)) {
                0 -> { // whistled glide, up or down
                    val up = rnd.nextBoolean()
                    val len = 0.16 + rnd.nextDouble() * 0.12
                    tone(out, t, len, if (up) base else base * 1.35, if (up) base * 1.35 else base, 0.30)
                    t += len
                }
                1 -> { // trill: a run of very short chirps
                    val n = 5 + rnd.nextInt(5)
                    repeat(n) { i ->
                        tone(out, t, 0.035, base * 1.25, base * 0.95, 0.24 * (1 - i * 0.05))
                        t += 0.055
                    }
                }
                else -> { // double tweet
                    repeat(2) {
                        tone(out, t, 0.07, base, base * 1.2, 0.28)
                        t += 0.11
                    }
                }
            }
            t += 0.12 + rnd.nextDouble() * 0.25 // rest between phrases
        }
        // Fade the last half second so the clip ends softly.
        val fade = RATE / 2
        return ShortArray(out.size) { i ->
            val g = if (i > out.size - fade) (out.size - i).toDouble() / fade else 1.0
            (out[i].coerceIn(-1.0, 1.0) * g * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Adds one pitch glide with a bell-shaped envelope (no clicks) and a light warble. */
    private fun tone(out: DoubleArray, start: Double, len: Double, fromHz: Double, toHz: Double, level: Double) {
        val s0 = (start * RATE).toInt()
        val n = (len * RATE).toInt()
        var phase = 0.0
        for (i in 0 until n) {
            val idx = s0 + i
            if (idx >= out.size) return
            val x = i.toDouble() / n
            val f = fromHz + (toHz - fromHz) * (0.5 - 0.5 * cos(PI * x)) + 60 * sin(2 * PI * 28 * i / RATE)
            phase += 2 * PI * f / RATE
            out[idx] += sin(phase) * (0.5 - 0.5 * cos(2 * PI * x)) * level
        }
    }

    private fun writeWav(file: File, samples: ShortArray) {
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        FileOutputStream(file).use { it.write(header.array()); it.write(data) }
    }

    private companion object {
        const val RATE = 44_100
        const val CLIP_MS = 5_000L
    }
}
