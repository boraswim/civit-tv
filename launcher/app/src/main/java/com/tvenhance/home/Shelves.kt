package com.tvenhance.home

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import java.io.File
import java.util.Locale

/** How a shelf's cards are shaped. */
enum class ShelfLayout {
    Featured, // one wide backdrop, then posters (films & series)
    Squares,  // square covers (music)
    Wide,     // 16:9 thumbnails (videos)
    Channels, // square tiles with a channel logo fitted inside
}

data class ShelfItem(
    val title: String,
    val meta: String,
    val image: Any?,      // Coil model: URL or File; null falls back to the app's banner
    val wideImage: Any?,
    val link: String?,    // deep link opened in the shelf's app, or null to just open the app
) {
    fun intent(pkg: String): Intent? = link?.let { Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(pkg) }
}

data class Shelf(val pkg: String, val heading: String, val layout: ShelfLayout, val items: List<ShelfItem>)

/**
 * Decides what the top shelf shows for each app:
 *  - TV+: the last watched channel(s), then the most popular live channels
 *  - Apple Music: a shortcut into Arşiv, your recently played songs, then Türkiye's top songs
 *  - film & series services: what's popular on them in Türkiye (TMDB)
 *  - everything else: what recently played in that app (from [MediaWatcher])
 * Apps with none of these keep the big-banner view.
 */
class ShelfSource(
    private val context: Context,
    private val tmdb: Tmdb,
    private val charts: AppleCharts,
    private val channels: TvPlusChannels,
) {

    /** Instant version from caches only, for drawing before the network answers. */
    fun cached(app: AppEntry, history: List<Played>): Shelf? = build(app, history, network = false)

    fun load(app: AppEntry, history: List<Played>): Shelf? = build(app, history, network = true)

    private fun build(app: AppEntry, history: List<Played>, network: Boolean): Shelf? {
        val pkg = app.pkg
        val played = history.filter { it.pkg == pkg }
        if (pkg == TV_PLUS) {
            val list = if (network) channels.popular() else channels.cached()
            if (list.isNotEmpty() || played.isNotEmpty()) {
                // Recently watched first (newest first), then popular channels not already shown.
                val recent = played.distinctBy { channelKey(it.title) }.take(SHELF_MAX).map(::playedItem)
                val shown = recent.map { channelKey(it.title) }.toSet()
                val popular = list.filter { channelKey(it.name) !in shown }.mapIndexed { i, ch -> channelItem(ch, i + 1) }
                return Shelf(
                    pkg, context.getString(R.string.tvplus_shelf, app.label), ShelfLayout.Channels,
                    (recent + popular).take(SHELF_MAX),
                )
            }
        }
        if (pkg == APPLE_MUSIC) {
            val songs = if (network) charts.topSongs() else charts.cached()
            // Arşiv can't be listed (Apple Music only shares it with approved apps like Android Auto),
            // so it's a shortcut card that opens Arşiv, followed by what you played and the charts.
            val archive = ShelfItem(
                context.getString(R.string.my_archive), context.getString(R.string.my_archive_meta),
                image = null, wideImage = null, link = "https://music.apple.com/library",
            )
            val mine = played.take(2).map(::playedItem)
            val top = songs.distinctBy { it.artwork }.map(::songItem)
            return Shelf(pkg, context.getString(R.string.apple_music_shelf, app.label), ShelfLayout.Squares, listOf(archive) + mine + top)
        }
        if (tmdb.covers(pkg)) {
            val recs = if (network) tmdb.recommendations(pkg) else tmdb.cached(pkg)
            if (recs.isNotEmpty()) {
                return Shelf(pkg, context.getString(R.string.popular_on, app.label), ShelfLayout.Featured, recs.map(::recItem))
            }
        }
        if (played.isNotEmpty()) {
            val layout = if (pkg in VIDEO_APPS) ShelfLayout.Wide else ShelfLayout.Squares
            return Shelf(pkg, context.getString(R.string.recently_played, app.label), layout, played.map(::playedItem))
        }
        return null
    }

    private fun recItem(r: Rec): ShelfItem {
        val kind = context.getString(if (r.isTv) R.string.type_tv else R.string.type_movie)
        val meta = listOfNotNull(
            r.year.takeIf { it.isNotBlank() },
            kind,
            r.rating.takeIf { it > 0 }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) },
            r.overview.takeIf { it.isNotBlank() },
        ).joinToString("  ·  ")
        return ShelfItem(r.title, meta, r.poster, r.backdrop, link = null)
    }

    private fun songItem(s: ChartSong): ShelfItem {
        val meta = listOfNotNull("#${s.rank}", s.artist.takeIf { it.isNotBlank() }, s.year.takeIf { it.isNotBlank() })
            .joinToString("  ·  ")
        // music.apple.com links open the song inside the Apple Music app.
        return ShelfItem(s.title, meta, s.artwork, s.artwork, link = s.url)
    }

    private fun channelItem(ch: Channel, rank: Int): ShelfItem =
        ShelfItem(ch.name, context.getString(R.string.channel_meta, rank), ch.logo, wideImage = null, link = ch.link)

    private fun playedItem(p: Played): ShelfItem {
        val now = System.currentTimeMillis()
        val ago = if (now - p.time < 60_000) context.getString(R.string.just_now)
        else DateUtils.getRelativeTimeSpanString(p.time, now, DateUtils.MINUTE_IN_MILLIS).toString()
        val label = context.getString(if (p.pkg == TV_PLUS) R.string.last_watched else R.string.last_played_short)
        val meta = listOfNotNull(label, ago, p.subtitle.takeIf { it.isNotBlank() }).joinToString("  ·  ")
        val art: Any? = p.art?.let { if (it.startsWith("/")) File(it) else it }
        // Channel logos aren't backdrops, so they don't tint the page background.
        val wide = if (p.pkg == TV_PLUS) null else art
        return ShelfItem(p.title, meta, art, wide, link = playedLink(p))
    }

    private companion object {
        const val SHELF_MAX = 10

        // Apps whose artwork is usually 16:9 video frames rather than square covers.
        val VIDEO_APPS = setOf(
            "com.google.android.youtube.tv",
            "ar.tvplayer.tv",
            "ru.iptvremote.android.iptv.pro",
            "org.videolan.vlc",
            "com.digiturk.iq.mobil",
            "com.google.android.tv",
        )
    }
}

/** Reopens a played item where the app supports it (YouTube videos), else null to open the app. */
fun playedIntent(p: Played): Intent? = playedLink(p)?.let {
    Intent(Intent.ACTION_VIEW, Uri.parse(it)).setPackage(p.pkg)
}

/** YouTube videos and TV+ channels we opened ourselves can be reopened directly. */
private fun playedLink(p: Played): String? =
    youtubeId(p.pkg, p.mediaId)?.let { "https://www.youtube.com/watch?v=$it" }
        ?: p.mediaId?.takeIf { p.pkg == TV_PLUS && it.startsWith(TV_PLUS_LINK) }
