package dev.opencover.phone

import dev.opencover.quick.ShizukuShell

/**
 * Background restriction per app, through Shizuku: Android's "Restricted" battery setting (no background work or
 * background starts) plus the restricted standby bucket. Messages for a restricted app can arrive later.
 */
object AppSleep {
    /** Whether [pkg] may run in the background. Blocking. */
    fun restricted(pkg: String): Boolean =
        ShizukuShell.output("cmd", "appops", "get", pkg, "RUN_ANY_IN_BACKGROUND")?.contains("RUN_ANY_IN_BACKGROUND: ignore") == true

    fun set(pkg: String, restrict: Boolean): Boolean = ShizukuShell.run(
        "sh", "-c",
        "cmd appops set $pkg RUN_ANY_IN_BACKGROUND ${if (restrict) "ignore" else "allow"}; " +
            "am set-standby-bucket $pkg ${if (restrict) "restricted" else "active"}",
    )
}
