package com.example.sdr.receiver

import java.math.BigDecimal
import java.math.RoundingMode

internal enum class FrequencyInputError(val message: String) {
    INVALID("Enter a positive frequency in MHz"),
    OUT_OF_RANGE("Frequency is outside the supported range"),
}
internal data class FrequencyInputResult(val hz: Long?, val error: FrequencyInputError?)

/** Decimal MHz input is validated before it can reach a receiver or change its frequency. */
internal fun validateFrequencyInput(text: String, ranges: List<LongRange>): FrequencyInputResult {
    val normalized = text.trim().replace(',', '.')
    if (normalized.length !in 1..64 || !normalized.matches(Regex("[+]?(?:[0-9]+(?:[.][0-9]*)?|[.][0-9]+)")))
        return FrequencyInputResult(null, FrequencyInputError.INVALID)
    val hz = try {
        BigDecimal(normalized).multiply(BigDecimal(1_000_000))
            .setScale(0, RoundingMode.HALF_UP).longValueExact()
    } catch (_: NumberFormatException) { null }
      catch (_: ArithmeticException) { null }
    return when {
        hz == null || hz <= 0 -> FrequencyInputResult(null, FrequencyInputError.INVALID)
        ranges.none { hz in it } -> FrequencyInputResult(null, FrequencyInputError.OUT_OF_RANGE)
        else -> FrequencyInputResult(hz, null)
    }
}
