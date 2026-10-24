package dev.opencover.apps

import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
    private var style: Any? = null

    /**
     * An app's icon, in the chosen icon pack, shape and theming. Themed: a one-colour glyph (a monochrome pack's
     * icon, else the app's own themed-icon layer) tinted from the Home wallpaper; apps with neither keep their icon.
     */
    fun icon(pkg: String, activity: String, user: Long): ImageBitmap? {
        // Reading the settings here (Compose state) redraws every icon when one of them changes.
        val prefs = dev.opencover.home.HomePrefs
        val want = prefs.iconPack
        if (want != packId) { packId = want; pack = want?.let { IconPack.load(ctx, it) }; icons.clear() }
        val still = dev.opencover.home.Wallpaper.homeStill
        val now = listOf(prefs.iconShape, prefs.themedIcons, still)
        if (now != style) { style = now; icons.clear() }
        val key = appKey(pkg, activity, user)
        icons[key]?.let { return it }
        val handle = users.getUserForSerialNumber(user) ?: return null
        val info = launcherApps.getActivityList(pkg, handle).find { it.componentName.className == activity } ?: return null
        val own = info.getIcon(0)
        val packed = pack?.icon(pkg, activity)
        val glyph = if (!prefs.themedIcons) null
            else packed?.takeIf { pack?.monochrome == true } ?: own.takeIf { it is android.graphics.drawable.AdaptiveIconDrawable && it.monochrome != null }
        val (fg, bg) = themedColors(still?.let { wallpaperAverage(it) } ?: 0xFF34245A.toInt())
        // Work apps keep the system's badged icon (it isn't adaptive, so it isn't reshaped).
        val plain = packed ?: if (handle == android.os.Process.myUserHandle()) own else info.getBadgedIcon(0)
        return renderIcon(plain, glyph, prefs.iconShape, glyph != null, fg, bg, iconPx).asImageBitmap().also { icons[key] = it }
    }

    private fun wallpaperAverage(still: ImageBitmap): Int {
        val small = Bitmap.createScaledBitmap(still.asAndroidBitmap(), 24, 24, true)
        return IntArray(24 * 24).also { small.getPixels(it, 0, 24, 0, 0, 24, 24) }.let { dev.opencover.edge.averageColor(it, 0xFF34245A.toInt()) }
    }

    fun forget(pkg: String) {
        icons.keys.removeAll { it.contains("/$pkg/") }
    }

    fun register(cb: LauncherApps.Callback) = launcherApps.registerCallback(cb, Handler(Looper.getMainLooper()))
    fun unregister(cb: LauncherApps.Callback) = launcherApps.unregisterCallback(cb)
}
