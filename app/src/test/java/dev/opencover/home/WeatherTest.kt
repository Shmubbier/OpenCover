package dev.opencover.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    @Test fun skyCodes() {
        assertEquals(Sky.CLEAR, skyFor(0))
        assertEquals(Sky.PARTLY, skyFor(2))
        assertEquals(Sky.CLOUDY, skyFor(3))
        assertEquals(Sky.FOG, skyFor(45))
        assertEquals(Sky.DRIZZLE, skyFor(53))
        assertEquals(Sky.RAIN, skyFor(63))
        assertEquals(Sky.RAIN, skyFor(81))
        assertEquals(Sky.SNOW, skyFor(73))
        assertEquals(Sky.SNOW, skyFor(86))
        assertEquals(Sky.THUNDER, skyFor(95))
        assertEquals(Sky.CLOUDY, skyFor(42)) // unknown codes fall back
    }
    @Test fun parsesOpenMeteo() {
        val json = """{"latitude":14.6,"current":{"time":"2026-10-13T09:00","interval":900,"temperature_2m":28.4,"weather_code":3}}"""
        assertEquals(Reading(28.4, 3, 1000L), parseReading(json, 1000L))
    }
    @Test fun parseRejectsGarbage() {
        assertNull(parseReading("not json", 0))
        assertNull(parseReading("""{"error":true,"reason":"x"}""", 0))
    }
    @Test fun staleAfterTwoHours() {
        val r = Reading(20.0, 0, 0)
        assertFalse(stale(r, 2 * 3600_000L))
        assertTrue(stale(r, 2 * 3600_000L + 1))
    }
    @Test fun dueEveryHalfHour() {
        assertTrue(due(null, 0))
        assertFalse(due(Reading(20.0, 0, 0), 30 * 60_000L - 1))
        assertTrue(due(Reading(20.0, 0, 0), 30 * 60_000L))
    }
    @Test fun degreesRounded() {
        assertEquals("28°", degrees(28.4, fahrenheit = false))
        assertEquals("83°", degrees(28.4, fahrenheit = true))
        assertEquals("-3°", degrees(-2.6, fahrenheit = false))
    }
}
