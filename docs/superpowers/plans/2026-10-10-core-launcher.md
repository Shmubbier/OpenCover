# OpenCover Core Launcher Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A FOSS launcher app that runs on the Galaxy Z Flip 5's cover screen (display 1) with swipeable editable pages of apps and widgets, an app drawer, auto-open on unlock, Home-gesture redirect, and keep-awake.

**Architecture:** One Android app module. Pure-Kotlin core (`grid/`, `layout/`, `apps/AppEntry.kt`) is unit-tested on the JVM. Android-facing pieces (`AppLauncher`, `AppRepository`, `WidgetHost`, `CoverWatcher`, `KeepAwake`) and the Compose UI (`ui/`) are verified on the real phone over ADB with cover-screen screenshots. The three risky platform assumptions (R1–R3) are proven in Tasks 2–3 before feature work.

**Tech Stack:** Kotlin 2.4.21 (Compose compiler plugin), AGP 9.4.1 (built-in Kotlin), Gradle 9.8.1, Compose BOM 2026.09.00 (foundation + material3), activity-compose 1.13.0, JUnit 4.13.2, org.json 20260814 (unit tests only).

**Spec:** `docs/superpowers/specs/2026-10-10-core-launcher-design.md`

## Global Constraints

- License GPL-3.0; package / applicationId `dev.opencover`.
- minSdk 34, targetSdk 36, compileSdk 37.
- Runtime dependencies: Kotlin, Compose BOM, activity-compose. Nothing else. Test-only: JUnit, org.json.
- Cover screen = display id **1** (`COVER_DISPLAY`), 748×720 px, 340 dpi. Screenshot it with `adb shell screencap -d 4630947181303254916`.
- Grid is fixed **4×4** (`GRID = 4`) per page.
- Every activity we start goes to display 1 via `AppLauncher.options()`.
- Layout file: `filesDir/layout.json`, schema `version: 1` (see spec).
- Samsung cover home activity: `com.android.systemui.subscreen.SubHomeActivity`.
- The phone appears twice in `adb devices` (USB + wireless). Every adb command runs with `ANDROID_SERIAL=R5CW71QQAGW`.
- Build JDK: `C:\Program Files\Android\Android Studio\jbr` (set `JAVA_HOME`). SDK: `%LOCALAPPDATA%\Android\Sdk`.
- Device checks that need the phone folded/unlocked or a gesture: ask the user to do it, then verify with logcat or a screenshot. Never claim a device check passed without one.

## Review Focus

1. **App list comes back empty** (transient LauncherApps/usage failure on start) → the saved layout must not be wiped. Pinned by `pruneMissingSkipsWhenNoAppsKnown` in Task 5.
2. **Same app in two profiles** (Samsung Dual Messenger, work profile) → uninstalling one copy removes only that user's icon. Pinned by `removePackageOnlyAffectsThatUser` in Task 5.
3. **Adding to a full page** → no overlap, no silent drop; the user is offered a new page. Pinned by `addItemReturnsNullWhenPageFull` / `addPageThenAddFits` in Task 5 and the prompt in Task 8.
4. **Corrupt or hand-edited layout file** (truncated JSON, overlapping items, out-of-bounds cells, unknown version) → falls back to the default layout and keeps the bad file for inspection. Pinned by the `LayoutStoreTest` cases in Task 5.
5. **Uninstalling a widget's provider app** → the widget disappears and its id is freed (no orphaned widget ids). Pinned by `removePackageDropsWidgetsAndReturnsIds` in Task 5.

---

## File Structure

```
OpenCover/
  settings.gradle.kts, build.gradle.kts, gradle.properties, gradlew(.bat), gradle/wrapper/*
  LICENSE                                   GPL-3.0 text
  scripts/run.ps1                           build → install → launch on cover → screenshot
  app/build.gradle.kts
  app/src/main/AndroidManifest.xml
  app/src/main/java/dev/opencover/
    Cover.kt                                COVER_DISPLAY, SAMSUNG_COVER_HOME
    grid/GridEngine.kt                      Cell, GRID, canPlace, firstFreeSpot, cellSpan (pure)
    layout/Layout.kt                        Item/AppItem/WidgetItem/Page/Layout + edit ops (pure)
    layout/LayoutJson.kt                    encode/decode + validation (org.json)
    layout/LayoutStore.kt                   load-with-fallback, atomic save (java.io)
    apps/AppEntry.kt                        AppEntry, Sort, filterApps, letterIndex (pure)
    apps/AppLauncher.kt                     launch apps / our screens on display 1
    apps/AppRepository.kt                   LauncherApps + UsageStats + icon cache
    widgets/WidgetHost.kt                   AppWidgetHost: bind → configure → place, views
    watcher/CoverWatcher.kt                 foreground service: auto-open + Home redirect
    watcher/BootReceiver.kt                 starts CoverWatcher on boot
    watcher/KeepAwake.kt                    1px overlay with FLAG_KEEP_SCREEN_ON on display 1
    ui/CoverActivity.kt                     owns state, wires everything
    ui/LauncherScreen.kt                    pager + edit bar + overlays + page-full prompt
    ui/GridPage.kt                          one 4×4 page; tap/launch, drag/resize/remove in edit
    ui/AppIcon.kt                           icon + label cell
    ui/AppDrawer.kt                         search, sort, letter index
    ui/WidgetPicker.kt                      list of widget providers
    ui/SettingsPanel.kt                     keep-awake, Samsung widgets
    ui/Checklist.kt                         first-run permissions + Permissions object
  app/src/test/java/dev/opencover/
    grid/GridEngineTest.kt
    layout/LayoutTest.kt
    layout/LayoutStoreTest.kt
    apps/AppEntryTest.kt
```

---

### Task 1: Project scaffold, run script, blank screen on the cover

