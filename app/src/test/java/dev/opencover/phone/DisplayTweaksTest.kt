package dev.opencover.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayTweaksTest {
    @Test fun densityPrefersTheOverride() {
        assertEquals(280, parseDensity("Physical density: 340\nOverride density: 280"))
        assertEquals(340, parseDensity("Physical density: 340"))
        assertNull(parseDensity("nonsense"))
    }

    @Test fun innerScreenScriptChecksTheSize() {
        assertEquals("wm size 1080x1600; wm density 400; ", innerScreenScript("1080x1600", 400))
        assertEquals("wm size 1080x1600; wm density 640; ", innerScreenScript("1080x1600", 9000)) // clamped
        assertNull(innerScreenScript("1080x1600; reboot", 400)) // never more than a size goes into the shell
        assertNull(innerScreenScript("big", 400))
    }
}
