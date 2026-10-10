package dev.opencover.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellPagesTest {
    @Test fun pageCountAddsNotificationsAndHome() = assertEquals(5, pagerPageCount(3))
    @Test fun appsPagesMapToLayoutPages() = assertEquals(0, layoutPageFor(FIRST_APPS_PAGE))
    @Test fun homeAndNotificationsAreNotLayoutPages() {
        assertNull(layoutPageFor(HOME_PAGE))
        assertNull(layoutPageFor(NOTIFICATIONS_PAGE))
    }
    @Test fun removingLaterAppsPageGoesBackOne() = assertEquals(3, pageAfterRemoving(4))
    @Test fun removingFirstAppsPageLandsOnHome() = assertEquals(HOME_PAGE, pageAfterRemoving(FIRST_APPS_PAGE))
    @Test fun homeSwipesOpenMediaAndQuickSettings() {
        assertEquals(Panel.MEDIA, panelFor(HOME_PAGE, up = true))
        assertEquals(Panel.QUICK_SETTINGS, panelFor(HOME_PAGE, up = false))
    }
    @Test fun appsPageSwipeUpOpensDrawerOnly() {
        assertEquals(Panel.DRAWER, panelFor(FIRST_APPS_PAGE + 1, up = true))
        assertNull(panelFor(FIRST_APPS_PAGE, up = false))
    }
    @Test fun notificationsPageHasNoPanels() = assertNull(panelFor(NOTIFICATIONS_PAGE, up = true))
}