**Files:**
- Delete: `spike/` (its finding is recorded in the spec)
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `local.properties` (git-ignored), `LICENSE`, `scripts/run.ps1`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/opencover/Cover.kt`, `app/src/main/java/dev/opencover/apps/AppLauncher.kt`, `app/src/main/java/dev/opencover/ui/CoverActivity.kt`
- Generated: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

**Interfaces:**
- Produces: `const val COVER_DISPLAY: Int`, `const val SAMSUNG_COVER_HOME: String` (package `dev.opencover`); `object AppLauncher { fun options(): Bundle; fun start(ctx: Context, intent: Intent); fun launch(ctx: Context, pkg: String, activity: String, user: Long) }` (package `dev.opencover.apps`); `class CoverActivity : ComponentActivity` (package `dev.opencover.ui`); `scripts/run.ps1` (writes `build/cover.png`).

- [ ] **Step 1: Remove the spike and bootstrap the Gradle wrapper**

```powershell
Set-Location "D:\TenTne Projects\OpenCover"
git rm -r -q spike
Set-Content settings.gradle.kts -Encoding utf8 -Value 'rootProject.name = "OpenCover"'
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$z = "$env:TEMP\gradle-9.8.1-bin.zip"
Invoke-WebRequest https://services.gradle.org/distributions/gradle-9.8.1-bin.zip -OutFile $z
Expand-Archive $z -DestinationPath "$env:TEMP\gradle-dist" -Force
& "$env:TEMP\gradle-dist\gradle-9.8.1\bin\gradle.bat" wrapper --gradle-version 9.8.1
```

Expected: `gradlew.bat` and `gradle/wrapper/` exist.

- [ ] **Step 2: Write the build files**

`settings.gradle.kts` (overwrite):
```kotlin
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "OpenCover"
include(":app")
```

`build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2g
android.useAndroidX=true
```

`local.properties` (already covered by `.gitignore`):
```properties
sdk.dir=C\:\\Users\\naomi\\AppData\\Local\\Android\\Sdk
```

`app/build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.opencover"
    compileSdk = 37
    defaultConfig {
        applicationId = "dev.opencover"
        minSdk = 34
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
```

`LICENSE`: download the GPL-3.0 text:
```powershell
Invoke-WebRequest https://www.gnu.org/licenses/gpl-3.0.txt -OutFile LICENSE
```

- [ ] **Step 3: Write the manifest and the minimal app code**

`app/src/main/AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.PACKAGE_USAGE_STATS"
        tools:ignore="ProtectedPermissions" />
    <uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

    <!-- Package visibility: see every launchable app and every widget provider. -->
    <queries>
        <intent>
            <action android:name="android.intent.action.MAIN" />
            <category android:name="android.intent.category.LAUNCHER" />
        </intent>
        <intent>
            <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
        </intent>
    </queries>

    <application
        android:label="OpenCover"
        android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
        <activity
            android:name=".ui.CoverActivity"
            android:exported="true"
            android:launchMode="singleTask"
            android:resizeableActivity="true"
            android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|density|keyboardHidden">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/java/dev/opencover/Cover.kt`:
```kotlin
package dev.opencover

/** Logical display id of the Z Flip 5 cover screen. */
const val COVER_DISPLAY = 1

/** Samsung's own cover-screen home; seeing it resumed means the user swiped Home. */
const val SAMSUNG_COVER_HOME = "com.android.systemui.subscreen.SubHomeActivity"
```

`app/src/main/java/dev/opencover/apps/AppLauncher.kt`:
```kotlin
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

object AppLauncher {
    fun options(): Bundle =
        ActivityOptions.makeBasic().setLaunchDisplayId(COVER_DISPLAY).toBundle()

    /** Starts one of our own screens (or a Settings page) on the cover display. */
    fun start(ctx: Context, intent: Intent) {
        ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), options())
    }

    /** Launches an installed app on the cover display as the given user (serial number). */
    fun launch(ctx: Context, pkg: String, activity: String, user: Long) {
        val handle = ctx.getSystemService(UserManager::class.java).getUserForSerialNumber(user)
        try {
            requireNotNull(handle) { "user $user gone" }
            ctx.getSystemService(LauncherApps::class.java)
                .startMainActivity(ComponentName(pkg, activity), handle, null, options())
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't open $pkg", Toast.LENGTH_SHORT).show()
        }
    }
}
```

`app/src/main/java/dev/opencover/ui/CoverActivity.kt`:
```kotlin
package dev.opencover.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppLauncher

class CoverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The main screen is broken: if we were opened anywhere else, move to the cover.
        if (display?.displayId != COVER_DISPLAY) {
            AppLauncher.start(this, Intent(this, CoverActivity::class.java))
            finish()
            return
        }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("OpenCover", color = Color.White)
            }
        }
    }
}
```

- [ ] **Step 4: Write the run script**

`scripts/run.ps1`:
```powershell
# Build, install, open on the cover screen, and save a cover screenshot to build/cover.png.
param([switch]$NoBuild)
$ErrorActionPreference = 'Stop'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
$env:ANDROID_SERIAL = 'R5CW71QQAGW'
$root = Split-Path $PSScriptRoot
if (-not $NoBuild) {
    & "$root\gradlew.bat" -p $root :app:assembleDebug
    if ($LASTEXITCODE) { exit 1 }
}
adb install -r "$root\app\build\outputs\apk\debug\app-debug.apk"
adb shell am start --display 1 -n dev.opencover/.ui.CoverActivity
Start-Sleep 2
New-Item -ItemType Directory -Force "$root\build" | Out-Null
adb shell screencap -d 4630947181303254916 -p /sdcard/oc.png
adb pull /sdcard/oc.png "$root\build\cover.png" | Out-Null
"screenshot: $root\build\cover.png"
```

- [ ] **Step 5: Build**

Run: `$env:JAVA_HOME="C:\Program Files\Android\Android Studio\jbr"; .\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`.

If it fails with "Failed to find target android-37" or a platform/SDK resolution error: change `compileSdk = 37` to `compileSdk = 36` (AGP downloads android-36 itself because the SDK licenses are already accepted), record that in the spec's Decisions table, and rebuild.
If it fails because the Compose compiler plugin and the Kotlin version don't match: add `id("org.jetbrains.kotlin.android") version "2.4.21" apply false` to the root `build.gradle.kts` and `gradle.properties` line `android.builtInKotlin=false`, apply `id("org.jetbrains.kotlin.android")` in `app/build.gradle.kts`, and rebuild.

- [ ] **Step 6: Verify on the cover screen**

Ask the user to fold the phone and unlock the cover screen. Run: `.\scripts\run.ps1 -NoBuild`
Read `build/cover.png`. Expected: black screen with "OpenCover" centred.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Scaffold OpenCover app that opens on the cover display"
```

---

### Task 2: WidgetHost bind/configure flow + R1 probe (widget dialog on display 1)

**Files:**
- Create: `app/src/main/java/dev/opencover/widgets/WidgetHost.kt`
- Modify: `app/src/main/java/dev/opencover/ui/CoverActivity.kt`

**Interfaces:**
- Consumes: `AppLauncher.options()`.
- Produces:
  ```kotlin
  class WidgetHost(activity: Activity, onAdded: (widgetId: Int, info: AppWidgetProviderInfo, page: Int) -> Unit) {
      fun providers(): List<AppWidgetProviderInfo>
      fun find(flatProvider: String): AppWidgetProviderInfo?
      fun exists(widgetId: Int): Boolean
      fun delete(widgetId: Int)
      fun view(widgetId: Int): AppWidgetHostView?
      fun resize(view: AppWidgetHostView, widthDp: Float, heightDp: Float)
      fun startListening(); fun stopListening()
      fun begin(info: AppWidgetProviderInfo, page: Int)
      fun onResult(requestCode: Int, resultCode: Int): Boolean
      fun cancel()
  }
  ```

- [ ] **Step 1: Write WidgetHost**

`app/src/main/java/dev/opencover/widgets/WidgetHost.kt`:
```kotlin
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
```

- [ ] **Step 2: Add a temporary probe hook to CoverActivity**

In `CoverActivity`, add a field and wire it. Replace the class body with:
```kotlin
class CoverActivity : ComponentActivity() {
    private lateinit var widgets: WidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (display?.displayId != COVER_DISPLAY) {
            AppLauncher.start(this, Intent(this, CoverActivity::class.java))
            finish()
            return
        }
        widgets = WidgetHost(this) { id, info, _ ->
            Toast.makeText(this, "PROBE widget $id added: ${info.provider}", Toast.LENGTH_LONG).show()
            Log.i("OCPROBE", "added $id ${info.provider}")
        }
        // PROBE (removed in Task 9): adb ... --es probeWidget <pkg/cls>
        intent.getStringExtra("probeWidget")?.let { flat ->
            widgets.find(flat)?.let { widgets.begin(it, 0) } ?: Log.e("OCPROBE", "no provider $flat")
        }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("OpenCover", color = Color.White)
            }
        }
    }

    @Deprecated("Widget flows need startActivityForResult with display options")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }
}
```
Add imports: `android.util.Log`, `android.widget.Toast`, `dev.opencover.widgets.WidgetHost`.

- [ ] **Step 3: Build, install, and pick a widget provider**

Run: `.\scripts\run.ps1`
Then: `$env:ANDROID_SERIAL="R5CW71QQAGW"; adb shell "dumpsys appwidget | grep -o 'provider=ComponentInfo{[^}]*}' | sort -u | head -20"`
Pick a provider without a configure screen (a clock widget is ideal), e.g. `com.sec.android.app.clockpackage/...`. Call it `<P>`.

- [ ] **Step 4: Run the R1 probe**

Ask the user to fold and unlock the cover screen. Then:
```powershell
$env:ANDROID_SERIAL="R5CW71QQAGW"; adb shell am force-stop dev.opencover; adb logcat -c
adb shell am start --display 1 -n dev.opencover/.ui.CoverActivity --es probeWidget "<P>"
Start-Sleep 2; adb shell screencap -d 4630947181303254916 -p /sdcard/oc.png; adb pull /sdcard/oc.png build\cover.png
adb shell "dumpsys activity activities | grep -E 'Display #|topResumedActivity'"
```
Read `build/cover.png`.
- **Pass:** the "Allow OpenCover to create widgets…" dialog is on the cover screen (screenshot, and `topResumedActivity` for display 1 is the bind dialog). Ask the user to tap Allow (tick "always"), then confirm `adb logcat -d -s OCPROBE` shows `added`.
- **Fail (dialog on display 0 or not visible):** apply the fallback: `adb shell appwidget grantbind --package dev.opencover --user 0`, rerun the probe, confirm `OCPROBE added` with no dialog. Add a line to the spec's First-run section: "Run once over ADB: `adb shell appwidget grantbind --package dev.opencover --user 0`".

Record the result (Pass / Fallback) in the spec's Risks table, R1 row, as a new column "Result".

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add WidgetHost bind/configure flow; verify widget bind on cover (R1)"
```

---

### Task 3: CoverWatcher: auto-open on unlock (R3) and Home redirect (R2)

**Files:**
- Create: `app/src/main/java/dev/opencover/watcher/CoverWatcher.kt`, `app/src/main/java/dev/opencover/watcher/BootReceiver.kt`, `app/src/main/java/dev/opencover/watcher/KeepAwake.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/opencover/ui/CoverActivity.kt`

**Interfaces:**
- Consumes: `COVER_DISPLAY`, `SAMSUNG_COVER_HOME`, `AppLauncher.start`.
- Produces: `class CoverWatcher : Service` with `companion object { @Volatile var paused: Boolean }`; `object KeepAwake { val isOn: Boolean; fun set(ctx: Context, on: Boolean) }`; `class BootReceiver : BroadcastReceiver`.

- [ ] **Step 1: Write KeepAwake** (CoverWatcher turns it off when the phone opens; the switch arrives in Task 10)

`app/src/main/java/dev/opencover/watcher/KeepAwake.kt`:
```kotlin
package dev.opencover.watcher

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.view.View
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import dev.opencover.COVER_DISPLAY

/** Keeps the cover screen on in any app via an invisible 1x1 overlay with FLAG_KEEP_SCREEN_ON. */
object KeepAwake {
    private var view: View? = null
    val isOn get() = view != null

    fun set(ctx: Context, on: Boolean) {
        if (on == isOn) return
        if (!on) {
            view?.let { it.context.getSystemService(WindowManager::class.java).removeView(it) }
            view = null
            return
        }
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        val wctx = ctx.applicationContext.createDisplayContext(display)
            .createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val v = View(wctx)
        val params = LayoutParams(
            1, 1, LayoutParams.TYPE_APPLICATION_OVERLAY,
            LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSPARENT,
        )
        wctx.getSystemService(WindowManager::class.java).addView(v, params)
        view = v
    }
}
```

- [ ] **Step 2: Write CoverWatcher and BootReceiver**

`app/src/main/java/dev/opencover/watcher/CoverWatcher.kt`:
```kotlin
package dev.opencover.watcher

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import dev.opencover.COVER_DISPLAY
import dev.opencover.SAMSUNG_COVER_HOME
import dev.opencover.apps.AppLauncher
import dev.opencover.ui.CoverActivity

/**
 * Opens the launcher when the cover screen becomes usable, and brings it back whenever
 * Samsung's cover home is resumed (the user swiped Home) unless [paused].
 */
class CoverWatcher : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displays: DisplayManager
    private lateinit var usage: UsageStatsManager
    private lateinit var keyguard: KeyguardManager
    private var lastSeen = 0L
    private var polling = false
    private var wasActive = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) = onCoverStateMaybeChanged()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_USER_PRESENT -> if (coverActive()) openLauncher("unlock")
                Intent.ACTION_SCREEN_OFF -> paused = false
            }
            onCoverStateMaybeChanged()
        }
    }

    private val poll = object : Runnable {
        override fun run() {
            if (!coverActive()) {
                polling = false
                return
            }
            if (!paused && samsungHomeResumed()) openLauncher("home")
            handler.postDelayed(this, 300)
        }
    }

    override fun onCreate() {
        super.onCreate()
        displays = getSystemService(DisplayManager::class.java)
        usage = getSystemService(UsageStatsManager::class.java)
        keyguard = getSystemService(KeyguardManager::class.java)
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        displays.registerDisplayListener(displayListener, handler)
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        lastSeen = System.currentTimeMillis()
        onCoverStateMaybeChanged()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY
    override fun onBind(intent: Intent?) = null

    override fun onDestroy() {
        displays.unregisterDisplayListener(displayListener)
        unregisterReceiver(receiver)
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun coverActive() =
        displays.getDisplay(COVER_DISPLAY)?.state == Display.STATE_ON &&
            displays.getDisplay(Display.DEFAULT_DISPLAY)?.state != Display.STATE_ON

    private fun onCoverStateMaybeChanged() {
        val active = coverActive()
        if (active && !wasActive && !keyguard.isKeyguardLocked) openLauncher("fold")
        if (displays.getDisplay(Display.DEFAULT_DISPLAY)?.state == Display.STATE_ON) KeepAwake.set(this, false)
        wasActive = active
        if (active && !polling) {
            polling = true
            lastSeen = System.currentTimeMillis()
            handler.post(poll)
        }
    }

    private fun samsungHomeResumed(): Boolean {
        val events = usage.queryEvents(lastSeen, System.currentTimeMillis())
        val e = UsageEvents.Event()
        var top: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.ACTIVITY_RESUMED) top = e.className
            lastSeen = maxOf(lastSeen, e.timeStamp + 1)
        }
        if (top != null) Log.d(TAG, "resumed $top")
        return top == SAMSUNG_COVER_HOME
    }

    private fun openLauncher(why: String) {
        Log.i(TAG, "open launcher ($why)")
        AppLauncher.start(this, Intent(this, CoverActivity::class.java))
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Cover launcher", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("OpenCover is running")
            .build()
    }

    companion object {
        /** Set by "Open Samsung widgets"; cleared when the screen turns off. */
        @Volatile var paused = false
        private const val CHANNEL = "watcher"
        private const val TAG = "CoverWatcher"
    }
}
```

