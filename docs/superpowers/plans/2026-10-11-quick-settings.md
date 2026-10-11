# Quick Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swipe down on Home opens an editable quick settings panel with a cover brightness slider, battery level and toggle tiles.

**Architecture:** Pure tile list logic in `quick/Tiles.kt` (unit tested). `quick/ShizukuShell.kt` wraps Shizuku. `quick/QuickState.kt` reads live state, observes changes (one version counter bumped by settings observers, broadcasts, torch and Shizuku callbacks) and performs toggles. `ui/QuickPanel.kt` renders it and replaces the Shell placeholder.

**Tech Stack:** Kotlin, Jetpack Compose, Shizuku API 13.1.5, material-icons-extended 1.7.8.

**Spec:** `docs/superpowers/specs/2026-10-11-quick-settings-design.md`

## Spike results (2026-10-11, on device, adb shell = Shizuku powers)

- `svc` on Android 16 only has power, usb, nfc. Use: `cmd wifi set-wifi-enabled enabled|disabled`, `cmd bluetooth_manager enable|disable`, `cmd phone data enable|disable`, `cmd connectivity airplane-mode enable|disable`, `svc nfc enable|disable`, `cmd power set-mode 1|0`, `cmd uimode night yes|no`.
- Hotspot: no shell command. Tile dropped.
- Cover brightness is System `sub_screen_brightness` (74 seen), mode `sub_screen_brightness_mode`.
- Location via Secure `location_mode` 0/3 works (`cmd location is-location-enabled` follows).
- System-table writes (rotation, brightness) pass with WRITE_SECURE_SETTINGS, so no "Modify system settings" step.

## Global Constraints

- minSdk 34, targetSdk 36, compileSdk 37. Device: SM-F731B, Android 16, cover display id 1.
- Commits as Shmubbier. No Claude attribution, no em dashes, no emojis. Short, concise docs.
- Build: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
- Install: `ANDROID_SERIAL=R5CW71QQAGW adb install -r app/build/outputs/apk/debug/app-debug.apk` (Git Bash: `MSYS_NO_PATHCONV=1`).
- New dependencies: Shizuku API and provider 13.1.5; material-icons-extended 1.7.8 (tile icons). Nothing else.

## Review Focus

1. Shizuku stops while the panel is open: Shizuku tiles turn grey, taps show "Start Shizuku", no crash (`run` catches everything).
2. A change made in Samsung's panel or by the system shows on the tile within a second (observers bump `version`).
3. Saved order with unknown or duplicate ids loads cleanly; an empty saved list stays empty, not default (Task 1 tests).
4. Mute without DND access: the sound cycle skips Mute instead of throwing (Task 1 test `ringerSkipsMuteWithoutAccess`).
5. Shizuku commands never run on the main thread; rapid taps don't freeze the panel (Task 4 runs toggles on `Dispatchers.IO`).

---

### Task 1: Tile list logic

**Files:**
- Create: `app/src/main/java/dev/opencover/quick/Tiles.kt`
- Test: `app/src/test/java/dev/opencover/quick/TilesTest.kt`

