package dev.opencover.edge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EdgeColorsTest {
    @Test fun hexParses() {
        assertEquals(0xFF1A2B3C.toInt(), parseHex("#1A2B3C"))
        assertEquals(0xFF1A2B3C.toInt(), parseHex("1a2b3c"))
        assertNull(parseHex("#12345"))
        assertNull(parseHex("#GGGGGG"))
    }

    @Test fun hexFormats() = assertEquals("#1A2B3C", formatHex(0xFF1A2B3C.toInt()))

    // Per-app colours are saved as "pkg=RRGGBB;..."; broken entries are dropped.
    @Test fun perAppRoundTripAndBadEntries() {
        val m = mapOf("com.discord" to 0xFF5865F2.toInt(), "com.whatsapp" to 0xFF25D366.toInt())
        assertEquals(m, parsePerApp(formatPerApp(m)))
        assertEquals(mapOf("a" to 0xFF00FF00.toInt()), parsePerApp("a=00FF00;b=zz;=112233;junk"))
        assertEquals(emptyMap<String, Int>(), parsePerApp(null))
    }

    // The app's own colour wins, then the custom glow colour, then the colour taken from its icon.
    @Test fun colourPriority() {
        val per = mapOf("a" to 1)
        assertEquals(1, glowColor("a", per, custom = 2, iconColor = { 3 }))
        assertEquals(2, glowColor("b", per, custom = 2, iconColor = { 3 }))
        assertEquals(3, glowColor("b", per, custom = null, iconColor = { 3 }))
    }

    // The wheel: angle is hue (0 at the right, counter-clockwise like the colour circle), distance from the centre is saturation.
    @Test fun wheelPositionToHueAndSaturation() {
        assertEquals(0f to 1f, wheelHueSat(dx = 100f, dy = 0f, radius = 100f))
        assertEquals(90f to 0.5f, wheelHueSat(dx = 0f, dy = -50f, radius = 100f))
        assertEquals(180f to 1f, wheelHueSat(dx = -200f, dy = 0f, radius = 100f)) // outside: clamped to the rim
    }
}