`app/src/main/java/dev/opencover/watcher/BootReceiver.kt`:
```kotlin
package dev.opencover.watcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            context.startForegroundService(Intent(context, CoverWatcher::class.java))
        }
    }
}
```

- [ ] **Step 3: Register them in the manifest and start the service**

In `AndroidManifest.xml`, add after the existing `<uses-permission>` lines:
```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```
and inside `<application>`, after the `<activity>`:
```xml
        <service
            android:name=".watcher.CoverWatcher"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="Keeps the cover-screen launcher in front when the user presses Home" />
        </service>
        <receiver android:name=".watcher.BootReceiver" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```

In `CoverActivity.onCreate`, right after `widgets = WidgetHost(...) { ... }`, add:
```kotlin
        startForegroundService(Intent(this, CoverWatcher::class.java))
```
with import `dev.opencover.watcher.CoverWatcher`.

- [ ] **Step 4: Build, install, grant permissions over ADB**

```powershell
.\scripts\run.ps1
$env:ANDROID_SERIAL="R5CW71QQAGW"
adb shell appops set dev.opencover GET_USAGE_STATS allow
adb shell appops set dev.opencover SYSTEM_ALERT_WINDOW allow
adb shell pm grant dev.opencover android.permission.POST_NOTIFICATIONS
```
(Task 11 builds the on-screen checklist for these; ADB is used here so the risk checks come first.)

- [ ] **Step 5: Verify R3: auto-open on unlock**

```powershell
adb logcat -c
```
Ask the user: with the phone folded, open any other app on the cover screen (e.g. via MultiStar), lock the phone with the side key, then unlock the cover screen.
Then:
```powershell
adb logcat -d -s CoverWatcher
adb shell "dumpsys activity activities | grep topResumedActivity"
```
- **Pass:** log shows `open launcher (unlock)` and display 1's `topResumedActivity` is `dev.opencover/.ui.CoverActivity`.
- **Fail** (log line present but activity not in front → background start blocked): stop. Report to the user; the spec's R3 fallback (MultiStar tile) needs a design change before continuing.

- [ ] **Step 6: Verify R2: Home redirect**

```powershell
adb logcat -c
adb shell am start --display 1 -n com.android.chrome/com.google.android.apps.chrome.Main
```
Ask the user to swipe Home on the cover screen once. Then:
```powershell
adb logcat -d -v time -s CoverWatcher
```
- **Pass:** a `resumed com.android.systemui.subscreen.SubHomeActivity` line followed by `open launcher (home)` within 500 ms (compare timestamps), and the user sees OpenCover.
- **Fail** (no `SubHomeActivity` line, or the class name differs): if a different class name appears for Samsung's cover home, update `SAMSUNG_COVER_HOME` in `Cover.kt` and re-test. If no resume event appears at all, stop and report: the R2 fallback (AccessibilityService) needs a spec change.

Record R2 and R3 results in the spec's Risks table "Result" column.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "Add CoverWatcher: auto-open on unlock (R3) and Home redirect (R2)"
```

---

### Task 4: GridEngine (pure geometry)

**Files:**
- Create: `app/src/main/java/dev/opencover/grid/GridEngine.kt`
- Test: `app/src/test/java/dev/opencover/grid/GridEngineTest.kt`

**Interfaces:**
- Produces (package `dev.opencover.grid`):
  ```kotlin
  const val GRID: Int = 4
  data class Cell(val x: Int, val y: Int, val w: Int, val h: Int) { fun overlaps(o: Cell): Boolean; fun inBounds(): Boolean }
  object GridEngine {
      fun <K> canPlace(occupied: Map<K, Cell>, cell: Cell, ignoreId: K? = null): Boolean
      fun <K> firstFreeSpot(occupied: Map<K, Cell>, w: Int, h: Int): Cell?
  }
  fun cellSpan(targetCells: Int, minDp: Int, cellDp: Int): Int
  ```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/opencover/grid/GridEngineTest.kt`:
```kotlin
package dev.opencover.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridEngineTest {
    private val occ = mapOf("a" to Cell(0, 0, 2, 2))
    private val none = emptyMap<String, Cell>()

    @Test fun placesInFreeSpace() = assertTrue(GridEngine.canPlace(occ, Cell(2, 0, 2, 2)))
    @Test fun rejectsOverlap() = assertFalse(GridEngine.canPlace(occ, Cell(1, 1, 1, 1)))
    @Test fun touchingEdgesIsNotOverlap() = assertTrue(GridEngine.canPlace(occ, Cell(2, 1, 1, 1)))
    @Test fun ignoresItselfWhenMoving() = assertTrue(GridEngine.canPlace(occ, Cell(1, 1, 2, 2), ignoreId = "a"))

    @Test fun rejectsOutOfBounds() {
        assertFalse(GridEngine.canPlace(none, Cell(3, 0, 2, 1)))
        assertFalse(GridEngine.canPlace(none, Cell(0, 3, 1, 2)))
        assertFalse(GridEngine.canPlace(none, Cell(-1, 0, 1, 1)))
        assertFalse(GridEngine.canPlace(none, Cell(0, 0, 0, 1)))
    }

    @Test fun firstFreeSpotIsRowMajor() = assertEquals(Cell(2, 0, 1, 1), GridEngine.firstFreeSpot(occ, 1, 1))
    @Test fun firstFreeSpotFindsRoomForWideItem() = assertEquals(Cell(0, 2, 4, 2), GridEngine.firstFreeSpot(occ, 4, 2))

    @Test fun fullPageHasNoSpot() {
        val full = (0 until 16).associate { "$it" to Cell(it % 4, it / 4, 1, 1) }
        assertNull(GridEngine.firstFreeSpot(full, 1, 1))
    }

    @Test fun tooBigHasNoSpot() = assertNull(GridEngine.firstFreeSpot(none, 5, 1))
    @Test fun cellSpanPrefersTargetCells() = assertEquals(2, cellSpan(targetCells = 2, minDp = 10, cellDp = 88))
    @Test fun cellSpanRoundsMinSizeUp() = assertEquals(2, cellSpan(targetCells = 0, minDp = 89, cellDp = 88))

    @Test fun cellSpanClampsToGrid() {
        assertEquals(4, cellSpan(targetCells = 0, minDp = 1000, cellDp = 88))
        assertEquals(4, cellSpan(targetCells = 6, minDp = 0, cellDp = 88))
        assertEquals(1, cellSpan(targetCells = 0, minDp = 0, cellDp = 88))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.grid.*"`
Expected: compilation FAIL, `Unresolved reference 'Cell'`.

- [ ] **Step 3: Implement**

`app/src/main/java/dev/opencover/grid/GridEngine.kt`:
```kotlin
package dev.opencover.grid

/** Cells per row and per column on every page. */
const val GRID = 4

data class Cell(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(o: Cell) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    fun inBounds() = x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= GRID && y + h <= GRID
}

object GridEngine {
    /** True if [cell] is on the grid and overlaps nothing in [occupied] except [ignoreId]. */
    fun <K> canPlace(occupied: Map<K, Cell>, cell: Cell, ignoreId: K? = null): Boolean =
        cell.inBounds() && occupied.none { (id, c) -> id != ignoreId && c.overlaps(cell) }

    /** Row-major scan for the first free w×h spot; null if none. */
    fun <K> firstFreeSpot(occupied: Map<K, Cell>, w: Int, h: Int): Cell? {
        for (y in 0..GRID - h) for (x in 0..GRID - w) {
            val c = Cell(x, y, w, h)
            if (canPlace(occupied, c)) return c
        }
        return null
    }
}

/** Cells a widget should span: its target cell count if it declares one, else its min size rounded up. */
fun cellSpan(targetCells: Int, minDp: Int, cellDp: Int): Int =
    (if (targetCells > 0) targetCells else (minDp + cellDp - 1) / cellDp).coerceIn(1, GRID)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.grid.*"`
