package dev.opencover.apps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.LauncherApps
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Samsung only lets apps on Good Lock MultiStar's cover list open on the cover (without Shizuku, anything else gets
 * "Open phone to continue"). The system reads that list in plain text from slot 26 of the secure setting
 * `multistar_setting_repository` ("pkg,userId;..."), which OpenCover can write. Every launchable app is added, at
 * start and as apps are installed.
 */
object CoverAllowList {
    private const val KEY = "multistar_setting_repository"

    private val handler = Handler(Looper.getMainLooper())

    /**
     * Fills the list now, or, without the secure settings permission yet (it's granted over adb, which no callback
     * reports), checks every 5 s and fills it the moment the permission arrives.
     */
    fun start(ctx: Context) {
        val app = ctx.applicationContext
        if (granted(app)) {
            Thread { sync(app) }.start()
        } else handler.postDelayed({ start(app) }, 5_000)
    }

    private fun granted(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** Adds every launchable app, for every profile. */
    fun sync(ctx: Context) {
        val launcher = ctx.getSystemService(LauncherApps::class.java)
        val entries = ctx.getSystemService(UserManager::class.java).userProfiles.flatMap { user ->
            launcher.getActivityList(null, user).map { "${it.componentName.packageName},${userId(user)}" }
        }
        add(ctx, entries)
    }

    /**
     * Keeps the list up to date while OpenCover runs: new apps are added as they install, and MultiStar rewriting the
     * setting from its own copy (it does at boot, after OpenCover has started) puts them back a moment later.
     */
    fun watch(ctx: Context) {
        val app = ctx.applicationContext
        val again = Runnable { if (granted(app)) Thread { sync(app) }.start() }
        app.contentResolver.registerContentObserver(Settings.Secure.getUriFor(KEY), false, object : ContentObserver(handler) {
            // After a short wait, so MultiStar's own writes are done; a rewrite that leaves nothing missing writes nothing.
            override fun onChange(selfChange: Boolean) { handler.removeCallbacks(again); handler.postDelayed(again, 2_000) }
        })
        app.getSystemService(LauncherApps::class.java).registerCallback(object : LauncherApps.Callback() {
            override fun onPackageAdded(pkg: String, user: UserHandle) = add(app, listOf("$pkg,${userId(user)}"))
            override fun onPackageRemoved(pkg: String, user: UserHandle) {}
            override fun onPackageChanged(pkg: String, user: UserHandle) {}
            override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) {}
            override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) {}
        }, Handler(Looper.getMainLooper()))
    }

    private fun add(ctx: Context, entries: List<String>) {
        val now = Settings.Secure.getString(ctx.contentResolver, KEY) ?: return
        val next = withCoverApps(now, entries) ?: return
        runCatching { Settings.Secure.putString(ctx.contentResolver, KEY, next) }
            .onSuccess { Log.i("CoverAllowList", "added to the cover list") }
            .onFailure { Log.w("CoverAllowList", "couldn't write the cover list", it) }
    }

    // UserHandle's id is hidden API; its toString is "UserHandle{<id>}".
    private fun userId(user: UserHandle) = user.toString().substringAfter('{').substringBefore('}')
}

/**
 * [repo] with [entries] ("pkg,userId") added to its cover-app slot; null when there is nothing to add or the setting
 * isn't laid out as expected (MultiStar never set up, or a format this doesn't know), so it's left alone.
 */
fun withCoverApps(repo: String, entries: List<String>): String? {
    val slots = repo.split('/').toMutableList()
    if (slots.size < 26) return null
    val list = slots[25].split(';').filter { it.isNotEmpty() && it != "0" }
    if (list.any { !it.contains(',') }) return null
    val missing = entries.distinct().filter { it !in list }
    if (missing.isEmpty()) return null
    slots[25] = (list + missing).joinToString(";")
    return slots.joinToString("/")
}
