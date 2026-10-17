package dev.opencover.apps

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserManager
import android.widget.Toast
import dev.opencover.COVER_DISPLAY
import dev.opencover.watcher.CoverAccessibility

object AppLauncher {
    fun options(): Bundle =
        ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY).toBundle()

    /** Starts one of our own screens (or a Settings page) on the cover display. */
    fun start(ctx: Context, intent: Intent) {
        // The accessibility service is exempt from the background-start block (the mirror view opens from a service).
        (CoverAccessibility.instance ?: ctx).startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options())
    }

    /** Launches an installed app on the cover display as the given user (serial number). False if it failed. */
    fun launch(ctx: Context, pkg: String, activity: String, user: Long): Boolean {
        val handle = ctx.getSystemService(UserManager::class.java).getUserForSerialNumber(user)
        try {
            requireNotNull(handle) { "user $user gone" }
            ctx.getSystemService(LauncherApps::class.java)
                .startMainActivity(ComponentName(pkg, activity), handle, null, options())
            return true
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't open $pkg", Toast.LENGTH_SHORT).show()
            return false
        }
    }
}