Expected: PASS (12 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/grid app/src/test/java/dev/opencover/grid
git commit -m "Add GridEngine with placement, free-spot search and widget cell spans"
```

---

### Task 5: Layout model, JSON, and LayoutStore

**Files:**
- Create: `app/src/main/java/dev/opencover/apps/AppEntry.kt` (only the `AppEntry` class in this task; Task 6 adds the rest), `app/src/main/java/dev/opencover/layout/Layout.kt`, `app/src/main/java/dev/opencover/layout/LayoutJson.kt`, `app/src/main/java/dev/opencover/layout/LayoutStore.kt`
- Test: `app/src/test/java/dev/opencover/layout/LayoutTest.kt`, `app/src/test/java/dev/opencover/layout/LayoutStoreTest.kt`

**Interfaces:**
- Consumes: `Cell`, `GRID`, `GridEngine` (Task 4).
- Produces:
  ```kotlin
  // dev.opencover.apps
  data class AppEntry(val label: String, val pkg: String, val activity: String, val user: Long, val lastUsed: Long) { val key: String }
  // dev.opencover.layout
  sealed interface Item { val id: String; val cell: Cell }
  data class AppItem(id, cell, pkg: String, activity: String, user: Long) : Item { val key: String }
  data class WidgetItem(id, cell, widgetId: Int, provider: String) : Item { val providerPkg: String }
  data class Page(val items: List<Item>)
  data class Layout(val pages: List<Page>)
  fun appKey(pkg: String, activity: String, user: Long): String
  fun newId(): String
  fun Item.withCell(c: Cell): Item
  fun Page.occupied(): Map<String, Cell>
  fun Layout.moveItem(page: Int, id: String, x: Int, y: Int): Layout?
  fun Layout.resizeItem(page: Int, id: String, w: Int, h: Int): Layout?
  fun Layout.addItem(page: Int, w: Int, h: Int, make: (Cell) -> Item): Layout?
  fun Layout.addPage(): Layout
  fun Layout.removePage(page: Int): Layout?
  fun Layout.removeItem(id: String): Layout
  fun Layout.retain(keep: (Item) -> Boolean): Pair<Layout, List<Int>>
  fun Layout.removePackage(pkg: String, user: Long): Pair<Layout, List<Int>>
  fun Layout.pruneMissing(installedKeys: Set<String>, widgetExists: (Int) -> Boolean): Pair<Layout, List<Int>>
  fun defaultLayout(apps: List<AppEntry>): Layout
  object LayoutJson { fun encode(l: Layout): String; fun decode(s: String): Layout }
  class LayoutStore(dir: File) { fun load(fallback: () -> Layout): Layout; fun save(l: Layout) }
  ```
  `retain`/`removePackage`/`pruneMissing` return the widget ids that must be freed with `WidgetHost.delete`.

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/opencover/layout/LayoutTest.kt`:
```kotlin
package dev.opencover.layout

import dev.opencover.apps.AppEntry
import dev.opencover.grid.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun app(id: String, cell: Cell, pkg: String = "p.$id", user: Long = 0) = AppItem(id, cell, pkg, "$pkg.Main", user)
    private val clock = WidgetItem("w", Cell(0, 2, 4, 2), widgetId = 7, provider = "com.clock/.Widget")
    private val base = Layout(listOf(Page(listOf(app("a", Cell(0, 0, 1, 1)), app("b", Cell(1, 0, 1, 1)), clock))))

    @Test fun moveToFreeCell() {
        val moved = base.moveItem(0, "a", 3, 0)!!
        assertEquals(Cell(3, 0, 1, 1), moved.pages[0].items.first { it.id == "a" }.cell)
    }

    @Test fun moveOntoOccupiedIsRejected() = assertNull(base.moveItem(0, "a", 1, 0))
    @Test fun moveOffGridIsRejected() = assertNull(base.moveItem(0, "a", 4, 0))
    @Test fun moveUnknownIdIsRejected() = assertNull(base.moveItem(0, "zzz", 2, 0))

    @Test fun resizeWidgetIntoFreeSpace() {
        val r = base.resizeItem(0, "w", 4, 1)!!
        assertEquals(Cell(0, 2, 4, 1), r.pages[0].items.first { it.id == "w" }.cell)
    }

    @Test fun resizeIntoNeighbourIsRejected() = assertNull(base.resizeItem(0, "a", 2, 1))

    @Test fun addItemUsesFirstFreeSpot() {
        val l = base.addItem(0, 1, 1) { app("c", it) }!!
        assertEquals(Cell(2, 0, 1, 1), l.pages[0].items.last().cell)
    }

    @Test fun addItemReturnsNullWhenPageFull() {
        val full = Layout(listOf(Page((0 until 16).map { app("$it", Cell(it % 4, it / 4, 1, 1)) })))
        assertNull(full.addItem(0, 1, 1) { app("x", it) })
    }

    @Test fun addPageThenAddFits() {
        val full = Layout(listOf(Page((0 until 16).map { app("$it", Cell(it % 4, it / 4, 1, 1)) })))
        val withPage = full.addPage()
        val l = withPage.addItem(withPage.pages.lastIndex, 2, 2) { app("x", it) }
        assertNotNull(l)
        assertEquals(2, l!!.pages.size)
    }

    @Test fun removePageOnlyWhenEmptyAndNotLast() {
        assertNull(base.removePage(0))                 // last page stays
        val two = base.addPage()
        assertNull(two.removePage(0))                  // not empty
        assertEquals(1, two.removePage(1)!!.pages.size)
    }

    @Test fun removeItem() = assertEquals(2, base.removeItem("a").pages[0].items.size)

    @Test fun removePackageDropsWidgetsAndReturnsIds() {
        val (l, freed) = base.removePackage("com.clock", user = 0)
        assertEquals(listOf(7), freed)
        assertTrue(l.pages[0].items.none { it.id == "w" })
    }

    @Test fun removePackageOnlyAffectsThatUser() {
        val dual = Layout(listOf(Page(listOf(
            app("main", Cell(0, 0, 1, 1), pkg = "chat", user = 0),
            app("dual", Cell(1, 0, 1, 1), pkg = "chat", user = 95),
        ))))
        val (l, _) = dual.removePackage("chat", user = 95)
        assertEquals(listOf("main"), l.pages[0].items.map { it.id })
    }

    @Test fun pruneMissingDropsUninstalledAndDeadWidgets() {
        val keys = setOf(appKey("p.a", "p.a.Main", 0))
        val (l, freed) = base.pruneMissing(keys) { false }
        assertEquals(listOf("a"), l.pages[0].items.map { it.id })
        assertEquals(listOf(7), freed)
    }

    @Test fun pruneMissingSkipsWhenNoAppsKnown() {
        val (l, freed) = base.pruneMissing(emptySet()) { false }
        assertEquals(base, l)
        assertTrue(freed.isEmpty())
    }

    @Test fun defaultLayoutTakesEightMostRecent() {
        val apps = (1..10).map { AppEntry("App$it", "p$it", "p$it.Main", 0, lastUsed = it.toLong()) }
        val items = defaultLayout(apps).pages.single().items.map { it as AppItem }
        assertEquals(8, items.size)
        assertEquals("p10", items[0].pkg)
        assertEquals(Cell(3, 1, 1, 1), items[7].cell)
    }

    @Test fun jsonRoundTrip() = assertEquals(base, LayoutJson.decode(LayoutJson.encode(base)))
}
```

`app/src/test/java/dev/opencover/layout/LayoutStoreTest.kt`:
```kotlin
package dev.opencover.layout

import dev.opencover.grid.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LayoutStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fallback = Layout(listOf(Page(emptyList())))
    private val sample = Layout(listOf(Page(listOf(AppItem("a", Cell(1, 1, 1, 1), "p", "p.Main", 0)))))

    private fun storeWith(content: String?): LayoutStore {
        if (content != null) File(tmp.root, "layout.json").writeText(content)
        return LayoutStore(tmp.root)
    }

    @Test fun missingFileUsesFallback() = assertEquals(fallback, storeWith(null).load { fallback })

    @Test fun saveThenLoad() {
        val s = storeWith(null)
        s.save(sample)
        s.save(sample) // overwriting an existing file must work too
        assertEquals(sample, s.load { fallback })
    }

    private fun assertFallsBack(content: String) {
        assertEquals(fallback, storeWith(content).load { fallback })
        assertTrue(File(tmp.root, "layout.bad.json").exists())
    }

    @Test fun truncatedJsonFallsBack() = assertFallsBack("""{"version":1,"pages":[{"items":[""")
    @Test fun unknownVersionFallsBack() = assertFallsBack("""{"version":2,"pages":[{"items":[]}]}""")
    @Test fun noPagesFallsBack() = assertFallsBack("""{"version":1,"pages":[]}""")

    @Test fun outOfBoundsFallsBack() = assertFallsBack(
        """{"version":1,"pages":[{"items":[{"id":"a","type":"app","x":3,"y":0,"w":2,"h":1,"package":"p","activity":"p.M","user":0}]}]}"""
    )

    @Test fun overlapFallsBack() = assertFallsBack(
        """{"version":1,"pages":[{"items":[
          {"id":"a","type":"app","x":0,"y":0,"w":2,"h":2,"package":"p","activity":"p.M","user":0},
          {"id":"b","type":"app","x":1,"y":1,"w":1,"h":1,"package":"q","activity":"q.M","user":0}]}]}"""
    )

    @Test fun unknownItemTypeFallsBack() = assertFallsBack(
        """{"version":1,"pages":[{"items":[{"id":"a","type":"folder","x":0,"y":0,"w":1,"h":1}]}]}"""
    )
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.layout.*"`
Expected: compilation FAIL, `Unresolved reference 'AppItem'`.

- [ ] **Step 3: Implement**

`app/src/main/java/dev/opencover/apps/AppEntry.kt`:
```kotlin
package dev.opencover.apps

import dev.opencover.layout.appKey

/** One launchable activity. [user] is the profile's serial number; [lastUsed] is epoch ms (0 = unknown). */
data class AppEntry(val label: String, val pkg: String, val activity: String, val user: Long, val lastUsed: Long) {
    val key get() = appKey(pkg, activity, user)
}
```

`app/src/main/java/dev/opencover/layout/Layout.kt`:
```kotlin
package dev.opencover.layout

import dev.opencover.apps.AppEntry
import dev.opencover.grid.Cell
import dev.opencover.grid.GRID
import dev.opencover.grid.GridEngine
import java.util.UUID

sealed interface Item {
    val id: String
    val cell: Cell
}

data class AppItem(
    override val id: String,
    override val cell: Cell,
    val pkg: String,
    val activity: String,
    val user: Long,
) : Item {
    val key get() = appKey(pkg, activity, user)
}

data class WidgetItem(
    override val id: String,
    override val cell: Cell,
    val widgetId: Int,
    val provider: String,
) : Item {
    val providerPkg get() = provider.substringBefore('/')
}

data class Page(val items: List<Item>)
data class Layout(val pages: List<Page>)

fun appKey(pkg: String, activity: String, user: Long) = "$user/$pkg/$activity"
fun newId() = UUID.randomUUID().toString()

fun Item.withCell(c: Cell): Item = when (this) {
    is AppItem -> copy(cell = c)
    is WidgetItem -> copy(cell = c)
}

fun Page.occupied(): Map<String, Cell> = items.associate { it.id to it.cell }

private fun Layout.withPage(i: Int, p: Page) = copy(pages = pages.toMutableList().also { it[i] = p })

private fun Layout.reshape(page: Int, id: String, f: (Cell) -> Cell): Layout? {
    val p = pages.getOrNull(page) ?: return null
    val item = p.items.find { it.id == id } ?: return null
    val target = f(item.cell)
    if (!GridEngine.canPlace(p.occupied(), target, ignoreId = id)) return null
    return withPage(page, p.copy(items = p.items.map { if (it.id == id) it.withCell(target) else it }))
}

/** Null means rejected (off the grid or overlapping); keep the old layout. */
fun Layout.moveItem(page: Int, id: String, x: Int, y: Int) = reshape(page, id) { it.copy(x = x, y = y) }
fun Layout.resizeItem(page: Int, id: String, w: Int, h: Int) = reshape(page, id) { it.copy(w = w, h = h) }

/** Places a w×h item at the first free spot on [page]; null if the page has no room. */
fun Layout.addItem(page: Int, w: Int, h: Int, make: (Cell) -> Item): Layout? {
    val p = pages.getOrNull(page) ?: return null
    val spot = GridEngine.firstFreeSpot(p.occupied(), w, h) ?: return null
    return withPage(page, p.copy(items = p.items + make(spot)))
}

fun Layout.addPage() = copy(pages = pages + Page(emptyList()))

/** Only an empty page can be removed, and the last page always stays. */
fun Layout.removePage(page: Int): Layout? =
    if (pages.size > 1 && pages.getOrNull(page)?.items?.isEmpty() == true) {
        copy(pages = pages.filterIndexed { i, _ -> i != page })
    } else null

fun Layout.removeItem(id: String) = copy(pages = pages.map { p -> p.copy(items = p.items.filter { it.id != id }) })

/** Keeps items matching [keep]. Returns the new layout and the widget ids of dropped widgets. */
fun Layout.retain(keep: (Item) -> Boolean): Pair<Layout, List<Int>> {
    val dropped = pages.flatMap { it.items }.filterNot(keep)
    val ids = dropped.map { it.id }.toSet()
    val l = copy(pages = pages.map { p -> p.copy(items = p.items.filter { it.id !in ids }) })
    return l to dropped.filterIsInstance<WidgetItem>().map { it.widgetId }
}

/** An app was uninstalled for [user]: drop its icons for that user and any widgets it provided. */
fun Layout.removePackage(pkg: String, user: Long) = retain {
    when (it) {
        is AppItem -> !(it.pkg == pkg && it.user == user)
        is WidgetItem -> it.providerPkg != pkg
    }
}

/** Drops icons for apps no longer installed and widgets that no longer exist.
 *  Does nothing if [installedKeys] is empty: an empty app list means loading failed, not "no apps". */
fun Layout.pruneMissing(installedKeys: Set<String>, widgetExists: (Int) -> Boolean): Pair<Layout, List<Int>> =
    if (installedKeys.isEmpty()) this to emptyList()
    else retain {
        when (it) {
            is AppItem -> it.key in installedKeys
            is WidgetItem -> widgetExists(it.widgetId)
        }
    }

/** First-run layout: one page with the 8 most recently used apps. */
fun defaultLayout(apps: List<AppEntry>): Layout {
    val top = apps.sortedByDescending { it.lastUsed }.take(2 * GRID)
    val items = top.mapIndexed { i, a -> AppItem(newId(), Cell(i % GRID, i / GRID, 1, 1), a.pkg, a.activity, a.user) }
    return Layout(listOf(Page(items)))
}
```

`app/src/main/java/dev/opencover/layout/LayoutJson.kt`:
```kotlin
package dev.opencover.layout

import dev.opencover.grid.Cell
import org.json.JSONArray
import org.json.JSONObject

object LayoutJson {
    fun encode(l: Layout): String = JSONObject()
        .put("version", 1)
        .put("pages", JSONArray(l.pages.map { p -> JSONObject().put("items", JSONArray(p.items.map(::encodeItem))) }))
        .toString()

    private fun encodeItem(i: Item): JSONObject {
        val o = JSONObject().put("id", i.id)
            .put("x", i.cell.x).put("y", i.cell.y).put("w", i.cell.w).put("h", i.cell.h)
        return when (i) {
            is AppItem -> o.put("type", "app").put("package", i.pkg).put("activity", i.activity).put("user", i.user)
            is WidgetItem -> o.put("type", "widget").put("widgetId", i.widgetId).put("provider", i.provider)
        }
    }

    /** Throws on anything malformed or invalid; LayoutStore turns that into a fallback. */
    fun decode(s: String): Layout {
        val root = JSONObject(s)
        require(root.getInt("version") == 1) { "unknown version" }
        val pagesJson = root.getJSONArray("pages")
        val pages = (0 until pagesJson.length()).map { pi ->
            val arr = pagesJson.getJSONObject(pi).getJSONArray("items")
            val items = (0 until arr.length()).map { decodeItem(arr.getJSONObject(it)) }
            items.forEachIndexed { a, ia ->
                require(ia.cell.inBounds()) { "item ${ia.id} off the grid" }
                items.drop(a + 1).forEach { ib -> require(!ia.cell.overlaps(ib.cell)) { "items ${ia.id}/${ib.id} overlap" } }
            }
            Page(items)
        }
        require(pages.isNotEmpty()) { "no pages" }
        return Layout(pages)
    }

    private fun decodeItem(o: JSONObject): Item {
        val c = Cell(o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"))
        val id = o.getString("id")
        return when (val type = o.getString("type")) {
            "app" -> AppItem(id, c, o.getString("package"), o.getString("activity"), o.getLong("user"))
            "widget" -> WidgetItem(id, c, o.getInt("widgetId"), o.getString("provider"))
            else -> throw IllegalArgumentException("unknown item type $type")
        }
    }
}
```

`app/src/main/java/dev/opencover/layout/LayoutStore.kt`:
```kotlin
package dev.opencover.layout

import java.io.File

/** Reads and writes filesDir/layout.json. A bad file is kept as layout.bad.json for inspection. */
class LayoutStore(private val dir: File) {
    private val file = File(dir, "layout.json")

    fun load(fallback: () -> Layout): Layout {
        if (!file.exists()) return fallback()
        return try {
            LayoutJson.decode(file.readText())
        } catch (e: Exception) {
            val bad = File(dir, "layout.bad.json")
            bad.delete()
            file.renameTo(bad)
            fallback()
        }
    }

    /** Write to a temp file, then rename over the original so a crash never leaves half a file. */
    fun save(l: Layout) {
        val tmp = File(dir, "layout.json.tmp")
        tmp.writeText(LayoutJson.encode(l))
        if (!tmp.renameTo(file)) { // Windows JVM (unit tests) can't rename over an existing file
            file.delete()
            tmp.renameTo(file)
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.layout.*"`
Expected: PASS (all LayoutTest and LayoutStoreTest cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/apps/AppEntry.kt app/src/main/java/dev/opencover/layout app/src/test/java/dev/opencover/layout
git commit -m "Add layout model, edit operations, JSON format and LayoutStore"
```

---

### Task 6: App list, filtering, and the app drawer

**Files:**
- Modify: `app/src/main/java/dev/opencover/apps/AppEntry.kt`
- Create: `app/src/main/java/dev/opencover/apps/AppRepository.kt`, `app/src/main/java/dev/opencover/ui/AppIcon.kt`, `app/src/main/java/dev/opencover/ui/AppDrawer.kt`
- Test: `app/src/test/java/dev/opencover/apps/AppEntryTest.kt`

**Interfaces:**
- Consumes: `AppEntry`, `appKey` (Task 5), `GRID` (Task 4).
- Produces:
  ```kotlin
  // dev.opencover.apps
  enum class Sort { AZ, RECENT }
  fun filterApps(apps: List<AppEntry>, query: String, sort: Sort): List<AppEntry>
  fun letterIndex(apps: List<AppEntry>): Map<Char, Int>
  class AppRepository(ctx: Context) {
      fun load(): List<AppEntry>
      fun serial(user: UserHandle): Long
      fun icon(pkg: String, activity: String, user: Long): ImageBitmap?
      fun forget(pkg: String)
      fun register(cb: LauncherApps.Callback); fun unregister(cb: LauncherApps.Callback)
  }
  // dev.opencover.ui
  @Composable fun AppIcon(label: String, icon: ImageBitmap?, modifier: Modifier = Modifier)
  @Composable fun AppDrawer(apps: List<AppEntry>, icon: (AppEntry) -> ImageBitmap?, onLaunch: (AppEntry) -> Unit, onAddToHome: (AppEntry) -> Unit, onClose: () -> Unit)
  ```

- [ ] **Step 1: Write the failing tests**

`app/src/test/java/dev/opencover/apps/AppEntryTest.kt`:
```kotlin
package dev.opencover.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppEntryTest {
    private fun e(label: String, used: Long = 0) = AppEntry(label, label.lowercase(), "x.Main", 0, used)
    private val apps = listOf(e("chrome", 5), e("Camera", 9), e("YouTube", 1), e("9GAG", 3))

    @Test fun azIgnoresCase() =
        assertEquals(listOf("9GAG", "Camera", "chrome", "YouTube"), filterApps(apps, "", Sort.AZ).map { it.label })

    @Test fun recentIsMostRecentFirst() =
        assertEquals(listOf("Camera", "chrome", "9GAG", "YouTube"), filterApps(apps, "", Sort.RECENT).map { it.label })

    @Test fun searchIsCaseInsensitiveSubstringAndTrimmed() =
        assertEquals(listOf("Camera", "chrome"), filterApps(apps, " C ", Sort.AZ).map { it.label })

    @Test fun letterIndexPointsAtFirstOfEachLetterAndGroupsNonLetters() {
        val sorted = filterApps(apps, "", Sort.AZ)
        assertEquals(mapOf('#' to 0, 'C' to 1, 'Y' to 3), letterIndex(sorted))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.apps.*"`
Expected: compilation FAIL, `Unresolved reference 'filterApps'`.

- [ ] **Step 3: Implement the pure part**

Append to `app/src/main/java/dev/opencover/apps/AppEntry.kt`:
```kotlin
enum class Sort { AZ, RECENT }

fun filterApps(apps: List<AppEntry>, query: String, sort: Sort): List<AppEntry> {
    val q = query.trim()
    val hits = apps.filter { it.label.contains(q, ignoreCase = true) }
    return when (sort) {
        Sort.AZ -> hits.sortedBy { it.label.lowercase() }
        Sort.RECENT -> hits.sortedByDescending { it.lastUsed }
    }
}

/** First index of each starting letter in an A–Z sorted list; anything not A–Z groups under '#'. */
fun letterIndex(apps: List<AppEntry>): Map<Char, Int> {
    val index = linkedMapOf<Char, Int>()
    apps.forEachIndexed { i, a ->
        val c = a.label.firstOrNull()?.uppercaseChar()?.takeIf { it in 'A'..'Z' } ?: '#'
        index.putIfAbsent(c, i)
    }
    return index
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.apps.*"`
Expected: PASS (4 tests).

- [ ] **Step 5: Write AppRepository, AppIcon and AppDrawer**

`app/src/main/java/dev/opencover/apps/AppRepository.kt`:
```kotlin
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
```

`app/src/main/java/dev/opencover/ui/AppIcon.kt`:
```kotlin
package dev.opencover.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AppIcon(label: String, icon: ImageBitmap?, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) Image(icon, contentDescription = label, modifier = Modifier.size(48.dp))
        else Box(Modifier.size(48.dp).background(Color.DarkGray, CircleShape))
        Text(label, color = Color.White, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
```

`app/src/main/java/dev/opencover/ui/AppDrawer.kt`:
```kotlin
package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.apps.AppEntry
import dev.opencover.apps.Sort
import dev.opencover.apps.filterApps
import dev.opencover.apps.letterIndex
import dev.opencover.grid.GRID
import kotlinx.coroutines.launch

@Composable
fun AppDrawer(
    apps: List<AppEntry>,
    icon: (AppEntry) -> ImageBitmap?,
    onLaunch: (AppEntry) -> Unit,
    onAddToHome: (AppEntry) -> Unit,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(Sort.AZ) }
    val shown = remember(apps, query, sort) { filterApps(apps, query, sort) }
    val index = remember(shown, sort) { if (sort == Sort.AZ) letterIndex(shown) else emptyMap() }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize().background(Color(0xF0101010))) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search") }, modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { sort = if (sort == Sort.AZ) Sort.RECENT else Sort.AZ }) {
                Text(if (sort == Sort.AZ) "A–Z" else "Recent")
            }
        }
        Row(Modifier.weight(1f)) {
            LazyVerticalGrid(GridCells.Fixed(GRID), state = grid, modifier = Modifier.weight(1f)) {
                items(shown, key = { it.key }) { e ->
                    AppIcon(e.label, icon(e), Modifier.combinedClickable(
                        onClick = { onLaunch(e) },
                        onLongClick = { onAddToHome(e) },
                    ))
                }
            }
            if (index.isNotEmpty()) {
                Column(Modifier.fillMaxHeight().width(20.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                    index.forEach { (letter, i) ->
                        Text("$letter", color = Color.White, fontSize = 10.sp,
                            modifier = Modifier.clickable { scope.launch { grid.scrollToItem(i) } })
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Build**

Run: `.\gradlew.bat :app:assembleDebug`
Expected: `BUILD SUCCESSFUL`. (The drawer is shown on screen in Task 7.)

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/dev/opencover/apps app/src/main/java/dev/opencover/ui/AppIcon.kt app/src/main/java/dev/opencover/ui/AppDrawer.kt app/src/test/java/dev/opencover/apps
git commit -m "Add app repository, search/sort/letter index and the app drawer"
```

---

### Task 7: Home pages showing apps, tap to launch, swipe up for the drawer

**Files:**
- Create: `app/src/main/java/dev/opencover/ui/LauncherScreen.kt`, `app/src/main/java/dev/opencover/ui/GridPage.kt`
- Modify (rewrite): `app/src/main/java/dev/opencover/ui/CoverActivity.kt`

**Interfaces:**
- Consumes: everything from Tasks 1–6.
- Produces:
  ```kotlin
  // dev.opencover.ui
  class LauncherActions(
      val repo: AppRepository,
      val widgets: WidgetHost,
      val launch: (pkg: String, activity: String, user: Long) -> Unit,
      val change: (Layout) -> Unit,
      val addApp: (AppEntry, page: Int) -> Unit,
      val remove: (Item) -> Unit,
  )
  class PageFullPrompt(val confirm: () -> Unit, val cancel: () -> Unit)
  @Composable fun LauncherScreen(layout: Layout, apps: List<AppEntry>, a: LauncherActions, prompt: PageFullPrompt?)
  @Composable fun GridPage(layout: Layout, pageIndex: Int, editing: Boolean, labels: Map<String, String>, a: LauncherActions, onLongPress: () -> Unit)
  ```
  Tasks 9 and 10 add fields to `LauncherActions`.

- [ ] **Step 1: Write GridPage (view mode only; edit mode arrives in Task 8)**

`app/src/main/java/dev/opencover/ui/GridPage.kt`:
```kotlin
package dev.opencover.ui

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import dev.opencover.grid.GRID
import dev.opencover.layout.AppItem
import dev.opencover.layout.Layout
import dev.opencover.layout.WidgetItem

@Composable
fun GridPage(
    layout: Layout,
    pageIndex: Int,
    editing: Boolean,
    labels: Map<String, String>,
    a: LauncherActions,
    onLongPress: () -> Unit,
) {
    val page = layout.pages.getOrNull(pageIndex) ?: return
    BoxWithConstraints(
        Modifier.fillMaxSize().combinedClickable(onClick = {}, onLongClick = onLongPress)
    ) {
        val cw = maxWidth / GRID
        val ch = maxHeight / GRID
        page.items.forEach { item ->
            key(item.id) {
                val c = item.cell
                Box(Modifier.offset(cw * c.x, ch * c.y).size(cw * c.w, ch * c.h)) {
                    when (item) {
                        is AppItem -> AppIcon(
                            labels[item.key] ?: item.pkg,
                            a.repo.icon(item.pkg, item.activity, item.user),
                            Modifier.fillMaxSize().combinedClickable(
                                onClick = { a.launch(item.pkg, item.activity, item.user) },
                                onLongClick = onLongPress,
                            ),
                        )
                        is WidgetItem -> {} // Task 9
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Write LauncherScreen**

`app/src/main/java/dev/opencover/ui/LauncherScreen.kt`:
```kotlin
package dev.opencover.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import dev.opencover.apps.AppEntry
import dev.opencover.apps.AppRepository
import dev.opencover.layout.Item
import dev.opencover.layout.Layout
import dev.opencover.widgets.WidgetHost

class LauncherActions(
    val repo: AppRepository,
    val widgets: WidgetHost,
    val launch: (pkg: String, activity: String, user: Long) -> Unit,
    val change: (Layout) -> Unit,
    val addApp: (AppEntry, page: Int) -> Unit,
    val remove: (Item) -> Unit,
)

/** Shown when something doesn't fit on the current page. */
class PageFullPrompt(val confirm: () -> Unit, val cancel: () -> Unit)

private enum class Overlay { NONE, DRAWER }

@Composable
fun LauncherScreen(layout: Layout, apps: List<AppEntry>, a: LauncherActions, prompt: PageFullPrompt?) {
    var editing by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    val pageCount by rememberUpdatedState(layout.pages.size)
    val pager = rememberPagerState { pageCount }
    val labels = remember(apps) { apps.associate { it.key to it.label } }
    var dragTotal by remember { mutableFloatStateOf(0f) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).pointerInput(editing) {
                    if (!editing) detectVerticalDragGestures(
                        onDragStart = { dragTotal = 0f },
                        onDragEnd = { if (dragTotal < -80f) overlay = Overlay.DRAWER },
                    ) { _, dy -> dragTotal += dy }
                },
            ) { i ->
                GridPage(layout, i, editing, labels, a, onLongPress = { editing = true })
            }
        }
        when (overlay) {
            Overlay.DRAWER -> AppDrawer(
                apps = apps,
                icon = { a.repo.icon(it.pkg, it.activity, it.user) },
                onLaunch = { overlay = Overlay.NONE; a.launch(it.pkg, it.activity, it.user) },
                onAddToHome = { overlay = Overlay.NONE; a.addApp(it, pager.currentPage) },
                onClose = { overlay = Overlay.NONE },
            )
            Overlay.NONE -> {}
        }
        prompt?.let { p ->
            AlertDialog(
                onDismissRequest = p.cancel,
                text = { Text("This page is full. Add it on a new page?") },
                confirmButton = { TextButton(onClick = p.confirm) { Text("Add page") } },
                dismissButton = { TextButton(onClick = p.cancel) { Text("Cancel") } },
            )
        }
    }
}
```

- [ ] **Step 3: Rewrite CoverActivity to own the state**

`app/src/main/java/dev/opencover/ui/CoverActivity.kt` (full replacement; the Task 2 probe stays until Task 9):
```kotlin
package dev.opencover.ui

import android.content.Intent
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.UserHandle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppEntry
import dev.opencover.apps.AppLauncher
import dev.opencover.apps.AppRepository
import dev.opencover.grid.Cell
import dev.opencover.layout.AppItem
import dev.opencover.layout.Item
import dev.opencover.layout.Layout
import dev.opencover.layout.LayoutStore
import dev.opencover.layout.Page
import dev.opencover.layout.WidgetItem
import dev.opencover.layout.addItem
import dev.opencover.layout.addPage
import dev.opencover.layout.defaultLayout
import dev.opencover.layout.newId
import dev.opencover.layout.pruneMissing
import dev.opencover.layout.removeItem
import dev.opencover.layout.removePackage
import dev.opencover.watcher.CoverWatcher
import dev.opencover.widgets.WidgetHost

class CoverActivity : ComponentActivity() {
    private lateinit var store: LayoutStore
    private lateinit var repo: AppRepository
    private lateinit var widgets: WidgetHost
    private var layout by mutableStateOf(Layout(listOf(Page(emptyList()))))
    private var apps by mutableStateOf(emptyList<AppEntry>())
    private var prompt by mutableStateOf<PageFullPrompt?>(null)
    private var loaded = false

    private val appsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(pkg: String, user: UserHandle) {
            val (l, freed) = layout.removePackage(pkg, repo.serial(user))
            freed.forEach(widgets::delete)
            update(l)
            refreshApps()
        }
        override fun onPackageAdded(pkg: String, user: UserHandle) = refreshApps()
        override fun onPackageChanged(pkg: String, user: UserHandle) {
            repo.forget(pkg)
            refreshApps()
        }
        override fun onPackagesAvailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = refreshApps()
        override fun onPackagesUnavailable(pkgs: Array<out String>, user: UserHandle, replacing: Boolean) = refreshApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (display?.displayId != COVER_DISPLAY) {
            AppLauncher.start(this, Intent(this, CoverActivity::class.java))
            finish()
            return
        }
        store = LayoutStore(filesDir)
        repo = AppRepository(this)
        widgets = WidgetHost(this) { id, info, _ ->
            Toast.makeText(this, "PROBE widget $id added: ${info.provider}", Toast.LENGTH_LONG).show()
            Log.i("OCPROBE", "added $id ${info.provider}")
        }
        // PROBE (removed in Task 9): adb ... --es probeWidget <pkg/cls>
        intent.getStringExtra("probeWidget")?.let { flat ->
            widgets.find(flat)?.let { widgets.begin(it, 0) } ?: Log.e("OCPROBE", "no provider $flat")
        }
        startForegroundService(Intent(this, CoverWatcher::class.java))
        repo.register(appsCallback)

        val actions = LauncherActions(
            repo = repo,
            widgets = widgets,
            launch = { pkg, activity, user -> AppLauncher.launch(this, pkg, activity, user) },
            change = ::update,
            addApp = { e, page -> place(page, 1, 1, { AppItem(newId(), it, e.pkg, e.activity, e.user) }) },
            remove = ::remove,
        )
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                LauncherScreen(layout, apps, actions, prompt)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        widgets.startListening()
    }

    override fun onStop() {
        widgets.stopListening()
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        refreshApps()
        if (!loaded) loadLayout()
    }

    override fun onDestroy() {
        if (::repo.isInitialized) repo.unregister(appsCallback)
        super.onDestroy()
    }

    @Deprecated("Widget flows need startActivityForResult with display options")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }

    private fun refreshApps() {
        apps = repo.load()
    }

    private fun loadLayout() {
        val (l, freed) = store.load { defaultLayout(apps) }
            .pruneMissing(apps.map { it.key }.toSet(), widgets::exists)
        freed.forEach(widgets::delete)
        update(l)
        loaded = true
    }

    private fun update(l: Layout) {
        layout = l
        store.save(l)
    }

    private fun remove(item: Item) {
        if (item is WidgetItem) widgets.delete(item.widgetId)
        update(layout.removeItem(item.id))
    }

    /** Adds a w×h item on [page]; if it doesn't fit, asks whether to put it on a new page. */
    private fun place(page: Int, w: Int, h: Int, make: (Cell) -> Item, onCancel: () -> Unit = {}) {
        layout.addItem(page, w, h, make)?.let { update(it); return }
        prompt = PageFullPrompt(
            confirm = {
                val withPage = layout.addPage()
                // A fresh empty page always has room for anything up to GRID×GRID.
                withPage.addItem(withPage.pages.lastIndex, w, h, make)?.let(::update)
                prompt = null
            },
            cancel = {
                onCancel()
                prompt = null
            },
        )
    }
}
```

- [ ] **Step 4: Verify on the cover screen**

Ask the user to fold and unlock the cover screen. Run `.\scripts\run.ps1` (do not `pm clear` the app: that would drop the ADB-granted permissions from Task 3).
Read `build/cover.png`. Expected: two rows of up to four app icons with labels on black (the default layout from recent apps).
Ask the user to tap one icon: it opens on the cover screen. Ask them to swipe Home: OpenCover comes back.
Ask the user to swipe up on the home page: the drawer appears. Capture it without relaunching:
```powershell
adb shell screencap -d 4630947181303254916 -p /sdcard/oc.png; adb pull /sdcard/oc.png build\cover.png
```
Confirm the search field, A–Z button and letter index are visible. Ask them to long-press an app in the drawer: it appears on the home page.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Show home pages with app icons, launch on tap, swipe up for drawer"
```

