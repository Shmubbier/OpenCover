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

    // Recents | Home | Back, all left of the camera lip (the cutout starts at x 379 of 748); elsewhere nothing.
    @Test fun touchHitsTheButtonUnderIt() {
        assertEquals(NavButton.RECENTS, buttonAt(61f, 748))
        assertEquals(NavButton.HOME, buttonAt(185f, 748))
        assertEquals(NavButton.BACK, buttonAt(309f, 748))
        assertEquals(null, buttonAt(500f, 748)) // beside the lip
        assertEquals(NavButton.HOME, buttonAt(92f, 374)) // a half-width bar scales the same spots
    }

    @Test fun buttonsStayLeftOfTheLip() = assertEquals(null, buttonAt(379f, 748))

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
