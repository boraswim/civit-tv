package com.tvenhance.home

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.edit
import androidx.core.graphics.drawable.toBitmap
import java.util.concurrent.ConcurrentHashMap

const val SWITCH_KEY = "switch:"

data class AppEntry(
    val pkg: String,
    val label: String,
    val banner: ImageBitmap,   // 16:9 tile art: the app's TV banner, or its icon on a tinted card
    val ambient: ImageBitmap,  // tiny pre-blurred copy used as the full-screen background
    val launch: Intent,
    val removable: Boolean = false, // installed by the user (system apps can't be uninstalled)
) {
    val isBuiltIn get() = pkg == SWITCH_KEY
}

class AppRepository(private val context: Context) {
    private val pm = context.packageManager
    private val selfPkg = context.packageName
    private val prefs = context.getSharedPreferences("home", Context.MODE_PRIVATE)
    private val cache = ConcurrentHashMap<String, Pair<Long, AppEntry>>()
    private val switchEntry by lazy { buildSwitchEntry() }

    // Phone-only apps that ship no TV banner but get one from us.
    private val bannerOverrides = mapOf(APPLE_MUSIC to R.drawable.banner_apple_music)

    // Banners whose logo is tiny inside lots of empty background: shown zoomed into the centre.
    private val bannerZoom = mapOf(TV_PLUS to 2.2f)

    // Apps that go to the front of the dock until the user reorders things.
    private val defaultFront = listOf(
        "com.google.android.youtube.tv",
        "com.netflix.ninja",
        "com.disney.disneyplus",
        "com.apple.atve.androidtv.appletv",
        "com.amazon.amazonvideo.livingroom",
    )

