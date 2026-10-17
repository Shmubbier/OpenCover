# FreeFlip Port Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port FreeFlip's per-app rotation, keep-cover-on, mirroring and heat guard into OpenCover.

**Architecture:** FreeFlip code (`FreeFlip_handoff/source/`, package `dev.freeflip`, GPL-3.0, same author) is copied into new packages `dev.opencover.phone` (rotation, fold, heat, prefs) and `dev.opencover.mirror`, rewired onto OpenCover's own `quick/ShizukuShell`, `watcher/CoverAccessibility`, `watcher/CoverWatcher` and `apps/AppLauncher`. FreeFlip's `Feature` hub, `CoverLaunch`, `freeformBounds`, setup screen and `Cover.kt` are not ported.

**Tech Stack:** Kotlin, Compose, Shizuku 13.1.5 (already a dependency), JUnit 4.

**Spec:** `docs/superpowers/specs/2026-10-18-freeflip-port-design.md`. Read `FreeFlip_handoff/README.md` (local, ignored by git) before Task 6.

## Global Constraints

- minSdk 34; no new dependencies.
- Commits: no Claude attribution, no em dashes, no emojis; short messages. Commit as Shmubbier.
- Build and test: `./gradlew :app:testDebugUnitTest :app:assembleDebug` (JAVA_HOME = Android Studio `jbr`).
- Every Shizuku call off the main thread.
- Device tests: phone unlocked; check `dumpsys window displays | grep mCurrentFocus` shows OpenCover before any adb input; never reboot; keep sessions short.
- Hotspot stop: `service call tethering 5 i32 0 s16 com.android.shell i32 -1 null` (version specific).
- Device states: `1` = TENT (held), `4` = unfolded (mirroring), `reset` only when keep-cover-on is off.

## Review Focus

