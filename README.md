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
3. Open OpenCover on the cover screen and follow the setup checklist. Because the app is
   sideloaded, Android first needs App info → ⋮ → **Allow restricted settings** before the
   Accessibility and Notification access switches can be turned on.

## Why Accessibility?
On Android 16, only apps with a system-bound service such as an accessibility service may
bring their screen back from the background, which is what returning to OpenCover on Home
needs. The service receives window-change events from Samsung's system UI only
(`packageNames="com.android.systemui"`, `canRetrieveWindowContent="false"`): it never sees
other apps, screen contents, or what you type.

## Status
Sub-project 1 of 8. See `docs/superpowers/specs/` for the roadmap.
