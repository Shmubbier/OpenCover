# OpenCover: Quick Settings (sub-project 3)

Date: 2026-10-11

## Goal

Change common settings from the cover screen without the main screen. Opens by swiping down on Home. Tiles are editable.

## Layout

```
+----------------------------+
| [sun]======O-------  85%   |
|                            |
|  (Wi)  (BT)  (Fl)  (Rot)   |
|  Wi-Fi  BT  Torch  Rotate  |
|                            |
|  (Snd) (DND) (Bat) (Loc)   |
|  Sound  DND  Saver  Loc    |
|            . o             |
+----------------------------+
```

- Top: brightness slider and battery level.
- Tiles: round, 4 x 2 per page, swipe sideways for more pages. Clear of the camera cutout.
- Swipe up or Back closes the panel.

## Tiles

Prefer non-Shizuku paths so most tiles work after a reboot before Shizuku is back.

| Tile | How | Needs |
|---|---|---|
| Wi-Fi | Shizuku `svc wifi enable/disable` | Shizuku |
| Bluetooth | Shizuku `svc bluetooth enable/disable` | Shizuku |
| Mobile data | Shizuku `svc data enable/disable` | Shizuku |
| Airplane | Shizuku `cmd connectivity airplane-mode enable/disable` | Shizuku |
| NFC | Shizuku `svc nfc enable/disable` | Shizuku |
| Battery saver | Shizuku `cmd power set-mode 1/0` | Shizuku |
| Dark mode | Shizuku `cmd uimode night yes/no` | Shizuku |
| Hotspot | Shizuku command, if one works (spike first; drop the tile if not) | Shizuku |
| Location | Secure setting `location_mode` | WRITE_SECURE_SETTINGS (granted) |
| Flashlight | CameraManager torch | none |
| Sound | AudioManager ringer: Ring, Vibrate, Mute | none |
| DND | NotificationAccess `requestInterruptionFilter` | notification access (granted) |
| Rotation | System setting `accelerometer_rotation` | Modify system settings |
| Keep awake | Existing KeepAwake setting | none |

- State comes from the system and updates live (receivers, callbacks, settings observers), so tiles match changes made in Samsung's panel.
- A tile missing what it needs is grey. Tap shows a hint: "Start Shizuku" or "Grant in Setup".
- Brightness: spike first. If the cover screen has its own brightness setting, the slider sets it. If not, the slider dims only OpenCover's window.

## Editing

- Long-press any tile: edit mode.
- Each tile shows a remove badge. A last "+" page lists hidden tiles; tap to append.
- Long-press and drag a tile to reorder.
- "Done" or Back leaves edit mode.

## Data

- SharedPreferences "quick", key "tiles": ordered tile ids.
- Unknown ids are dropped on load. Tiles added in later versions start hidden.
- Default order: Wi-Fi, Bluetooth, Flashlight, Rotation, Sound, DND, Battery saver, Location, Mobile data, Airplane, Hotspot, NFC, Dark mode, Keep awake.

## Components

- `quick/Tiles.kt`: tile ids, default order, pure list logic (add, remove, move, load), sound cycle, availability rule.
- `quick/Shizuku.kt`: running and permission state, run a command.
- `quick/TileState.kt`: reads and observes live state; performs toggles.
- `ui/QuickPanel.kt`: slider, battery, tile pages, edit mode.
- Setup checklist: Shizuku (installed, running, allowed) and Modify system settings.

## Setup (README)

- `adb shell appops set dev.opencover WRITE_SETTINGS allow`
- Install Shizuku, start it over adb, allow OpenCover.
- Start on boot without a PC (one time): grant Shizuku WRITE_SECURE_SETTINGS and pair it with wireless debugging. Needs Wi-Fi at boot.

## Dependencies

Shizuku API (`dev.rikka.shizuku:api` and `provider`).

## Testing

- Unit: list logic (add, remove, move, unknown and new ids), sound cycle, availability rule.
- Device: each tile toggles and matches Samsung's panel; brightness; editing; tiles recover after Shizuku restarts at boot.

## Out of scope

Pull-down strip over other apps. Falling back to tapping Samsung's panel tiles. Volume slider (in the media panel).
