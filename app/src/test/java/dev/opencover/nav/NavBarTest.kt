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

    // The third button sits after Back, the same width: x 302-404.
    @Test fun recentsButtonAfterBack() = assertEquals(NavButton.RECENTS, buttonAt(353f, 748))

    // Gestures on the bar: a short swipe up is Home, swipe up and hold is Recents, a tap or sideways move is nothing.
    @Test fun swipeUpHomeHoldRecents() {
        assertEquals(NavButton.HOME, barGesture(dy = -60f, dx = 5f, heldMs = 0))
        assertEquals(NavButton.RECENTS, barGesture(dy = -60f, dx = 5f, heldMs = 400))
        assertEquals(null, barGesture(dy = -10f, dx = 0f, heldMs = 0))
        assertEquals(null, barGesture(dy = -60f, dx = 120f, heldMs = 0))
    }

    // From a screen edge, a swipe inward far enough is Back; a short one is nothing.
    @Test fun edgeSwipeInIsBack() {
        assertEquals(true, edgeBack(inward = 80f))
        assertEquals(false, edgeBack(inward = 20f))
    }
}
