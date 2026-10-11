# OpenCover: Media Player (sub-project 2c)

Date: 2026-10-11

## Goal

Control music and video playback from the cover screen. Opens by swiping up on Home. Layout follows the YouTube Music player.

## Layout

```
+------------------------------------------+
|#       [ Spotify | YT Music ]           #|
|#                                        #|
|#   shuf   prev   ||   next   repeat     #|
|#                                        #|
|# Bobby                                  #|
|# Alex G       0:03 / 3:43        [ >< ] #|
+------------------------------------------+
```

- Background: album art, full screen, dimmed. No art: dark background.
- Left edge: vertical seek bar, flush. Top = start.
- Right edge: vertical media volume bar, flush. Top = loudest.
- Top pill: one icon per active player; tap to control it. Hidden when only one plays.
- Center: shuffle, previous, play/pause (large), next, repeat (off, all, one).
- Bottom left: title, artist, time. Bottom right: [ >< ] closes the panel.

## Components

| Part | Role |
|---|---|
| MediaHub | Active media sessions via the notification access already granted; watches players start and stop |
| Player | Plain record: app, title, artist, art, duration, position, update time, speed, playing, shuffle, repeat, supported actions |
| Pure rules | Live position, default player, repeat cycle, vertical bar to time or volume |
| MediaPanel | The screen above, replacing the placeholder |
| VerticalBar | Reusable vertical slider for seek and volume |

## Rules

- Default player: the one playing, else the most recently updated.
- Live position = last position + elapsed time x speed while playing; clamped to duration.
- Controls the app doesn't support are hidden. Seek bar ignores drags if seeking isn't supported; hidden if duration is unknown.
- Player stops: removed from the pill; panel moves to the next player or shows "Nothing playing".
- Notification access off: "Turn on Notification access in Setup".
- Volume changes media volume only.

## Testing

- Unit: live position (playing, paused, 2x speed, clamp), default player, repeat cycle, bar mapping (ends, unknown duration), hidden controls.
- Device, with YouTube Music and a second player: play/pause, next, previous, shuffle, repeat, scrub, volume, player switch, close, "Nothing playing".

## Out of scope

Lock-screen and AOD media (sub-project 8), other themes, output device switching, apps' own extra buttons.
