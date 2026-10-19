# OpenCover: launcher feel

Date: 2026-10-19

## Backgrounds

- Two backgrounds: Home and lock screen. The lock screen one defaults to Home's. Each is a still image or an animated GIF or WebP, picked with the photo picker (Settings > Set wallpaper for Home, Settings > Lock screen > Background for the lock screen). An animation also keeps its first frame as a still, for blurred panels. With no lock screen background set, the lock screen plays its own copy of Home's.
- Animated GIFs play from a playhead that only moves while frames are drawn, so:
  - Lock screen: loops while shown. The AOD shows the frame it stopped on, as a still at AOD brightness; waking carries on from that frame.
  - Home: plays only on the Home page with no panel open; leaving pauses on the current frame, coming back carries on.
- Decoding happens off the main thread into one shared frame per background, which Home, the lock screen and the AOD all draw, so they agree on the frame.
  - GIF: `android.graphics.Movie`, seekable by time, drawn into a bitmap (it can't draw straight to a hardware canvas).
  - Animated WebP: OpenCover reads the frame table itself, decodes each frame with `BitmapFactory` and composites them in order (blend and dispose per frame). Going back means starting over from the first frame. `AnimatedImageDrawable` was not used because it restarts from the first frame after a pause.

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

- Unit: playhead (advances by frame time, loops), WebP frame table and frame wrapping, per-style settings parse and defaults, the panel open decision (distance or flick).
- Device: GIF on Home pauses off the Home page; lock screen GIF loops and the AOD shows the frozen frame, waking resumes it; each panel style; drawer opens with a short swipe.