---

### Task 8: Edit mode: drag, remove, pages, page-full prompt

**Files:**
- Modify: `app/src/main/java/dev/opencover/ui/GridPage.kt`, `app/src/main/java/dev/opencover/ui/LauncherScreen.kt`

**Interfaces:**
- Consumes: `Layout.moveItem`, `Layout.addPage`, `Layout.removePage`, `LauncherActions.change/remove` (Tasks 5, 7).
- Produces: `@Composable fun EditBar(onAddPage: () -> Unit, onRemovePage: (() -> Unit)?, onDone: () -> Unit, extra: @Composable RowScope.() -> Unit = {})` in `LauncherScreen.kt`. Tasks 9 and 10 put their buttons in `extra`.

- [ ] **Step 1: Add drag and remove to GridPage**

Replace the `Box(Modifier.offset(...).size(...)) { when (item) { ... } }` block in `GridPage` with:
```kotlin
                var drag by remember { mutableStateOf(Offset.Zero) }
                val cwPx = with(LocalDensity.current) { cw.toPx() }
                val chPx = with(LocalDensity.current) { ch.toPx() }
                Box(
                    Modifier
                        .offset(cw * c.x, ch * c.y)
                        .offset { IntOffset(drag.x.roundToInt(), drag.y.roundToInt()) }
                        .size(cw * c.w, ch * c.h)
                ) {
                    when (item) {
                        is AppItem -> AppIcon(
                            labels[item.key] ?: item.pkg,
                            a.repo.icon(item.pkg, item.activity, item.user),
                            Modifier.fillMaxSize().combinedClickable(
                                enabled = !editing,
                                onClick = { a.launch(item.pkg, item.activity, item.user) },
                                onLongClick = onLongPress,
                            ),
                        )
                        is WidgetItem -> {} // Task 9
                    }
                    if (editing) {
                        // Transparent layer on top so drags reach us, not the app icon or widget.
                        Box(
                            Modifier.fillMaxSize()
                                .border(1.dp, Color.White.copy(alpha = 0.5f))
                                .pointerInput(item.id, c) {
                                    detectDragGestures(
                                        onDragEnd = {
                                            val nx = c.x + (drag.x / cwPx).roundToInt()
                                            val ny = c.y + (drag.y / chPx).roundToInt()
                                            layout.moveItem(pageIndex, item.id, nx, ny)?.let(a.change)
                                            drag = Offset.Zero // rejected drops spring back
                                        },
                                        onDragCancel = { drag = Offset.Zero },
                                    ) { change, delta ->
                                        change.consume()
                                        drag += delta
                                    }
                                }
                        )
                        Text(
                            "✕", color = Color.White, fontSize = 14.sp,
                            modifier = Modifier.align(Alignment.TopStart)
                                .background(Color(0xCCB00020), CircleShape)
                                .clickable { a.remove(item) }
                                .padding(horizontal = 6.dp),
                        )
                    }
                }
```
Add imports: `androidx.compose.foundation.background`, `androidx.compose.foundation.border`, `androidx.compose.foundation.clickable`, `androidx.compose.foundation.gestures.detectDragGestures`, `androidx.compose.foundation.layout.padding`, `androidx.compose.foundation.shape.CircleShape`, `androidx.compose.material3.Text`, `androidx.compose.runtime.getValue`, `androidx.compose.runtime.mutableStateOf`, `androidx.compose.runtime.remember`, `androidx.compose.runtime.setValue`, `androidx.compose.ui.Alignment`, `androidx.compose.ui.geometry.Offset`, `androidx.compose.ui.graphics.Color`, `androidx.compose.ui.input.pointer.pointerInput`, `androidx.compose.ui.platform.LocalDensity`, `androidx.compose.ui.unit.IntOffset`, `androidx.compose.ui.unit.dp`, `androidx.compose.ui.unit.sp`, `dev.opencover.layout.moveItem`, `kotlin.math.roundToInt`.

