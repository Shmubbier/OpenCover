package dev.opencover.apps

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import dev.opencover.layout.appKey

/** Installed launchable apps across all profiles, with last-used times and cached icons. */
class AppRepository(private val ctx: Context) {
    private val launcherApps = ctx.getSystemService(LauncherApps::class.java)
    private val users = ctx.getSystemService(UserManager::class.java)
    private val iconPx = (48 * ctx.resources.displayMetrics.density).toInt()
    private val icons = HashMap<String, ImageBitmap>()

    fun serial(user: UserHandle) = users.getSerialNumberForUser(user)

    /** True if icons for this profile may be pruned: it's gone, or it's unlocked and running. */
    fun userReady(serial: Long): Boolean {
        val handle = users.getUserForSerialNumber(serial) ?: return true
        return users.isUserUnlocked(handle) && !users.isQuietModeEnabled(handle)
    }

    fun load(): List<AppEntry> {
        val used = lastUsedByPackage()
        return launcherApps.profiles.flatMap { u ->
            launcherApps.getActivityList(null, u).map { a ->
                val cn = a.componentName
                AppEntry(a.label.toString(), cn.packageName, cn.className, serial(u), used[cn.packageName] ?: 0L)
            }
        }.filter { it.pkg != ctx.packageName }
    }

    private fun lastUsedByPackage(): Map<String, Long> {
        val now = System.currentTimeMillis()
        return ctx.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(now - 30L * 24 * 3600 * 1000, now)
            .mapValues { it.value.lastTimeUsed }
    }

    private var pack: IconPack? = null
    private var packId: String? = null

    /** An app's icon: from the chosen icon pack if it has one for the app, else the app's own (badged for work apps). */
    fun icon(pkg: String, activity: String, user: Long): ImageBitmap? {
        // Reading the setting here (Compose state) redraws every icon when another pack is picked.
        val want = dev.opencover.home.HomePrefs.iconPack
        if (want != packId) { packId = want; pack = want?.let { IconPack.load(ctx, it) }; icons.clear() }
        val key = appKey(pkg, activity, user)
        icons[key]?.let { return it }
        val handle = users.getUserForSerialNumber(user) ?: return null
        val info = launcherApps.getActivityList(pkg, handle).find { it.componentName.className == activity } ?: return null
        val drawable = pack?.icon(pkg, activity) ?: info.getBadgedIcon(0)
        return drawable.toImageBitmap(iconPx).also { icons[key] = it }
    }

    fun forget(pkg: String) {
        icons.keys.removeAll { it.contains("/$pkg/") }
    }

    fun register(cb: LauncherApps.Callback) = launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
    fun unregister(cb: LauncherApps.Callback) = launcherApps.unregisterCallback(cb)
}

private fun Drawable.toImageBitmap(px: Int): ImageBitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, px, px)
    draw(Canvas(bmp))
    return bmp.asImageBitmap()
}
