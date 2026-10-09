package com.example.sdr

data class RdsFrame(val data: ByteArray, val frequencyHz: Long, val ageMs: Int, val groups: Long) {
    fun information(): RdsInformation {
        if (data.size != 76 || groups == 0L) return RdsInformation(frequencyHz = frequencyHz)
        fun byte(index: Int) = data[index].toInt() and 255
        val flags = byte(3)
        return RdsInformation(
            frequencyHz, (byte(0) shl 8) or byte(1), byte(2),
            if (flags and 1 != 0) RdsCharset.decode(data, 4, 8) else "",
            if (flags and 2 != 0) RdsCharset.decode(data, 12, 64) else "",
            flags and 4 != 0, flags and 8 != 0, ageMs < 5000,
        )
    }
}

data class RdsInformation(
    val frequencyHz: Long = 0,
    val pi: Int? = null,
    val pty: Int = 0,
    val station: String = "",
    val text: String = "",
    val trafficProgramme: Boolean = false,
    val trafficAnnouncement: Boolean = false,
    val synchronized: Boolean = false,
) {
    val programmeType: String get() = programmeTypes[pty.coerceIn(0, 31)]

    companion object {
        private val programmeTypes = listOf(
            "", "News", "Current affairs", "Information", "Sport", "Education", "Drama", "Culture",
            "Science", "Varied", "Pop music", "Rock music", "Easy listening", "Light classical", "Classical", "Other music",
            "Weather", "Finance", "Children", "Social affairs", "Religion", "Phone-in", "Travel", "Leisure",
            "Jazz", "Country", "National music", "Oldies", "Folk music", "Documentary", "Alarm test", "Alarm",
        )
    }
}
