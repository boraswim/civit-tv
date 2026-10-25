package com.tvenhance.home

import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class NextEzan(val nameRes: Int, val at: LocalDateTime)

/**
 * Diyanet's official prayer times for Esenyurt/İstanbul, via the public ezanvakti.emushaf.net
 * mirror of Diyanet's schedule. One request returns about a month of days; it's cached on disk
 * and refreshed when fewer than a week of days remain.
 */
class Ezan(private val cacheDir: File) {
    @Volatile private var days: Map<LocalDate, List<Pair<Int, LocalTime>>> = emptyMap()

    /** The next ezan after [now], loading/refreshing the schedule as needed. */
    fun next(now: LocalDateTime): NextEzan? {
        if (days.isEmpty()) days = parse(runCatching { cacheFile.readText() }.getOrNull())
        if (days.keys.none { it >= now.toLocalDate().plusDays(7) }) {
            runCatching { download() }.getOrNull()?.let { json ->
                parse(json).takeIf { it.isNotEmpty() }?.let { days = it; cacheFile.writeText(json) }
            }
        }
        for (offset in 0L..1L) {
            val date = now.toLocalDate().plusDays(offset)
            days[date]?.firstOrNull { (_, t) -> date.atTime(t) > now }?.let { (name, t) -> return NextEzan(name, date.atTime(t)) }
        }
        return null
    }

    private fun download(): String {
        val c = URL("https://ezanvakti.emushaf.net/vakitler/$ESENYURT").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun parse(json: String?): Map<LocalDate, List<Pair<Int, LocalTime>>> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).associate { i ->
                val o = arr.getJSONObject(i)
                val date = LocalDate.parse(o.getString("MiladiTarihKisa"), DATE)
                // Sunrise (Güneş) has no ezan, so it's left out.
                date to listOf(
                    R.string.ezan_imsak to o.getString("Imsak"),
                    R.string.ezan_ogle to o.getString("Ogle"),
                    R.string.ezan_ikindi to o.getString("Ikindi"),
                    R.string.ezan_aksam to o.getString("Aksam"),
                    R.string.ezan_yatsi to o.getString("Yatsi"),
                ).map { (name, t) -> name to LocalTime.parse(t) }
            }
        }.getOrDefault(emptyMap())
    }

    private val cacheFile get() = File(cacheDir, "ezan_esenyurt.json")

    private companion object {
        const val ESENYURT = "9540" // Diyanet district id for Esenyurt, İstanbul
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
