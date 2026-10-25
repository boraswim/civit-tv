package com.tvenhance.home

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.SystemClock
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * Interface sounds, synthesised once into tiny WAV files (no audio assets ship):
 *  - moving focus plays one of ten very soft budgie chirps, picked at random (never the same
 *    one twice in a row), fitting ÇivitTV's bird;
 *  - selecting something plays a soft, deeper "tock".
 */
object UiSounds {
    private const val RATE = 44_100

    private var pool: SoundPool? = null
    private var chirps = IntArray(0) // filled in init(); CHIRPS is declared further down
    private var select = 0
    private var lastTick = 0L
    private var lastChirp = -1

    fun init(context: Context) {
        if (pool != null) return
        val p = SoundPool.Builder()
            .setMaxStreams(3)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        val dir = File(context.cacheDir, "ui_sounds").apply { mkdirs() }
        chirps = IntArray(CHIRPS.size)
        CHIRPS.forEachIndexed { i, recipe -> chirps[i] = p.load(save(File(dir, "chirp_v1_$i.wav")) { chirp(recipe) }.path, 1) }
        select = p.load(save(File(dir, "select_v2.wav")) { tock() }.path, 1)
        pool = p
    }

    /** Focus moved. Throttled so a fast glide along a row doesn't become a flock. */
    fun tick() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTick < 60 || chirps.isEmpty()) return
        lastTick = now
        var i = Random.nextInt(chirps.size)
        if (i == lastChirp) i = (i + 1 + Random.nextInt(chirps.size - 1)) % chirps.size
        lastChirp = i
        pool?.play(chirps[i], 1f, 1f, 0, 0, 1f)
    }

    fun select() {
        pool?.play(select, 1f, 1f, 1, 0, 1f)
    }

    /** One short glide in a chirp: start -> end pitch over [ms], with optional warble. */
    private class Syllable(val ms: Int, val fromHz: Double, val toHz: Double, val warbleHz: Double = 0.0, val warbleDepth: Double = 0.0)

    private class Chirp(val level: Double, val gapMs: Int, vararg val parts: Syllable)

    // Ten gentle chirps in a mellow 1.6-2.6 kHz range (well below a sharp "click"), kept short and
    // quiet: single tweets, rising and falling glides, quick doubles, and a couple with a soft warble.
    private val CHIRPS = listOf(
        Chirp(0.13, 0, Syllable(55, 1900.0, 2400.0)),
        Chirp(0.12, 0, Syllable(60, 2500.0, 1950.0)),
        Chirp(0.12, 22, Syllable(32, 2000.0, 2350.0), Syllable(38, 2150.0, 2550.0)),
        Chirp(0.13, 0, Syllable(70, 1750.0, 2200.0, warbleHz = 32.0, warbleDepth = 55.0)),
        Chirp(0.11, 0, Syllable(45, 2300.0, 2600.0)),
        Chirp(0.12, 26, Syllable(36, 2400.0, 2050.0), Syllable(34, 2250.0, 1900.0)),
        Chirp(0.13, 0, Syllable(65, 1650.0, 2100.0)),
        Chirp(0.12, 0, Syllable(80, 2200.0, 2000.0, warbleHz = 40.0, warbleDepth = 70.0)),
        Chirp(0.11, 18, Syllable(28, 2100.0, 2450.0), Syllable(28, 2100.0, 2450.0)),
        Chirp(0.12, 0, Syllable(58, 2050.0, 2500.0)),
    )

    /** A pure-tone glide per syllable, shaped by a raised-cosine (bell) envelope: no clicks, no edge. */
    private fun chirp(c: Chirp): ShortArray {
        val gap = RATE * c.gapMs / 1000
        val total = c.parts.sumOf { RATE * it.ms / 1000 } + gap * (c.parts.size - 1)
        val out = ShortArray(total)
        var pos = 0
        for ((k, s) in c.parts.withIndex()) {
            val n = RATE * s.ms / 1000
            var phase = 0.0
            for (i in 0 until n) {
                val x = i.toDouble() / n
                // Ease-in-out glide between the two pitches, plus a light warble.
                val glide = 0.5 - 0.5 * cos(PI * x)
                val t = i.toDouble() / RATE
                val f = s.fromHz + (s.toHz - s.fromHz) * glide + s.warbleDepth * sin(2 * PI * s.warbleHz * t)
                phase += 2 * PI * f / RATE
                val env = 0.5 - 0.5 * cos(2 * PI * x) // bell: silent at both ends
                out[pos + i] = (sin(phase) * env * c.level * Short.MAX_VALUE).toInt().toShort()
            }
            pos += n
            if (k < c.parts.lastIndex) pos += gap
        }
        return out
    }

    /** The select sound: a soft low "tock" with a faint octave. */
    private fun tock(): ShortArray {
        val n = RATE * 90 / 1000
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val attack = (i / (RATE * 0.005)).coerceAtMost(1.0)
            val release = ((n - i) / (RATE * 0.006)).coerceAtMost(1.0)
            val env = attack * release * exp(-t * 45.0)
            val s = sin(2 * PI * 440.0 * t) + 0.08 * sin(2 * PI * 880.0 * t)
            (s * env * 0.30 * Short.MAX_VALUE / 1.08).toInt().toShort()
        }
    }

    /** Writes 16-bit mono PCM as a WAV file once; later launches reuse it. */
    private fun save(file: File, pcm: () -> ShortArray): File {
        if (file.exists()) return file
        val samples = pcm()
        val data = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { samples.forEach { putShort(it) } }.array()
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(RATE); putInt(RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data.size)
        }
        FileOutputStream(file).use { it.write(header.array()); it.write(data) }
        return file
    }
}
