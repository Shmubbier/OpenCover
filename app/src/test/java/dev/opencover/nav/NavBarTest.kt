package dev.opencover.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBarTest {
    @Test fun showsOnlyOverSamsungsBarWhenUnlocked() {
        assertTrue(navBarShown(enabled = true, samsungBar = true, locked = false))
        assertFalse(navBarShown(enabled = true, samsungBar = false, locked = false)) // full-screen app: no bar to cover
        assertFalse(navBarShown(enabled = true, samsungBar = true, locked = true))
        assertFalse(navBarShown(enabled = false, samsungBar = true, locked = false))
    }

    // Samsung's buttons: Home x 64-166, Back x 183-285 on the 748 px wide bar; elsewhere nothing.
    @Test fun touchHitsTheButtonUnderIt() {
        assertEquals(NavButton.HOME, buttonAt(115f, 748))
        assertEquals(NavButton.BACK, buttonAt(234f, 748))
        assertEquals(null, buttonAt(500f, 748))
        assertEquals(NavButton.HOME, buttonAt(57f, 374)) // a half-width bar scales the same spots
    }
}
