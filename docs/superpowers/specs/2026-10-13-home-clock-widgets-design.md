# OpenCover: Home Clock and Widgets (sub-project 5a)

Date: 2026-10-13

## Goal

Choose how the Home clock looks and which small widgets sit with it. Edge lighting is 5b. The now-playing chip belongs to the lock screen (sub-project 8), not Home.

## Clock styles

| Style | Look | Widgets |
|---|---|---|
| Current (default) | Time and date, top left (today's clock) | Chips under the clock |
| Big centered | Large time centered, date below | Chips centered under the date |
| Stacked | Bold hours over minutes | Chips to the right of the digits |
| Analog | Drawn face with hands | Chips centered under the face |
| At a Glance | Large time; "Tue, Oct 13 · ☁ 18°"; smaller "07:30 · 76%" line | Inline text, dot separated |

- All styles stay clear of the camera cutout and the quick-app shortcuts.
- System font (Roboto); no bundled fonts.

## Widgets

| Widget | Shows | Default |
|---|---|---|
| Battery | Level, bolt while charging | On |
| Next alarm | Next system alarm time; hidden when none | On |
| Weather | Condition icon and temperature | Off |

- User picks which show and their order. At a Glance uses the same order: weather sits in the date line, the rest in the second line.
- Battery and alarm update from system broadcasts while Home is visible.

## Weather

- Off by default. Turning it on asks for coarse location; declined means it stays off with a short note.
- Adds the INTERNET and ACCESS_COARSE_LOCATION permissions, used only by weather.
- Source: Open-Meteo (free, no account, no key), current temperature and weather code for rough coordinates.
- Fetches at most every 30 minutes, only while the screen is on. The last result (temperature, code, time) is kept, so it shows instantly. Older than 2 hours: shown grey.
- °C or °F setting.

## Settings > Home

- Clock style: five small previews; tap switches Home immediately.
- Widgets: each with a switch and up/down arrows.
- Weather: unit choice under its switch.

## Data

SharedPreferences "home": `clock` (style id), `widgets` (ordered ids), `off` (hidden ids), `unit` (C or F), last weather (`temp`, `code`, `at`).

## Components

- `home/HomePrefs.kt`: style, widget order and visibility (pure list logic, unit tested).
- `home/Weather.kt`: Open-Meteo request (HttpURLConnection, no new library), response parsing, code to icon and label, staleness and unit conversion (pure parts unit tested).
- `home/Widgets.kt`: battery and alarm state.
- `ui/HomeScreen.kt`: the five clock styles and widget rendering.
- `ui/HomeSettings.kt`: the Settings > Home screen, opened from Settings.

## Privacy

README: weather is the only internet use, sends only rough coordinates to Open-Meteo, and is off unless turned on.

## Testing

- Unit: widget order and visibility edits, unknown ids dropped; weather code mapping; staleness; °C/°F; Open-Meteo response parsing.
- Device: each style; widgets on, off, reordered; weather off by default; location prompt on the cover; a real fetch; battery and alarm updating.

## Out of scope

Edge lighting (5b). Now-playing chip (sub-project 8). Custom fonts and colors.
