# OpenCover: Overlay Keyboard, core (sub-project 4a)

Date: 2026-10-11

## Goal

Type on the cover screen with OpenCover's own keyboard: QWERTY, word suggestions and swipe typing. Later: 4b T9, 4c emoji and voice key.

## Why an overlay, not a keyboard app

Samsung's InputMethodManagerService (decompiled, `startInputUncheckedLocked`) replaces any keyboard on the cover display whose package isn't `com.samsung.android.honeyboard` or `com.google.android.tts` with Samsung Keyboard. Hard-coded, no setting. Gboard, SwiftKey, FlorisBoard or an OpenCover keyboard app would all be swapped out.

Spike (2026-10-11, on device): the accessibility service can hide Samsung's keyboard (`softKeyboardController.showMode = SHOW_MODE_HIDDEN`) and type into the focused field of another app (`ACTION_SET_TEXT`, verified in Microsoft Edge), once its scope covers all apps.

## Layout

```
+------------------------------------------+
| [ world ]   [ work ]   [ worse ]         |  suggestions, 24dp (optional)
| q  w  e  r  t  y  u  i  o  p             |
|  a  s  d  f  g  h  j  k  l               |  4 key rows, 34dp
| ^  z  x  c  v  b  n  m   <x              |
| 123  ,  [      space      ]  .   ->      |
|                                          |  20dp bottom gap (clears the camera cutout)
+------------------------------------------+
```

- Full width, docked at the bottom. About 180dp, 156dp with suggestions hidden. No preview line (removed at the user's request).
- Password fields keep Samsung's keyboard: accessibility sees their text masked, so rewriting it would corrupt the password.

## Behavior

- Appears when a text field gains input focus on the cover display: Samsung's keyboard is hidden, ours slides up. Hides when focus leaves; Samsung's keyboard show mode is restored.
- Keys: tap types; long-press gives the alternate (top row digits, other letters symbols). Shift: once for one capital, twice for caps lock. "123": numbers and symbols, a second symbols page. Backspace repeats while held, speeding up to whole words. Drag on space moves the cursor. Paste on the symbols page and long-press comma. Enter: `ACTION_IME_ENTER`.
- Suggestions: 3 dictionary words for the current prefix, most common first. Tap replaces the word and adds a space. Hidden via Settings or long-press on the strip.
- Swipe typing: sliding across letters draws a trail. On release the best dictionary match (start key, end key, path shape, length, frequency) is typed with a space; the next two show as suggestions. Taps and swipes mix.
- Light haptic tick on key press.

## Components

- `keyboard/EditState.kt` (pure): text and selection; insert, backspace, delete word, move cursor, replace current word.
- `keyboard/Dictionary.kt` (pure): load word list, ranked prefix matches.
- `keyboard/Swipe.kt` (pure): trail and key positions to ranked words.
- `keyboard/Layouts.kt`: QWERTY, numbers, symbols pages, long-press alternates.
- `keyboard/KeyboardView.kt`: one custom-drawn View (precise touch for trails, holds, long-presses), in an app overlay window on the cover display (same as the notification banner).
- CoverAccessibility: focus watching on the cover, hiding and restoring Samsung's keyboard, applying edits (`ACTION_SET_TEXT`, `ACTION_SET_SELECTION`, `ACTION_PASTE`, `ACTION_IME_ENTER`).

## Settings and data

- Settings > Keyboard: "Use OpenCover keyboard" (default on), "Show suggestions" (default on). Remembering the layout comes with T9 (4b).
- `assets/words_en.txt`: top 50,000 English (UK) words with frequencies from AOSP LatinIME's `en_GB_wordlist.combined` (Apache 2.0), without entries flagged offensive or non-word. Credited in `NOTICE`.

## Privacy

Implemented with the accessibility service's input method (Android 13+, `FLAG_INPUT_METHOD_EDITOR`): Android connects it to the focused field next to the real keyboard. The service's scope stays narrow (no events or screen content from other apps). It reads only the text around the cursor, for suggestions, and stores nothing. Banking mode still turns the service off around banking apps.

## Testing

- Unit: every EditState operation; dictionary ranking; swipe matching on recorded trails ("hello", "the", "keyboard").
- Device: typing in Edge and OpenCover's search; swipe typing; suggestions hide; a password field gets Samsung's keyboard; turning the setting off brings Samsung's keyboard back.

## Out of scope

T9 (4b). Emoji, voice key (4c). Learning new words. Other languages.
