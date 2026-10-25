package com.tvenhance.home

import android.graphics.Bitmap
import coil.size.Size
import coil.transform.Transformation

/** Cheap "frosted glass" blur: shrink to a tiny bitmap, then box-blur it a few times. */
object Blur {
    fun ambient(src: Bitmap): Bitmap {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val small = Bitmap.createScaledBitmap(soft, 96, 54, true).copy(Bitmap.Config.ARGB_8888, true)
        repeat(3) { boxBlur(small, 4) }
        return small
    }

    private fun boxBlur(bmp: Bitmap, radius: Int) {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        blurPass(a, b, w, h, radius, horizontal = true)
        blurPass(b, a, w, h, radius, horizontal = false)
        bmp.setPixels(a, 0, w, 0, 0, w, h)
    }

    private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) for (i in 0 until len) {
            var sa = 0; var sr = 0; var sg = 0; var sb = 0
            for (k in -r..r) {
                val j = (i + k).coerceIn(0, len - 1)
                val p = if (horizontal) src[line * w + j] else src[j * w + line]
                sa += p ushr 24; sr += (p shr 16) and 0xFF; sg += (p shr 8) and 0xFF; sb += p and 0xFF
            }
            val n = 2 * r + 1
            val idx = if (horizontal) line * w + i else i * w + line
            dst[idx] = ((sa / n) shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
        }
    }
}

/** Coil transformation that turns downloaded artwork into the blurred page background. */
class AmbientBlur : Transformation {
    override val cacheKey = "ambient-blur"
    override suspend fun transform(input: Bitmap, size: Size): Bitmap = Blur.ambient(input)
}
