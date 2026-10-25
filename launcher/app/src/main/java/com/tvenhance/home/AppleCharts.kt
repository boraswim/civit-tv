package com.tvenhance.home

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class ChartSong(val rank: Int, val title: String, val artist: String, val year: String, val artwork: String, val url: String)

/** Apple Music's public "Top Songs" chart for Türkiye (no account needed). Cached for 6 hours. */
class AppleCharts(private val cacheDir: File) {
    @Volatile private var memory: Pair<Long, List<ChartSong>>? = null

    fun cached(): List<ChartSong> = (memory ?: readCache())?.second.orEmpty()

    fun topSongs(): List<ChartSong> {
        val now = System.currentTimeMillis()
        val cached = memory ?: readCache()?.also { memory = it }
        if (cached != null && now - cached.first < TTL_MS) return cached.second
        val fresh = runCatching { fetch() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return cached?.second.orEmpty()
        memory = now to fresh
        writeCache(now, fresh)
        return fresh
    }

    private fun fetch(): List<ChartSong> {
        val c = URL(FEED).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        val body = try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        val results = JSONObject(body).getJSONObject("feed").getJSONArray("results")
        return (0 until results.length()).map { i ->
            val o = results.getJSONObject(i)
            ChartSong(
                rank = i + 1,
                title = o.getString("name"),
                artist = o.optString("artistName"),
                year = o.optString("releaseDate").take(4),
                // Feed gives 100px covers; the same URL serves any size.
                artwork = o.getString("artworkUrl100").replace("100x100bb", "400x400bb"),
                url = o.getString("url"),
            )
        }
    }

    private val cacheFile get() = File(cacheDir, "apple_top_songs.json")

    private fun readCache(): Pair<Long, List<ChartSong>>? = runCatching {
        val o = JSONObject(cacheFile.readText())
        val items = o.getJSONArray("items")
        o.getLong("t") to (0 until items.length()).map { i ->
            val s = items.getJSONObject(i)
            ChartSong(s.getInt("rank"), s.getString("title"), s.getString("artist"), s.getString("year"), s.getString("artwork"), s.getString("url"))
        }
    }.getOrNull()

    private fun writeCache(time: Long, songs: List<ChartSong>) = runCatching {
        val items = JSONArray()
        for (s in songs) {
            items.put(
                JSONObject().put("rank", s.rank).put("title", s.title).put("artist", s.artist)
                    .put("year", s.year).put("artwork", s.artwork).put("url", s.url)
            )
        }
        cacheFile.writeText(JSONObject().put("t", time).put("items", items).toString())
    }

    private companion object {
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val FEED = "https://rss.applemarketingtools.com/api/v2/tr/music/most-played/20/songs.json"
    }
}