- [ ] **Step 2: Add the edit bar to LauncherScreen**

Add to `LauncherScreen.kt`:
```kotlin
@Composable
fun EditBar(
    onAddPage: () -> Unit,
    onRemovePage: (() -> Unit)?,
    onDone: () -> Unit,
    extra: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xCC202020)),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        extra()
        TextButton(onClick = onAddPage) { Text("+ Page") }
        TextButton(onClick = { onRemovePage?.invoke() }, enabled = onRemovePage != null) { Text("− Page") }
        TextButton(onClick = onDone) { Text("Done") }
    }
}
```
In `LauncherScreen`, add `BackHandler(enabled = editing) { editing = false }` after the state declarations, and inside the `Column`, before `HorizontalPager`, add:
```kotlin
            if (editing) {
                val removable = layout.removePage(pager.currentPage)
                EditBar(
                    onAddPage = { a.change(layout.addPage()) },
                    onRemovePage = removable?.let { l -> { a.change(l) } },
                    onDone = { editing = false },
                )
            }
```
Add imports: `androidx.activity.compose.BackHandler`, `androidx.compose.foundation.layout.Arrangement`, `androidx.compose.foundation.layout.Row`, `androidx.compose.foundation.layout.RowScope`, `androidx.compose.foundation.layout.fillMaxWidth`, `dev.opencover.layout.addPage`, `dev.opencover.layout.removePage`.

