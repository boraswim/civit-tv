package com.tvenhance.home

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A film or series to show on the top shelf. */
data class Rec(
    val title: String,
    val overview: String,
    val year: String,
    val rating: Double,
    val isTv: Boolean,
    val backdrop: String,
    val poster: String,
)

/**
 * "Popular on <service> in Türkiye" from TMDB (themoviedb.org), used because Android doesn't
 * let a non-system launcher read other apps' own recommendation channels.
 * Accepts either a v3 API key or a v4 read access token. Results are cached for 6 hours,
 * on disk too, so the shelf fills instantly after a reboot or when offline.
 */
class Tmdb(private val key: String, private val cacheDir: File) {
    private val memory = ConcurrentHashMap<String, Pair<Long, List<Rec>>>()
    @Volatile private var providers: Map<String, Int>? = null

    // TMDB provider names (normalised) to try for each app; the first one TMDB lists wins.
    private val appProviders = mapOf(
        "com.netflix.ninja" to listOf("netflix"),
        "com.disney.disneyplus" to listOf("disneyplus"),
        "com.amazon.amazonvideo.livingroom" to listOf("amazonprimevideo", "primevideo"),
        "com.wbd.stream" to listOf("hbomax", "max"),
        // In Türkiye TMDB only lists Apple's rental/purchase store, not Apple TV+.
        "com.apple.atve.androidtv.appletv" to listOf("appletvplus", "appletv+", "appletvstore"),
        "com.trt.tabii.android" to listOf("tabii"),
        "com.dogusdigital.puhutv" to listOf("puhutv"),
        "com.digiturk.iq.mobil" to listOf("beinconnect"),
        "com.turkcell.ott" to listOf("tv+", "turkcelltv+"),
    )

    fun covers(pkg: String) = key.isNotBlank() && pkg in appProviders

    /** Whatever is cached, without touching the network. */
    fun cached(pkg: String): List<Rec> =
        memory[pkg]?.second ?: readCache(cacheFile(pkg))?.second.orEmpty()

    /** Fresh-enough recommendations, fetching if the cache is older than 6 hours. */
    fun recommendations(pkg: String): List<Rec> {
        if (!covers(pkg)) return emptyList()
        val now = System.currentTimeMillis()
        val cached = memory[pkg] ?: readCache(cacheFile(pkg))?.also { memory[pkg] = it }
        if (cached != null && cached.second.isNotEmpty() && now - cached.first < TTL_MS) return cached.second
        // Offline, TMDB down, or nothing found: keep showing what we had, and don't cache emptiness.
        val fresh = runCatching { fetch(pkg) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: return cached?.second.orEmpty()
        memory[pkg] = now to fresh
        writeCache(cacheFile(pkg), now, fresh)
        return fresh
    }

    private fun fetch(pkg: String): List<Rec> {
        val ids = providerIds()
        val name = appProviders.getValue(pkg).firstOrNull { it in ids } ?: return emptyList()
        val id = ids.getValue(name)
        // Subscription catalogues use "flatrate"; stores sell or rent instead (%7C is "|").
        val monetization = if (name.endsWith("store")) "rent%7Cbuy" else "flatrate"
        val query = "watch_region=TR&with_watch_providers=$id&with_watch_monetization_types=$monetization" +
            "&sort_by=popularity.desc&language=tr-TR&page=1"
        val series = parse(get("discover/tv?$query"), isTv = true)
        val films = parse(get("discover/movie?$query"), isTv = false)
        // Alternate series and films so one kind doesn't take the whole shelf.
        val mixed = ArrayList<Rec>()
        for (i in 0 until maxOf(series.size, films.size)) {
            series.getOrNull(i)?.let(mixed::add)
            films.getOrNull(i)?.let(mixed::add)
        }
        return mixed.take(10)
    }

    private fun providerIds(): Map<String, Int> {
        providers?.let { return it }
        val map = HashMap<String, Int>()
        for (kind in listOf("tv", "movie")) {
            val list = JSONObject(get("watch/providers/$kind?watch_region=TR&language=tr-TR")).getJSONArray("results")
            for (i in 0 until list.length()) {
                val o = list.getJSONObject(i)
                map.putIfAbsent(normalise(o.getString("provider_name")), o.getInt("provider_id"))
            }
        }
        providers = map
        return map
    }

    private fun parse(json: String, isTv: Boolean): List<Rec> {
        val results = JSONObject(json).getJSONArray("results")
        return (0 until results.length()).mapNotNull { i ->
            val o = results.getJSONObject(i)
            val backdrop = o.path("backdrop_path") ?: return@mapNotNull null
            val poster = o.path("poster_path") ?: return@mapNotNull null
            Rec(
                title = o.optString(if (isTv) "name" else "title"),
                overview = o.optString("overview"),
                year = o.optString(if (isTv) "first_air_date" else "release_date").take(4),
                rating = o.optDouble("vote_average", 0.0),
                isTv = isTv,
                backdrop = "$IMAGES/w780$backdrop",
                poster = "$IMAGES/w342$poster",
            )
        }
    }

    private fun JSONObject.path(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.startsWith("/") }

    private fun get(path: String): String {
        val bearer = key.startsWith("eyJ")
        val url = URL("https://api.themoviedb.org/3/$path" + if (bearer) "" else "&api_key=$key")
        val c = url.openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        c.setRequestProperty("Accept", "application/json")
        if (bearer) c.setRequestProperty("Authorization", "Bearer $key")
        try {
            if (c.responseCode != 200) throw IOException("TMDB HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun normalise(s: String) = s.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() || it == '+' }

    // Versioned so a change in how results are chosen ignores older cached lists.
    private fun cacheFile(pkg: String) = File(cacheDir, "tmdb_v3_$pkg.json")

    private fun readCache(file: File): Pair<Long, List<Rec>>? = runCatching {
        val o = JSONObject(file.readText())
        val items = o.getJSONArray("items")
        o.getLong("t") to (0 until items.length()).map { i ->
            val r = items.getJSONObject(i)
            Rec(
                r.getString("title"), r.getString("overview"), r.getString("year"), r.getDouble("rating"),
                r.getBoolean("isTv"), r.getString("backdrop"), r.getString("poster"),
            )
        }
    }.getOrNull()

    private fun writeCache(file: File, time: Long, recs: List<Rec>) = runCatching {
        val items = JSONArray()
        for (r in recs) {
            items.put(
                JSONObject()
                    .put("title", r.title).put("overview", r.overview).put("year", r.year)
                    .put("rating", r.rating).put("isTv", r.isTv).put("backdrop", r.backdrop).put("poster", r.poster)
            )
        }
        file.writeText(JSONObject().put("t", time).put("items", items).toString())
    }

    private companion object {
        const val TTL_MS = 6 * 60 * 60 * 1000L
        const val IMAGES = "https://image.tmdb.org/t/p"
    }
}
