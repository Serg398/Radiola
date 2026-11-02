package com.example.sdr.receiver

internal data class AircraftReport(
    val icao: String,
    val flight: String = "",
    val registration: String = "",
    val countryCode: String = "",
    val altitudeFeet: Int? = null,
    val speedKnots: Int? = null,
    val courseDegrees: Int? = null,
    val squawk: String = "",
    val emergency: String = "",
    val timestampMillis: Long? = null,
)
