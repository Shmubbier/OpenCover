# Overlay Keyboard (4a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** OpenCover's own keyboard on the cover: an overlay that appears over focused text fields, hides Samsung's keyboard, and types through accessibility, with QWERTY, suggestions and swipe typing.

**Architecture:** Pure, tested logic (`EditState`, `Dictionary`, `Swipe`, `Layouts`). One custom-drawn `KeyboardView` in an app-overlay window on display 1 (same mechanism as the notification banner). `OverlayKeyboard` (owned by `CoverAccessibility`) watches input focus on the cover, hides Samsung's keyboard via `softKeyboardController`, and applies edits with `ACTION_SET_TEXT` / `ACTION_SET_SELECTION` / `ACTION_PASTE` / `ACTION_IME_ENTER`.

**Tech Stack:** Kotlin, Android Views (Canvas), AccessibilityService, SharedPreferences. Word list from AOSP LatinIME (Apache 2.0).

**Spec:** `docs/superpowers/specs/2026-10-11-overlay-keyboard-design.md`

## Global Constraints

- minSdk 34, targetSdk 36. Device SM-F731B, Android 16, cover display 1 (748x720 px, 340 dpi). Camera cutout bottom right.
- Commits as Shmubbier. No Claude attribution, no em dashes, no emojis in docs or commits. Short docs.
- Build: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
- Install: `ANDROID_SERIAL=R5CW71QQAGW adb install -r app/build/outputs/apk/debug/app-debug.apk` (Git Bash: `MSYS_NO_PATHCONV=1`).
- No new Gradle dependencies.
- Device tests: only when the user says the phone is free; type only into OpenCover's search and Edge's address bar; never press Enter outside OpenCover; leave Samsung's keyboard working afterwards.

## Review Focus

1. Password fields: OpenCover's keyboard never shows; Samsung's does (accessibility sees masked text).
2. Overlay can't be added (no overlay permission): Samsung's keyboard is never hidden.
3. Setting turned off, service disconnected, or field gone: the overlay is removed and Samsung's keyboard show mode restored.
4. Lock screen: no OpenCover keyboard while the keyguard is locked.
5. Swipe before the dictionary loads, or with no match: nothing typed, no crash.

---

### Task 1: Word list asset

**Files:**
- Create: `tools/make_wordlist.py`, `app/src/main/assets/words_en.txt` (generated), `NOTICE`

- [ ] **Step 1: Script**

```python
"""Builds app/src/main/assets/words_en.txt from AOSP LatinIME's en_GB word list (Apache 2.0)."""
import base64, gzip, re, sys, urllib.request

URL = ("https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/"
       "dictionaries/en_GB_wordlist.combined.gz?format=TEXT")
LIMIT = 50000

raw = gzip.decompress(base64.b64decode(urllib.request.urlopen(URL).read())).decode("utf-8")
words = {}
for line in raw.splitlines():
    if not line.startswith(" word="):
        continue
    fields = dict(kv.split("=", 1) for kv in line.strip().split(",") if "=" in kv)
    w, f = fields.get("word", ""), int(fields.get("f", "0"))
    if fields.get("flags") in ("offensive", "nonword") or fields.get("not_a_word") == "true":
        continue
    if not re.fullmatch(r"[A-Za-z']+", w):
        continue
    words[w] = max(f, words.get(w, 0))
top = sorted(words.items(), key=lambda kv: -kv[1])[:LIMIT]
out = sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/words_en.txt"
with open(out, "w", encoding="utf-8", newline="\n") as fh:
    for w, f in top:
        fh.write(f"{w}\t{f}\n")
print(len(top))
```

- [ ] **Step 2: Generate**

Run: `mkdir -p app/src/main/assets && python -I tools/make_wordlist.py`
Expected: prints `50000`; `head -3 app/src/main/assets/words_en.txt` shows `the	222`, `to	215`, `of	214`; file under 700 KB.

- [ ] **Step 3: NOTICE**

```
OpenCover is licensed under GPL-3.0.

app/src/main/assets/words_en.txt is derived from the AOSP LatinIME word list
dictionaries/en_GB_wordlist.combined.gz, Copyright The Android Open Source Project,
licensed under the Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0).
Changes: entries flagged offensive or non-word removed; cut to the 50,000 most frequent words;
rewritten as "word<TAB>frequency" lines by tools/make_wordlist.py.
```

- [ ] **Step 4: Commit**

```bash
git add tools/make_wordlist.py app/src/main/assets/words_en.txt NOTICE
git commit -m "Add English word list for keyboard suggestions"
```

---

### Task 2: EditState

**Files:**
- Create: `app/src/main/java/dev/opencover/keyboard/EditState.kt`
- Test: `app/src/test/java/dev/opencover/keyboard/EditStateTest.kt`

**Interfaces:**
- Produces: `data class EditState(text: String, start: Int, end: Int)` with `insert(s)`, `backspace()`, `deleteWord()`, `moveCursor(by)`, `currentWord()`, `replaceWord(word)`, `replaceBefore(count, s)`.

- [ ] **Step 1: Failing tests**