    fun loadApps(): List<AppEntry> {
        val found = LinkedHashMap<String, Pair<ResolveInfo, String>>()
        for (category in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                found.putIfAbsent(ri.activityInfo.packageName, ri to category)
            }
        }
        found.remove(selfPkg)
        val hidden = hiddenSet()
        val entries = found.filterKeys { it !in hidden }.values
            .mapNotNull { (ri, category) -> runCatching { entryFor(ri, category) }.getOrNull() }
        // Settings lives in the top-right gear button rather than as a tile.
        return applyOrder(entries + switchEntry)
    }

    fun saveOrder(pkgs: List<String>) = prefs.edit { putString("order", pkgs.joinToString(",")) }

    fun hide(pkg: String) = prefs.edit { putStringSet("hidden", hiddenSet() + pkg) }

    fun unhideAll() = prefs.edit { remove("hidden") }

    fun hiddenCount() = hiddenSet().size

    fun markLaunched(pkg: String) = prefs.edit { putLong("launched_$pkg", System.currentTimeMillis()) }

    fun lastLaunched(pkg: String) = prefs.getLong("launched_$pkg", 0L)

    private fun hiddenSet(): Set<String> = prefs.getStringSet("hidden", emptySet()).orEmpty().toSet()

    private fun applyOrder(entries: List<AppEntry>): List<AppEntry> {
        val saved = prefs.getString("order", null).orEmpty().split(',').filter { it.isNotEmpty() }
        val rank = saved.withIndex().associate { (i, p) -> p to i }
        val (known, fresh) = entries.partition { it.pkg in rank }
        val freshSorted = fresh.sortedWith(
            compareBy<AppEntry>(
                { defaultFront.indexOf(it.pkg).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                { it.isBuiltIn },
                { it.pkg == SWITCH_KEY },
                { it.label.lowercase() },
            )
        )
        return known.sortedBy { rank.getValue(it.pkg) } + freshSorted
    }

    private fun entryFor(ri: ResolveInfo, category: String): AppEntry {
        val pkg = ri.activityInfo.packageName
        val updated = pm.getPackageInfo(pkg, 0).lastUpdateTime
        cache[pkg]?.let { (stamp, entry) -> if (stamp == updated) return entry }

        val label = ri.loadLabel(pm).toString()
        val banner = bannerOverrides[pkg]?.let { context.getDrawable(it) }?.toBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)
            ?: ri.activityInfo.loadBanner(pm)?.toBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)
                ?.let { b -> bannerZoom[pkg]?.let { zoomCentre(b.softwareCopy(), it) } ?: b }
            ?: iconCard(ri.loadIcon(pm).toBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).softwareCopy())
        val launch = Intent(Intent.ACTION_MAIN)
            .addCategory(category)
            .setClassName(pkg, ri.activityInfo.name)

        val system = ri.activityInfo.applicationInfo.flags and
            (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
        val entry = AppEntry(pkg, label, banner.asImageBitmap(), ambientOf(banner.softwareCopy()).asImageBitmap(), launch, removable = !system)
        cache[pkg] = updated to entry
        return entry
    }

    private fun buildSwitchEntry(): AppEntry {
        val bmp = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(), 0xFF2B5876.toInt(), 0xFF4E4376.toInt(), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(), bg)
        val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 22f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        // Two opposing arrows: "swap home screens".
        val path = android.graphics.Path().apply {
            moveTo(210f, 145f); lineTo(430f, 145f)
            moveTo(385f, 100f); lineTo(430f, 145f); lineTo(385f, 190f)
            moveTo(430f, 225f); lineTo(210f, 225f)
            moveTo(255f, 180f); lineTo(210f, 225f); lineTo(255f, 270f)
        }
        c.drawPath(path, arrow)
        val launch = Intent(context, SwitchActivity::class.java)
        return AppEntry(SWITCH_KEY, context.getString(R.string.switch_tile), bmp.asImageBitmap(), ambientOf(bmp).asImageBitmap(), launch)
    }

    // Apps without a TV banner get their icon centred on a card tinted with the icon's own colour.
    private fun iconCard(icon: Bitmap): Bitmap {
        val tint = averageColor(icon)
        val bmp = Bitmap.createBitmap(BANNER_W, BANNER_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply {
            shader = LinearGradient(
                0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(),
                lerp(tint, Color.White, 0.12f).toArgb(), lerp(tint, Color.Black, 0.25f).toArgb(),
                Shader.TileMode.CLAMP,
            )
        }
        c.drawRect(0f, 0f, BANNER_W.toFloat(), BANNER_H.toFloat(), bg)
        val size = BANNER_H * 0.56f
        val left = (BANNER_W - size) / 2f
        val top = (BANNER_H - size) / 2f
        c.drawBitmap(icon, null, android.graphics.RectF(left, top, left + size, top + size), Paint(Paint.FILTER_BITMAP_FLAG))
        return bmp
    }

    private fun averageColor(b: Bitmap): Color {
        val s = Bitmap.createScaledBitmap(b, 12, 12, true)
        var r = 0L; var g = 0L; var bl = 0L; var n = 0
        for (y in 0 until s.height) for (x in 0 until s.width) {
            val p = s.getPixel(x, y)
            if ((p ushr 24) < 128) continue
            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; bl += p and 0xFF; n++
        }
        if (n == 0) return Color(0xFF2C2C34)
        return lerp(Color((r / n).toInt(), (g / n).toInt(), (bl / n).toInt()), Color.Black, 0.3f)
    }

    private fun ambientOf(src: Bitmap): Bitmap = Blur.ambient(src)

    /** Crops the middle 1/[zoom] of a banner and scales it back up to full size. */
    private fun zoomCentre(src: Bitmap, zoom: Float): Bitmap {
        val w = (src.width / zoom).toInt()
        val h = (src.height / zoom).toInt()
        val crop = Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
        return Bitmap.createScaledBitmap(crop, BANNER_W, BANNER_H, true)
    }

    private fun Bitmap.softwareCopy(): Bitmap =
        if (config == Bitmap.Config.HARDWARE) copy(Bitmap.Config.ARGB_8888, false) else this

    private companion object {
        const val BANNER_W = 640
        const val BANNER_H = 360
        const val ICON_PX = 192
    }
}
