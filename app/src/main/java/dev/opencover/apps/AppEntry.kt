package dev.opencover.apps

import dev.opencover.layout.appKey

/** One launchable activity. [user] is the profile's serial number; [lastUsed] is epoch ms (0 = unknown). */
data class AppEntry(val label: String, val pkg: String, val activity: String, val user: Long, val lastUsed: Long) {
    val key get() = appKey(pkg, activity, user)
}

enum class Sort { AZ, RECENT }

fun filterApps(apps: List<AppEntry>, query: String, sort: Sort): List<AppEntry> {
    val q = query.trim()
    val hits = apps.filter { it.label.contains(q, ignoreCase = true) }
    return when (sort) {
        Sort.AZ -> hits.sortedBy { it.label.lowercase() }
        Sort.RECENT -> hits.sortedByDescending { it.lastUsed }
    }
}

/** First index of each starting letter in an A–Z sorted list; anything not A–Z groups under '#'. */
fun letterIndex(apps: List<AppEntry>): Map<Char, Int> {
    val index = linkedMapOf<Char, Int>()
    apps.forEachIndexed { i, a ->
        val c = a.label.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
        index.putIfAbsent(c, i)
    }
    return index
}
