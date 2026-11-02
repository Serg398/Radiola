package com.example.sdr.sources.openwebrx

import com.example.sdr.receiver.AircraftReport
import org.json.JSONObject
import java.util.Locale

internal object OpenWebRxAircraft {
    fun parse(message: JSONObject): List<AircraftReport> {
        val entries = message.optJSONArray("aircraft")
        val objects = if (entries != null) (0 until minOf(entries.length(), 4096)).mapNotNull { entries.optJSONObject(it) }
            else if (message.optString("mode") == "ADSB") listOf(message) else emptyList()
        return objects.mapNotNull { entry ->
            val icao = entry.optString("icao").trim().uppercase(Locale.ROOT)
            if (!icao.matches(Regex("[0-9A-F]{6}"))) return@mapNotNull null
            fun number(key: String): Int? = entry.optDouble(key, Double.NaN)
                .takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE }?.toInt()
            fun field(key: String) = if (entry.isNull(key)) "" else entry.optString(key).trim().take(32)
            AircraftReport(icao, field("flight"), field("aircraft"), field("ccode").uppercase(Locale.ROOT),
                number("altitude"), number("speed")?.takeIf { it >= 0 },
                number("course")?.takeIf { it in 0..360 }?.rem(360), field("squawk"),
                field("emergency").takeUnless { it.equals("none", true) }.orEmpty(),
                entry.optDouble("timestamp", Double.NaN).takeIf { it.isFinite() && it > 0 && it < Long.MAX_VALUE }?.toLong())
        }.distinctBy { it.icao }.sortedByDescending { it.timestampMillis ?: 0 }.take(200).sortedBy { it.icao }
    }

    fun merge(previous: List<AircraftReport>, incoming: List<AircraftReport>): List<AircraftReport> {
        val reports = previous.associateBy { it.icao }.toMutableMap()
        for (next in incoming) {
            val old = reports[next.icao]
            if (old?.timestampMillis != null && next.timestampMillis != null && next.timestampMillis < old.timestampMillis) continue
            reports[next.icao] = if (old == null) next else next.copy(
                flight = next.flight.ifBlank { old.flight }, registration = next.registration.ifBlank { old.registration },
                countryCode = next.countryCode.ifBlank { old.countryCode }, altitudeFeet = next.altitudeFeet ?: old.altitudeFeet,
                speedKnots = next.speedKnots ?: old.speedKnots, courseDegrees = next.courseDegrees ?: old.courseDegrees,
                squawk = next.squawk.ifBlank { old.squawk }, timestampMillis = next.timestampMillis ?: old.timestampMillis)
        }
        return reports.values.sortedByDescending { it.timestampMillis ?: 0 }.take(200).sortedBy { it.icao }
    }
}
