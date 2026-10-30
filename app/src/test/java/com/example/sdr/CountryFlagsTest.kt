package com.example.sdr

import com.example.sdr.decoders.ftx.countryDisplayName
import com.example.sdr.decoders.ftx.countryFlag
import org.junit.Assert.*
import org.junit.Test

class CountryFlagsTest {
    @Test fun isoNamesAndDxccAliasesHaveFlags() {
        assertEquals("🇵🇱", countryFlag("Poland"))
        assertEquals("🇰🇬", countryFlag("Kyrgyzstan"))
        assertEquals("🇩🇪", countryFlag("Fed. Rep. of Germany"))
        assertEquals("🇷🇺", countryFlag("European Russia"))
        assertEquals("🇷🇺", countryFlag("Asiatic Russia"))
        assertEquals("🇬🇧", countryFlag("England"))
        assertEquals("🇯🇵", countryFlag("Japan"))
    }
    @Test fun unknownEntityDoesNotInventFlag() {
        assertEquals("🌐", countryFlag(null))
        assertEquals("🌐", countryFlag("Unknown entity"))
        assertEquals("Germany", countryDisplayName("Fed. Rep. of Germany"))
    }
}
