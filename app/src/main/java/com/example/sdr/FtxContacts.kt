package com.example.sdr

import java.util.Locale
import kotlin.math.*

internal data class RadioPoint(val lat: Double, val lon: Double)
internal data class FtxContact(val utc: String, val audioHz: String, val call: String?, val country: String?,
    val grid: String?, val distanceKm: Double?, val message: String)

internal fun maidenheadPoint(value: String): RadioPoint? {
    val grid = value.uppercase(Locale.ROOT)
    if (!Regex("[A-R]{2}[0-9]{2}([A-X]{2})?").matches(grid)) return null
    var lon = (grid[0] - 'A') * 20.0 - 180 + (grid[2] - '0') * 2
    var lat = (grid[1] - 'A') * 10.0 - 90 + (grid[3] - '0')
    if (grid.length == 6) {
        lon += (grid[4] - 'A') / 12.0 + 1.0 / 24
        lat += (grid[5] - 'A') / 24.0 + 1.0 / 48
    } else { lon += 1; lat += .5 }
    return RadioPoint(lat, lon)
}
internal fun radioDistanceKm(a: RadioPoint, b: RadioPoint): Double {
    val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 6371.0088 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

internal class CallsignCountries(text: String) {
    private val exact = mutableMapOf<String, String>()
    private val prefixes = mutableMapOf<String, String>()
    init {
        var country = ""
        text.lineSequence().forEach { line ->
            if (line.isNotBlank() && !line.first().isWhitespace() && ':' in line) {
                country = line.substringBefore(':').trim()
            } else if (country.isNotBlank()) line.trim().split(',', ';').forEach { item ->
                val key = item.trim().takeWhile { it !in "([<{~" }.uppercase(Locale.ROOT)
                if (key.startsWith('=')) exact[key.drop(1)] = country
                else if (key.isNotBlank()) prefixes[key] = country
            }
        }
    }
    private fun prefix(call: String): String? {
        for (size in call.length downTo 1) prefixes[call.take(size)]?.let { return it }
        return null
    }
    fun country(value: String): String? {
        val call = value.trim().uppercase(Locale.ROOT)
        if ('<' in call || '>' in call) return null
        exact[call]?.let { return it }
        val pieces = call.split('/')
        if (pieces.last() in listOf("MM", "AM")) return null
        if (pieces.size == 2 && pieces[1] !in listOf("P", "M", "QRP") && pieces[1].length in 2..4 &&
            pieces[1].last().isDigit()) prefix(pieces[1])?.let { return it }
        return prefix(call)
    }
}

/** Resolve transmitters, never the addressee; grids are learned from CQ/standard messages. */
internal class FtxContacts(private val countries: CallsignCountries) {
    private val grids = linkedMapOf<String, String>()
    private val rowPattern = Regex("^(\\d{2}:\\d{2}:\\d{2})\\s+(\\d+) Hz\\s+(.+)$")
    private fun isCall(token: String) = token.trim('<', '>').split('/').any {
        Regex("[A-Z0-9]{1,4}[0-9][A-Z]{1,4}").matches(it)
    } && token.all { it.isLetterOrDigit() || it in "/<>" }
    fun rows(lines: List<String>, origin: RadioPoint?): List<FtxContact> {
        val parsed = lines.mapNotNull { line ->
            val match = rowPattern.matchEntire(line) ?: return@mapNotNull null
            val message = match.groupValues[3]
            val words = message.uppercase(Locale.ROOT).split(Regex("\\s+"))
            val grid = words.lastOrNull()?.takeIf { it != "RR73" && maidenheadPoint(it) != null }
            val call = if (words.firstOrNull() in listOf("CQ", "QRZ"))
                words.drop(1).firstOrNull { it != grid && isCall(it) }
            else if (words.size >= 2 && (isCall(words[0]) || words[0] == "<...>") && isCall(words[1])) words[1] else null
            if (call != null && grid != null && !call.startsWith('<')) grids[call] = grid
            if (grids.size > 1000) grids.remove(grids.keys.first())
            FtxContact(match.groupValues[1], match.groupValues[2], call, call?.let(countries::country),
                grid, null, message)
        }
        return parsed.map { row ->
            val grid = row.grid ?: row.call?.let { grids[it] }
            val point = grid?.let(::maidenheadPoint)
            row.copy(grid = grid, distanceKm = if (origin != null && point != null) radioDistanceKm(origin, point) else null)
        }
    }
}

internal fun coordinateLabel(value: Double, latitude: Boolean): String {
    val direction = if (latitude) if (value < 0) "S" else "N" else if (value < 0) "W" else "E"
    return String.format(Locale.US, "%.4f\u00b0 %s", kotlin.math.abs(value), direction)
}
