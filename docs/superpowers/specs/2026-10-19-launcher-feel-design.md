# OpenCover: launcher feel

Date: 2026-10-19

## Backgrounds

- Two backgrounds: Home and lock screen. The lock screen one defaults to Home's. Each is a still image or an animated GIF, picked with the photo picker (Settings > Set wallpaper for Home, Settings > Lock screen > Background for the lock screen). A GIF also keeps its first frame as a still, for blurred panels.
- Animated GIFs play from a playhead that only moves while frames are drawn, so:
  - Lock screen: loops while shown. The AOD shows the frame it stopped on, as a still at AOD brightness; waking carries on from that frame.
  - Home: plays only on the Home page with no panel open; leaving pauses on the current frame, coming back carries on.
- Decoding: `android.graphics.Movie` (GIF, seekable by time). Animated WebP is out of scope.

## Panels

Four styles, picked in Settings > Home, each with its own opacity and blur (saved per style):

| Style | Look | Defaults |
|---|---|---|
| Frosted glass | Wallpaper blurred and darkened behind everything | opacity 0.45, blur 18 dp |
| Scrim (Lawnchair) | Dark sheet with rounded top corners, wallpaper unblurred around it | opacity 0.78, blur 0 |
| Material You tint | Sheet tinted with the wallpaper's colour, slight blur | opacity 0.86, blur 6 dp |
| Floating cards | Lightly blurred wallpaper, content on a rounded card | opacity 0.82 (card), blur 10 dp |

Applied to the notifications page, the apps pages, the app drawer, quick settings and the media panel. The backdrop uses the background's still.

## App drawer

Opens on a short swipe up (4% of the screen instead of 15%) or a quick upward flick.

## Testing

- Unit: playhead (advances only while playing, loops, holds when paused), per-style settings parse and defaults, the panel open decision (distance or flick).
- Device: GIF on Home pauses off the Home page; lock screen GIF loops and the AOD shows the frozen frame, waking resumes it; each panel style; drawer opens with a short swipe.