**Interfaces:**
- Produces: `enum Need { NONE, SHIZUKU, SECURE_SETTINGS, NOTIFICATION_ACCESS }`, `enum Tile(label: String, need: Need)`, `parseTiles(String?): List<Tile>`, `formatTiles(List<Tile>): String`, `hiddenTiles(List<Tile>): List<Tile>`, `List<Tile>.moved(from, to)`, `dropIndex(index, dCols, dRows, size): Int`, `pageCount(shown, editing, hidden): Int`, `enum Ringer { RING, VIBRATE, MUTE; next(canMute) }`, `usable(need, has: Set<Need>)`, `PAGE_SIZE = 8`, `COLS = 4`, `object QuickPrefs { tiles(ctx); setTiles(ctx, tiles) }`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.opencover.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilesTest {
    @Test fun missingSaveGivesDefault() = assertEquals(Tile.entries.toList(), parseTiles(null))
    @Test fun emptySaveStaysEmpty() = assertEquals(emptyList<Tile>(), parseTiles(""))
    @Test fun unknownAndDuplicateIdsDropped() =
        assertEquals(listOf(Tile.WIFI, Tile.NFC), parseTiles("WIFI,HOTSPOT,NFC,WIFI"))
    @Test fun roundTrip() {
        val t = listOf(Tile.SOUND, Tile.WIFI, Tile.DND)
        assertEquals(t, parseTiles(formatTiles(t)))
    }
    @Test fun tilesNotSavedStayHidden() {
        val shown = parseTiles("WIFI")
        assertEquals(Tile.entries.size - 1, hiddenTiles(shown).size)
        assertFalse(Tile.WIFI in hiddenTiles(shown))
    }
    @Test fun moveForwardAndBack() {
        val t = listOf(Tile.WIFI, Tile.BLUETOOTH, Tile.FLASHLIGHT)
        assertEquals(listOf(Tile.BLUETOOTH, Tile.FLASHLIGHT, Tile.WIFI), t.moved(0, 2))
        assertEquals(listOf(Tile.FLASHLIGHT, Tile.WIFI, Tile.BLUETOOTH), t.moved(2, 0))
    }
    @Test fun dropStaysOnPageAndInRange() {
        assertEquals(5, dropIndex(0, 1, 1, 13))   // one right, one down
        assertEquals(3, dropIndex(0, 9, 0, 13))   // clamped to last column
        assertEquals(8, dropIndex(8, -2, -1, 13)) // page 2 stays on page 2
        assertEquals(12, dropIndex(8, 3, 1, 13))  // past the end lands on the last tile
    }
    @Test fun pages() {
        assertEquals(1, pageCount(0, editing = false, hidden = 13))
        assertEquals(2, pageCount(13, editing = false, hidden = 0))
        assertEquals(3, pageCount(13, editing = true, hidden = 1))
        assertEquals(2, pageCount(13, editing = true, hidden = 0))
    }
    @Test fun ringerCycles() {
        assertEquals(Ringer.VIBRATE, Ringer.RING.next(canMute = true))
        assertEquals(Ringer.MUTE, Ringer.VIBRATE.next(canMute = true))
        assertEquals(Ringer.RING, Ringer.MUTE.next(canMute = true))
    }
    @Test fun ringerSkipsMuteWithoutAccess() = assertEquals(Ringer.RING, Ringer.VIBRATE.next(canMute = false))
    @Test fun usableNeeds() {
        assertTrue(usable(Need.NONE, emptySet()))
        assertFalse(usable(Need.SHIZUKU, setOf(Need.SECURE_SETTINGS)))
        assertTrue(usable(Need.SHIZUKU, setOf(Need.SHIZUKU)))
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests dev.opencover.quick.TilesTest`
Expected: compilation failure, `Tile` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package dev.opencover.quick

import android.content.Context
import kotlin.math.max

/** What a tile needs before it can switch. */
enum class Need { NONE, SHIZUKU, SECURE_SETTINGS, NOTIFICATION_ACCESS }

/** Declaration order is the default order. Ids (names) are saved, so never rename one. */
enum class Tile(val label: String, val need: Need) {
    WIFI("Wi-Fi", Need.SHIZUKU),
    BLUETOOTH("Bluetooth", Need.SHIZUKU),
    FLASHLIGHT("Flashlight", Need.NONE),
    ROTATION("Rotate", Need.SECURE_SETTINGS),
    SOUND("Sound", Need.NONE),
    DND("Do not disturb", Need.NOTIFICATION_ACCESS),
    BATTERY_SAVER("Battery saver", Need.SHIZUKU),
    LOCATION("Location", Need.SECURE_SETTINGS),
    MOBILE_DATA("Mobile data", Need.SHIZUKU),
    AIRPLANE("Airplane", Need.SHIZUKU),
    NFC("NFC", Need.SHIZUKU),
    DARK_MODE("Dark mode", Need.SHIZUKU),
    KEEP_AWAKE("Keep awake", Need.NONE),
}

const val COLS = 4
const val ROWS = 2
const val PAGE_SIZE = COLS * ROWS

/** Null (never saved) = default. Unknown and repeated ids are dropped; tiles added later start hidden. */
fun parseTiles(saved: String?): List<Tile> =
    saved?.split(',')?.mapNotNull { id -> Tile.entries.find { it.name == id } }?.distinct() ?: Tile.entries.toList()

fun formatTiles(tiles: List<Tile>) = tiles.joinToString(",") { it.name }

fun hiddenTiles(shown: List<Tile>) = Tile.entries - shown.toSet()

fun List<Tile>.moved(from: Int, to: Int): List<Tile> = toMutableList().apply { add(to, removeAt(from)) }

/** Where a tile dragged by [dCols] columns and [dRows] rows lands. Stays on its page and in range. */
fun dropIndex(index: Int, dCols: Int, dRows: Int, size: Int): Int {
    val page = index / PAGE_SIZE
    val col = (index % COLS + dCols).coerceIn(0, COLS - 1)
    val row = (index % PAGE_SIZE / COLS + dRows).coerceIn(0, ROWS - 1)
    return (page * PAGE_SIZE + row * COLS + col).coerceAtMost(size - 1)
}

/** Tile pages, plus the "+" page in edit mode when something is hidden. */
fun pageCount(shown: Int, editing: Boolean, hidden: Int) =
    max(1, (shown + PAGE_SIZE - 1) / PAGE_SIZE) + if (editing && hidden > 0) 1 else 0

enum class Ringer {
    RING, VIBRATE, MUTE;

    /** Mute needs DND access; without it the cycle is Ring, Vibrate. */
    fun next(canMute: Boolean) = when (this) {
        RING -> VIBRATE
        VIBRATE -> if (canMute) MUTE else RING
        MUTE -> RING
    }
}

fun usable(need: Need, has: Set<Need>) = need == Need.NONE || need in has

object QuickPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("quick", Context.MODE_PRIVATE)
    fun tiles(ctx: Context) = parseTiles(prefs(ctx).getString("tiles", null))
    fun setTiles(ctx: Context, tiles: List<Tile>) = prefs(ctx).edit().putString("tiles", formatTiles(tiles)).apply()
}
```

- [ ] **Step 4: Run to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests dev.opencover.quick.TilesTest`
Expected: PASS (11 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/quick/Tiles.kt app/src/test/java/dev/opencover/quick/TilesTest.kt
git commit -m "Add quick settings tile list logic"
```

---

### Task 2: Shizuku

**Files:**
- Modify: `app/build.gradle.kts` (dependencies)
- Modify: `app/src/main/AndroidManifest.xml` (provider, queries)
- Create: `app/src/main/java/dev/opencover/quick/ShizukuShell.kt`
- Modify: `app/src/main/java/dev/opencover/ui/CoverActivity.kt` (`setupSteps()`)

**Interfaces:**
- Produces: `object ShizukuShell { installed(ctx): Boolean; running(): Boolean; allowed(): Boolean; request(); listen(onChange: () -> Unit); unlisten(); run(vararg cmd: String): Boolean }`

- [ ] **Step 1: Dependencies**

In `app/build.gradle.kts` dependencies, after the media line:

```kotlin
    implementation("dev.rikka.shizuku:api:13.1.5")      // Shizuku: Wi-Fi, Bluetooth and other blocked toggles
    implementation("dev.rikka.shizuku:provider:13.1.5")
```

- [ ] **Step 2: Manifest**

Inside `<queries>` add `<package android:name="moe.shizuku.privileged.api" />`. Inside `<application>` add:

```xml
        <!-- Shizuku hands OpenCover its binder through this provider. -->
        <provider
            android:name="rikka.shizuku.ShizukuProvider"
            android:authorities="${applicationId}.shizuku"
            android:multiprocess="false"
            android:enabled="true"
            android:exported="true"
            android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

- [ ] **Step 3: ShizukuShell**

```kotlin
package dev.opencover.quick

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/** Runs shell commands through Shizuku (shell-level access, no root). */
object ShizukuShell {
    fun installed(ctx: Context) = try {
        ctx.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0); true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun running() = try { Shizuku.pingBinder() } catch (e: Exception) { false }

    fun allowed() = running() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun request() { if (running() && !allowed()) Shizuku.requestPermission(1) }

    private var listeners: Triple<Shizuku.OnBinderReceivedListener, Shizuku.OnBinderDeadListener, Shizuku.OnRequestPermissionResultListener>? = null

    fun listen(onChange: () -> Unit) {
        unlisten()
        val l = Triple(
            Shizuku.OnBinderReceivedListener { onChange() },
            Shizuku.OnBinderDeadListener { onChange() },
            Shizuku.OnRequestPermissionResultListener { _, _ -> onChange() },
        )
        Shizuku.addBinderReceivedListenerSticky(l.first)
        Shizuku.addBinderDeadListener(l.second)
        Shizuku.addRequestPermissionResultListener(l.third)
        listeners = l
    }

    fun unlisten() {
        listeners?.let {
            Shizuku.removeBinderReceivedListener(it.first)
            Shizuku.removeBinderDeadListener(it.second)
            Shizuku.removeRequestPermissionResultListener(it.third)
        }
        listeners = null
    }

    // ponytail: Shizuku 13 hides newProcess; reflection is the documented stopgap. Move to a UserService if it breaks.
    private val newProcess by lazy {
        Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            .apply { isAccessible = true }
    }

    /** Blocking; call off the main thread. False if Shizuku isn't ready or the command fails. */
    fun run(vararg cmd: String): Boolean = try {
        allowed() && (newProcess.invoke(null, arrayOf(*cmd), null, null) as Process).waitFor() == 0
    } catch (e: Exception) {
        false
    }
}
```

- [ ] **Step 4: Setup step**

In `CoverActivity.setupSteps()`, before the Microphone step:

```kotlin
        SetupStep("Shizuku (optional)",
            if (!ShizukuShell.installed(this)) "Wi-Fi, Bluetooth, data and other tiles. Install Shizuku, see README"
            else if (!ShizukuShell.running()) "Shizuku is installed but not running, see README"
            else "Tap to allow OpenCover in Shizuku",
            ShizukuShell.allowed()) { ShizukuShell.request() },
```

Import `dev.opencover.quick.ShizukuShell`.

- [ ] **Step 5: Build, install, verify on device**

Build and install. On the PC: install Shizuku (`adb install` the APK from its GitHub releases, user downloads it), then start it:
`adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`
Open OpenCover Setup: the Shizuku step reads "Tap to allow"; tap it and allow (if Shizuku's dialog opens on the main screen, accept it with `adb shell input` or note it for the README). Step turns green.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/AndroidManifest.xml app/src/main/java/dev/opencover/quick/ShizukuShell.kt app/src/main/java/dev/opencover/ui/CoverActivity.kt
git commit -m "Add Shizuku shell and setup step"
```

---

### Task 3: Live state and toggles

**Files:**
- Create: `app/src/main/java/dev/opencover/quick/QuickState.kt`
- Modify: `app/src/main/AndroidManifest.xml` (ACCESS_NETWORK_STATE)

**Interfaces:**
- Consumes: Task 1 `Tile`, `Need`, `Ringer`, `usable`; Task 2 `ShizukuShell`; existing `dev.opencover.watcher.KeepAwake { isOn; set(ctx, on) }`.
- Produces: `object QuickState { version: Int (Compose state); start(ctx); stop(ctx); granted(ctx): Set<Need>; isOn(ctx, tile): Boolean?; ringer(ctx): Ringer; toggle(ctx, tile): String? (hint on failure, blocking); battery(ctx): Int; brightness(ctx): Float; setBrightness(ctx, f: Float): Boolean }`

- [ ] **Step 1: Manifest**

Add `<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />` (reading mobile data state).

- [ ] **Step 2: QuickState**

```kotlin
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
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.telephony.TelephonyManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
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

    /** Null = this phone doesn't have it. */
    fun isOn(ctx: Context, tile: Tile): Boolean? {
        val r = ctx.contentResolver
        return when (tile) {
            Tile.WIFI -> ctx.getSystemService(WifiManager::class.java).isWifiEnabled
            Tile.BLUETOOTH -> Settings.Global.getInt(r, Settings.Global.BLUETOOTH_ON, 0) != 0
            Tile.FLASHLIGHT -> if (flashId == null) null else torchOn
            Tile.ROTATION -> Settings.System.getInt(r, Settings.System.ACCELEROMETER_ROTATION, 0) != 0
            Tile.SOUND -> ringer(ctx) == Ringer.RING
            Tile.DND -> ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
            Tile.BATTERY_SAVER -> ctx.getSystemService(PowerManager::class.java).isPowerSaveMode
            Tile.LOCATION -> ctx.getSystemService(LocationManager::class.java).isLocationEnabled
            Tile.MOBILE_DATA -> ctx.getSystemService(TelephonyManager::class.java).isDataEnabled
            Tile.AIRPLANE -> Settings.Global.getInt(r, Settings.Global.AIRPLANE_MODE_ON, 0) != 0
            Tile.NFC -> NfcAdapter.getDefaultAdapter(ctx)?.isEnabled
            Tile.DARK_MODE -> ctx.getSystemService(UiModeManager::class.java).nightMode == UiModeManager.MODE_NIGHT_YES
            Tile.KEEP_AWAKE -> KeepAwake.isOn
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
            Need.SHIZUKU -> if (ShizukuShell.running()) "Allow OpenCover in Shizuku (Setup)" else "Start Shizuku"
            else -> "Grant in Setup"
        }
        val on = isOn(ctx, tile) ?: return "Not on this phone"
        val r = ctx.contentResolver
        val ok = try {
            when (tile) {
                Tile.WIFI -> ShizukuShell.run("cmd", "wifi", "set-wifi-enabled", if (on) "disabled" else "enabled")
                Tile.BLUETOOTH -> ShizukuShell.run("cmd", "bluetooth_manager", if (on) "disable" else "enable")
                Tile.MOBILE_DATA -> ShizukuShell.run("cmd", "phone", "data", if (on) "disable" else "enable")
                Tile.AIRPLANE -> ShizukuShell.run("cmd", "connectivity", "airplane-mode", if (on) "disable" else "enable")
                Tile.NFC -> ShizukuShell.run("svc", "nfc", if (on) "disable" else "enable")
                Tile.BATTERY_SAVER -> ShizukuShell.run("cmd", "power", "set-mode", if (on) "0" else "1")
                Tile.DARK_MODE -> ShizukuShell.run("cmd", "uimode", "night", if (on) "no" else "yes")
                Tile.FLASHLIGHT -> { ctx.getSystemService(CameraManager::class.java).setTorchMode(flashId!!, !on); true }
                Tile.ROTATION -> Settings.System.putInt(r, Settings.System.ACCELEROMETER_ROTATION, if (on) 0 else 1)
                Tile.LOCATION -> Settings.Secure.putInt(r, Settings.Secure.LOCATION_MODE,
                    if (on) Settings.Secure.LOCATION_MODE_OFF else Settings.Secure.LOCATION_MODE_HIGH_ACCURACY)
                Tile.SOUND -> {
                    val next = ringer(ctx).next(canMute = Need.NOTIFICATION_ACCESS in has)
                    ctx.getSystemService(AudioManager::class.java).ringerMode = when (next) {
                        Ringer.RING -> AudioManager.RINGER_MODE_NORMAL
                        Ringer.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
                        Ringer.MUTE -> AudioManager.RINGER_MODE_SILENT
                    }
                    true
                }
                Tile.DND -> {
                    ctx.getSystemService(NotificationManager::class.java).setInterruptionFilter(
                        if (on) NotificationManager.INTERRUPTION_FILTER_ALL else NotificationManager.INTERRUPTION_FILTER_PRIORITY)
                    true
                }
                Tile.KEEP_AWAKE -> { KeepAwake.set(ctx, !on); true }
            }
        } catch (e: Exception) {
            false
        }
        bump()
        return if (ok) null else "Couldn't switch ${tile.label}"
    }

    fun battery(ctx: Context) = ctx.getSystemService(BatteryManager::class.java).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

    fun brightness(ctx: Context) = Settings.System.getInt(ctx.contentResolver, COVER_BRIGHTNESS, 128) / 255f

    fun setBrightness(ctx: Context, f: Float) = try {
        Settings.System.putInt(ctx.contentResolver, COVER_BRIGHTNESS, (1 + f.coerceIn(0f, 1f) * 254).toInt())
    } catch (e: Exception) {
        false
    }
}
```

Note: `KeepAwake.set` touches the WindowManager, so Task 4's `tap` runs only `Need.SHIZUKU` tiles on IO and the rest on the main thread.

- [ ] **Step 3: Build**

Run the build. Expected: BUILD SUCCESSFUL, TilesTest still passes.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/dev/opencover/quick/QuickState.kt
git commit -m "Add quick settings live state and toggles"
```

---

### Task 4: Panel UI

**Files:**
- Modify: `app/build.gradle.kts` (icons)
- Create: `app/src/main/java/dev/opencover/ui/QuickPanel.kt`
- Modify: `app/src/main/java/dev/opencover/ui/Shell.kt:232` (replace placeholder)

**Interfaces:**
- Consumes: Task 1 (all), Task 3 `QuickState`.
- Produces: `@Composable fun QuickPanel()`.

- [ ] **Step 1: Icons dependency**

```kotlin
    implementation("androidx.compose.material:material-icons-extended:1.7.8") // quick settings tile icons
```

- [ ] **Step 2: QuickPanel**

```kotlin
package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.quick.COLS
import dev.opencover.quick.Need
import dev.opencover.quick.PAGE_SIZE
import dev.opencover.quick.QuickPrefs
import dev.opencover.quick.QuickState
import dev.opencover.quick.ROWS
import dev.opencover.quick.Ringer
import dev.opencover.quick.Tile
import dev.opencover.quick.dropIndex
import dev.opencover.quick.hiddenTiles
import dev.opencover.quick.moved
import dev.opencover.quick.pageCount
import dev.opencover.quick.usable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Swipe-down quick settings. Long-press a tile to edit; swipe up or Back closes (Shell). */
@Composable
fun QuickPanel() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        QuickState.start(ctx)
        onDispose { QuickState.stop(ctx) }
    }
    val v = QuickState.version // read so every change recomposes
    var tiles by remember { mutableStateOf(QuickPrefs.tiles(ctx)) }
    var editing by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(hint) { if (hint != null) { delay(2500); hint = null } }
    BackHandler(enabled = editing) { editing = false }
    val scope = rememberCoroutineScope()
    fun save(t: List<Tile>) { tiles = t; QuickPrefs.setTiles(ctx, t) }
    val has = remember(v) { QuickState.granted(ctx) }
    val hidden = hiddenTiles(tiles)

    fun tap(t: Tile) {
        // Shizuku commands block; everything else touches main-thread APIs (window, camera).
        if (t.need == Need.SHIZUKU) scope.launch {
            val h = withContext(Dispatchers.IO) { QuickState.toggle(ctx, t) }
            if (h != null) hint = h
        } else QuickState.toggle(ctx, t)?.let { hint = it }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).windowInsetsPadding(WindowInsets.displayCutout)
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        // Header: cover brightness and battery, or Done while editing.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (editing) {
                Text("Edit tiles", color = Color.White, fontSize = 16.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = { editing = false }) { Text("Done") }
            } else {
                Icon(Icons.Filled.BrightnessMedium, null, tint = Color.White, modifier = Modifier.size(22.dp))
                var level by remember { mutableFloatStateOf(QuickState.brightness(ctx)) }
                Slider(
                    value = level, modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    onValueChange = { level = it; if (!QuickState.setBrightness(ctx, it)) hint = "Grant in Setup" },
                )
                Text("${remember(v) { QuickState.battery(ctx) }}%", color = Color.White, fontSize = 14.sp)
            }
        }

        val pages = pageCount(tiles.size, editing, hidden.size)
        val pager = rememberPagerState { pages }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth()) { page ->
            val addPage = editing && hidden.isNotEmpty() && page == pages - 1
            val list = if (addPage) hidden.take(PAGE_SIZE) else tiles.drop(page * PAGE_SIZE).take(PAGE_SIZE)
            var cell by remember { mutableStateOf(IntSize.Zero) }
            Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly) {
                for (row in 0 until ROWS) Row(Modifier.fillMaxWidth()) {
                    for (col in 0 until COLS) Box(
                        Modifier.weight(1f).onSizeChanged { cell = it },
                        contentAlignment = Alignment.Center,
                    ) {
                        val t = list.getOrNull(row * COLS + col) ?: return@Box
                        val index = page * PAGE_SIZE + row * COLS + col
                        when {
                            addPage -> TileButton(t, on = false, enabled = true, badge = "+", onTap = { save(tiles + t) })
                            editing -> {
                                var drag by remember(t) { mutableStateOf(Offset.Zero) }
                                Box(
                                    Modifier.graphicsLayer { translationX = drag.x; translationY = drag.y }
                                        .pointerInput(t, tiles) {
                                            detectDragGesturesAfterLongPress(
                                                onDragEnd = {
                                                    val to = dropIndex(index, (drag.x / cell.width).roundToInt(), (drag.y / cell.height).roundToInt(), tiles.size)
                                                    if (to != index) save(tiles.moved(index, to))
                                                    drag = Offset.Zero
                                                },
                                                onDragCancel = { drag = Offset.Zero },
                                            ) { c, amount -> c.consume(); drag += amount }
                                        },
                                ) { TileButton(t, on = false, enabled = true, badge = "-", onTap = { save(tiles - t) }) }
                            }
                            else -> TileButton(
                                t, on = remember(v) { QuickState.isOn(ctx, t) } == true,
                                enabled = usable(t.need, has),
                                label = if (t == Tile.SOUND) remember(v) { QuickState.ringer(ctx) }.label() else t.label,
                                icon = if (t == Tile.SOUND) remember(v) { QuickState.ringer(ctx) }.icon() else t.icon(),
                                onTap = { tap(t) }, onLongPress = { editing = true },
                            )
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(hint ?: if (editing) "Tap - to remove, drag to move, + page to add" else "",
                color = Color.LightGray, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileButton(
    t: Tile,
    on: Boolean,
    enabled: Boolean,
    onTap: () -> Unit,
    label: String = t.label,
    icon: ImageVector = t.icon(),
    badge: String? = null,
    onLongPress: () -> Unit = {},
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            Box(
                Modifier.size(56.dp).background(if (on) Color.White else Color(0xFF2A2A2A), CircleShape)
                    .combinedClickable(onClick = onTap, onLongClick = onLongPress),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, label, tint = when { on -> Color.Black; enabled -> Color.White; else -> Color.Gray })
            }
            if (badge != null) Box(
                Modifier.align(Alignment.TopEnd).size(20.dp).background(Color(0xFF7A1F1F), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Text(badge, color = Color.White, fontSize = 14.sp) }
        }
        Text(label, color = if (enabled) Color.White else Color.Gray, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun Tile.icon(): ImageVector = when (this) {
    Tile.WIFI -> Icons.Filled.Wifi
    Tile.BLUETOOTH -> Icons.Filled.Bluetooth
    Tile.FLASHLIGHT -> Icons.Filled.FlashlightOn
    Tile.ROTATION -> Icons.Filled.ScreenRotation
    Tile.SOUND -> Icons.Filled.VolumeUp
    Tile.DND -> Icons.Filled.DoNotDisturbOn
    Tile.BATTERY_SAVER -> Icons.Filled.BatterySaver
    Tile.LOCATION -> Icons.Filled.LocationOn
    Tile.MOBILE_DATA -> Icons.Filled.SignalCellularAlt
    Tile.AIRPLANE -> Icons.Filled.AirplanemodeActive
    Tile.NFC -> Icons.Filled.Nfc
    Tile.DARK_MODE -> Icons.Filled.DarkMode
    Tile.KEEP_AWAKE -> Icons.Filled.Coffee
}

private fun Ringer.label() = when (this) { Ringer.RING -> "Ring"; Ringer.VIBRATE -> "Vibrate"; Ringer.MUTE -> "Mute" }
private fun Ringer.icon() = when (this) { Ringer.RING -> Icons.Filled.VolumeUp; Ringer.VIBRATE -> Icons.Filled.Vibration; Ringer.MUTE -> Icons.Filled.VolumeOff }
```

Note: Sound tile shows "on" (white) only when ringing; its label and icon always show the current mode.

- [ ] **Step 3: Hook into Shell**

In `Shell.kt` replace `Panel.QUICK_SETTINGS -> Placeholder("Quick settings")` with:

```kotlin
                    Panel.QUICK_SETTINGS -> QuickPanel() // close by swiping up or Back
```

Then `grep -rn "Placeholder(" app/src/main`; if nothing else calls it, delete the `Placeholder` composable and its now-unused imports.

- [ ] **Step 4: Build, install, device check**

Build and install. Swipe down on Home: panel shows slider, battery and two pages of tiles. Confirm swipe up closes it and Back closes it. Long-press a tile: edit mode; remove one, add it back from the "+" page, drag one to a new spot; reopen the panel and the order is kept.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/src/main/java/dev/opencover/ui/QuickPanel.kt app/src/main/java/dev/opencover/ui/Shell.kt
git commit -m "Add quick settings panel"
```

---

### Task 5: Device verification, brightness fallback, README

**Files:**
- Modify: `app/src/main/java/dev/opencover/quick/QuickState.kt` (only if Step 2 fails)
- Modify: `README.md`

- [ ] **Step 1: Each tile on device**

With Shizuku running and allowed, tap each tile and check the result against Samsung's panel or `adb shell`:
- Wi-Fi: `cmd wifi status`. Bluetooth: `settings get global bluetooth_on`. Data: `settings get global mobile_data`. Airplane: `cmd connectivity airplane-mode`. NFC: `svc nfc` state in Samsung's panel. Battery saver: `settings get global low_power`. Dark mode: `cmd uimode night`.
- Location: `cmd location is-location-enabled`. Rotation: `settings get system accelerometer_rotation` and an app actually rotating on the cover. Flashlight, Sound (Ring, Vibrate, Mute), DND, Keep awake: visible effect.
- Change Wi-Fi from Samsung's panel while ours is open: our tile follows.
- `adb shell am force-stop moe.shizuku.privileged.api`: Shizuku tiles turn grey; tap shows "Start Shizuku"; no crash.

- [ ] **Step 2: Brightness**

Drag the slider: the cover screen's brightness must change live and `settings get system sub_screen_brightness` must follow. If the value changes but the screen doesn't, replace `setBrightness` with a window-level fallback in `QuickPanel` (dims OpenCover only):

```kotlin
// QuickPanel header, in onValueChange instead of QuickState.setBrightness:
(ctx as? android.app.Activity)?.window?.let { w -> w.attributes = w.attributes.apply { screenBrightness = it.coerceAtLeast(0.02f) } }
```

and note "Brightness dims OpenCover only" in the README.

- [ ] **Step 3: README**

Under "What works" add:

```markdown
- Quick settings: swipe down from Home. Cover brightness, battery, and tiles for Wi-Fi, Bluetooth, flashlight, rotation, sound, Do Not Disturb, battery saver, location, mobile data, airplane, NFC, dark mode and keep awake. Long-press a tile to add, remove or move tiles.
```

Add a section:

```markdown
## Shizuku
Wi-Fi, Bluetooth, mobile data, airplane, NFC, battery saver and dark mode need Shizuku (no root).
1. Install Shizuku from its GitHub releases: `adb install shizuku.apk`
2. Start it: `adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh`
3. In OpenCover Setup, tap Shizuku and allow it.

Shizuku stops on reboot. To have it start itself (needs Wi-Fi at boot), once:
1. `adb shell pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS`
2. In Shizuku, pair with wireless debugging and turn on "Start on boot".
```

Adjust step 2 of the boot section to what actually worked on the device in Step 4.

- [ ] **Step 4: Shizuku start on boot**

Do the one-time boot setup with the user, reboot, wait for Wi-Fi, and confirm Shizuku tiles work without a PC. If pairing needs the main screen, try `adb shell am start --display 1 -a android.settings.APPLICATION_DEVELOPMENT_SETTINGS` and record what worked in the README.

- [ ] **Step 5: Commit**

```bash
git add README.md app/src/main/java/dev/opencover/quick/QuickState.kt app/src/main/java/dev/opencover/ui/QuickPanel.kt
git commit -m "Quick settings: device fixes and README"
```