```kotlin
package dev.opencover.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class EditStateTest {
    private fun at(text: String, cursor: Int = text.length) = EditState(text, cursor, cursor)

    @Test fun insertAtCursor() = assertEquals(at("heXllo", 3), at("hello", 2).insert("X"))
    @Test fun insertReplacesSelection() = assertEquals(at("hXo", 2), EditState("hello", 1, 4).insert("X"))
    @Test fun backspaceDeletesCharBefore() = assertEquals(at("helo", 3), at("hello", 4).backspace())
    @Test fun backspaceAtStartIsNoOp() = assertEquals(at("hi", 0), at("hi", 0).backspace())
    @Test fun backspaceDeletesSelection() = assertEquals(at("ho", 1), EditState("hello", 1, 4).backspace())
    @Test fun backspaceRemovesWholeSurrogatePair() = assertEquals(at("a"), at("a😀").backspace())
    @Test fun deleteWordTakesWordAndTrailingSpaces() = assertEquals(at("hello "), at("hello world  ").deleteWord())
    @Test fun moveCursorClamps() {
        assertEquals(at("abc", 0), at("abc", 1).moveCursor(-5))
        assertEquals(at("abc", 3), at("abc", 1).moveCursor(9))
    }
    @Test fun moveCollapsesSelection() = assertEquals(at("hello", 0), EditState("hello", 1, 4).moveCursor(-1))
    @Test fun currentWord() {
        assertEquals("wor", at("hello wor").currentWord())
        assertEquals("", at("hello ").currentWord())
        assertEquals("don't", at("I don't").currentWord())
    }
    @Test fun replaceWordAddsSpace() = assertEquals(at("hello world "), at("hello wor").replaceWord("world"))
    @Test fun replaceWordKeepsTextAfter() = assertEquals(EditState("hello world !", 12, 12), at("hello wor!", 9).replaceWord("world"))
    @Test fun replaceBeforeSwapsSwipedWord() = assertEquals(at("hi there "), at("hi three ").replaceBefore(6, "there "))
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests dev.opencover.keyboard.EditStateTest`
Expected: compilation failure, `EditState` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package dev.opencover.keyboard

/** A text field's text and selection ([start] <= [end]). Pure, so every edit is unit tested. */
data class EditState(val text: String, val start: Int, val end: Int) {
    init {
        require(start in 0..end && end <= text.length)
    }

    private fun at(t: String, c: Int) = EditState(t, c, c)

    fun insert(s: String) = at(text.substring(0, start) + s + text.substring(end), start + s.length)

    fun backspace(): EditState {
        if (start != end) return insert("")
        if (start == 0) return this
        val from = Character.offsetByCodePoints(text, start, -1) // a whole emoji, not half
        return at(text.removeRange(from, start), from)
    }

    /** Deletes the spaces before the cursor, then the word before them. */
    fun deleteWord(): EditState {
        if (start != end) return insert("")
        var i = start
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return at(text.removeRange(i, start), i)
    }

    /** Collapses any selection toward [by]'s direction, then moves; clamped to the text. */
    fun moveCursor(by: Int) = at(text, ((if (by < 0) start else end) + by).coerceIn(0, text.length))

    /** Letters and apostrophes just before the cursor. */
    fun currentWord() = text.substring(0, start).takeLastWhile { it.isLetter() || it == '\'' }

    /** Replaces the word being typed with [word] and a space. */
    fun replaceWord(word: String): EditState {
        val from = start - currentWord().length
        return at(text.substring(0, from) + word + " " + text.substring(end), from + word.length + 1)
    }

    /** Replaces the [count] characters before the cursor with [s] (swapping a swiped word). */
    fun replaceBefore(count: Int, s: String): EditState {
        val from = (start - count).coerceAtLeast(0)
        return at(text.substring(0, from) + s + text.substring(end), from + s.length)
    }
}
```

- [ ] **Step 4: Run, expect PASS** (13 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/keyboard/EditState.kt app/src/test/java/dev/opencover/keyboard/EditStateTest.kt
git commit -m "Add keyboard edit model"
```

---

### Task 3: Dictionary

**Files:**
- Create: `app/src/main/java/dev/opencover/keyboard/Dictionary.kt`
- Test: `app/src/test/java/dev/opencover/keyboard/DictionaryTest.kt`

**Interfaces:**
- Produces: `class Dictionary(entries: List<Pair<String, Int>>) { val all: List<Pair<String, Int>>; fun complete(prefix: String, n: Int = 3): List<String>; companion { fun parse(text: String): Dictionary; fun matchCase(word: String, typed: String): String } }`

- [ ] **Step 1: Failing tests**

```kotlin
package dev.opencover.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryTest {
    private val d = Dictionary.parse("the\t222\nthere\t180\nthey\t190\nthem\t185\nthermal\t90\nLondon\t150\nI\t200\nhello\t160\n")

    @Test fun mostCommonFirst() = assertEquals(listOf("the", "they", "them"), d.complete("the"))
    @Test fun limit() = assertEquals(listOf("the", "they"), d.complete("th", 2))
    @Test fun capitalizedPrefix() = assertEquals(listOf("The", "They", "Them"), d.complete("The"))
    @Test fun allCapsPrefix() = assertEquals(listOf("THE", "THEY", "THEM"), d.complete("THE"))
    @Test fun keepsProperNounCase() = assertEquals(listOf("London"), d.complete("lon"))
    @Test fun noMatch() = assertEquals(emptyList<String>(), d.complete("xyz"))
    @Test fun emptyPrefix() = assertEquals(emptyList<String>(), d.complete(""))
    @Test fun parseSkipsBadLines() {
        val p = Dictionary.parse("ok\t5\nbroken\nalso\tx\n")
        assertEquals(listOf("ok" to 5), p.all)
    }
}
```

