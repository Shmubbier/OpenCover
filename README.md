# OpenCover

A free, open-source (GPL-3.0) launcher for the Galaxy Z Flip 5 cover screen. No ads, no paywall.

## What works
- Home with wallpaper, clock and 4 shortcuts. Clock styles (Current, Big, Stacked, Analog, At a Glance) and widgets (battery, next alarm, weather) in Settings > Home. Swipe left for notifications, right for apps, up for media, down for quick settings.
- Apps pages: put apps and widgets anywhere on a 4x4 grid. Swipe up for the app drawer.
- Opens automatically when you unlock the cover screen; Samsung's Home gesture returns to OpenCover.
- Notifications: swipe left from Home. Grouped by conversation, with actions, swipe to dismiss, and replies by text or voice. New ones pop up over any app.
- Media player: swipe up from Home. Tap to play or pause, swipe left or right to skip, swipe up for the next playing app, swipe down to close. Left edge seeks, right edge sets volume. Settings > Media player has a graded swipe-up option and a classic layout with buttons.
- Quick settings: swipe down from Home. Cover brightness, battery, and tiles for Wi-Fi, Bluetooth, flashlight, rotation, sound, Do Not Disturb, battery saver, location, mobile data, airplane, NFC, dark mode and keep awake. Long-press a tile to add, remove or move tiles.
- Keyboard (experimental, off by default): OpenCover's own keyboard on the cover (Samsung only allows its own keyboard app there). QWERTY, word suggestions, swipe typing, long-press popups. Turn it on in Settings > Experimental. Known issues: chat boxes don't move up for it, and it can stay open after swiping back.
- Rotate tile: turns the app you last had on the cover a quarter turn; it opens that way from then on. The cover never rotates by itself while folded.
- Mirror tile: shows the inner screen on the cover with touch, for apps that need the phone open. Pick "Share entire screen" in Android's dialog. The phone locks when mirroring ends.
- Settings > Phone: keep the cover on when the phone is opened, and a heat guard that warns at 42 °C and stops the hotspot and mirroring at 45 °C (only while one of them is on).
- Settings > Lock screen: OpenCover's AOD (while the phone sleeps) and lock screen (swipe up for Samsung's PIN). Clock (Home's styles), date, battery, notification icons and a now-playing chip with play/pause; drag each anywhere, switch each off, then Lock. Tap the icons to peek at notifications. "Fingerprint opens Home" (Shizuku) switches Samsung's fingerprint "Stay on Lock screen": off keeps you on the lock screen after the power-key fingerprint, then a swipe opens without the PIN. The AOD needs Samsung's Always On Display set to Always with tap to show off, which keeps the cover dimly lit (some battery).
- OpenCover navigation bar (Settings, on by default): drawn over Samsung's cover bar, same look; Home goes straight to OpenCover, never Samsung's cover home. A Recents button opens One UI's recents on the cover. Gestures style: swipe up for Home, swipe up and hold for Recents, swipe in from a side edge for Back.
- Edge lighting (Settings, on by default): the cover's edges glow in the app's colour for new notifications. With the screen off or on the AOD it wakes the cover, glows and sleeps again (Shizuku); a touch keeps it awake. Not while you're using the cover (unlocked), the phone is open or in a call. Settings > Edge lighting: each app's own colour or one custom colour (colour wheel, brightness, hex), plus colours per app.
- Calls (phone, Messenger, Discord, Viber and other apps): one cover call screen, over the lock screen, with Answer, Decline, Answer muted and Decline and mute that person for 30 minutes; during the call a timer, Mute and Hang up. Not while the phone is open.
- Keep the cover screen awake in any app.
- "Open Samsung widgets" in settings to reach Samsung's own cover widgets.

## Install
1. Build: `.\gradlew.bat :app:assembleDebug` (JDK: Android Studio's `jbr`).
2. `adb install -r app\build\outputs\apk\debug\app-debug.apk`
3. Open OpenCover on the cover screen and follow the setup checklist. Because the app is
   sideloaded, Android first needs App info → ⋮ → **Allow restricted settings** before the
   Accessibility and Notification access switches can be turned on.
4. Turn off Samsung's own cover notification pop-ups to avoid doubles: Settings > Notifications > Cover screen.

## Why Accessibility?
On Android 16, only apps with a system-bound service such as an accessibility service may
bring their screen back from the background, which is what returning to OpenCover on Home
needs.

- By default the service only hears window changes from Samsung's system UI and the banking
  apps you pick. It never reads screen content.
- OpenCover's keyboard uses the service's input method (Android 13+): Android connects it to the
  text field you're typing in, so it can insert and delete text and show suggestions from the
  words around the cursor. Nothing is stored or sent anywhere.
- The experimental "Top-edge priority" setting (off by default) also reads the window list to
  catch Samsung's pull-down panel. While it's on, window-change events from every app arrive
  (they can include short text such as dialog titles; OpenCover doesn't read it). Android
  requires the screen-access capability to be declared up front, so the Accessibility consent
  screen mentions it either way.
- Once an app has a saved rotation, window-change events from every app arrive, to know which
  app is on the cover. Screen content is still never read.
- Mirroring captures the inner screen only while the Mirror tile is on, and turns OpenCover's
  accessibility service, developer options and unknown sources off for the session (banking
  apps refuse to run with them on). Everything is restored when it ends. Nothing is stored.
- While OpenCover's lock screen is on, the service reads the window list on the cover (not their
  content), so it never covers an incoming call or alarm.
- The navigation bar follows Samsung's through the window list, so window-change events from every app arrive while it is on (no content).
- Only the opt-in weather widget uses the internet: it sends rough coordinates (about 1 km) to
  Open-Meteo every 30 minutes at most, while the screen is on. Nothing else leaves your phone.

## Shizuku
Wi-Fi, Bluetooth, mobile data, airplane, NFC, battery saver, dark mode, Rotate, Mirror, keep cover on, the heat guard's hotspot stop and the cover brightness slider need [Shizuku](https://github.com/RikkaApps/Shizuku) (no root). Without it those tiles are grey and the slider only dims OpenCover.
1. Install the Shizuku APK from its GitHub releases: `adb install shizuku.apk`
2. Start it (again after every reboot):
   `adb shell 'p=$(pm path moe.shizuku.privileged.api | cut -d: -f2); $(dirname $p)/lib/arm64/libshizuku.so'`
3. Shizuku's permission dialog opens on the main screen, so allow OpenCover from the cover instead:
   `adb shell am start --display 1 -n moe.shizuku.privileged.api/moe.shizuku.manager.MainActivity`,
   then Authorized applications, turn on OpenCover.

## Banking apps
Some banking apps refuse to run while any accessibility service is on. Pick them under
Settings > Banking apps and OpenCover steps aside while they're open. To let it turn itself back
on afterwards, run once: `adb shell pm grant dev.opencover android.permission.WRITE_SECURE_SETTINGS`.
That permission allows changing any secure setting; OpenCover only uses it to switch its own
accessibility service. It also needs Usage access to notice when you leave the banking app.

## Status
Sub-project 1 of 8. See `docs/superpowers/specs/` for the roadmap.