- [ ] **Step 3: Verify on the cover screen**

Run `.\scripts\run.ps1`, then ask the user (phone folded, cover unlocked) to:
1. Long-press empty space → edit bar appears, items get outlines and ✕ badges.
2. Drag an icon to an empty cell → it moves. Drag it onto another icon → it springs back.
3. Tap ✕ on an icon → it disappears.
4. "+ Page" → swipe left shows an empty page; "− Page" there removes it; "− Page" is disabled on a page with items.
5. Fill a page (add apps from the drawer by long-press until 16 cells are used), long-press one more app in the drawer → "This page is full" dialog; "Add page" puts it on a new page.
6. Back gesture or "Done" leaves edit mode.
Then confirm persistence: `adb shell am force-stop dev.opencover; .\scripts\run.ps1 -NoBuild`, read `build/cover.png`: the edited layout is unchanged.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Add edit mode: drag to move, remove, add/remove pages, page-full prompt"
```

---

### Task 9: Widgets on the grid: picker, placement, rendering, resize

**Files:**
- Create: `app/src/main/java/dev/opencover/ui/WidgetPicker.kt`
- Modify: `app/src/main/java/dev/opencover/ui/CoverActivity.kt`, `app/src/main/java/dev/opencover/ui/GridPage.kt`, `app/src/main/java/dev/opencover/ui/LauncherScreen.kt`

**Interfaces:**
- Consumes: `WidgetHost` (Task 2), `cellSpan` (Task 4), `Layout.resizeItem` (Task 5), `EditBar(extra)` (Task 8).
- Produces: `LauncherActions.addWidget: (AppWidgetProviderInfo, page: Int) -> Unit`; `@Composable fun WidgetPicker(providers: List<AppWidgetProviderInfo>, label: (AppWidgetProviderInfo) -> String, onPick: (AppWidgetProviderInfo) -> Unit, onClose: () -> Unit)`.

- [ ] **Step 1: Write WidgetPicker**

`app/src/main/java/dev/opencover/ui/WidgetPicker.kt`:
```kotlin
package dev.opencover.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WidgetPicker(
    providers: List<AppWidgetProviderInfo>,
    label: (AppWidgetProviderInfo) -> String,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val sorted = remember(providers) { providers.map { it to label(it) }.sortedBy { it.second.lowercase() } }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xF0101010))) {
        items(sorted) { (p, name) ->
            Text(name, color = Color.White,
                modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(12.dp))
        }
    }
}
```

- [ ] **Step 2: Wire widgets into CoverActivity (and remove the probe)**

In `CoverActivity.onCreate`, delete the probe block (the `// PROBE` comment and the `intent.getStringExtra("probeWidget")…` statement), and replace the `widgets = WidgetHost(this) { … }` statement with:
```kotlin
        widgets = WidgetHost(this) { id, info, page ->
            // Provider sizes are px at the main display's density; convert to dp, then to cells.
            val sys = Resources.getSystem().displayMetrics.density
            val w = cellSpan(info.targetCellWidth, (info.minWidth / sys).toInt(), CELL_W_DP)
            val h = cellSpan(info.targetCellHeight, (info.minHeight / sys).toInt(), CELL_H_DP)
            val provider = info.provider.flattenToString()
            place(page, w, h, { WidgetItem(newId(), it, id, provider) }, onCancel = { widgets.delete(id) })
        }
```
Add to the `LauncherActions(...)` call: `addWidget = { info, page -> widgets.begin(info, page) },`
Add a companion object at the end of `CoverActivity`:
```kotlin
    companion object {
        // 748×720 px at 340 dpi ≈ 352×339 dp, split 4×4.
        private const val CELL_W_DP = 88
        private const val CELL_H_DP = 84
    }
```
Add imports: `android.content.res.Resources`, `dev.opencover.grid.cellSpan`. Remove the now-unused `android.util.Log` and `android.widget.Toast` imports.

In `LauncherScreen.kt`, add to `LauncherActions` after `remove`:
```kotlin
    val addWidget: (AppWidgetProviderInfo, page: Int) -> Unit,
```
(import `android.appwidget.AppWidgetProviderInfo`).

- [ ] **Step 3: Add the picker overlay and "+ Widget" button**

In `LauncherScreen.kt`: change `private enum class Overlay { NONE, DRAWER }` to `private enum class Overlay { NONE, DRAWER, WIDGETS }`.
In the `EditBar(...)` call add:
```kotlin
                    extra = { TextButton(onClick = { overlay = Overlay.WIDGETS }) { Text("+ Widget") } },
```
In the `when (overlay)` block add:
```kotlin
            Overlay.WIDGETS -> {
                val pm = LocalContext.current.packageManager
                WidgetPicker(
                    providers = remember { a.widgets.providers() },
                    label = { p ->
                        val app = pm.getApplicationLabel(pm.getApplicationInfo(p.provider.packageName, 0))
                        "$app · ${p.loadLabel(pm)}"
                    },
                    onPick = { overlay = Overlay.NONE; a.addWidget(it, pager.currentPage) },
                    onClose = { overlay = Overlay.NONE },
                )
            }
```
(import `androidx.compose.ui.platform.LocalContext`).

- [ ] **Step 4: Render widgets and add the resize handle in GridPage**

