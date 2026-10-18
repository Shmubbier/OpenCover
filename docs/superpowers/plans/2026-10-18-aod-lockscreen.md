# AOD and Lock Screen Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenCover draws the cover's AOD (while dozing) and lock screen (woken, locked) from one movable, toggleable module layout; Samsung keeps unlocking.

**Architecture:** New package `dev.opencover.lock`. Pure layout, icon and privacy logic is unit tested. The AOD is a Compose overlay (`TYPE_ACCESSIBILITY_OVERLAY`) added by `CoverAccessibility` on `DREAMING_STARTED`. The lock screen is `LockActivity` (`showWhenLocked`) on display 1, started on wake while locked; swipe up calls `requestDismissKeyguard`. Both render one composable, `LockFace`, reusing Home's `ClockFace`.

**Tech Stack:** Kotlin, Compose, JUnit 4. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-10-18-aod-lockscreen-design.md`. Device notes: `RE_handoff/ijp/README.md` §2 and `RE_handoff/ijp/COVER_TWEAKS_POP_TOGGLES.md` §2 (local, ignored by git).

## Global Constraints

- minSdk 34; no new dependencies.
- Commits: no Claude attribution, no em dashes, no emojis; short messages; as Shmubbier.
- Build and test: `./gradlew :app:testDebugUnitTest :app:assembleDebug` (JAVA_HOME = Android Studio `jbr`).
- Cover: display 1, 748x720, camera cutout bottom right (`Rect(379, 654 - 748, 720)`).
- AOD needs `Settings.System aod_mode == 1 && aod_tap_to_show_mode == 0`.
- Device tests: OpenCover in front before any adb input; never reboot; never type outside OpenCover.

## Review Focus

1. Samsung AOD not "Always" (or tap-to-show on): no overlay is added, nothing breaks, the setup row says what to change (Task 5 test `aodNeedsAlwaysWithoutTapToShow`).
2. Waking straight into an incoming call or alarm: OpenCover's lock screen must not cover it (Task 6 test `callOrAlarmOnTopKeepsTheCover`).
3. A saved layout from an older version with a missing or unknown module: unknown dropped, missing ones get defaults (Task 2 test `unknownDroppedMissingDefaulted`).
4. A module dragged under the camera cutout or off screen: clamped back on screen and out of the cutout (Task 2 test `clampKeepsOutOfCutout`).
5. Lock screen notification setting "Don't show": no icons, no peek, on both screens (Task 3 test `dontShowHidesEverything`).

---

### Task 1: Spike: lock screen activity over the keyguard

Throwaway check before building on it; keep only `LockActivity`'s skeleton.

**Files:**
- Create: `app/src/main/java/dev/opencover/lock/LockActivity.kt` (`ComponentActivity`; `setShowWhenLocked(true)`, `setTurnScreenOn(false)`; full screen black with a "Swipe up" text; swipe up calls `getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, null)` then `finish()`)
- Modify: `AndroidManifest.xml` (`.lock.LockActivity`: not exported, `excludeFromRecents`, `launchMode="singleInstance"`, `showWhenLocked="true"`, `taskAffinity=""`, meta-data `com.samsung.android.multiwindow.show_on_cover` = `true`)

- [ ] **Step 1:** Mark it `exported="true"` for the spike only. Build, install, lock the phone, wake the cover, then `adb shell am start --display 1 -n dev.opencover/.lock.LockActivity`.
- [ ] **Step 2:** Check and ledger each: shows over Samsung's lock screen; Samsung's quick panel pulls down from the top over it; swipe up brings Samsung's PIN pad on the cover; fingerprint still unlocks; after unlock the activity is gone.
- [ ] **Step 3:** If it doesn't show over the keyguard or the PIN pad doesn't appear, ledger the finding and stop to ask the user. Otherwise set `exported="false"` again and commit `Lock screen: activity skeleton over the keyguard`.

### Task 2: Layout model

**Files:**
- Create: `lock/LockLayout.kt`
- Test: `app/src/test/java/dev/opencover/lock/LockLayoutTest.kt`

**Interfaces:**
- Produces:
  - `enum class Module { CLOCK, DATE, BATTERY, ICONS, NOW_PLAYING }` (ids are saved; never rename)
  - `data class Spot(val x: Float, val y: Float, val on: Boolean)`: top-left as fractions of the cover (0..1).
  - `val DEFAULT_LAYOUT: Map<Module, Spot>`: CLOCK (0.05, 0.06), DATE (0.05, 0.30), BATTERY (0.05, 0.38), ICONS (0.05, 0.46), NOW_PLAYING (0.03, 0.82); all on.
  - `fun parseLayout(s: String?): Map<Module, Spot>` / `fun formatLayout(m: Map<Module, Spot>): String`, format `CLOCK:0.05,0.06,1;DATE:...`.
  - `fun clamp(s: Spot, w: Float, h: Float): Spot`: `w`,`h` are the module's size as fractions; keeps the box on screen and moves it up out of the cutout (fractions `x >= 379/748f && y + h > 654/720f`).

- [ ] **Step 1: Tests.**
  - `roundTrip`: `parseLayout(formatLayout(DEFAULT_LAYOUT)) == DEFAULT_LAYOUT`
  - `unknownDroppedMissingDefaulted`: `parseLayout("CLOCK:0.5,0.5,0;BOGUS:0.1,0.1,1")` gives CLOCK `Spot(0.5f, 0.5f, false)` and every other module its default
  - `nullIsDefault`: `parseLayout(null) == DEFAULT_LAYOUT`
  - `clampKeepsOnScreen`: `clamp(Spot(0.9f, -0.1f, true), 0.3f, 0.1f) == Spot(0.7f, 0f, true)`
  - `clampKeepsOutOfCutout`: `clamp(Spot(0.6f, 0.9f, true), 0.2f, 0.1f).let { it.y + 0.1f <= 654 / 720f }`
- [ ] **Step 2:** Run `./gradlew :app:testDebugUnitTest --tests '*LockLayoutTest'`; expect FAIL (unresolved).
- [ ] **Step 3:** Implement; run; expect PASS. Commit `Lock: layout model`.

### Task 3: Notification icons and privacy

**Files:**
- Create: `lock/LockNotes.kt`
- Test: `app/src/test/java/dev/opencover/lock/LockNotesTest.kt`

**Interfaces:**
- Consumes: `notify.Note`, `notify.shouldShow`.
- Produces:
  - `enum class Privacy { ALL, APP_ONLY, NONE }`
  - `fun privacy(showOnLock: Int, allowPrivate: Int): Privacy`: from `Settings.Secure lock_screen_show_notifications` and `lock_screen_allow_private_notifications` (0 = off); `showOnLock == 0` → NONE, `allowPrivate == 0` → APP_ONLY, else ALL.
  - `fun iconApps(notes: List<Note>, ownPkg: String, max: Int = 4): Pair<List<String>, Int>`: distinct packages of shown notes, newest first, first `max`, plus the count of the rest.

- [ ] **Step 1: Tests.** `dontShowHidesEverything` (`privacy(0, 1) == NONE`), `hiddenContentShowsAppOnly` (`privacy(1, 0) == APP_ONLY`), `iconsDedupeByApp` (three notes, two from one app → 2 apps), `iconsCapWithMore` (6 apps, max 4 → 4 and 2), `ownAndOngoingSkipped`.
- [ ] **Step 2:** Run, expect FAIL. Implement. Run, expect PASS. Commit `Lock: notification icons and privacy`.

### Task 4: Prefs and the shared face

**Files:**
- Create: `lock/LockPrefs.kt`: `object LockPrefs` with Compose state loaded by `load(ctx)`, SharedPreferences `"lock"`: `aod` (Boolean, default false), `lockScreen` (Boolean, default false), `clock` (`ClockStyle`, default CURRENT), `layout` (`Map<Module, Spot>`); setters save.
- Create: `ui/LockFace.kt`: `@Composable fun LockFace(layout: Map<Module, Spot>, dim: Boolean, locked: Boolean, onPeek: () -> Unit, modifier: Modifier = Modifier)`. Draws each `on` module at its spot: CLOCK via `ClockFace(LockPrefs.clock, ...)`, DATE `EEE, MMM d`, BATTERY (reuse Home's battery chip logic), ICONS from `iconApps` hidden when `Privacy.NONE` (tap calls `onPeek`), NOW_PLAYING only when `locked` and `MediaHub.players` has one (art, title, artist, play/pause via `MediaHub.controls(id)`). `dim`: black background and 40% alpha; otherwise the OpenCover wallpaper.
- Modify: `ui/HomeScreen.kt` only to make what LockFace reuses non-private (`ClockFace` is already public).

- [ ] **Step 1:** Build passes; commit `Lock: prefs and face`.

### Task 5: AOD overlay

**Files:**
- Create: `lock/Aod.kt`: `fun aodReady(aodMode: Int, tapToShow: Int): Boolean = aodMode == 1 && tapToShow == 0`; `fun burnIn(minute: Long): Pair<Int, Int>` (dp offsets cycling through (0,0),(4,0),(4,4),(0,4)); `object AodOverlay { fun show(service: AccessibilityService); fun hide() }` hosting a `ComposeView` with `LockFace(dim = true, locked = false)` in a `TYPE_ACCESSIBILITY_OVERLAY` window on display 1: `MATCH_PARENT`, flags `FLAG_NOT_TOUCHABLE or FLAG_NOT_FOCUSABLE or FLAG_LAYOUT_IN_SCREEN or FLAG_LAYOUT_NO_LIMITS`, `TRANSLUCENT`, `TOP or START`, `fitInsetsTypes = 0`. The view gets its own `LifecycleOwner`/`SavedStateRegistryOwner` (set via `setViewTreeLifecycleOwner`/`setViewTreeSavedStateRegistryOwner`).
- Modify: `watcher/CoverAccessibility.kt`: register a receiver for `Intent.ACTION_DREAMING_STARTED` / `ACTION_DREAMING_STOPPED`; started: if `LockPrefs.aod` and `aodReady(...)` → `AodOverlay.show(this)`; stopped: `hide()`. Unregister in `onDestroy`.
- Modify: `ui/CoverActivity.kt` setup steps: `SetupStep("Samsung AOD set to Always (for OpenCover AOD)", "Settings > Lock screen and AOD > Always On Display > Always, tap to show off", done = aodReady(...), open = { open Samsung AOD settings on display 1: action "com.samsung.android.app.aodservice.settings.AODSETTINGS", fallback Settings.ACTION_DISPLAY_SETTINGS })`, shown only when `LockPrefs.aod`.
- Test: `lock/AodTest.kt`: `aodNeedsAlwaysWithoutTapToShow` (`aodReady(1,0)` true; `(1,1)`, `(0,0)` false), `burnInCycles` (`burnIn(0) == 0 to 0`, `burnIn(4) == burnIn(0)`).

- [ ] **Step 1:** Tests; run, expect FAIL. Implement. Run, expect PASS. Build. Commit `Lock: AOD overlay while dozing`.

### Task 6: Lock screen

**Files:**
- Modify: `lock/LockActivity.kt`: content `LockFace(dim = false, locked = true)`; swipe up anywhere below the top 40 dp → `requestDismissKeyguard` with a callback that `finish()`es on success; peek list (bottom sheet over the face) from `NotificationStore.notes` honoring `Privacy` (APP_ONLY: app name only); tapping a note stores `pendingOpen = key` then requests dismiss. Finishes on `ACTION_USER_PRESENT`.
- Create in `lock/Aod.kt`: `fun shouldShowLock(enabled: Boolean, locked: Boolean, coverOn: Boolean, topPkg: String?): Boolean`: true only when all hold and `topPkg` is null or `com.android.systemui` (keyguard or Samsung's cover home), so an in-call or alarm activity on top keeps the cover.
- Create in `lock/LockActivity.kt` companion: `var pendingOpen: String?`; `fun maybeShow(service: CoverAccessibility)`: `topPkg` = package of the top application window on display 1 from `service.windowsOnAllDisplays[1]`; if `shouldShowLock(...)`, start `LockActivity` on display 1 through `AppLauncher.start`.
- Test: `lock/AodTest.kt` add `callOrAlarmOnTopKeepsTheCover` (`shouldShowLock(true, true, true, "com.samsung.android.incallui")` false; with `"com.android.systemui"` true; `locked = false` false).
- Modify: `watcher/CoverAccessibility.kt`: on `DREAMING_STOPPED` and on `ACTION_SCREEN_ON` call `LockActivity.maybeShow(this)`.
- Modify: `watcher/CoverWatcher.kt` `USER_PRESENT`: if `LockActivity.pendingOpen` set, `NotificationStore.open(this, key)` (it opens on display 1) and clear it instead of `openLauncher`.

- [ ] **Step 1:** Add the test; run, expect FAIL. Implement. Run tests and build, expect PASS. Commit `Lock: OpenCover lock screen with peek and handoff to PIN`.

### Task 7: Settings > Lock screen

**Files:**
- Create: `ui/LockSettings.kt`: `@Composable fun LockSettings(onBack: () -> Unit)`: switches "OpenCover AOD" and "OpenCover lock screen"; clock style row (the five `ClockStyle`s, names as in Home); "Edit layout" opens a full-cover editor: `LockFace(locked = true)` preview, each module draggable (`detectDragGestures`, position saved through `clamp` on drag end), a top bar of module toggles and "Lock" (saves and closes).
- Modify: `ui/SettingsPanel.kt`: "Lock screen" button, same pattern as "Phone".
- Modify: `ui/CoverActivity.kt`: `LockPrefs.load(this)` next to `HomePrefs.load`.

- [ ] **Step 1:** Build passes; commit `Settings > Lock screen: switches, clock style, layout editor`.

### Task 8: Docs and device check

- [ ] **Step 1:** README: Lock screen and AOD features, the Samsung AOD "Always" requirement, battery note. Roadmap: 8 done; 6 on hold with its decided design (one call screen, Answer / Decline / Answer muted / Decline and mute that person 30 min, small in-call screen, phone and Discord calls don't reach the cover today). Commit `Docs: AOD and lock screen`.
- [ ] **Step 2:** Device: the spec's device list. Ask the user before changing Samsung's AOD setting.
