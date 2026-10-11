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
| Wi-Fi | Shizuku `cmd wifi set-wifi-enabled` | Shizuku |
| Bluetooth | Shizuku `cmd bluetooth_manager enable/disable` | Shizuku |
| Mobile data | Shizuku `cmd phone data enable/disable` | Shizuku |
| Airplane | Shizuku `cmd connectivity airplane-mode enable/disable` | Shizuku |
| NFC | Shizuku `svc nfc enable/disable` | Shizuku |
| Battery saver | Shizuku `cmd power set-mode 1/0` | Shizuku |
| Dark mode | Shizuku `cmd uimode night yes/no` | Shizuku |
| Location | Secure setting `location_mode` | WRITE_SECURE_SETTINGS (granted) |
| Flashlight | CameraManager torch | none |
| Sound | AudioManager ringer: Ring, Vibrate, Mute | none |
| DND | NotificationAccess `requestInterruptionFilter` | notification access (granted) |
| Rotation | System setting `accelerometer_rotation` | WRITE_SECURE_SETTINGS (granted) |
| Keep awake | Existing KeepAwake setting | none |

- State comes from the system and updates live (receivers, callbacks, settings observers), so tiles match changes made in Samsung's panel.
- A tile missing what it needs is grey. Tap shows a hint: "Start Shizuku" or "Grant in Setup".
- Brightness: the slider sets Samsung's cover setting `sub_screen_brightness`. If that has no live effect, it dims only OpenCover's window.
- Hotspot: dropped, Android 16 has no shell command for it.

## Editing

- Long-press any tile: edit mode.
- Each tile shows a remove badge. A last "+" page lists hidden tiles; tap to append.
- Long-press and drag a tile to reorder.
- "Done" or Back leaves edit mode.

## Data

- SharedPreferences "quick", key "tiles": ordered tile ids.
- Unknown ids are dropped on load. Tiles added in later versions start hidden.
- Default order: Wi-Fi, Bluetooth, Flashlight, Rotation, Sound, DND, Battery saver, Location, Mobile data, Airplane, NFC, Dark mode, Keep awake.

## Components

- `quick/Tiles.kt`: tile ids, default order, pure list logic (add, remove, move, load), sound cycle, availability rule.
- `quick/Shizuku.kt`: running and permission state, run a command.
- `quick/TileState.kt`: reads and observes live state; performs toggles.
- `ui/QuickPanel.kt`: slider, battery, tile pages, edit mode.
- Setup checklist: Shizuku (installed, running, allowed).

## Setup (README)

- Install Shizuku, start it over adb, allow OpenCover.
- Start on boot without a PC (one time): grant Shizuku WRITE_SECURE_SETTINGS and pair it with wireless debugging. Needs Wi-Fi at boot.

## Dependencies

Shizuku API (`dev.rikka.shizuku:api` and `provider`).

## Testing

- Unit: list logic (add, remove, move, unknown and new ids), sound cycle, availability rule.
- Device: each tile toggles and matches Samsung's panel; brightness; editing; tiles recover after Shizuku restarts at boot.

## Out of scope

Pull-down strip over other apps. Falling back to tapping Samsung's panel tiles. Volume slider (in the media panel).
