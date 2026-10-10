# OpenCover: Core Launcher Design (sub-project 1)

Date: 2026-10-10
Status: draft for review

## Goal

A FOSS (GPL-3.0) launcher that makes the Galaxy Z Flip 5's cover screen usable as the
whole phone, for a device whose main screen is broken. Replaces CoverScreen OS (ads,
paywall) and supersedes MultiStar for daily use.

This spec covers sub-project 1 of 8 (see "Roadmap"). It must leave clean extension
points for later sub-projects but must not build them.

## Target device (measured)

- SM-F731B, Android 16 (SDK 36), One UI 8.5.
- Cover screen = logical display **1**, 748×720 px, 340 dpi (≈352×339 dp).
  Physical id `4630947181303254916` (for `adb shell screencap -d`).
- Samsung's cover home is `com.android.systemui/.subscreen.SubHomeActivity` on display 1.
- Samsung Back/Home gestures work on the cover screen inside apps.

## Verified finding (spike, 2026-10-10)

An ordinary, unprivileged app calling
`startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(1).toBundle())`
shows any app (tested: Chrome, which is **not** in MultiStar's allow-list) on the cover
screen. No Shizuku, root, or Accessibility service is required to launch apps.

## Decisions

| Topic | Decision |
|---|---|
| Stack | Kotlin + Jetpack Compose, written from scratch |
| License | GPL-3.0 |
| Package | `dev.opencover` |
| SDK | minSdk 34, targetSdk 36, compileSdk 37 |
| Entry | Auto-opens on cover unlock and on fold |
| Layout | Swipeable home pages; each page is one editable grid of apps + widgets; swipe up for the drawer |
| Grid | Fixed 4×4 cells per page (~88 dp cells) |
| Navigation | Samsung's own gestures. Back unchanged. Home is redirected to our launcher (see CoverAccessibility) |
| Storage | One JSON file via `org.json`, temp-file + rename |
| Dependencies | Kotlin, Compose BOM, activity-compose, JUnit. Nothing else. |

## Components

### CoverActivity
The launcher UI, always running on display 1, `launchMode="singleTask"` so repeated
launches never stack. Contains:
- **Home pager**: horizontal pages, each rendering one 4×4 grid.
- **App drawer**: opened by swiping up on home.
- **Edit mode**: entered by long-press.
- **Settings**: keep-awake switch, pause-redirect button, "Open Samsung widgets" button.
- **First-run checklist**: shown instead of home until required permissions are granted.

### CoverWatcher (foreground service)
- "Cover active" = display 1 is ON and display 0 is not ON, read from `DisplayManager`
  (a `DisplayListener` reports changes). This avoids `DeviceStateManager`, whose callback
  API differs between SDK 34 and 35.
- Starts `CoverActivity` on display 1 when the cover becomes active while unlocked, and on
  `ACTION_USER_PRESENT` while the cover is active.
- **Pause:** "Open Samsung widgets" sets a pause flag; it clears on screen off.
- Attaches the invisible 1×1 overlay (see Keep-awake) on display 1.

### CoverAccessibility (accessibility service): added 2026-10-10
Android 16 blocks background activity starts from an app that only holds
`SYSTEM_ALERT_WINDOW`, even with a visible overlay window (logcat `BAL_BLOCK`,
`callingUidHasNonAppVisibleWindow: true`). An app with a system-bound accessibility service
is exempt, so:
- **Home redirect:** listens only for `TYPE_WINDOW_STATE_CHANGED`. When the event is
  `com.android.systemui` / `SubHomeActivity` on display 1 and redirect is not paused, it
  starts `CoverActivity`. Instant; replaces the 300 ms UsageStats polling (deleted).
- Its presence also exempts CoverWatcher's unlock/fold starts.
- `canRetrieveWindowContent="false"`: it never reads screen contents.

### NotificationAccess (notification listener): added 2026-10-10
Empty `NotificationListenerService`, granted from the checklist now so sub-project 2
(notifications + media) can use it. Being system-bound also keeps the process alive.

### AppLauncher
`launch(component, user)`: starts the app's launch intent on display 1 with
`LauncherApps.startMainActivity(component, user, null, options)` so apps in other
profiles (Dual Messenger, work profile) launch as the right user, and an existing task
is resumed rather than duplicated.
Failure (`ActivityNotFoundException`, `SecurityException`) shows a toast; never crashes.

### GridEngine (pure Kotlin, no Android imports)
Operates on `Page(items)` where every item has `x, y, w, h` in cells on a 4×4 grid.
- `canPlace(page, rect, ignoreId?)`: inside bounds and no overlap.
- `move(page, id, x, y)` / `resize(page, id, w, h)`: returns new page or rejection.
- `firstFreeSpot(page, w, h)`: row-major scan, null if none.
- Immutable inputs and outputs. Rejected edits leave the layout unchanged.

### WidgetHost
Wraps `AppWidgetHost` / `AppWidgetManager`.
- Picker lists `getInstalledProvidersForProfile`.
- Add flow: allocate id → `bindAppWidgetIdIfAllowed`; if refused, launch
  `ACTION_APPWIDGET_BIND` on display 1 → if the provider has a configure activity,
  `startAppWidgetConfigureActivityForResult` with display-1 options → place it.
- Initial size = provider min size converted to cells, clamped to 1..4 per axis, at
  `firstFreeSpot`. If nothing fits, offer to add it on a new page.
- Cancel or failure at any step → `deleteAppWidgetId`. No orphaned ids.
- Rendering: `AppWidgetHostView` inside Compose `AndroidView`, sized to its cell rect;
  `updateAppWidgetSize` is called on resize. Collection widgets scroll natively; the
  pager is horizontal-only so vertical widget scrolling is not intercepted.

### LayoutStore
- File: `filesDir/layout.json`, written to `layout.json.tmp` then renamed over the original
  (atomic on Android's filesystem, and plain JVM code so it is unit-testable without
  Robolectric).
- Pruning missing apps at load is skipped when the installed-app list comes back empty,
  so a transient failure can never wipe the layout.
- Schema:
  ```json
  { "version": 1,
    "pages": [ { "items": [
      { "id": "uuid", "type": "app", "x": 0, "y": 0, "w": 1, "h": 1,
        "package": "com.android.chrome", "activity": "com.google...Main", "user": 0 },
      { "id": "uuid", "type": "widget", "x": 0, "y": 1, "w": 4, "h": 2,
        "widgetId": 12, "provider": "pkg/cls" } ] } ] }
  ```
- Unreadable or invalid file → renamed to `layout.bad.json`; a default layout is created
  (one page with the 8 most recently used apps).

### App drawer
- Source: `LauncherApps.getActivityList` for all profiles; `LauncherApps.Callback`
  for live install/uninstall/update.
- Search box filters by name (case-insensitive substring); an A–Z letter index jumps to
  that letter.
- Sort: A–Z, or recently used (`UsageStats.lastTimeUsed`).
- Uninstalled apps are also removed from home pages.

### Keep-awake
A 1×1 px invisible `TYPE_APPLICATION_OVERLAY` window on display 1 is attached while
CoverWatcher runs. The keep-awake switch adds `FLAG_KEEP_SCREEN_ON` to it, so the cover
stays on in any app; the flag is cleared when switched off or when the device is OPENED.

## First-run checklist
Each row opens the matching Settings screen on display 1. Home shows only once the
required rows are granted.
- **Required:** Accessibility (OpenCover service), Display over other apps.
- **Recommended:** ignore battery optimizations.
- **Optional:** Usage access (drawer "Recent" sort, default layout), Notification access
  (for sub-project 2), Notifications (watcher's status notification).
- Sideloaded apps on Android 13+ must first get "Allow restricted settings" from App info
  before Accessibility and Notification access can be switched on; the checklist row for
  Accessibility also offers an "App info" button for that.

## Risks: verified first, before feature work

| # | Assumption | Fallback if false | Result |
|---|---|---|---|
| R1 | Widget bind dialog can be shown on display 1 | One-time `adb shell appwidget grantbind --package dev.opencover` | PASS 2026-10-10: Samsung "Allow access to widget content?" dialog shows on the cover; "Always allow" ticked |
| R2 | UsageEvents report `SubHomeActivity` resumes promptly (<500 ms) | AccessibilityService listening only for `TYPE_WINDOW_STATE_CHANGED` | PASS 2026-10-10: SubHomeActivity resume seen and redirect issued in the same 300 ms poll |
| R3 | `SYSTEM_ALERT_WINDOW` permits starting `CoverActivity` from the service on unlock | A MultiStar tile that launches the launcher manually | PARTIAL 2026-10-10: unlock starts passed, but starts during normal use (Home redirect) are `BAL_BLOCK`ed on Android 16 even with a visible overlay. Resolved with CoverAccessibility (user-approved), not the MultiStar tile |

## Testing
- **Unit (JUnit):** GridEngine covers placement, overlap, bounds, move and resize
  rejection, `firstFreeSpot` on full and empty pages. LayoutStore covers round-trip and
  falling back to default on a corrupt file.
- **On device:** install over ADB, launch on display 1, verify with cover screenshots
  (`screencap -d 4630947181303254916`). Each feature task ends with a screenshot check.

## Out of scope (v1)
Configurable grid size, icon packs, themes, landscape/rotation handling, notifications,
media, quick toggles (except keep-awake), keyboard, clock face, edge lighting, VoIP,
AOD/lock screen. Those belong to later sub-projects.

## Roadmap (later sub-projects, each gets its own spec)
2. Notifications + media · 3. Quick toggles · 4. Keyboard (T9/QWERTY IME) ·
5. Clock face + edge lighting · 6. VoIP caller ID · 7. Rotation (spike first) ·
8. AOD / lock screen (spike first, may be infeasible)