- [ ] **Step 2: Run, expect FAIL** (`Dictionary` unresolved)

- [ ] **Step 3: Implement**

```kotlin
package dev.opencover.keyboard

/** Words with frequencies; prefix completion for the suggestion strip. */
class Dictionary(entries: List<Pair<String, Int>>) {
    /** Sorted by lowercase word, for prefix ranges. */
    val all: List<Pair<String, Int>> = entries.sortedBy { it.first.lowercase() }
    private val keys = all.map { it.first.lowercase() }

    /** Up to [n] words starting with [prefix] (any case), most common first, cased like [prefix]. */
    fun complete(prefix: String, n: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        var i = keys.binarySearch(p).let { if (it < 0) -it - 1 else it }
        while (i > 0 && keys[i - 1].startsWith(p)) i-- // equal keys may sit before the hit
        val out = ArrayList<Pair<String, Int>>()
        while (i < keys.size && keys[i].startsWith(p)) out += all[i++]
        return out.sortedByDescending { it.second }.take(n).map { matchCase(it.first, prefix) }
    }

    companion object {
        /** "word<TAB>frequency" lines; anything else is skipped. */
        fun parse(text: String) = Dictionary(text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2) null else parts[1].toIntOrNull()?.let { parts[0] to it }
        }.toList())

        fun matchCase(word: String, typed: String) = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
```

- [ ] **Step 4: Run, expect PASS** (8 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/keyboard/Dictionary.kt app/src/test/java/dev/opencover/keyboard/DictionaryTest.kt
git commit -m "Add keyboard dictionary"
```

---

### Task 4: Swipe matching

**Files:**
- Create: `app/src/main/java/dev/opencover/keyboard/Swipe.kt`
- Test: `app/src/test/java/dev/opencover/keyboard/SwipeTest.kt`

**Interfaces:**
- Produces: `data class Pt(x: Float, y: Float)`, `object Swipe { fun resample(path: List<Pt>, n: Int = 32): List<Pt>; fun rank(trail: List<Pt>, centers: Map<Char, Pt>, keyWidth: Float, dict: List<Pair<String, Int>>, n: Int = 3): List<String> }`

- [ ] **Step 1: Failing tests**

```kotlin
package dev.opencover.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeTest {
    // QWERTY with 10-unit keys, like the keyboard's letter rows.
    private val centers = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c, Pt(i * 10f + 5, 5f)) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, Pt(i * 10f + 10, 15f)) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, Pt(i * 10f + 20, 25f)) }
    }
    private val dict = listOf(
        "hello" to 160, "hell" to 120, "help" to 150, "jello" to 40, "hollow" to 60, "hero" to 90,
        "the" to 222, "tie" to 100, "toe" to 80, "three" to 150, "them" to 185,
        "keyboard" to 60, "keyboards" to 30, "key" to 140,
    )

    /** A finger trail through [word]'s keys, wobbling 3 units either side. */
    private fun trail(word: String): List<Pt> {
        val keys = word.map { centers.getValue(it) }
        val out = ArrayList<Pt>()
        keys.zipWithNext().forEach { (a, b) ->
            for (s in 0 until 8) {
                val t = s / 8f
                val wob = if (s % 2 == 0) 3f else -3f
                out += Pt(a.x + (b.x - a.x) * t + wob, a.y + (b.y - a.y) * t - wob)
            }
        }
        out += keys.last()
        return out
    }

    @Test fun resampleKeepsEnds() {
        val r = Swipe.resample(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f)), 5)
        assertEquals(5, r.size)
        assertEquals(Pt(0f, 0f), r.first())
        assertEquals(Pt(10f, 10f), r.last())
    }
    @Test fun hello() = assertEquals("hello", Swipe.rank(trail("hello"), centers, 10f, dict).first())
    @Test fun the() = assertEquals("the", Swipe.rank(trail("the"), centers, 10f, dict).first())
    @Test fun keyboard() = assertEquals("keyboard", Swipe.rank(trail("keyboard"), centers, 10f, dict).first())
    @Test fun alternatives() = assertEquals(3, Swipe.rank(trail("hello"), centers, 10f, dict).size)
    @Test fun tooShort() = assertEquals(emptyList<String>(), Swipe.rank(listOf(Pt(0f, 0f)), centers, 10f, dict))
}
```

- [ ] **Step 2: Run, expect FAIL** (`Swipe` unresolved)

- [ ] **Step 3: Implement**

```kotlin
package dev.opencover.keyboard

import kotlin.math.hypot
import kotlin.math.ln

data class Pt(val x: Float, val y: Float)

/** Swipe typing: compare the finger's trail with each word's path through the key centers. */
object Swipe {
    private const val N = 32

