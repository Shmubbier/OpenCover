package dev.opencover.home

import dev.opencover.lock.Spot
import dev.opencover.lock.formatLayout
import dev.opencover.lock.parseSpots
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeLayoutTest {
    @Test fun savedItemsKeepTheirSpotsAndMissingOnesTakeDefaults() {
        val m = parseSpots("CLOCK:0.2,0.3,0,1.5;BOGUS:0.1,0.1,1", HomeItem.entries, DEFAULT_HOME)
        assertEquals(Spot(0.2f, 0.3f, false, 1.5f), m[HomeItem.CLOCK])
        assertEquals(DEFAULT_HOME[HomeItem.APPS], m[HomeItem.APPS])
        assertEquals(m, parseSpots(formatLayout(m), HomeItem.entries, DEFAULT_HOME))
    }
}