1. Shizuku stopped or not allowed when Rotate or Mirror is tapped: the tile shows the usual hint, nothing crashes or half-applies (covered by `QuickState.toggle`'s `Need.SHIZUKU` path, Task 3 and 6 set the need).
2. Process killed mid-mirror: next process start restores developer options, unknown sources, accessibility and device state (Task 6 `MirrorEnv.recover`, test `accessibilityRestoreOnlyWhenItWasOn`).
3. Mirroring started while banking mode already paused accessibility: mirroring must not turn it back on at the end (Task 6 test `accessibilityRestoreOnlyWhenItWasOn`).
4. Rotate tapped with no app on the cover besides OpenCover and home: hint "Open an app on the cover first", nothing saved (Task 2 test `noTargetWhenOnlyOwnAndHome`).
5. Hotspot turned on while already hot: the hot notification appears then, not only on a rising temperature (Task 5 test `activationWhileHotNotifies`).

---

### Task 1: Bounded Shizuku commands

**Files:**
- Create: `app/src/main/java/dev/opencover/quick/Await.kt` (copy of FreeFlip `shell/Await.kt`, package changed)
- Modify: `app/src/main/java/dev/opencover/quick/ShizukuShell.kt` (`output`/`run` via one private `exec` with `await`, 30 s ceiling, as FreeFlip's)
- Test: `app/src/test/java/dev/opencover/quick/AwaitTest.kt` (copy of FreeFlip `AwaitTest`, package changed)

**Interfaces:**
- Produces: `fun await(p: Process, timeoutSeconds: Long): Finished?`, `data class Finished(val exit: Int, val out: String)`; `ShizukuShell.run/output` signatures unchanged.

- [ ] **Step 1:** Copy the test; run `./gradlew :app:testDebugUnitTest --tests '*AwaitTest'`. Expected: FAIL (unresolved `await`).
- [ ] **Step 2:** Copy `Await.kt`; route `ShizukuShell.output`/`run` through `exec`. Keep the existing KDoc wording, add "or it times out".
- [ ] **Step 3:** Run all unit tests. Expected: PASS.
- [ ] **Step 4:** Commit `Shizuku: bounded waits, drained output`.

### Task 2: Phone prefs and rotation logic

**Files:**
- Create: `app/src/main/java/dev/opencover/phone/PhonePrefs.kt`
- Create: `app/src/main/java/dev/opencover/phone/Rotation.kt`
- Test: `app/src/test/java/dev/opencover/phone/RotationTest.kt`

**Interfaces:**
- Produces:
  - `class PhonePrefs(ctx: Context)` on SharedPreferences `"phone"`: `turnsFor(pkg): Int`, `setTurns(pkg, turns)`, `hasRotations: Boolean`, `var mirrorTurns: Int`, `var restoreSettings: String?`, `var restoreAccessibility: Boolean`, `var resetPending: Boolean`, `var rotationResetPending: Boolean`, `var keepCoverOn: Boolean` (default false), `var heatGuard: Boolean` (default **true**). Keys as FreeFlip's, plus `heat_guard`.
  - `Rotation.kt` (pure, from FreeFlip `rotate/Rotation.kt`): `holdRotation(rotation: Int): String`, `nextTurns(turns: Int): Int`, `parseRotations(s: String?): Map<String, Int>`, `encodeRotations(m: Map<String, Int>): String`. Not ported: `turnsLabel`, `rotationNote`.
  - New: `fun flipTarget(stackList: String, skip: Set<String>): String?` parses `am stack list` output: walk `RootTask ... displayId=N` blocks in order (newest first); for blocks with `displayId=1` and `mActivityType=standard`, take the package of `topActivity=ComponentInfo{pkg/...}` on the `taskId=` line; return the first not in `skip`.

- [ ] **Step 1: Tests.** Port FreeFlip `turnsCycleThroughFourQuarters`, `holdingFixesTheCoverToUserRotationThenLocksIt`, `rotationsRoundTrip`, `uprightAndBadEntriesAreDropped`. Add, using a trimmed fixture copied from a real `am stack list` (home task `com.android.systemui` type `home`, then standard tasks `dev.opencover`, `com.facebook.orca`, a recents task, and one task with `displayId=0`):
  - `targetIsNewestCoverAppNotSkipped`: `flipTarget(fixture, setOf("dev.opencover", "com.sec.android.app.launcher"))` == `"com.facebook.orca"`
  - `targetUsesTopActivityPackage`: block whose taskId line is `com.samsung.android.goodlock/...` with `topActivity=ComponentInfo{com.samsung.android.multistar/...}` gives `"com.samsung.android.multistar"`
  - `innerDisplayAndRecentsAreIgnored`
  - `noTargetWhenOnlyOwnAndHome`: returns null
- [ ] **Step 2:** Run; expected FAIL (unresolved).
- [ ] **Step 3:** Implement both files.
- [ ] **Step 4:** Run; expected PASS. Commit `Phone: prefs and rotation logic`.

### Task 3: Per-app rotation on the cover

**Files:**
- Modify: `quick/Tiles.kt` (`ROTATION("Rotate", Need.SHIZUKU)`, comment updated)
- Modify: `quick/QuickState.kt` (ROTATION read and toggle)
- Create: `phone/RotationFeature.kt`
- Modify: `watcher/CoverAccessibility.kt` (`refreshScope`, `onAccessibilityEvent`)
- Modify: `ui/CoverActivity.kt` (`onResume`)
- Test: `quick/TilesTest.kt` (only if it asserts ROTATION is `LATER`; update it)

**Interfaces:**
- Consumes: Task 2.
- Produces: `object RotationFeature { fun onWindow(ctx: Context, pkg: String); fun flip(ctx: Context): String?; fun upright(ctx: Context); fun resetToDefault(ctx: Context) }`. `flip` returns a hint or null.

- [ ] **Step 1:** `RotationFeature` from FreeFlip `RotationFeature`, minus the `Feature` interface and `lastApp`:
  - `onWindow`: remember foreground; pin `turnsFor(pkg)` via `holdRotation` if it differs from the cover display's rotation. Single-thread executor for shell calls.
  - `flip` (blocking): `ShizukuShell.output("am", "stack", "list")` then `flipTarget(out, setOf(ctx.packageName, homePackage(ctx), "com.android.systemui"))`; null target returns `"Open an app on the cover first"`; else `setTurns(pkg, nextTurns(...))`, `CoverAccessibility.instance?.refreshScope()`, returns null.
  - `upright`: hold 0 if the cover's rotation isn't 0 (OpenCover itself and Samsung's cover home).
  - `resetToDefault`: as FreeFlip (hold 0, 1.5 s later clear `rotationResetPending` and re-apply foreground).
- [ ] **Step 2:** `CoverAccessibility`:
  - `refreshScope`: `packageNames = null` when `topEdge || PhonePrefs(this).hasRotations`; event types unchanged (window-state already included).
  - `onAccessibilityEvent`: for `TYPE_WINDOW_STATE_CHANGED` on `COVER_DISPLAY`, before the existing checks: `com.android.systemui` events call `upright` only when the class is `SAMSUNG_COVER_HOME`, own-package events are ignored (overlays), other packages call `onWindow`.
  - `onServiceConnected`: `if (PhonePrefs(this).rotationResetPending) RotationFeature.resetToDefault(this)`.
- [ ] **Step 3:** `QuickState`: `Tile.ROTATION` reads `false` always (an action, not a switch); toggle calls `RotationFeature.flip(ctx)` and returns its hint. `CoverActivity.onResume` calls `RotationFeature.upright` off the main thread when Shizuku is allowed.
- [ ] **Step 4:** Unit tests and build pass. Commit `Rotate tile: per-app cover rotation`.

### Task 4: Keep cover on when opened

**Files:**
- Create: `phone/Fold.kt` (from FreeFlip `fold/Fold.kt`; tag `opencover-fold-watch`; `FOLD_WATCH_STOP = "pkill -f opencover-fold-watch; pkill -f freeflip-fold-watch"`; plus `object Fold { fun apply(ctx: Context) }` from `FoldFeature.apply`)
- Create: `ui/PhoneSettings.kt` (`@Composable fun PhoneSettings(onBack: () -> Unit)`)
- Modify: `ui/SettingsPanel.kt` (a "Phone" button opening it, same pattern as "Home")
- Modify: `watcher/CoverAccessibility.kt` (`onServiceConnected` calls `Fold.apply`)
- Test: `phone/FoldTest.kt` (port FreeFlip's three tests; the watcher test also asserts the stop string contains `freeflip-fold-watch`)

**Interfaces:**
- Consumes: `PhonePrefs.keepCoverOn`, `ShizukuShell`.
- Produces: `foldStateArgs(keepCoverOn: Boolean): List<String>`, `Fold.apply(ctx)`. Task 6 adds the "no-op while mirroring" check.

- [ ] **Step 1:** Port tests; run, expect FAIL.
- [ ] **Step 2:** Implement `Fold.kt`.
- [ ] **Step 3:** `PhoneSettings`: switch "Keep cover on when opened" with note "Needs Shizuku. Stops the cover turning off when the phone opens." Toggling saves and calls `Fold.apply`. Without Shizuku allowed, the switch still saves and the note reads "Start Shizuku to apply".
- [ ] **Step 4:** Tests and build pass. Commit `Phone: keep cover on when opened`.

### Task 5: Heat guard

**Files:**
- Create: `phone/Heat.kt` (from FreeFlip `heat/Heat.kt`, plus `fun shown(level: Heat, active: Boolean): Heat = if (active) level else Heat.OK` and `const val AP_ENABLED = 13`)
- Create: `phone/HeatGuard.kt` (from `HeatFeature`, no owners: `fun start(ctx: Context)`, `fun stop()`)
- Modify: `watcher/CoverWatcher.kt` (`onCreate` starts, `onDestroy` stops)
- Modify: `ui/PhoneSettings.kt` (switch "Heat guard", note "Only while the hotspot or mirroring is on. Warns at 42 °C, stops both at 45 °C.")
- Test: `phone/HeatTest.kt`

**Interfaces:**
- Produces: `HeatGuard.start/stop`, `HeatGuard.evaluate()` callable by Task 6 when mirroring starts or stops.

- [ ] **Step 1: Tests.** Port FreeFlip's HeatTest. Add:
  - `inactiveShowsNothing`: `shown(Heat.CUT, active = false)` == `Heat.OK`
  - `activationWhileHotNotifies`: previous shown `OK`, `shown(Heat.CUT, true)` == `CUT` (so the ordinal rise triggers a notification)
- [ ] **Step 2:** Run, expect FAIL. Implement.
  - Raw level keeps updating from thermal status and battery temperature whatever the activity.
  - `active` = hotspot on (sticky `android.net.wifi.WIFI_AP_STATE_CHANGED`, extra `wifi_state` == `AP_ENABLED`; register that receiver too) or `MirrorService.instance != null`.
  - Notify when `shown` rises; cancel the notification when it drops to OK or the guard is switched off. Cut: stop mirroring, hotspot off (only if on), notify.
- [ ] **Step 3:** Tests and build pass. Commit `Phone: heat guard while hotspot or mirroring is on`.

### Task 6: Mirroring

**Files:**
- Create: `mirror/Mirror.kt` (FreeFlip `Mirror.kt` with `data class Box` from `launch/Bounds.kt` moved in; drop `withoutOwnService`/`withOwnService`, `withService` from `watcher/BankingRules.kt` replaces them)
- Create: `mirror/ConsentActivity.kt`, `mirror/MirrorActivity.kt`, `mirror/MirrorInput.kt`, `mirror/MirrorService.kt`, `mirror/MirrorEnv.kt` (from FreeFlip; `Prefs` -> `PhonePrefs`, `CoverLaunch.open` -> `AppLauncher.start`, `HeatFeature.start/stop` -> `HeatGuard.evaluate()`, `RotationFeature.resetToDefault()` -> `RotationFeature.resetToDefault(ctx)`, toast text "Start Shizuku and allow OpenCover first", display name `OpenCover-mirror`)
- Create: `app/src/main/java/dev/opencover/OpenCoverApp.kt` (`Application`, `onCreate` calls `MirrorEnv.recover(this)`)
- Modify: `apps/AppLauncher.kt` (`start` uses `CoverAccessibility.instance ?: ctx` as the starter, so the mirror view can open from the service)
- Modify: `quick/Tiles.kt` (add `MIRROR("Mirror", Need.SHIZUKU)` last; like any new tile it starts hidden for saved layouts and is added from the edit "+" page), `quick/QuickState.kt` (read: `MirrorService.instance != null`; toggle: on stops the service, off starts `ConsentActivity` with `AppLauncher.start`), `ui/QuickPanel.kt` (icon `Icons.Filled.ScreenShare`)
- Modify: `watcher/BankingMode.kt` (`resume` returns early while `MirrorService.instance != null`), `watcher/CoverWatcher.kt` (`openLauncher` returns early while mirroring), `phone/Fold.kt` (`apply` no-op while mirroring)
- Modify: `AndroidManifest.xml`: `android:name=".OpenCoverApp"`; permission `FOREGROUND_SERVICE_MEDIA_PROJECTION`; `.mirror.ConsentActivity` (theme `@android:style/Theme.Translucent.NoTitleBar`, not exported, excludeFromRecents), `.mirror.MirrorActivity` (resizeable, not exported, meta-data `com.samsung.android.multiwindow.show_on_cover` = `true`), `.mirror.MirrorService` (`foregroundServiceType="mediaProjection"`, not exported)
- Test: `mirror/MirrorTest.kt` (port FreeFlip's, minus the `withoutOwnService`/`withOwnService` tests)
- Test: `watcher/BankingRulesTest.kt` add `accessibilityRestoreOnlyWhenItWasOn`: `withService("a/b", me, false)` == `"a/b"` and `withService("a/b", me, true)` == `"a/b:$me"`

**Interfaces:**
- Consumes: Tasks 1-5.
- Produces: `MirrorService.instance`, `MirrorService.stop()`.
- Accessibility during a session: `MirrorEnv.hideAccessibility` saves `restoreAccessibility = (me in list)` and writes `withService(now, me, false)` through `ShizukuShell.run("settings", "put", "secure", "enabled_accessibility_services", ...)`; restore writes `withService(now, me, true)` only when it saved `true`. `me = ComponentName(ctx, CoverAccessibility::class.java).flattenToString()`.

- [ ] **Step 1:** Port tests and add the BankingRules one; run, expect FAIL.
- [ ] **Step 2:** Port the six files and the wiring above.
- [ ] **Step 3:** Tests and build pass. Commit `Mirror tile: mirror the inner screen with touch`.

### Task 7: Docs

**Files:** `README.md`, `NOTICE`, `docs/superpowers/specs/2026-10-10-core-launcher-design.md`

- [ ] **Step 1:** README: features (Rotate tile, Mirror tile, Settings > Phone), privacy (rotation widens the accessibility scope to window changes from all apps once an app has a saved rotation; no screen content; mirroring captures the inner screen only while the tile is on, nothing is stored), Shizuku needs.
- [ ] **Step 2:** NOTICE: "Rotation, keep-cover-on, mirroring and heat guard come from FreeFlip (same author, GPL-3.0)."
- [ ] **Step 3:** Roadmap: mark 7, 9, 11 done by this port; add a "Later" list: recents on the cover, force apps onto the cover and dismiss Samsung's prompt, long-press quick tile to its settings, unlock from the cover. One line each.
- [ ] **Step 4:** Commit `Docs: FreeFlip port`.

### Task 8: Device check

- [ ] **Step 1:** Ask the user before uninstalling FreeFlip (`adb uninstall dev.freeflip`); then install OpenCover.
- [ ] **Step 2:** Run the spec's device tests (Rotate, Keep cover on, Heat guard with `cmd thermalservice override-status 2|3` and `reset`, Mirroring). Restore any setting changed.
- [ ] **Step 3:** Fix and commit anything found.
