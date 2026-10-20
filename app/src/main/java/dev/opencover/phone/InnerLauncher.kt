package dev.opencover.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import dev.opencover.quick.ShizukuShell

/**
 * The phone's default home, which shows on the inner screen (and so in the mirror). Samsung's cover home is separate
 * and unaffected. Lawnchair is recommended: with Lawnicons and its icon shapes it looks like OpenCover's Home.
 */
object InnerLauncher {
    /** Lawnchair from Play and from GitHub/F-Droid. */
    val LAWNCHAIR = setOf("app.lawnchair.play", "app.lawnchair")
    const val LAWNCHAIR_PLAY = "market://details?id=app.lawnchair.play"
    const val LAWNCHAIR_GITHUB = "https://github.com/LawnchairLauncher/lawnchair/releases"

    // Not real choices: Android's own picker and Settings' stand-in used while the phone starts.
    private val NOT_LAUNCHERS = setOf("android", "com.android.settings")

    data class Home(val component: ComponentName, val label: String)

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    fun installed(ctx: Context): List<Home> {
        val pm = ctx.packageManager
        return pm.queryIntentActivities(homeIntent(), 0)
            .filter { it.activityInfo.packageName !in NOT_LAUNCHERS }
            .map { Home(ComponentName(it.activityInfo.packageName, it.activityInfo.name), it.loadLabel(pm).toString()) }
            .distinctBy { it.component.packageName }
            .sortedBy { it.label.lowercase() }
    }

    /** The default home's package; null when none is chosen. */
    fun current(ctx: Context): String? =
        ctx.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName?.takeIf { it !in NOT_LAUNCHERS }

    /** Makes [home] the phone's default home. Blocking (Shizuku). */
    fun choose(home: ComponentName): Boolean =
        ShizukuShell.run("cmd", "package", "set-home-activity", "--user", "0", home.flattenToString())
}
