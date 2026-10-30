package com.example.sdr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sdr.sources.openwebrx.OpenWebRxDirectory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenWebRxDirectoryTest {
    @Test fun parsesGroupedSitesAndFiltersInvalidEntries() {
        val html = """<script>var receivers = [
            {"label":"Site","url":"https://fallback.example/","location":{"coordinates":[13.3,52.4]},
             "receivers":[
                {"label":"Radio ] ;","type":"OpenWebRX","url":"https://radio.example/"},
                {"label":"Duplicate","type":"OpenWebRX","url":"https://radio.example/"},
                {"label":"Fallback","type":"OpenWebRX"},
                {"type":"KiwiSDR","url":"https://kiwi.example/"},
                {"type":"OpenWebRX","url":"javascript:alert(1)"}]},
            {"location":{"coordinates":[0,95]},"receivers":[{"type":"OpenWebRX","url":"https://invalid.example/"}]}
        ]; maliciousCode();</script>"""
        val receivers = OpenWebRxDirectory.parse(html)
        assertEquals(listOf("https://radio.example/", "https://fallback.example/"), receivers.map { it.url })
        assertEquals("Radio ] ;", receivers.first().name)
        assertEquals(52.4f, receivers.first().lat, .001f)
        assertTrue(receivers.all { it.users == null && it.bands.isEmpty() })
    }

    @Test fun rejectsMissingDirectory() {
        assertThrows(IllegalStateException::class.java) { OpenWebRxDirectory.parse("<html>Unavailable</html>") }
    }
}
