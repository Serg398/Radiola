package com.example.sdr

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.sdr.sources.openwebrx.OpenWebRxAircraft
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OpenWebRxAircraftTest {
    @Test fun keepsRecentAircraftBeyondTheFirstTwoHundredEntries() {
        val entries = org.json.JSONArray()
        repeat(250) { i -> entries.put(JSONObject().put("icao", "%06X".format(i)).put("timestamp", i + 1)) }
        val reports = OpenWebRxAircraft.parse(JSONObject().put("mode", "ADSB-LIST").put("aircraft", entries))
        assertEquals(200, reports.size)
        assertTrue(reports.any { it.icao == "0000F9" })
        assertFalse(reports.any { it.icao == "000000" })
    }
    @Test fun parsesAircraftSnapshotAndPreservesZeroValuesAndSquawk() {
        val reports = OpenWebRxAircraft.parse(JSONObject("""{"mode":"ADSB-LIST","aircraft":[
            {"icao":"abc123","flight":" TEST123 ","ccode":"au","altitude":0,"speed":0,"course":360,"squawk":"0042","timestamp":1700000000123},
            {"icao":"DEF456","altitude":null,"speed":null,"course":null},
            {"icao":"abc123"},{"icao":"invalid"},null]}"""))
        assertEquals(2, reports.size)
        val first = reports.first()
        assertEquals("ABC123", first.icao)
        assertEquals("TEST123", first.flight)
        assertEquals("AU", first.countryCode)
        assertEquals(0, first.altitudeFeet)
        assertEquals(0, first.speedKnots)
        assertEquals(0, first.courseDegrees)
        assertEquals("0042", first.squawk)
        assertEquals(1700000000123L, first.timestampMillis)
        assertNull(reports.last().altitudeFeet)
        assertNull(reports.last().timestampMillis)
        assertTrue(OpenWebRxAircraft.parse(JSONObject("""{"mode":"ADSB-LIST","aircraft":[]}""")).isEmpty())
    }

    @Test fun mergesIndividualReportsWithoutLosingIdentityOrAcceptingOlderData() {
        val old = OpenWebRxAircraft.parse(JSONObject("""{"mode":"ADSB","icao":"ABC123","flight":"TEST123","altitude":1000,"timestamp":2000}"""))
        val update = OpenWebRxAircraft.parse(JSONObject("""{"mode":"ADSB","icao":"ABC123","speed":123,"timestamp":3000}"""))
        val merged = OpenWebRxAircraft.merge(old, update)
        assertEquals(1, merged.size)
        assertEquals("TEST123", merged.single().flight)
        assertEquals(1000, merged.single().altitudeFeet)
        assertEquals(123, merged.single().speedKnots)
        assertEquals(merged, OpenWebRxAircraft.merge(merged, old))
    }
}