    /** [n] points evenly spaced along [path]. */
    fun resample(path: List<Pt>, n: Int = N): List<Pt> {
        val seg = path.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
        val total = seg.sum()
        if (seg.isEmpty() || total == 0f) return List(n) { path.first() }
        val out = ArrayList<Pt>(n)
        var i = 0
        var walked = 0f
        for (k in 0 until n) {
            val target = total * k / (n - 1)
            while (i < seg.size - 1 && walked + seg[i] < target) walked += seg[i++]
            val t = if (seg[i] == 0f) 0f else ((target - walked) / seg[i]).coerceIn(0f, 1f)
            val a = path[i]
            val b = path[i + 1]
            out += Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        return out
    }

    // ponytail: linear scan of the word list per swipe; index by (first, last) letter if it feels slow.
    /** Up to [n] words for [trail] over letter keys at [centers] ([keyWidth] px), best first. */
    fun rank(trail: List<Pt>, centers: Map<Char, Pt>, keyWidth: Float, dict: List<Pair<String, Int>>, n: Int = 3): List<String> {
        if (trail.size < 2 || centers.isEmpty() || dict.isEmpty()) return emptyList()
        fun near(p: Pt) = centers.filterValues { hypot(it.x - p.x, it.y - p.y) <= keyWidth }.keys
        val starts = near(trail.first())
        val ends = near(trail.last())
        val t = resample(trail)
        val maxF = ln(dict.maxOf { it.second } + 1.0)
        return dict.asSequence()
            .filter { (w, _) ->
                val l = w.lowercase()
                l.length >= 2 && l.first() in starts && l.last() in ends && l.all { it in centers }
            }
            .map { (w, f) -> w to cost(t, path(w.lowercase(), centers), keyWidth) + 0.3 * (1 - ln(f + 1.0) / maxF) }
            .sortedBy { it.second }
            .map { it.first }
            .distinctBy { it.lowercase() }
            .take(n)
            .toList()
    }

    /** The word's ideal trail: key centers in order, repeated letters once. */
    private fun path(word: String, centers: Map<Char, Pt>): List<Pt> {
        val keys = ArrayList<Char>()
        word.forEach { if (keys.lastOrNull() != it) keys += it }
        return resample(keys.map { centers.getValue(it) })
    }

    /** Mean distance between matching points, in key widths. */
    private fun cost(a: List<Pt>, b: List<Pt>, keyWidth: Float) =
        a.indices.sumOf { hypot(a[it].x - b[it].x, a[it].y - b[it].y).toDouble() } / a.size / keyWidth
}
```

- [ ] **Step 4: Run, expect PASS** (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/keyboard/Swipe.kt app/src/test/java/dev/opencover/keyboard/SwipeTest.kt
git commit -m "Add swipe typing matcher"
```

---

### Task 5: Layouts, prefs and KeyboardView

**Files:**
- Create: `app/src/main/java/dev/opencover/keyboard/Layouts.kt`, `KeyboardPrefs.kt`, `KeyboardView.kt`
- Test: `app/src/test/java/dev/opencover/keyboard/LayoutsTest.kt`

**Interfaces:**
- Consumes: `Pt` (Task 4).
- Produces: `enum KeyKind`, `data class Key`, `object Layouts { letters; symbols; moreSymbols }`, `object KeyboardPrefs { enabled(ctx); setEnabled(ctx, on); suggestions(ctx); setSuggestions(ctx, on) }`, `class KeyboardView(ctx, listener: KeyboardView.Listener)` with `preview`, `suggestions`, `showSuggestions`, and `Listener { onText(s); onBackspace(); onDeleteWord(); onCursor(by); onEnter(); onPaste(); onSuggestion(index); onSwipe(trail, centers, keyWidth, caps); onHideSuggestions() }`.

- [ ] **Step 1: Failing test**

```kotlin
package dev.opencover.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
    private val pages = listOf(Layouts.letters, Layouts.symbols, Layouts.moreSymbols)

    @Test fun rowsFitTenKeyWidths() = pages.flatten().forEach { row -> assertTrue(row.sumOf { it.width.toDouble() } <= 10.0) }
    @Test fun lettersHaveAllLetters() = assertEquals(
        ('a'..'z').toSet(),
        Layouts.letters.flatten().filter { it.kind == KeyKind.CHAR && it.label[0].isLetter() }.map { it.label[0] }.toSet(),
    )
}
```

Run: `./gradlew :app:testDebugUnitTest --tests dev.opencover.keyboard.LayoutsTest` → compilation failure.

- [ ] **Step 2: Layouts**

```kotlin
package dev.opencover.keyboard

enum class KeyKind { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, PASTE, LETTERS, SYMBOLS, MORE_SYMBOLS }

/** [alt] is typed on long-press; [hold] is a long-press action instead (paste on comma). [width] in key widths. */
data class Key(val label: String, val kind: KeyKind = KeyKind.CHAR, val alt: String? = null, val width: Float = 1f, val hold: KeyKind? = null)

/** Pages of rows; every row is at most 10 key widths and is centered. */
object Layouts {
    private fun row(chars: String, alts: String = "") = chars.mapIndexed { i, c -> Key(c.toString(), alt = alts.getOrNull(i)?.toString()) }
    private val backspace = Key("⌫", KeyKind.BACKSPACE, width = 1.5f)
    private fun bottom(left: Key) = listOf(
        left, Key(",", hold = KeyKind.PASTE), Key("space", KeyKind.SPACE, width = 5f), Key("."), Key("↵", KeyKind.ENTER, width = 1.5f),
    )

    val letters = listOf(
        row("qwertyuiop", "1234567890"),
        row("asdfghjkl", "@#&*-+()'"),
        listOf(Key("⇧", KeyKind.SHIFT, width = 1.5f)) + row("zxcvbnm", "!\":;/?_") + backspace,
        bottom(Key("123", KeyKind.SYMBOLS, width = 1.5f)),
    )
    val symbols = listOf(
        row("1234567890"),
        row("@#$&-+()/"),
        listOf(Key("=\\<", KeyKind.MORE_SYMBOLS, width = 1.5f)) + row("*\"':;!") + Key("Paste", KeyKind.PASTE) + backspace,
        bottom(Key("ABC", KeyKind.LETTERS, width = 1.5f)),
    )
    val moreSymbols = listOf(
        row("~`|•√π÷×¶∆"),
        row("£€¥^°={}\\"),
        listOf(Key("?123", KeyKind.SYMBOLS, width = 1.5f)) + row("%©®™✓[]") + backspace,
        bottom(Key("ABC", KeyKind.LETTERS, width = 1.5f)),
    )
}
```

Run the test. Expected: PASS (2 tests).

- [ ] **Step 3: KeyboardPrefs**

```kotlin
package dev.opencover.keyboard

