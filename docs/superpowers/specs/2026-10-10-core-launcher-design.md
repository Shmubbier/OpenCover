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
- **CoverAccessibility**: redirects Home to OpenCover when Samsung's cover home appears. Needed because Android 16 blocks background starts for overlay-only apps. Listens to `com.android.systemui` window events only and never reads screen content. Skips while locked.
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

## Out of scope

Folders, grid size options, icon packs, themes, rotation, notifications, toggles, keyboard,
clock face, edge lighting, VoIP, AOD.

## Roadmap

2. Notifications and media
3. Quick toggles
4. Keyboard (T9 and QWERTY)
5. Clock face and edge lighting
6. VoIP caller ID
7. Rotation
8. AOD and lock screen
