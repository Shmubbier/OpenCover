package dev.opencover.ui

const val NOTIFICATIONS_PAGE = 0
const val HOME_PAGE = 1
const val FIRST_APPS_PAGE = 2

fun pagerPageCount(appsPages: Int) = FIRST_APPS_PAGE + appsPages

/** Layout page index for a pager page, or null for Notifications and Home. */
fun layoutPageFor(pagerPage: Int): Int? = (pagerPage - FIRST_APPS_PAGE).takeIf { it >= 0 }

/** After removing the apps page you're on: the page before it, never past Home. */
fun pageAfterRemoving(pagerPage: Int) = maxOf(HOME_PAGE, pagerPage - 1)

enum class Edge { TOP, BOTTOM }

enum class Panel(val edge: Edge) { MEDIA(Edge.BOTTOM), QUICK_SETTINGS(Edge.TOP), DRAWER(Edge.BOTTOM) }

/** Which panel a vertical swipe opens on [pagerPage], or null. */
fun panelFor(pagerPage: Int, up: Boolean): Panel? = when {
    pagerPage == HOME_PAGE -> if (up) Panel.MEDIA else Panel.QUICK_SETTINGS
    pagerPage >= FIRST_APPS_PAGE && up -> Panel.DRAWER
    else -> null
}
