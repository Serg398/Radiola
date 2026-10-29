package com.example.sdr

import org.json.JSONArray

internal data class OpenWebRxMode(
    val id: String, val label: String, val secondary: Boolean,
    val underlying: List<String>, val requirements: List<String>,
    val low: Int?, val high: Int?, val ifRate: Int?,
) {
    val baseId get() = if (secondary) underlying.firstOrNull() ?: "usb" else id
    val hasAudio get() = baseId != "empty"
    val basicMode get() = when (baseId) {
        "am" -> DemodulationMode.AM
        "wfm" -> DemodulationMode.WFM
        "usb" -> DemodulationMode.USB
        "lsb" -> DemodulationMode.LSB
        "cw" -> DemodulationMode.CW
        else -> DemodulationMode.NFM
    }
    val defaultWidth get() = if (low != null && high != null) high - low else ifRate ?: 12000
    val cwTone get() = if (id == "cw") ((low ?: 700) + (high ?: 900)) / 2 else 0
    fun lowCut(width: Int): Int? {
        if (low == null || high == null) return null
        return when {
            baseId == "usb" -> low
            baseId == "lsb" -> high - width
            else -> (low + high - width) / 2
        }
    }

    companion object {
        fun parse(array: JSONArray): List<OpenWebRxMode> = buildList {
            fun strings(array: JSONArray?) = if (array == null) emptyList() else
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            for (i in 0 until minOf(array.length(), 256)) {
                val entry = array.optJSONObject(i) ?: continue
                val id = entry.optString("modulation")
                if (!id.matches(Regex("[a-zA-Z0-9_-]{1,40}"))) continue
                val passband = entry.optJSONObject("bandpass")
                val low = passband?.optDouble("low_cut", Double.NaN)
                val high = passband?.optDouble("high_cut", Double.NaN)
                val valid = low != null && high != null && low.isFinite() && high.isFinite() &&
                    low >= -10_000_000 && high <= 10_000_000 && high > low
                add(OpenWebRxMode(id, entry.optString("name").ifBlank { id }.take(60),
                    entry.optString("type") == "digimode", strings(entry.optJSONArray("underlying")),
                    strings(entry.optJSONArray("requirements")),
                    if (valid) low!!.toInt() else null, if (valid) high!!.toInt() else null,
                    entry.optDouble("ifRate", Double.NaN).takeIf { it.isFinite() && it in 100.0..20_000_000.0 }?.toInt()))
            }
        }.distinctBy { it.id }.let { modes ->
            modes.map { mode ->
                if (mode.secondary && mode.low == null) {
                    val base = modes.firstOrNull { it.id == mode.baseId }
                    mode.copy(low = base?.low, high = base?.high)
                } else mode
            }
        }
    }
}