In `GridPage`, replace `is WidgetItem -> {} // Task 9` with:
```kotlin
                        is WidgetItem -> WidgetCell(item, a, cw * c.w, ch * c.h)
```
In the `if (editing) { … }` block, after the ✕ `Text`, add:
```kotlin
                        if (item is WidgetItem) {
                            var grow by remember { mutableStateOf(Offset.Zero) }
                            Box(
                                Modifier.align(Alignment.BottomEnd).size(28.dp)
                                    .offset { IntOffset(grow.x.roundToInt(), grow.y.roundToInt()) }
                                    .background(Color(0xCC2060C0), CircleShape)
                                    .pointerInput(item.id, c) {
                                        detectDragGestures(
                                            onDragEnd = {
                                                val nw = c.w + (grow.x / cwPx).roundToInt()
                                                val nh = c.h + (grow.y / chPx).roundToInt()
                                                layout.resizeItem(pageIndex, item.id, nw, nh)?.let(a.change)
                                                grow = Offset.Zero
                                            },
                                            onDragCancel = { grow = Offset.Zero },
                                        ) { change, delta ->
                                            change.consume()
                                            grow += delta
                                        }
                                    }
                            )
                        }
```
Add at the end of `GridPage.kt`:
```kotlin
@Composable
private fun WidgetCell(item: WidgetItem, a: LauncherActions, width: Dp, height: Dp) {
    val view = remember(item.widgetId) { a.widgets.view(item.widgetId) }
    if (view == null) {
        Box(Modifier.fillMaxSize().background(Color.DarkGray), contentAlignment = Alignment.Center) {
            Text("Widget unavailable", color = Color.White, fontSize = 11.sp)
        }
        return
    }
    AndroidView(
        factory = { view },
        modifier = Modifier.fillMaxSize(),
        update = { a.widgets.resize(it, width.value, height.value) },
    )
}
```
Add imports: `androidx.compose.ui.unit.Dp`, `androidx.compose.ui.viewinterop.AndroidView`, `dev.opencover.layout.resizeItem`.

- [ ] **Step 5: Verify on the cover screen**

Run `.\scripts\run.ps1`, then ask the user (folded, unlocked) to:
1. Long-press → "+ Widget" → pick a clock widget → it appears on the page at a sensible size. Screenshot: `build/cover.png` shows it.
2. Add a widget that has a setup screen (e.g. a Google Keep or Gmail list widget) → its setup screen appears **on the cover screen**; finish it → widget appears. Cancel one → nothing added (`adb shell dumpsys appwidget | grep -c "dev.opencover"` count unchanged between before and after the cancelled add).
3. Scroll inside a list widget → it scrolls; swiping sideways elsewhere still changes pages.
4. In edit mode, drag the blue handle of a widget → it resizes; resizing into another item springs back.
5. ✕ on the widget removes it.
6. Uninstall-test: add a widget from an app the user doesn't need (or skip if none), uninstall that app with `adb uninstall <pkg>` → the widget disappears.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Add widgets: picker, bind/configure on cover, rendering and resize"
```

---

### Task 10: Settings: keep-awake and Samsung widgets

**Files:**
- Create: `app/src/main/java/dev/opencover/ui/SettingsPanel.kt`
- Modify: `app/src/main/java/dev/opencover/ui/LauncherScreen.kt`, `app/src/main/java/dev/opencover/ui/CoverActivity.kt`

**Interfaces:**
- Consumes: `KeepAwake`, `CoverWatcher.paused` (Task 3).
- Produces: `LauncherActions.samsungWidgets: () -> Unit`, `LauncherActions.setKeepAwake: (Boolean) -> Unit`; `@Composable fun SettingsPanel(keepAwake: Boolean, onKeepAwake: (Boolean) -> Unit, onSamsungWidgets: () -> Unit, onClose: () -> Unit)`.

- [ ] **Step 1: Write SettingsPanel**

`app/src/main/java/dev/opencover/ui/SettingsPanel.kt`:
```kotlin
package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsPanel(
    keepAwake: Boolean,
    onKeepAwake: (Boolean) -> Unit,
    onSamsungWidgets: () -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 20.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep cover screen awake", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = keepAwake, onCheckedChange = onKeepAwake)
        }
        Button(onClick = onSamsungWidgets) { Text("Open Samsung widgets") }
        TextButton(onClick = onClose) { Text("Close") }
    }
}
```

- [ ] **Step 2: Wire it in**

In `LauncherScreen.kt`:
- Add to `LauncherActions` after `addWidget`:
  ```kotlin
      val samsungWidgets: () -> Unit,
      val setKeepAwake: (Boolean) -> Unit,
  ```
- Change the enum to `private enum class Overlay { NONE, DRAWER, WIDGETS, SETTINGS }`.
- Add state: `var keepAwake by remember { mutableStateOf(KeepAwake.isOn) }` (import `dev.opencover.watcher.KeepAwake`).
- In the `EditBar(extra = { … })` lambda, after the "+ Widget" button add: `TextButton(onClick = { overlay = Overlay.SETTINGS }) { Text("Settings") }`.
- In `when (overlay)` add:
  ```kotlin
            Overlay.SETTINGS -> SettingsPanel(
                keepAwake = keepAwake,
                onKeepAwake = { keepAwake = it; a.setKeepAwake(it) },
                onSamsungWidgets = { overlay = Overlay.NONE; editing = false; a.samsungWidgets() },
                onClose = { overlay = Overlay.NONE },
            )
  ```

In `CoverActivity`, add to the `LauncherActions(...)` call:
```kotlin
            samsungWidgets = {
                CoverWatcher.paused = true // cleared when the screen turns off
                moveTaskToBack(true)
            },
            setKeepAwake = { KeepAwake.set(this, it) },
```
(import `dev.opencover.watcher.KeepAwake`).

- [ ] **Step 3: Verify on the cover screen**

Run `.\scripts\run.ps1`; ask the user (folded, unlocked) to:
1. Long-press → Settings → turn on "Keep cover screen awake" → open any app → leave the phone untouched for longer than the screen timeout. Expected: cover stays on. Confirm with `adb shell "dumpsys power | grep -i 'mWakefulness='"` → `Awake`.
2. Turn it off → screen times out normally.
3. Settings → "Open Samsung widgets" → Samsung's cover widgets appear and swiping between them does **not** bounce back to OpenCover. Lock and unlock the cover → OpenCover opens again (pause cleared).

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "Add settings: keep-awake overlay and Samsung widgets pause"
```

---

### Task 11: First-run checklist

**Files:**
- Create: `app/src/main/java/dev/opencover/ui/Checklist.kt`
- Modify: `app/src/main/java/dev/opencover/ui/CoverActivity.kt`

**Interfaces:**
- Consumes: `AppLauncher.start`.
- Produces: `object Permissions { fun usage(ctx: Context): Boolean; fun overlay(ctx: Context): Boolean; fun notifications(ctx: Context): Boolean }`; `@Composable fun Checklist(usage: Boolean, overlay: Boolean, notifications: Boolean, onUsage: () -> Unit, onOverlay: () -> Unit, onNotifications: () -> Unit)`.

- [ ] **Step 1: Write Checklist**

`app/src/main/java/dev/opencover/ui/Checklist.kt`:
```kotlin
package dev.opencover.ui

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Permissions {
    fun usage(ctx: Context) = ctx.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
        AppOpsManager.MODE_ALLOWED

    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun notifications(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}

@Composable
fun Checklist(
    usage: Boolean,
    overlay: Boolean,
    notifications: Boolean,
    onUsage: () -> Unit,
    onOverlay: () -> Unit,
    onNotifications: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("OpenCover setup", color = Color.White, fontSize = 20.sp)
        Step("Usage access", "Brings the launcher back when you swipe Home", usage, onUsage)
        Step("Display over other apps", "Opens the launcher on unlock and keeps the screen awake", overlay, onOverlay)
        Step("Notifications (optional)", "Shows that OpenCover is running", notifications, onNotifications)
    }
}

@Composable
private fun Step(title: String, why: String, done: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !done, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (done) "✓" else "○", color = if (done) Color.Green else Color.White, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = Color.White)
            Text(why, color = Color.Gray, fontSize = 12.sp)
        }
    }
}
```

- [ ] **Step 2: Gate the launcher on the checklist**

In `CoverActivity`:
- Add state: `private var perms by mutableStateOf(Triple(false, false, false))`.
- Add to `onResume()`, as its first line after `super.onResume()`:
  ```kotlin
        perms = Triple(Permissions.usage(this), Permissions.overlay(this), Permissions.notifications(this))
  ```
- Replace the `setContent { … }` body's `LauncherScreen(layout, apps, actions, prompt)` with:
  ```kotlin
                val (usage, overlay, notifications) = perms
                if (usage && overlay) LauncherScreen(layout, apps, actions, prompt)
                else Checklist(
                    usage, overlay, notifications,
                    onUsage = { openSettings(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
                    onOverlay = { openSettings(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) },
                    onNotifications = { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) },
                )
  ```
- Add the helper:
  ```kotlin
    private fun openSettings(action: String) =
        AppLauncher.start(this, Intent(action, Uri.parse("package:$packageName")))
  ```
- Imports: `android.Manifest`, `android.net.Uri`, `android.provider.Settings`.

The permission dialog and Settings screens close back to us, which triggers `onResume` and refreshes `perms`.

- [ ] **Step 3: Verify on the cover screen**

```powershell
.\gradlew.bat :app:assembleDebug
$env:ANDROID_SERIAL="R5CW71QQAGW"
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell appops set dev.opencover GET_USAGE_STATS default
adb shell appops set dev.opencover SYSTEM_ALERT_WINDOW default
adb shell pm revoke dev.opencover android.permission.POST_NOTIFICATIONS
.\scripts\run.ps1 -NoBuild
```
Read `build/cover.png`: the checklist with three ○ rows.
Ask the user to tap each row and grant it. Expected: each Settings page opens **on the cover screen**; after returning, the row shows ✓; once the first two are ✓ the home pages appear.

- [ ] **Step 4: Run all unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest`
Expected: all GridEngine, Layout, LayoutStore and AppEntry tests PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add first-run permission checklist"
```

---

### Task 12: End-to-end check and README

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write README**

`README.md`:
````markdown
# OpenCover

A free, open-source (GPL-3.0) launcher for the Galaxy Z Flip 5 cover screen. No ads, no paywall.

## What works
- Swipeable home pages: put apps and widgets anywhere on a 4×4 grid, drag, resize widgets, add/remove pages.
- App drawer: swipe up; search, A–Z or recently used, letter index. Apps open on the cover screen.
- Opens automatically when you unlock the cover screen; Samsung's Home gesture returns to OpenCover.
- Keep the cover screen awake in any app.
- "Open Samsung widgets" in settings to reach Samsung's own cover widgets.

## Install
1. Build: `.\gradlew.bat :app:assembleDebug` (JDK: Android Studio's `jbr`).
2. `adb install -r app\build\outputs\apk\debug\app-debug.apk`
3. Open OpenCover on the cover screen and follow the setup checklist.

## Status
Sub-project 1 of 8. See `docs/superpowers/specs/` for the roadmap.
````
If Task 2 needed the R1 fallback, add under Install: `4. Run once: adb shell appwidget grantbind --package dev.opencover --user 0`.

- [ ] **Step 2: Full device pass**

Ask the user to reboot the phone, fold it, and unlock the cover screen. Expected: OpenCover opens (BootReceiver → CoverWatcher → unlock). Then walk through Task 7–10 checks once more quickly: launch an app, Home returns, drawer search, add/move/resize a widget, keep-awake toggle. Report any failure with the exact step.

- [ ] **Step 3: Commit**

```bash
git add README.md
git commit -m "Add README"
```
````
