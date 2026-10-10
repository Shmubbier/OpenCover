package dev.opencover.apps

import dev.opencover.layout.appKey

/** One launchable activity. [user] is the profile's serial number; [lastUsed] is epoch ms (0 = unknown). */
data class AppEntry(val label: String, val pkg: String, val activity: String, val user: Long, val lastUsed: Long) {
    val key get() = appKey(pkg, activity, user)
}
