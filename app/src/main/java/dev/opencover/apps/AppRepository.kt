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

    fun icon(pkg: String, activity: String, user: Long): ImageBitmap? {
        val key = appKey(pkg, activity, user)
        icons[key]?.let { return it }
        val handle = users.getUserForSerialNumber(user) ?: return null
        val info = launcherApps.getActivityList(pkg, handle).find { it.componentName.className == activity } ?: return null
        return info.getBadgedIcon(0).toImageBitmap(iconPx).also { icons[key] = it }
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
