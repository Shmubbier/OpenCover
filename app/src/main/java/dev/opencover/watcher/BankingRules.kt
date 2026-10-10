package dev.opencover.watcher

import dev.opencover.SAMSUNG_COVER_HOME

/** The secure-settings list of enabled accessibility services, with [me] added or removed. */
fun withService(enabled: String?, me: String, on: Boolean): String {
    val others = enabled.orEmpty().split(':').filter { it.isNotBlank() && it != me }
    return (if (on) others + me else others).joinToString(":")
}

/** While paused for [pausedPkg]: has the user left it for Home, OpenCover, or another real app? */
fun shouldResume(
    pausedPkg: String,
    resumedPkg: String,
    resumedClass: String,
    ownPkg: String,
    banking: Set<String>,
    launchable: (String) -> Boolean,
): Boolean = when {
    resumedPkg == pausedPkg || resumedPkg in banking -> false
    resumedClass == SAMSUNG_COVER_HOME || resumedPkg == ownPkg -> true
    else -> launchable(resumedPkg) // helper screens (Play services, dialogs) aren't launchable
}

/** Samsung's cover pull-down panel ("SubScreenQuickPanel") as measured: type -1, taller than half the screen. */
fun isSamsungPanel(type: Int, heightPx: Int) = type == -1 && heightPx > 720 / 2
