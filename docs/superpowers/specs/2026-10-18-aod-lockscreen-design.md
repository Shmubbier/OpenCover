# OpenCover: AOD and lock screen (sub-project 8)

Date: 2026-10-18

## Goal

OpenCover draws the cover while the phone sleeps (AOD) and while it is locked (lock screen), instead of Samsung. Samsung still owns unlocking: its PIN pad and the power-key fingerprint.

## Modules

| Module | AOD | Lock screen |
|---|---|---|
| Clock (style: Current, Big, Stacked, Analog, At a Glance; picked separately from Home's, default Current) | yes | yes |
| Date | yes | yes |
| Battery (level, bolt while charging) | yes | yes |
| Notification icons (app icons of current notifications, a few then "+N") | yes | yes |
| Now-playing chip (art, title, artist, play/pause), only while media is active | no | yes |

- One shared layout. Each module can be switched off and dragged freely; positions are saved and then locked.
- The now-playing chip defaults to bottom left; it shows on the lock screen only.

## Switches

- Settings > Lock screen has two switches, "OpenCover AOD" and "OpenCover lock screen", each working alone (off: Samsung's). My call.

## AOD (screen off)

- Dim, black background, modules at low brightness.
- Shown when the phone starts dozing (`DREAMING_STARTED`) and removed when it stops (`DREAMING_STOPPED`): an accessibility overlay on the cover (tested on this phone with the IJP Cover AOD app: it is redrawn about once a minute in doze).
- Requires Samsung's Always On Display set to "Always" with tap-to-show off (`Settings.System aod_mode == 1 && aod_tap_to_show_mode == 0`). Setup checklist row asks the user to set it and links to Samsung's AOD settings. Without it, nothing is drawn (Samsung's behavior stays).
- My call: the layout shifts a few pixels each minute against burn-in.

## Lock screen (woken, locked)

- Same layout, full brightness, over the OpenCover wallpaper, plus the now-playing chip.
- Built as a `showWhenLocked` activity on the cover (display 1), not an overlay. Cover Pop Notifications shows such an activity over the keyguard on this phone (verified), and it is the approach that survives Samsung's Android 17 change (overlays no longer viable on the cover, per CoverScreen OS).
- Samsung's quick panel stays: the top strip of the cover is left to Samsung so its pull-down still works while locked. Samsung's widgets, notification pages and camera shortcut are dropped while OpenCover's lock screen is up.
- Swipe up: hands off to Samsung's PIN (`KeyguardManager.requestDismissKeyguard` from the activity, as Cover Recents does), OpenCover's lock screen goes away.
- Chip: play/pause only; it does not unlock.
- Tap the notification icons: peek list of current notifications. Content follows Android's lock screen privacy (hidden-content notifications show app name only). Tapping one hands off to the PIN, then opens it after unlock.
- Removed on unlock; OpenCover's Home opens as today. After a peek tap, the notification opens on the cover instead of Home. My call.
- Steps aside whenever something else comes up over the keyguard on the cover: an incoming call, an alarm or timer, any full-screen intent. Being an activity, those open on top of it; it does not bring itself back while they are up. My call.
- Android's lock screen notification setting "Don't show" hides the icons and the peek list on both screens; "hide content" shows app names only. My call.

## Edit

- Settings > Lock screen: a full-size preview with each module draggable and a switch per module, a clock style picker, and Lock (save). Positions are fractions of the cover so they survive density changes.

## Interaction with existing features

- Banking mode or mirroring turn the accessibility service off: Samsung's AOD and lock screen show as usual meanwhile.
- The cover display is 748x720 with the camera cutout bottom right: modules can't be dropped under it.

## Risks (spike first)

- Whether the lock screen activity can be shown on wake without flicker of Samsung's lock screen first, and whether Samsung's quick panel pull-down still works over it.
- Whether the keyguard dismiss request brings up Samsung's PIN pad on the cover from OpenCover.
- How the AOD overlay and the lock screen activity hand over on wake (`DREAMING_STOPPED`).

## Left to the builder

File layout, the preview scale, wording, the peek list's look, how the burn-in shift moves.

## Testing

- Unit: layout save and load (fractions, unknown modules dropped, cutout clamp), notification icon list (dedupe by app, "+N"), privacy redaction rule.
- Device: AOD appears on sleep with Samsung AOD "Always" and is replaced on wake; nothing drawn with tap-to-show on; lock screen swipe up gives Samsung's PIN; fingerprint unlock still works; chip play/pause; peek and open after unlock; edit, drag, toggle, lock; banking app turns it off and Samsung's shows.

## Out of scope

Edge lighting (12). Custom AOD brightness schedule. Unlocking without Samsung's PIN or fingerprint. VoIP caller ID (6, on hold).
