package dev.opencover.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoverAllowListTest {
    private fun repo(slot: String) = (List(25) { "0" } + slot + List(4) { "0" }).joinToString("/")

    @Test fun addsOnlyMissingAppsToSlot26() {
        val out = withCoverApps(repo("a,0;b,0"), listOf("b,0", "c,0", "c,0"))!!
        assertEquals("a,0;b,0;c,0", out.split('/')[25])
        assertEquals(30, out.split('/').size)
    }

    @Test fun emptySlotIsFilled() = assertEquals("a,0", withCoverApps(repo("0"), listOf("a,0"))!!.split('/')[25])

    @Test fun leavesUnknownLayoutsAndNoChangeAlone() {
        assertNull(withCoverApps("0/0/0", listOf("a,0")))
        assertNull(withCoverApps(repo("a,0"), listOf("a,0")))
        assertNull(withCoverApps(repo("weird"), listOf("a,0")))
    }
}
