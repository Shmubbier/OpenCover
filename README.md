# OpenCover

A free, open-source (GPL-3.0) launcher for the Galaxy Z Flip 5 cover screen. No ads, no paywall.

## What works
- Home with wallpaper, clock and 4 shortcuts. Clock styles (Current, Big, Stacked, Analog, At a Glance) and widgets (battery, next alarm, weather) in Settings > Home. Swipe left for notifications, right for apps, up for media, down for quick settings.
- Long-press an empty spot on Home to move, resize or hide the clock and shortcuts.
- Backgrounds for Home and the lock screen: a photo, an animated GIF or an animated WebP, of any shape. After picking, drag and pinch to choose the part the cover shows; "Adjust" changes it later. Animations are made into a video once and played by the phone's video decoder (about a tenth of the CPU); Settings > Home can play them always, for the first 10 s, or not at all. Four panel styles (frosted glass, scrim, Material You tint, floating cards) in Settings > Home.
- Icons (Settings > Home): any Nova or ADW icon pack, Lawnchair's icon shapes, and themed icons in one colour taken from the wallpaper. Themed icons work best with [Lawnicons](https://github.com/lawnchairlauncher/lawnicons) by the Lawnchair team.
- Apps pages: put apps and widgets anywhere on a 4x4 grid. Swipe up for the app drawer.
- Opens automatically when you unlock the cover screen; Samsung's Home gesture returns to OpenCover.
- Notifications: swipe left from Home. Grouped by conversation, with actions, swipe to dismiss, and replies by text or voice. New ones pop up over any app.
- Media player: swipe up from Home. Tap to play or pause, swipe left or right to skip, swipe up for the next playing app, swipe down to close. Left edge seeks, right edge sets volume. Settings > Media player has a graded swipe-up option and a classic layout with buttons.
- Quick settings: swipe down from Home. Cover brightness, battery, and tiles for Wi-Fi, Bluetooth, flashlight, rotation, sound, Do Not Disturb, battery saver, location, mobile data, airplane, NFC, dark mode and keep awake. Long-press a tile to add, remove or move tiles.
- Keyboard (experimental, off by default): OpenCover's own keyboard on the cover (Samsung only allows its own keyboard app there). QWERTY, word suggestions, swipe typing, long-press popups. Turn it on in Settings > Experimental. Known issues: chat boxes don't move up for it, and it can stay open after swiping back.
- Rotate tile: turns the app you last had on the cover a quarter turn; it opens that way from then on. Settings > Phone lists every app's rotation. The cover never rotates by itself while folded.
- Mirror tile: shows the inner screen on the cover with touch, for apps that need the phone open. Pick "Share entire screen" in Android's dialog. The phone locks when mirroring ends.
- Settings > Phone: cover density, font size and animation speed (Shizuku), an optional inner-screen resize for mirroring (2:3 by default, put back afterwards), an inner screen launcher choice (Lawnchair recommended, it looks like OpenCover's Home when mirroring), keep the cover on when the phone is opened, and a heat guard that warns at 42 °C and stops the hotspot and mirroring at 45 °C (only while one of them is on).
- Settings > Lock screen: OpenCover's AOD (while the phone sleeps) and lock screen (swipe up for Samsung's PIN). Clock (Home's styles), date, battery, notification icons and a now-playing chip with play/pause; drag each anywhere, switch each off, then Lock. Tap the icons to peek at notifications. "Fingerprint opens Home" (Shizuku) switches Samsung's fingerprint "Stay on Lock screen": off keeps you on the lock screen after the power-key fingerprint, then a swipe opens without the PIN. The AOD needs Samsung's Always On Display set to Always with tap to show off, which keeps the cover dimly lit (some battery).
- OpenCover navigation bar (Settings, on by default): drawn over Samsung's cover bar, same look; Home goes straight to OpenCover, never Samsung's cover home. A Recents button opens One UI's recents on the cover. Gestures style: a pill with no background; swipe up for Home, long-press the pill for Recents (swipe up and hold also works with Samsung's buttons), swipe in from a side edge for Back. Works at any cover rotation and with Samsung's swipe gestures. A hide-keyboard button shows while a keyboard is up.
- Edge lighting (Settings, on by default): the cover's edges glow in the app's colour for new notifications. With the screen off or on the AOD it wakes the cover, glows and sleeps again (Shizuku); a touch keeps it awake. Not while you're using the cover (unlocked), the phone is open or in a call. Settings > Edge lighting: each app's own colour or one custom colour (colour wheel, brightness, hex), plus colours per app.
- Calls: one cover call screen, over the lock screen, with Answer, Decline, Answer muted and Decline and mute that person for 30 minutes; during the call a timer, Mute and Hang up. Not while the phone is open. Internet calls from apps (Messenger, Discord, Viber) are experimental, off by default: Settings > Experimental.
- Keep the cover screen awake in any app.
- "Open Samsung widgets" in settings to reach Samsung's own cover widgets.

## Install
Download the APK from the releases and follow the [setup guide](docs/SETUP.md).

To build it yourself (JDK: Android Studio's `jbr`):
- Debug: `.\gradlew.bat :app:assembleDebug`, then `adb install -r app\build\outputs\apk\debug\app-debug.apk`.
- Release: put `storeFile`, `storePassword`, `keyAlias` and `keyPassword` in `keystore.properties` at the project root (not in git), then `.\gradlew.bat :app:assembleRelease`. Without that file the release APK is unsigned.

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

## Shizuku (optional)
OpenCover works without [Shizuku](https://github.com/RikkaApps/Shizuku): apps open on the cover (once it can write secure settings, see the [setup guide](docs/SETUP.md#4-let-every-app-open-on-the-cover-computer-once)) and mirroring works (one-finger live touch) through its accessibility service. Shizuku adds extras, gathered in Settings > Shizuku: keep cover on, deep sleep, 5G, display tweaks, the inner screen launcher, background restriction and per-app rotation, plus multi-finger mirror touch, the Wi-Fi, Bluetooth, mobile data, airplane, NFC, battery saver and dark mode tiles, the heat guard's hotspot stop and the cover brightness slider.
1. Install the Shizuku APK from its GitHub releases: `adb install shizuku.apk`
2. Start it (again after every reboot), from Shizuku on the cover with Start via Wireless debugging, or from a computer:
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

## Battery
- Animated wallpapers play as hardware video (made once when set), or only for 10 s, or not at all (Settings > Home).
- Settings > Phone (Shizuku): deep sleep when idle (this phone never enters deep doze on its own), location scanning, 5G, and restricting apps in the background.
- Settings > Lock screen: black AOD, and turning the AOD off after 1 to 30 minutes.

## Credits
- [Lawnicons](https://github.com/lawnchairlauncher/lawnicons) by the Lawnchair team (Apache 2.0): the icon pack themed icons are made for. Install it separately; OpenCover doesn't include it.
- Rotation, keep cover on, mirroring and the heat guard come from FreeFlip (same author). See `NOTICE`.

## Status
Sub-project 1 of 8. See `docs/superpowers/specs/` for the roadmap.
