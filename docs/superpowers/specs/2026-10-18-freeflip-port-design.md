# OpenCover: FreeFlip port (rotation, keep cover on, mirroring, heat guard)

Date: 2026-10-18

## Goal

Bring four features from FreeFlip (the user's own GPL-3.0 app, `D:\TenTne Projects\FreeFlip`, commit `92d2873`) into OpenCover, so one app runs the cover. All four were tested on the user's phone in FreeFlip. Covers roadmap items 7 (rotation), 9 (cover while open) and 11 (mirroring), plus a new heat guard.

Source to port: `FreeFlip_handoff/source/` (local, not committed). Its README holds the hard-won device facts; follow it.

## Device limits (drive the design)

- No accelerometer, gyroscope or magnetometer: auto-rotate can't work. Rotation is manual, per app.
- The cover never rotates from app or overlay orientation requests. Only `cmd window fixed-to-user-rotation -d 1 enabled; cmd window user-rotation -d 1 lock N` through Shizuku works.
- The phone overheated badly before (hotspot). Reboots are risky: never reboot to test.
- GCash refuses to run with developer options, unknown sources, or a non-allowlisted accessibility service on.

## Features

### Per-app rotation (roadmap 7)
- The "Rotate" quick tile (now `Need.LATER`) becomes the flip button, needs Shizuku. Tap: the last real app on the cover turns a quarter turn clockwise; saved per package (0 to 3 turns). Not a toggle: it never shows "on".
- Whenever an app's window appears on the cover, its saved rotation is pinned. Apps never flipped stay upright. OpenCover and the launcher count as apps (upright) but are never the flip target; System UI windows are ignored.
- Scope: the accessibility service watches window changes from all apps only while at least one app has a saved rotation; otherwise its scope stays as today. Screen content is never read.
- Tapping the tile from OpenCover's quick panel: the target is the app shown before OpenCover (last real app), and the turn applies when that app comes back.
- First flip (no saved rotations, so the narrow scope never saw the app): the target comes from the cover's task list through Shizuku at tap time (newest task on display 1 that isn't OpenCover or the launcher). My call: keeps the narrow scope until a rotation exists.

### Keep cover on when opened (roadmap 9)
- Settings switch "Keep cover on when opened", off by default, needs Shizuku.
- Holds device state 1 (TENT) plus FreeFlip's detached shell watcher that re-requests it after each open or close. Re-applied when the accessibility service connects. Off: watcher stopped, `cmd device_state state reset`.
- Watcher tag becomes `opencover-fold-watch`. On apply, also stop `freeflip-fold-watch` (left over from FreeFlip).

### Mirroring (roadmap 11)
- New quick tile "Mirror", needs Shizuku. Tap: Android's screen-share dialog on the cover; the user picks "Share entire screen" themselves (nothing auto-accepts).
- Session as in FreeFlip: device state 4, developer options and unknown sources off, OpenCover's accessibility service off; mirror view on the cover with ✕ (stop) and ↻ (rotate view) buttons; touches replayed on the inner display with Shizuku `input`.
- End (✕, notification Stop, screen off, heat cut): everything restored, device state back to TENT or reset per the keep-cover-on setting, cover rotation back to 0 then the app's own. The phone locks on end (unavoidable) and the screen is woken.
- Crash recovery: on process start, finish any restore left pending.
- While mirroring, OpenCover doesn't bring its home to the front (CoverWatcher stays paused).

### Heat guard (new)
- Settings switch "Heat guard", on by default.
- Warn at battery 42 °C or thermal status moderate; cut at 45 °C or severe; levels drop only 2 °C below a line. Acts only while the hotspot or mirroring is on (the phone idles at 43-46 °C while charging, so otherwise it would warn on most charges). Warn: notification. Cut: notification, stop mirroring, hotspot off (`service call tethering 5 ...`, version specific). Both off: no notifications, and any shown is cleared.
- Runs inside CoverWatcher, the always-on foreground service, so it keeps working while banking mode or mirroring has the accessibility service off. My call: replaces FreeFlip's owners.

## Integration (decided)

- One Shizuku helper: OpenCover's `quick/ShizukuShell`, upgraded with FreeFlip's `Await` (drained streams, bounded wait).
- One accessibility service: `CoverAccessibility`. FreeFlip's `Feature` hub is not ported; hook rotation, fold and heat into it directly.
- One way to switch accessibility off and on: mirroring reuses BankingMode's secure-settings helper (`withService`) where it can. Developer options and unknown sources are still written through Shizuku.
- Launches through OpenCover's `AppLauncher`; FreeFlip's freeform launch code is not ported.
- Settings: a new "Phone" sub-panel holding Keep cover on and Heat guard (my call; builder may place them elsewhere in Settings).
- Unit tests come over with the code (rotation parsing, letterbox and touch mapping, fold args, heat levels, await).
- NOTICE/README: credit FreeFlip as the source; same author and licence.

- Banking mode stands down during a mirror session (mirroring owns the accessibility switch until it ends). My call.
- OpenCover's overlays (keyboard, banners) don't count as an app for rotation. My call.

## Left to the builder

File layout, how features hook into the service, whether an `Application` class is added for recovery, wording.

## Testing

- Unit: the ported tests, plus scope widening only with saved rotations, and picking the flip target from a task list.
- Device (short, cool, phone unlocked, OpenCover in front before any input):
  - Rotate tile turns an app; it comes back turned; OpenCover stays upright.
  - Keep cover on: open and close the phone a few times, cover stays on.
  - Heat guard: with the hotspot on, `cmd thermalservice override-status 2` then `3`, then `cmd thermalservice reset`; with hotspot and mirroring off, no notification.
  - Mirroring: start, tap and swipe, rotate view, ✕; settings and accessibility restored; banking app runs while mirroring.
- Before device tests, FreeFlip is uninstalled (ask the user first).

## Out of scope

Auto-rotate. FreeFlip's setup screen, widget and takeover feature. Charge pause. Live drag in the mirror (strokes replay on lift).
