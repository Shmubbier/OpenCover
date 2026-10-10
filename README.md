# OpenCover

A free, open-source (GPL-3.0) launcher for the Galaxy Z Flip 5 cover screen. No ads, no paywall.

## What works
- Home with wallpaper, clock and 4 shortcuts. Swipe left for notifications, right for apps, up for media, down for quick settings.
- Apps pages: put apps and widgets anywhere on a 4x4 grid. Swipe up for the app drawer.
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
needs.

- By default the service only hears window changes from Samsung's system UI and the banking
  apps you pick. It never reads screen content or what you type.
- The experimental "Top-edge priority" setting (off by default) also reads the window list to
  catch Samsung's pull-down panel. While it's on, window-change events from every app arrive
  (they can include short text such as dialog titles; OpenCover doesn't read it). Android
  requires the screen-access capability to be declared up front, so the Accessibility consent
  screen mentions it either way.
- OpenCover has no internet permission. Nothing leaves your phone.

## Banking apps
Some banking apps refuse to run while any accessibility service is on. Pick them under
Settings > Banking apps and OpenCover steps aside while they're open. To let it turn itself back
on afterwards, run once: `adb shell pm grant dev.opencover android.permission.WRITE_SECURE_SETTINGS`.
That permission allows changing any secure setting; OpenCover only uses it to switch its own
accessibility service. It also needs Usage access to notice when you leave the banking app.

## Status
Sub-project 1 of 8. See `docs/superpowers/specs/` for the roadmap.
