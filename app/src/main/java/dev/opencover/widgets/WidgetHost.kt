package dev.opencover.widgets

import android.app.Activity
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.util.SizeF
import android.widget.Toast
import dev.opencover.apps.AppLauncher

/**
 * Adds widgets with the bind -> configure -> place flow. Every system screen it opens
 * goes to the cover display. Any cancel or failure frees the allocated widget id.
 */
class WidgetHost(
    private val activity: Activity,
    private val onAdded: (widgetId: Int, info: AppWidgetProviderInfo, page: Int) -> Unit,
) {
    private val manager = AppWidgetManager.getInstance(activity)
    private val host = AppWidgetHost(activity, HOST_ID)
    private var pending: Pending? = null

    private class Pending(val id: Int, val info: AppWidgetProviderInfo, val page: Int)

    fun providers(): List<AppWidgetProviderInfo> =
        activity.getSystemService(LauncherApps::class.java).profiles
            .flatMap { manager.getInstalledProvidersForProfile(it) }

    fun find(flatProvider: String) = providers().find { it.provider.flattenToString() == flatProvider }
    fun exists(widgetId: Int) = manager.getAppWidgetInfo(widgetId) != null
    fun delete(widgetId: Int) = host.deleteAppWidgetId(widgetId)
    fun startListening() = host.startListening()
    fun stopListening() = host.stopListening()

    fun view(widgetId: Int): AppWidgetHostView? =
        manager.getAppWidgetInfo(widgetId)?.let { host.createView(activity, widgetId, it) }

    fun resize(view: AppWidgetHostView, widthDp: Float, heightDp: Float) =
        view.updateAppWidgetSize(Bundle(), listOf(SizeF(widthDp, heightDp)))

    fun begin(info: AppWidgetProviderInfo, page: Int) {
        cancel()
        val id = host.allocateAppWidgetId()
        pending = Pending(id, info, page)
        if (manager.bindAppWidgetIdIfAllowed(id, info.profile, info.provider, null)) {
            configure()
        } else safely {
            val bind = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, info.profile)
            activity.startActivityForResult(bind, REQ_BIND, AppLauncher.options())
        }
    }

    /** Forward Activity.onActivityResult here. Returns true if the result was ours. */
    fun onResult(requestCode: Int, resultCode: Int): Boolean {
        if (requestCode != REQ_BIND && requestCode != REQ_CONFIGURE) return false
        when {
            resultCode != Activity.RESULT_OK -> cancel()
            requestCode == REQ_BIND -> configure()
            else -> complete()
        }
        return true
    }

    fun cancel() {
        pending?.let { host.deleteAppWidgetId(it.id) }
        pending = null
    }

    private fun configure() {
        val p = pending ?: return
        val optional = p.info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
        if (p.info.configure == null || optional) complete()
        else safely {
            host.startAppWidgetConfigureActivityForResult(activity, p.id, 0, REQ_CONFIGURE, AppLauncher.options())
        }
    }

    private fun complete() {
        val p = pending ?: return
        pending = null
        onAdded(p.id, p.info, p.page)
    }

    private fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Toast.makeText(activity, "Couldn't add widget", Toast.LENGTH_SHORT).show()
            cancel()
        }
    }

    companion object {
        const val HOST_ID = 1024
        private const val REQ_BIND = 11
        private const val REQ_CONFIGURE = 12
    }
}
