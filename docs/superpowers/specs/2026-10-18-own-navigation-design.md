# OpenCover: own navigation (roadmap 10)

Date: 2026-10-18

## Goal

Pressing Home on the cover never shows Samsung's cover home: it goes straight to OpenCover, without the app-switch animation.

## Findings (on the phone)

- Samsung's cover Home and Back are handled inside System UI: they don't reach an accessibility key filter, and there is no setting to hide that bar (Good Lock's "auto hide" only).
- The bar is one `TYPE_SYSTEM` window titled "Navigation bar", `[0,654][748,720]`, background `#C1C1C2`; Home at x 64-166, Back at 183-285.

## Design

- OpenCover draws its own bar as an accessibility overlay exactly over Samsung's, same look and button spots, so it feels like the same bar. Touches land on ours.
- Home: opens OpenCover with no system animation; Home plays its own quick fade. Already on Home: it returns to the Home page.
- Back: `GLOBAL_ACTION_BACK`, as Samsung's.
- Shown only while Samsung's bar is on the cover (apps that hide it, the keyboard's own bar states and the lock screen are left alone); follows its window bounds. Needs the window list (already used for the lock screen and top-edge priority).
- Setting: "OpenCover navigation bar" in Settings, on by default.

## Out of scope

Recents button, gestures, long-press assistant, back from an app's last screen into Samsung's home (still caught by the existing redirect).

## Testing

- Unit: when the bar shows (setting, Samsung's bar present, unlocked), and which button a touch hits.
- Device: Home from an app lands on OpenCover with no Samsung home; Back works; full-screen apps keep no bar; locked: no bar.
