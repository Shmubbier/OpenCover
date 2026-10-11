# OpenCover: Core Launcher Design (sub-project 1)

Date: 2026-10-10

## Goal

A FOSS (GPL-3.0) launcher that makes the Galaxy Z Flip 5 cover screen usable as the whole
phone when the main screen is broken. Replaces CoverScreen OS and MultiStar.

## Target device

- SM-F731B, Android 16, One UI 8.5.
- Cover screen: display 1, 748x720 px, 340 dpi. Screenshot id `4630947181303254916`.
- Samsung's cover home: `com.android.systemui/.subscreen.SubHomeActivity`.

Any app can open other apps on display 1 with `ActivityOptions.setLaunchDisplayId(1)`.
No allowlist, root or Shizuku needed.

## Decisions

| Topic | Decision |
|---|---|
| Stack | Kotlin + Jetpack Compose |
| SDK | min 34, target 36, compile 37 |
| Entry | Opens on unlock and on fold |
| Layout | Swipeable pages, each a 4x4 grid of apps and widgets. Swipe up for the drawer |
| Navigation | Samsung's gestures. Home is redirected to OpenCover |
| Storage | One JSON file |
| Dependencies | Kotlin, Compose, activity-compose, JUnit |

## Components

- **CoverActivity**: the launcher UI on display 1. Home pages, drawer, edit mode, settings, setup checklist.
- **CoverWatcher** (foreground service): opens the launcher on unlock or fold. Holds a 1x1 invisible overlay used for keep-awake.
- **CoverAccessibility**: redirects Home to OpenCover when Samsung's cover home appears (needed because Android 16 blocks background starts for overlay-only apps), steps aside for banking apps, and optionally (experimental, off by default) replaces Samsung's pull-down panel. Skips while locked.
- **NotificationAccess**: empty listener, reserved for sub-project 2.
- **AppLauncher**: opens apps on display 1 as the right profile.
- **GridEngine**: pure Kotlin placement, collision and free-spot logic.
- **WidgetHost**: bind, configure and display widgets on display 1. Frees ids on cancel.
- **LayoutStore**: saves `layout.json` atomically. A bad file is kept as `layout.bad.json` and a default layout is used.

## Edit mode

Long-press an item and drag. Drop onto a same-sized item to swap. Resize widgets with the
corner handle. Add apps by long-pressing them in the drawer. A full page offers a new page.

## Setup checklist

- Required: Accessibility, Display over other apps.
- Recommended: ignore battery optimization.
- Optional: usage access, notification access, notifications.
- Sideloaded installs need App info > Allow restricted settings first.

## Data safety

- Missing apps are not pruned if the app list is empty or the profile is locked.
- Uninstalling one profile's copy only removes that profile's icons and widgets.

## Banking mode

Some banking apps (e.g. GCash) refuse to run while any third-party accessibility service is on.

- You pick banking apps in Settings.
- Opening one turns OpenCover's accessibility service off first. If the app is opened another way, the service sees it and turns itself off.
- Leaving the app (Home, OpenCover, or another launchable app) turns it back on.
- Turning it back on needs `WRITE_SECURE_SETTINGS`, granted once by the user: `adb shell pm grant dev.opencover android.permission.WRITE_SECURE_SETTINGS`. Without it, OpenCover still turns itself off but you turn it back on by hand.

## Out of scope

Folders, grid size options, icon packs, themes, rotation, notifications, toggles, keyboard,
clock face, edge lighting, VoIP, AOD.

## Roadmap

2. Navigation shell and Home (2a), notifications (2b), media (2c)
3. Quick toggles: swipe down on Home. Over other apps, a thin pull-down strip just below the status bar. OpenCover hides the status bar so Samsung's panel only peeks.
4. Keyboard: OpenCover overlay keyboard (Samsung forces its own keyboard app on the cover). 4a QWERTY, suggestions, swipe typing. 4b T9. 4c emoji and voice key.
5. Clock face and edge lighting
6. VoIP caller ID
7. Rotation: let apps that are stuck in one orientation rotate on the cover screen. Today some apps only flip when the phone is slightly opened, and snap back when closed.
8. AOD and lock screen
9. Cover screen while open: keep the cover screen on and usable when the phone is unfolded.
10. Own navigation: avoid the Samsung widgets flash on Home. Options: an OpenCover gesture zone above Samsung's, or OpenCover as default Home app (spike first; Samsung may keep its own cover home).
11. Screen mirroring: show the main screen on the cover display with full touch, clicks and swipes, while folded. Spike first: the main display is off when folded, so this may need a virtual display apps launch into, plus input injected through Shizuku.
