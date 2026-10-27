package com.example.sdr

import org.junit.Assert.*
import org.junit.Test

class FtxContactsTest {
    @Test fun coordinatesUseHemisphereAndStableDecimalFormatting() {
        assertEquals("55.7500° N", coordinateLabel(55.75, true))
        assertEquals("33.9000° S", coordinateLabel(-33.9, true))
        assertEquals("74.0000° W", coordinateLabel(-74.0, false))
        assertEquals("0.0000° E", coordinateLabel(0.0, false))
    }
    private val countries = CallsignCountries("""
Belgium: 14: 27: EU: 50: -4: -1: ON:
    ON,OO,OP;
United States: 5: 8: NA: 37: 95: 5: K:
    K,N,W,AA,AB;
Hawaii: 31: 61: OC: 21: 157: 10: KH6:
    KH6,=K1SPECIAL;
Canary Islands: 33: 36: AF: 28: 15: 0: EA8:
    EA8;
England: 14: 27: EU: 52: 0: 0: G:
    G;
Scotland: 14: 27: EU: 56: 4: 0: GM:
    GM,=GB0WRH;
""".trimIndent())

    @Test fun countriesUseLongestPrefixAndExactExceptions() {
        assertEquals("Hawaii", countries.country("KH6ABC"))
        assertEquals("Hawaii", countries.country("K1SPECIAL"))
        assertEquals("Scotland", countries.country("GB0WRH"))
        assertEquals("Belgium", countries.country("ON4CJU/P"))
        assertEquals("Canary Islands", countries.country("K1ABC/EA8"))
        assertNull(countries.country("K1ABC/MM"))
        assertNull(countries.country("<K1ABC>"))
    }
    @Test fun maidenheadUsesCellCenterAndRejectsReports() {
        assertEquals(RadioPoint(50.5, 5.0), maidenheadPoint("JO20"))
        assertEquals(4.0416667, maidenheadPoint("jo20aa")!!.lon, .00001)
        assertNull(maidenheadPoint("-11"))
        assertNull(maidenheadPoint("JO99ZZ"))
        assertEquals(0.0, radioDistanceKm(RadioPoint(0.0, 0.0), RadioPoint(0.0, 0.0)), .001)
        assertEquals(20015.1, radioDistanceKm(RadioPoint(0.0, 0.0), RadioPoint(0.0, 180.0)), .1)
    }
    @Test fun senderAndLearnedGridSurviveReportsAndRr73() {
        val resolver = FtxContacts(countries)
        val rows = resolver.rows(listOf(
            "12:00:00  1400 Hz  CQ DX ON4CJU JO20",
            "12:00:15  1400 Hz  K1ABC ON4CJU -11",
            "12:00:30  1400 Hz  K1ABC ON4CJU RR73"
        ), maidenheadPoint("FN42"))
        assertTrue(rows.all { it.call == "ON4CJU" && it.country == "Belgium" && it.grid == "JO20" })
        assertTrue(rows.all { it.distanceKm!! in 5000.0..6000.0 })
        assertEquals("RR73", rows.last().message.substringAfterLast(' '))
    }
    @Test fun unknownLocationsAndFreeTextDoNotInventDistances() {
        val resolver = FtxContacts(countries)
        val rows = resolver.rows(listOf(
            "12:00:00  1500 Hz  K1ABC ON4CJU RR73",
            "12:00:15  1000 Hz  HELLO WORLD",
            "12:00:30  1000 Hz  CQ K1ABC FN42"
        ), null)
        assertNull(rows[0].grid)
        assertNull(rows[1].call)
        assertTrue(rows.all { it.distanceKm == null })
    }
    @Test fun unresolvedAddresseeDoesNotHideKnownTransmitter() {
        val rows = FtxContacts(countries).rows(listOf("12:00:00  1000 Hz  <...> ON4CJU"), null)
        assertEquals("ON4CJU", rows.single().call)
        assertEquals("Belgium", rows.single().country)
    }
}
