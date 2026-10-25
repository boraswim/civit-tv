package com.tvenhance.home

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.roundToInt

data class DayForecast(val date: String, val code: Int, val maxC: Int, val minC: Int) // date = yyyy-MM-dd

data class WeatherNow(val place: String, val tempC: Int, val code: Int, val days: List<DayForecast>)

/** Current weather from Open-Meteo (free, no account). Cached for 30 minutes. */
object Weather {
    private data class Place(val name: String, val lat: Double, val lon: Double)

    @Volatile private var place: Place? = null
    @Volatile private var cached: Pair<Long, WeatherNow>? = null

    fun load(city: String): WeatherNow? {
        val now = System.currentTimeMillis()
        cached?.let { (t, w) -> if (now - t < 30 * 60 * 1000L) return w }
        return runCatching {
            val p = place ?: geocode(city)?.also { place = it } ?: return cached?.second
            val json = JSONObject(
                get(
                    "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
                        "&current=temperature_2m,weather_code" +
                        "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=6&timezone=auto"
                )
            )
            val current = json.getJSONObject("current")
            val daily = json.getJSONObject("daily")
            val dates = daily.getJSONArray("time")
            val days = (0 until dates.length()).map { i ->
                DayForecast(
                    dates.getString(i),
                    daily.getJSONArray("weather_code").getInt(i),
                    daily.getJSONArray("temperature_2m_max").getDouble(i).roundToInt(),
                    daily.getJSONArray("temperature_2m_min").getDouble(i).roundToInt(),
                )
            }
            WeatherNow(p.name, current.getDouble("temperature_2m").roundToInt(), current.getInt("weather_code"), days)
                .also { cached = now to it }
        }.getOrElse { cached?.second }
    }

    private fun geocode(city: String): Place? {
        val results = JSONObject(
            get("https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(city, "UTF-8")}&count=1&language=tr")
        ).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val r = results.getJSONObject(0)
        return Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    /** WMO weather code to an emoji and a description string. */
    fun describe(code: Int): Pair<String, Int> = when (code) {
        0 -> "☀️" to R.string.weather_clear
        1, 2 -> "⛅" to R.string.weather_partly
        3 -> "☁️" to R.string.weather_cloudy
        45, 48 -> "🌫️" to R.string.weather_fog
        in 51..57 -> "🌦️" to R.string.weather_drizzle
        in 61..67, in 80..82 -> "🌧️" to R.string.weather_rain
        in 71..77, 85, 86 -> "❄️" to R.string.weather_snow
        in 95..99 -> "⛈️" to R.string.weather_storm
        else -> "🌡️" to R.string.weather_cloudy
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 8000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