import android.content.Context

/** Settings > Keyboard. */
object KeyboardPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("keyboard", Context.MODE_PRIVATE)
    fun enabled(ctx: Context) = prefs(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("enabled", on).apply()
    fun suggestions(ctx: Context) = prefs(ctx).getBoolean("suggestions", true)
    fun setSuggestions(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("suggestions", on).apply()
}
```

- [ ] **Step 4: KeyboardView**

```kotlin
package dev.opencover.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** The whole keyboard in one View: preview line, suggestion strip, keys and swipe trail. */
@SuppressLint("ViewConstructor")
class KeyboardView(ctx: Context, private val listener: Listener) : View(ctx) {
    interface Listener {
        fun onText(s: String)
        fun onBackspace()
        fun onDeleteWord()
        fun onCursor(by: Int)
        fun onEnter()
        fun onPaste()
        fun onSuggestion(index: Int)
        fun onSwipe(trail: List<Pt>, centers: Map<Char, Pt>, keyWidth: Float, caps: Boolean)
        fun onHideSuggestions()
    }

    var preview: CharSequence = ""
        set(v) { field = v; invalidate() }
    var suggestions: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var showSuggestions = true
        set(v) { if (field != v) { field = v; requestLayout(); layoutKeys(); invalidate() } }

    private enum class Shift { OFF, ONCE, LOCK }
    private var shift = Shift.OFF
    private var page = Layouts.letters

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    private val previewH = dp(20f)
    private val stripH get() = if (showSuggestions) dp(24f) else 0f
    private val rowH = dp(34f)
    private val gap = dp(20f) // lifts the bottom row above the camera cutout
    private val keysTop get() = previewH + stripH

    private val bg = Paint().apply { color = Color.rgb(24, 24, 24) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(58, 58, 58) }
    private val modPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(40, 40, 40) }
    private val downPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(96, 96, 96) }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    private val suggest = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = dp(14f) }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.LTGRAY; textSize = dp(13f) }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(160, 120, 170, 255); style = Paint.Style.STROKE; strokeWidth = dp(5f)
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    private var keys: List<Pair<Key, RectF>> = emptyList()

    override fun onMeasure(w: Int, h: Int) =
        setMeasuredDimension(MeasureSpec.getSize(w), (previewH + stripH + rowH * page.size + gap).toInt())

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) = layoutKeys()

    private fun layoutKeys() {
        val unit = width / 10f
        val pad = dp(2.5f)
        keys = page.flatMapIndexed { r, row ->
            var x = (10f - row.sumOf { it.width.toDouble() }.toFloat()) / 2 * unit
            val top = keysTop + r * rowH
            row.map { k -> (k to RectF(x + pad, top + pad, x + k.width * unit - pad, top + rowH - pad)).also { x += k.width * unit } }
        }
    }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        // The end of the text, so the cursor stays in view.
        val shown = TextUtils.ellipsize(preview, small, width - dp(16f), TextUtils.TruncateAt.START)
        c.drawText(shown, 0, shown.length, dp(8f), previewH - dp(5f), small)
        if (showSuggestions) {
            val cell = width / 3f
            suggestions.take(3).forEachIndexed { i, s -> c.drawText(s, cell * i + cell / 2, previewH + stripH - dp(7f), suggest) }
        }
        keys.forEach { (k, r) ->
            c.drawRoundRect(r, dp(6f), dp(6f), when {
                k === pressed?.first -> downPaint
                k.kind == KeyKind.CHAR -> keyPaint
                else -> modPaint
            })
            val text = labelOf(k)
            label.textSize = if (text.length > 3) dp(12f) else dp(17f)
            c.drawText(text, r.centerX(), r.centerY() + label.textSize / 3, label)
        }
        if (trail.size > 1) c.drawPath(Path().apply {
            moveTo(trail[0].x, trail[0].y)
            trail.drop(1).forEach { lineTo(it.x, it.y) }
        }, trailPaint)
    }

    private fun labelOf(k: Key) = when {
        k.kind == KeyKind.SHIFT -> if (shift == Shift.LOCK) "⇪" else "⇧"
        k.kind == KeyKind.CHAR && shift != Shift.OFF -> k.label.uppercase()
        else -> k.label
    }

    private val handler = Handler(Looper.getMainLooper())
    private var pressed: Pair<Key, RectF>? = null
    private var handled = false // a long-press, hold or cursor drag already acted
    private var downX = 0f
    private var downY = 0f
    private var spaceSteps = 0
    private var repeats = 0
    private val trail = ArrayList<Pt>()
    private var swiping = false
    private var stripDown = -1

    private val hold = Runnable { onHold() }
    private val repeat = object : Runnable {
        override fun run() {
            repeats++
            if (repeats > 10) listener.onDeleteWord() else listener.onBackspace() // speeds up to whole words
            handler.postDelayed(this, if (repeats > 10) 250L else 60L)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y
                handled = false; swiping = false; spaceSteps = 0; repeats = 0; trail.clear()
                if (showSuggestions && e.y >= previewH && e.y < keysTop) {
                    stripDown = (e.x / (width / 3f)).toInt()
                    handler.postDelayed(hold, 500)
                    return true
                }
                stripDown = -1
                pressed = keys.firstOrNull { it.second.contains(e.x, e.y) } ?: nearest(e.x, e.y)
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                handler.postDelayed(hold, 400)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val k = pressed?.first ?: return true
                val dx = e.x - downX
                when {
                    k.kind == KeyKind.SPACE -> {
                        val steps = (dx / dp(12f)).toInt()
                        if (steps != spaceSteps) {
                            handler.removeCallbacks(hold)
                            listener.onCursor(steps - spaceSteps)
                            spaceSteps = steps
                            handled = true
                        }
                    }
                    swiping -> { trail += Pt(e.x, e.y); invalidate() }
                    page === Layouts.letters && k.kind == KeyKind.CHAR && !handled &&
                        hypot(dx, e.y - downY) > width / 10f * 0.6f -> {
                        swiping = true
                        handler.removeCallbacks(hold)
                        trail += Pt(downX, downY)
                        trail += Pt(e.x, e.y)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(hold)
                handler.removeCallbacks(repeat)
                if (e.actionMasked == MotionEvent.ACTION_UP) when {
                    stripDown >= 0 -> if (!handled && stripDown < suggestions.size) listener.onSuggestion(stripDown)
                    swiping -> {
                        listener.onSwipe(trail.toList(), letterCenters(), width / 10f, shift != Shift.OFF)
                        if (shift == Shift.ONCE) shift = Shift.OFF
                    }
                    !handled -> pressed?.first?.let(::tap)
                }
                pressed = null; trail.clear(); swiping = false; stripDown = -1
                invalidate()
            }
        }
        return true
    }

    private fun onHold() {
        if (stripDown >= 0) { handled = true; listener.onHideSuggestions(); return }
        val k = pressed?.first ?: return
        when {
            k.kind == KeyKind.BACKSPACE -> { handled = true; repeat.run() }
            k.hold == KeyKind.PASTE -> { handled = true; listener.onPaste() }
            k.alt != null -> { handled = true; listener.onText(k.alt) }
        }
    }

    private fun tap(k: Key) {
        when (k.kind) {
            KeyKind.CHAR -> {
                listener.onText(if (shift != Shift.OFF) k.label.uppercase() else k.label)
                if (shift == Shift.ONCE) shift = Shift.OFF
            }
            KeyKind.SHIFT -> shift = when (shift) { Shift.OFF -> Shift.ONCE; Shift.ONCE -> Shift.LOCK; Shift.LOCK -> Shift.OFF }
            KeyKind.BACKSPACE -> listener.onBackspace()
            KeyKind.SPACE -> listener.onText(" ")
            KeyKind.ENTER -> listener.onEnter()
            KeyKind.PASTE -> listener.onPaste()
            KeyKind.LETTERS -> setPage(Layouts.letters)
            KeyKind.SYMBOLS -> setPage(Layouts.symbols)
            KeyKind.MORE_SYMBOLS -> setPage(Layouts.moreSymbols)
        }
    }

    private fun setPage(p: List<List<Key>>) { page = p; layoutKeys(); invalidate() }

    private fun nearest(x: Float, y: Float) = keys.minByOrNull { hypot(it.second.centerX() - x, it.second.centerY() - y) }

    private fun letterCenters() = keys
        .filter { it.first.kind == KeyKind.CHAR && it.first.label.length == 1 && it.first.label[0].isLetter() }
        .associate { it.first.label[0] to Pt(it.second.centerX(), it.second.centerY()) }

    override fun onDetachedFromWindow() {
        handler.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
```

- [ ] **Step 5: Build** (`:app:testDebugUnitTest :app:assembleDebug`). Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/dev/opencover/keyboard/Layouts.kt app/src/main/java/dev/opencover/keyboard/KeyboardPrefs.kt app/src/main/java/dev/opencover/keyboard/KeyboardView.kt app/src/test/java/dev/opencover/keyboard/LayoutsTest.kt
git commit -m "Add keyboard layouts and view"
```

---

### Task 6: OverlayKeyboard and accessibility wiring

**Files:**
- Create: `app/src/main/java/dev/opencover/keyboard/OverlayKeyboard.kt`
- Modify: `app/src/main/java/dev/opencover/watcher/CoverAccessibility.kt`

**Interfaces:**
- Consumes: Tasks 2-5.
- Produces: `class OverlayKeyboard(service: AccessibilityService) : KeyboardView.Listener { fun onEvent(e: AccessibilityEvent); fun hide(); companion { const val EVENTS: Int } }`.

- [ ] **Step 1: OverlayKeyboard**

```kotlin
package dev.opencover.keyboard

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import dev.opencover.COVER_DISPLAY
import kotlin.concurrent.thread

/**
 * OpenCover's keyboard: shown over a focused text field on the cover (Samsung only allows its own
 * keyboard app there), editing the field through accessibility.
 */
class OverlayKeyboard(private val service: AccessibilityService) : KeyboardView.Listener {
    @Volatile private var dict: Dictionary? = null
    private var view: KeyboardView? = null
    private var field: AccessibilityNodeInfo? = null
    private var samsungHidden = false
    /** The word just swiped in, with its space, so a suggestion tap can swap it. */
    private var lastSwipe: String? = null

    init {
        thread(name = "dictionary") {
            dict = runCatching { Dictionary.parse(service.assets.open("words_en.txt").bufferedReader().readText()) }.getOrNull()
        }
    }

    fun onEvent(e: AccessibilityEvent) {
        if (e.eventType and EVENTS == 0) return
        val n = focusedField()
        if (n == null) hide() else show(n)
    }

    private fun focusedField(): AccessibilityNodeInfo? {
        if (!KeyboardPrefs.enabled(service)) return null
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return null
        val n = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        // Password text is masked to accessibility; rewriting it would corrupt it. Samsung's keyboard handles those.
        return n.takeIf { it.isEditable && !it.isPassword && it.window?.displayId == COVER_DISPLAY }
    }

    private fun show(n: AccessibilityNodeInfo) {
        field = n
        val v = view ?: attach() ?: return // no overlay: leave Samsung's keyboard alone
        if (!samsungHidden) {
            service.softKeyboardController.showMode = AccessibilityService.SHOW_MODE_HIDDEN
            samsungHidden = true
        }
        v.showSuggestions = KeyboardPrefs.suggestions(service)
        refresh()
    }

    private fun attach(): KeyboardView? {
        if (!Settings.canDrawOverlays(service)) return null
        val display = service.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return null
        val wctx = service.createDisplayContext(display).createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val v = KeyboardView(wctx, this)
        val params = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_FOCUSABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.BOTTOM
            layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        return runCatching { wctx.getSystemService(WindowManager::class.java).addView(v, params); v }.getOrNull()?.also { view = it }
    }

    fun hide() {
        field = null
        lastSwipe = null
        view?.let { v -> runCatching { v.context.getSystemService(WindowManager::class.java).removeView(v) } }
        view = null
        if (samsungHidden) {
            service.softKeyboardController.showMode = AccessibilityService.SHOW_MODE_AUTO
            samsungHidden = false
        }
    }

    private fun state(n: AccessibilityNodeInfo): EditState {
        val t = if (n.isShowingHintText) "" else n.text?.toString().orEmpty()
        val a = n.textSelectionStart.takeIf { it >= 0 } ?: t.length
        val b = n.textSelectionEnd.takeIf { it >= 0 } ?: t.length
        return EditState(t, minOf(a, b).coerceIn(0, t.length), maxOf(a, b).coerceIn(0, t.length))
    }

    private fun refresh() {
        val v = view ?: return
        val n = field ?: return
        n.refresh()
        val s = state(n)
        v.preview = s.text.substring(0, s.start) + "|" + s.text.substring(s.end)
        if (lastSwipe == null) v.suggestions = dict?.complete(s.currentWord()).orEmpty()
    }

    private fun edit(change: (EditState) -> EditState) {
        val n = field ?: return
        runCatching {
            n.refresh()
            val next = change(state(n))
            n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next.text)
            })
            n.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, next.start)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, next.end)
            })
        }
        refresh()
    }

    override fun onText(s: String) { lastSwipe = null; edit { it.insert(s) } }
    override fun onBackspace() { lastSwipe = null; edit { it.backspace() } }
    override fun onDeleteWord() { lastSwipe = null; edit { it.deleteWord() } }
    override fun onCursor(by: Int) { lastSwipe = null; edit { it.moveCursor(by) } }
    override fun onEnter() { lastSwipe = null; field?.performAction(AccessibilityAction.ACTION_IME_ENTER.id) }
    override fun onPaste() { lastSwipe = null; field?.performAction(AccessibilityNodeInfo.ACTION_PASTE); refresh() }

    override fun onSuggestion(index: Int) {
        val w = view?.suggestions?.getOrNull(index) ?: return
        val swiped = lastSwipe
        lastSwipe = null
        edit { if (swiped != null) it.replaceBefore(swiped.length, "$w ") else it.replaceWord(w) }
    }

    override fun onSwipe(trail: List<Pt>, centers: Map<Char, Pt>, keyWidth: Float, caps: Boolean) {
        val words = Swipe.rank(trail, centers, keyWidth, dict?.all ?: return)
        val best = words.firstOrNull() ?: return
        val w = if (caps) best.replaceFirstChar { it.uppercase() } else best
        edit { s ->
            val before = s.text.substring(0, s.start)
            s.insert(if (before.isEmpty() || before.last().isWhitespace()) "$w " else " $w ")
        }
        lastSwipe = "$w "
        view?.suggestions = words.drop(1)
    }

    override fun onHideSuggestions() {
        KeyboardPrefs.setSuggestions(service, false)
        view?.showSuggestions = false
    }

    companion object {
        /** Focus, text and window changes: any of them may move the focused field. */
        const val EVENTS = AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
    }
}
```

- [ ] **Step 2: CoverAccessibility**

Add a field and use it (import `dev.opencover.keyboard.KeyboardPrefs`, `dev.opencover.keyboard.OverlayKeyboard`):

```kotlin
    private var keyboard: OverlayKeyboard? = null
```

In `onServiceConnected`, after `refreshScope()`: nothing else. In `onDestroy`, before `instance = null`: `keyboard?.hide()`.

Replace `refreshScope()` with:

```kotlin
    /**
     * Default: listen to Samsung's system UI plus the banking apps only.
     * Top-edge priority: also watch the window list (all apps) to catch Samsung's panel.
     * OpenCover keyboard: all apps, to see which text field has focus on the cover.
     */
    fun refreshScope() {
        val topEdge = TopEdge.enabled(this)
        val typing = KeyboardPrefs.enabled(this)
        if (typing && keyboard == null) keyboard = OverlayKeyboard(this)
        if (!typing) keyboard?.hide()
        serviceInfo = serviceInfo.apply {
            packageNames = if (topEdge || typing) null
            else (setOf("com.android.systemui") + BankingMode.apps(this@CoverAccessibility)).toTypedArray()
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                (if (topEdge) AccessibilityEvent.TYPE_WINDOWS_CHANGED else 0) or
                (if (typing) OverlayKeyboard.EVENTS else 0)
            flags = if (topEdge || typing) flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            else flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
        }
    }
```

At the top of `onAccessibilityEvent`:

```kotlin
        keyboard?.onEvent(event)
```

- [ ] **Step 3: Build, install, device check (phone free)**

1. Open OpenCover, swipe to apps, swipe up, tap Search: OpenCover's keyboard shows, Samsung's doesn't. Type "hel", tap the "hello" suggestion: field reads "hello ". Backspace, hold backspace, shift, 123 page, long-press q (types 1).
2. Swipe "hello" with `adb shell input -d 1 motionevent DOWN/MOVE/UP` through the h, e, l, o key centers (key rows start at the overlay top + 44 dp, 34 dp each; overlay top from `adb shell dumpsys window | grep -A3 KeyboardView` or 720 px minus the overlay height). Expected: "hello " typed.
3. Back closes the drawer: the keyboard disappears.
4. Edge address bar: keyboard shows, typing works. Do not press Enter.
5. Restore: leave Edge with Back.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/opencover/keyboard/OverlayKeyboard.kt app/src/main/java/dev/opencover/watcher/CoverAccessibility.kt
git commit -m "Show the overlay keyboard over focused cover text fields"
```

---

### Task 7: Settings, README, privacy

**Files:**
- Modify: `app/src/main/java/dev/opencover/ui/SettingsPanel.kt`, `README.md`

- [ ] **Step 1: Settings section** (before `Button(onClick = onSamsungWidgets)`; import `dev.opencover.keyboard.KeyboardPrefs`, `dev.opencover.watcher.CoverAccessibility`):

```kotlin
        Text("Keyboard", color = Color.Gray, fontSize = 13.sp)
        var typing by remember { mutableStateOf(KeyboardPrefs.enabled(ctx)) }
        var suggest by remember { mutableStateOf(KeyboardPrefs.suggestions(ctx)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use OpenCover keyboard", color = Color.White)
                Text("Replaces Samsung's keyboard on the cover. Accessibility then sees which text field has focus in any app, only to type into it.",
                    color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = typing, onCheckedChange = {
                typing = it
                KeyboardPrefs.setEnabled(ctx, it)
                CoverAccessibility.instance?.refreshScope()
            })
        }
        if (typing) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show suggestions", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = suggest, onCheckedChange = { suggest = it; KeyboardPrefs.setSuggestions(ctx, it) })
        }
```

- [ ] **Step 2: README**

Under "What works" add:

```markdown
- Keyboard: OpenCover's own keyboard on the cover (Samsung only allows its own keyboard app there). QWERTY, word suggestions, swipe typing, cursor drag on the space bar. Password fields keep Samsung's keyboard. Turn it off in Settings > Keyboard.
```

In "Why Accessibility?", replace the first bullet with:

```markdown
- By default the service hears window changes from Samsung's system UI and the banking apps you pick.
  With OpenCover's keyboard on (default), it also sees which text field has focus in any app on the
  cover, and edits that field when you type. It never reads other screen content and stores nothing.
```

- [ ] **Step 3: Build, install, device check**

Settings > Keyboard: turn "Use OpenCover keyboard" off, open OpenCover search: Samsung's keyboard shows. Turn it on again. Turn "Show suggestions" off: no strip. Long-press the strip (with it on) hides it too. Turn suggestions back on.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/opencover/ui/SettingsPanel.kt README.md
git commit -m "Keyboard settings and README"
```
