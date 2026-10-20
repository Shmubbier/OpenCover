package dev.opencover.mirror

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import dev.opencover.apps.AppLauncher
import dev.opencover.phone.HeatGuard
import android.view.Display
import android.view.Surface
import android.widget.Toast

/**
 * Foreground service owning the MediaProjection and VirtualDisplay.
 * Started by ConsentActivity after the user accepts the system consent dialog.
 */
class MirrorService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    var captureWidth = 0; private set
    var captureHeight = 0; private set
    private var captureDpi = 0
    private val handler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stop(); return START_NOT_STICKY }
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra(KEY_RESULT_CODE, 0)
        val data: Intent = intent.getParcelableExtra(KEY_DATA, Intent::class.java) ?: run { stopSelf(); return START_NOT_STICKY }

        ensureChannel()
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, MirrorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Mirroring inner screen")
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        if (HeatGuard.hot()) { // checked before anything changes: stopping mid-start would leave the phone forced open
            Toast.makeText(this, "Phone is too hot to mirror. Let it cool.", Toast.LENGTH_LONG).show()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val dm = getSystemService(DisplayManager::class.java)
        val main = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: run { stopSelf(); return START_NOT_STICKY }
        readMetrics(main)
        instance = this
        HeatGuard.evaluate() // mirroring counts as a heat source

        // Folded, display 0 is off and renders nothing, so MediaProjection would capture the cover
        // (infinite mirror). Z Cover Power forces the unfolded device state first; so do we.
        val ready = Runnable { acquireProjection(resultCode, data) }
        val listener = object : DisplayManager.DisplayListener {
            var fired = false
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY || fired) return
                fired = true
                dm.unregisterDisplayListener(this)
                handler.removeCallbacks(ready)
                handler.postDelayed(ready, 150)
            }
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
        }
        dm.registerDisplayListener(listener, handler)
        // ponytail: 1.5 s fallback copied from Z Cover Power in case display 0 never reports a change.
        handler.postDelayed({ if (!listener.fired) { listener.fired = true; dm.unregisterDisplayListener(listener); ready.run() } }, 1500)
        val app = applicationContext
        MirrorEnv.later {
            Log.d(TAG, "mirror env -> ${MirrorEnv.enter(app)?.trim() ?: "skipped"}")
            // Putting the phone's home on the inner screen also brings Samsung's cover home up over the mirror.
            handler.post { if (instance === this && mirrorShown) showMirror(clear = false) }
        }
        return START_NOT_STICKY
    }

    private fun readMetrics(d: Display) {
        val m = DisplayMetrics()
        d.getRealMetrics(m)
        if (m.widthPixels > 0 && m.heightPixels > 0) {
            captureWidth = m.widthPixels; captureHeight = m.heightPixels; captureDpi = m.densityDpi
        }
    }

    private fun acquireProjection(resultCode: Int, data: Intent) {
        if (instance == null) return
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.let(::readMetrics)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        projection = try { mpm.getMediaProjection(resultCode, data) } catch (_: Exception) { null }
        val p = projection ?: run { stop(); return }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { this@MirrorService.stop() }
        }, handler)
        Log.d(TAG, "projection ready ${captureWidth}x$captureHeight")
        // Launch only now: the device-state switch moves whatever is on the cover to display 0.
        showMirror(clear = true)
        mirrorShown = true
        MirrorInput.start(this) // live touch
        val app = applicationContext
        MirrorEnv.later { MirrorEnv.hideAccessibility(app) }
    }

    /** The mirror screen has been opened for this session (so bringing it back to the front is safe). */
    private var mirrorShown = false

    /** Opens the mirror screen on the cover; [clear] starts it fresh, otherwise it just comes back to the front. */
    private fun showMirror(clear: Boolean) {
        val i = Intent(this, MirrorActivity::class.java)
        AppLauncher.start(this, if (clear) i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK) else i)
    }

    fun setSurface(s: Surface?) {
        if (s != null && s.isValid) {
            val p = projection ?: return
            if (virtualDisplay == null) {
                virtualDisplay = p.createVirtualDisplay(
                    "OpenCover-mirror", captureWidth, captureHeight, captureDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC,
                    s, null, null,
                )
            } else {
                virtualDisplay?.surface = s
            }
        } else {
            virtualDisplay?.surface = null
        }
    }

    fun stop() {
        MirrorInput.stop()
        if (instance != null) MirrorEnv.leave(this)
        instance = null
        HeatGuard.evaluate()
        runCatching { virtualDisplay?.release() }
        runCatching { projection?.stop() }
        virtualDisplay = null
        projection = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Mirroring", NotificationManager.IMPORTANCE_LOW)
                    .apply { description = "Shown while the inner screen is mirrored on the cover" },
            )
        }
    }

    companion object {
        const val KEY_RESULT_CODE = "rc"
        const val KEY_DATA = "data"
        const val ACTION_STOP = "stop"
        private const val TAG = "OpenCoverMirror"
        private const val CHANNEL_ID = "mirror"
        private const val NOTIFICATION_ID = 4 // 1 is CoverWatcher's

        @Volatile var instance: MirrorService? = null
            private set
    }
}
