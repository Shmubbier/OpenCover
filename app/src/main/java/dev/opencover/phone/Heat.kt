package dev.opencover.phone

enum class Heat { OK, WARN, CUT }

// Battery temperature in tenths of a degree C. The phone idled at 43-46 C with Android's thermal status at 2 when
// the sensor hub started failing, so warning starts below that. Fixed for now; make them settings if they need tuning.
const val WARN_TENTHS = 420
const val CUT_TENTHS = 450
private const val CLEAR_TENTHS = 20 // must cool this far below a threshold before the level drops
private const val STATUS_MODERATE = 2 // PowerManager.THERMAL_STATUS_MODERATE
private const val STATUS_SEVERE = 3 // PowerManager.THERMAL_STATUS_SEVERE

/**
 * Stops the Wi-Fi hotspot: ITetheringConnector.stopTethering(TETHERING_WIFI, "com.android.shell", null, null). The
 * transaction number (5) is the method's position in the AIDL, so it is only right for this Android version; no
 * shell command stops tethering. The null result listener makes the call print a NullPointerException after the
 * hotspot has already stopped, so the exit status says nothing.
 */
val STOP_HOTSPOT: List<String> =
    listOf("service", "call", "tethering", "5", "i32", "0", "s16", "com.android.shell", "i32", "-1", "null")

/** The heat level for Android's thermal [status] and the battery temperature, never dropping until clearly cooler. */
fun heatLevel(status: Int, tenths: Int, previous: Heat): Heat {
    val cut = status >= STATUS_SEVERE || tenths >= CUT_TENTHS || (previous == Heat.CUT && tenths >= CUT_TENTHS - CLEAR_TENTHS)
    if (cut) return Heat.CUT
    val warn = status >= STATUS_MODERATE || tenths >= WARN_TENTHS || (previous != Heat.OK && tenths >= WARN_TENTHS - CLEAR_TENTHS)
    return if (warn) Heat.WARN else Heat.OK
}

data class Step(val cut: Boolean, val notify: Boolean)

/** What to do at [now] after telling the user [told]: cut on every check while hot (a stop may have failed), notify only when worse. */
fun heatStep(now: Heat, told: Heat) = Step(cut = now == Heat.CUT, notify = now.ordinal > told.ordinal)

/** What the user is told: only while the hotspot or mirroring is on, since the phone idles warm while charging. */
fun shown(level: Heat, active: Boolean): Heat = if (active) level else Heat.OK

/** WifiManager.WIFI_AP_STATE_ENABLED (hidden): extra `wifi_state` of `android.net.wifi.WIFI_AP_STATE_CHANGED`. */
const val AP_ENABLED = 13
