# OpenCover: Navigation Shell and Home (sub-project 2a)

Date: 2026-10-10

## Goal

Replace the single row of app pages with a swipe layout centered on a new Home screen.
Notifications (2b), media (2c) and quick settings (3) plug into it later.

## Layout

| Page | Swipe up | Swipe down |
|---|---|---|
| 0 Notifications (placeholder) | - | - |
| 1 Home (start page) | Media player (placeholder) | Quick settings (placeholder) |
| 2+ Apps pages (existing grids) | App drawer | - |

Home gesture, unlock and fold always land on Home with panels closed and edit mode off.

## Components

- **Shell** (replaces LauncherScreen): horizontal pager plus pull panels. Pager page `n >= 2` maps to layout page `n - 2`.
- **PullPanel**: slides in from top or bottom, follows the finger, snaps open or closed. Used by the drawer, media and quick settings.
- **HomeScreen**: wallpaper, large clock and date (updates each minute), 4 quick-app slots at the bottom left (clear of the camera cutout). Tap opens the app. Long-press picks an app from the drawer or clears the slot.
- **Wallpaper**: "Set wallpaper" in Settings opens the system photo picker on display 1. The image is scaled to 748x720 and saved as `wallpaper.jpg`. Missing or unreadable means a black background.
- Edit mode stays on apps pages only.

## Data

`layout.json` gains an optional `home` object; files without it load with empty slots.

```json
"home": { "quick": [ {"package": "p", "activity": "a", "user": 0}, null, null, null ] }
```

- Uninstalls and load-time pruning clear quick slots the same way as grid icons, per profile.
- A malformed `home` object falls back to empty slots without discarding the pages.

## Edge cases

- Removing the current apps page moves to the previous page, never past Home.
- Vertical swipes on a scrolling widget stay with the widget; panels open from empty space or icons.
- If the photo picker fails to open, show a short message and keep the current wallpaper.

## Testing

- Unit: page mapping, `home` JSON round trip, old file without `home`, malformed `home`, quick-slot pruning per profile, set and clear slot.
- Device: starts on Home, left and right reach notifications and apps, each panel opens and tracks the finger, Home/unlock/fold return to Home, wallpaper picker, quick-slot editing.

## Out of scope

Notification, media and quick settings content. Clock styles. GIF and video wallpapers.
Replacing Samsung's lock screen (sub-project 8).
