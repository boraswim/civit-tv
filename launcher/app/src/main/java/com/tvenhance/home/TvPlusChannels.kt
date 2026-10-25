package com.tvenhance.home

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

const val TV_PLUS = "com.turkcell.ott"

data class Channel(val name: String, val path: String, val logo: String) {
    /**
     * Opens the channel straight in TV+'s live player. TV+ builds these as
     * `<prefix>tv-izle/<name>--<id>` and Android only routes the web.tvplus.com.tr prefix to it;
     * the website's own /canli-tv/ paths carry the same `<name>--<id>` slug.
     */
    val link get() = "$TV_PLUS_LINK${path.substringAfterLast('/')}"
}

const val TV_PLUS_LINK = "https://web.tvplus.com.tr/tv-izle/"

/** Matching key for a channel name: "TRT1 HD", "TRT1" and "trt1-hd" all become "trt1". */
fun channelKey(name: String) =
    name.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.removeSuffix("hd")

/**
 * TV+ live channels, read from the public channel list on tvplus.com.tr (TV+ offers no API
 * to other apps). Cached for a day; the big national channels are listed first.
 */
class TvPlusChannels(private val cacheDir: File) {
    @Volatile private var memory: Pair<Long, List<Channel>>? = null

    fun cached(): List<Channel> = (memory ?: readCache())?.second.orEmpty()

    fun popular(): List<Channel> {
        val now = System.currentTimeMillis()
        val cached = memory ?: readCache()?.also { memory = it }
        if (cached != null && cached.second.isNotEmpty() && now - cached.first < TTL_MS) return cached.second
        val fresh = runCatching { fetch() }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return cached?.second.orEmpty()
        memory = now to fresh
        writeCache(now, fresh)
        return fresh
    }

    private fun fetch(): List<Channel> {
        val c = URL(PAGE).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0 Safari/537.36")
        val html = try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
        // Each channel is an <a href="/canli-tv/<slug>--<id>"> holding a logo <img> and a name <span>.
        val all = CHANNEL.findAll(html).map { m ->
            Channel(name = m.groupValues[3].trim(), path = m.groupValues[1], logo = m.groupValues[2])
        }.distinctBy { it.path }.toList()
        val rank = PRIORITY.withIndex().associate { (i, n) -> n to i }
        return all.sortedBy { rank[channelKey(it.name)] ?: Int.MAX_VALUE }
    }

    private val cacheFile get() = File(cacheDir, "tvplus_channels.json")

    private fun readCache(): Pair<Long, List<Channel>>? = runCatching {
        val o = JSONObject(cacheFile.readText())
        val items = o.getJSONArray("items")
        o.getLong("t") to (0 until items.length()).map { i ->
            val ch = items.getJSONObject(i)
            Channel(ch.getString("name"), ch.getString("path"), ch.getString("logo"))
        }
    }.getOrNull()

    private fun writeCache(time: Long, channels: List<Channel>) = runCatching {
        val items = JSONArray()
        for (ch in channels) items.put(JSONObject().put("name", ch.name).put("path", ch.path).put("logo", ch.logo))
        cacheFile.writeText(JSONObject().put("t", time).put("items", items).toString())
    }

    private companion object {
        const val TTL_MS = 24 * 60 * 60 * 1000L
        const val PAGE = "https://tvplus.com.tr/canli-tv"
        val CHANNEL = Regex(
            """href="(/canli-tv/[a-z0-9-]+--\d+)"><div[^>]*><img[^>]*?src="([^"]+)"[^>]*/?></div><span[^>]*__name">([^<]+)</span>"""
        )
        // Türkiye's most-watched national channels, in rough ratings order.
        val PRIORITY = listOf("trt1", "atv", "showtv", "kanald", "startv", "now", "tv8", "kanal7", "trthaber", "habertürk")
    }
}
