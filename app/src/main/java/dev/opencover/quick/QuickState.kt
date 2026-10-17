package dev.opencover.quick

import android.Manifest
import android.app.NotificationManager
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.VibratorManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import dev.opencover.phone.RotationFeature
import dev.opencover.apps.AppLauncher
import dev.opencover.mirror.ConsentActivity
import dev.opencover.mirror.MirrorService
import dev.opencover.watcher.KeepAwake

/** Live tile state while the panel is open. Anything that may change a tile bumps [version]. */
object QuickState {
    var version by mutableIntStateOf(0)
        private set
    private val handler = Handler(Looper.getMainLooper())
    private fun bump() { handler.post { version++ } }

    private val observer = object : ContentObserver(handler) { override fun onChange(selfChange: Boolean) = bump() }
    private val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = bump() }
    private var flashId: String? = null
    private var torchOn = false
    private val torch = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(id: String, on: Boolean) { if (id == flashId) { torchOn = on; bump() } }
    }

    private const val COVER_BRIGHTNESS = "sub_screen_brightness" // Samsung: the cover screen's own level, 1..255

    fun start(ctx: Context) {
        val r = ctx.contentResolver
        listOf(Settings.Global.CONTENT_URI, Settings.Secure.CONTENT_URI, Settings.System.CONTENT_URI)
            .forEach { r.registerContentObserver(it, true, observer) }
        val filter = IntentFilter().apply {
            listOf(
                WifiManager.WIFI_STATE_CHANGED_ACTION, "android.bluetooth.adapter.action.STATE_CHANGED",
                Intent.ACTION_AIRPLANE_MODE_CHANGED, NfcAdapter.ACTION_ADAPTER_STATE_CHANGED,
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED, LocationManager.MODE_CHANGED_ACTION,
                AudioManager.RINGER_MODE_CHANGED_ACTION, NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED,
                Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_CONFIGURATION_CHANGED,
            ).forEach(::addAction)
        }
        ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        val cm = ctx.getSystemService(CameraManager::class.java)
        flashId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        cm.registerTorchCallback(torch, handler)
        ShizukuShell.listen(::bump)
    }

    fun stop(ctx: Context) {
        ctx.contentResolver.unregisterContentObserver(observer)
        ctx.unregisterReceiver(receiver)
        ctx.getSystemService(CameraManager::class.java).unregisterTorchCallback(torch)
        ShizukuShell.unlisten()
    }

    fun granted(ctx: Context): Set<Need> = buildSet {
        if (ShizukuShell.allowed()) add(Need.SHIZUKU)
        if (ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) add(Need.SECURE_SETTINGS)
        // Apps with notification access also get Do Not Disturb access.
        if (ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted) add(Need.NOTIFICATION_ACCESS)
    }

    /** Null = this phone doesn't have it, or its state can't be read. */
    fun isOn(ctx: Context, tile: Tile): Boolean? = try {
        read(ctx, tile)
    } catch (e: Exception) {
        null
    }

    private fun read(ctx: Context, tile: Tile): Boolean? {
        val r = ctx.contentResolver
        return when (tile) {
            Tile.WIFI -> ctx.getSystemService(WifiManager::class.java).isWifiEnabled
            Tile.BLUETOOTH -> Settings.Global.getInt(r, Settings.Global.BLUETOOTH_ON, 0) != 0
            Tile.FLASHLIGHT -> if (flashId == null) null else torchOn
            Tile.ROTATION -> false // an action, never "on"
            Tile.SOUND -> ringer(ctx) != Ringer.MUTE
            Tile.DND -> ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
            Tile.BATTERY_SAVER -> ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
            Tile.LOCATION -> ctx.getSystemService(LocationManager::class.java).isLocationEnabled
            Tile.MOBILE_DATA -> ctx.getSystemService(TelephonyManager::class.java).isDataEnabled
            Tile.AIRPLANE -> Settings.Global.getInt(r, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            Tile.NFC -> NfcAdapter.getDefaultAdapter(ctx)?.isEnabled
            Tile.DARK_MODE -> ctx.getSystemService(UiModeManager::class.java).nightMode == UiModeManager.MODE_NIGHT_YES
            Tile.KEEP_AWAKE -> KeepAwake.isOn
            Tile.MIRROR -> MirrorService.instance != null
        }
    }

    /** A short buzz for Vibrate, a short beep for Ring, nothing for Mute. */
    private fun cue(ctx: Context, r: Ringer) {
        when (r) {
            Ringer.VIBRATE -> ctx.getSystemService(VibratorManager::class.java).defaultVibrator
                .vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            Ringer.RING -> ToneGenerator(AudioManager.STREAM_RING, 90).let { tone ->
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 160)
                handler.postDelayed({ tone.release() }, 400)
            }
            Ringer.MUTE -> {}
        }
    }

    fun ringer(ctx: Context) = when (ctx.getSystemService(AudioManager::class.java).ringerMode) {
        AudioManager.RINGER_MODE_VIBRATE -> Ringer.VIBRATE
        AudioManager.RINGER_MODE_SILENT -> Ringer.MUTE
        else -> Ringer.RING
    }

    /** Blocking (Shizuku); call off the main thread. Returns a hint when it couldn't switch. */
    fun toggle(ctx: Context, tile: Tile): String? {
        val has = granted(ctx)
        if (!usable(tile.need, has)) return when (tile.need) {
            Need.SHIZUKU -> if (ShizukuShell.running()) { ShizukuShell.request(); "Allow OpenCover in Shizuku" } else "Start Shizuku"
            Need.LATER -> "Coming in a later update"
            else -> "Grant in Setup"
        }
        if (tile == Tile.ROTATION) return RotationFeature.flip(ctx)
        val on = isOn(ctx, tile) ?: return "Not on this phone"
        val r = ctx.contentResolver
        val ok = try {
            when (tile) {
                Tile.WIFI -> ShizukuShell.run("cmd", "wifi", "set-wifi-enabled", if (on) "disabled" else "enabled")
                Tile.BLUETOOTH -> ShizukuShell.run("cmd", "bluetooth_manager", if (on) "disable" else "enable")
                Tile.MOBILE_DATA -> ShizukuShell.run("cmd", "phone", "data", if (on) "disable" else "enable")
                Tile.AIRPLANE -> ShizukuShell.run("cmd", "connectivity", "airplane-mode", if (on) "disable" else "enable")
                Tile.NFC -> if (on) ShizukuShell.run("cmd", "nfc", "disable-nfc", "persist") else ShizukuShell.run("cmd", "nfc", "enable-nfc")
                Tile.BATTERY_SAVER -> ShizukuShell.run("cmd", "power", "set-mode", if (on) "0" else "1")
                Tile.DARK_MODE -> ShizukuShell.run("cmd", "uimode", "night", if (on) "no" else "yes")
                Tile.FLASHLIGHT -> { ctx.getSystemService(CameraManager::class.java).setTorchMode(flashId!!, !on); true }
                Tile.ROTATION -> false // handled above
                Tile.LOCATION -> Settings.Secure.putInt(r, Settings.Secure.LOCATION_MODE,
                    if (on) Settings.Secure.LOCATION_MODE_OFF else Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
                Tile.SOUND -> {
                    val next = ringer(ctx).next(canMute = Need.NOTIFICATION_ACCESS in has)
                    ctx.getSystemService(AudioManager::class.java).ringerMode = when (next) {
                        Ringer.RING -> AudioManager.RINGER_MODE_NORMAL
                        Ringer.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                        Ringer.MUTE -> AudioManager.RINGER_MODE_SILENT
                    }
                    cue(ctx, next)
                    true
                }
                Tile.DND -> {
                    ctx.getSystemService(NotificationManager::class.java).setInterruptionFilter(
                        if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    true
                }
                Tile.KEEP_AWAKE -> { KeepAwake.set(ctx, !on); true }
                Tile.MIRROR -> {
                    if (on) handler.post { MirrorService.instance?.stop() }
                    else AppLauncher.start(ctx, Intent(ctx, ConsentActivity::class.java))
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
        bump()
        return if (ok) null else "Couldn't switch ${tile.label}"
    }

    fun battery(ctx: Context) = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    /** Cover brightness 0..1. Apps can't read Samsung's hidden key, so this goes through Shizuku. Blocking; null without Shizuku. */
    fun brightness(): Float? =
        ShizukuShell.output("settings", "get", "system", COVER_BRIGHTNESS)?.trim()?.toIntOrNull()?.let { it / 255f }

    /** Blocking. False without Shizuku. */
    fun setBrightness(f: Float) =
        ShizukuShell.run("settings", "put", "system", COVER_BRIGHTNESS, (1 + f.coerceIn(0f, 1f) * 254).toInt().toString())
}
