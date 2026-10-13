# Home Clock and Widgets (5a) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Five Home clock styles and customizable widgets (battery, next alarm, opt-in weather), chosen in Settings > Home.

**Architecture:** `home/HomePrefs.kt` holds the clock style and widget order as Compose state backed by SharedPreferences (pure list logic unit tested). `home/Weather.kt` has the pure Open-Meteo parsing and code mapping plus a small HttpURLConnection fetch. `ui/HomeScreen.kt` draws the chosen style with its widgets; `ui/HomeSettings.kt` is the new settings screen, opened from Settings like Experimental.

**Tech Stack:** Kotlin, Jetpack Compose, material-icons-extended (already a dependency), org.json (Android built-in; already a test dependency), HttpURLConnection, LocationManager.

**Spec:** `docs/superpowers/specs/2026-10-13-home-clock-widgets-design.md`

## Global Constraints

- minSdk 34, targetSdk 36. Cover display 1 (748x720 px, 340 dpi), camera cutout bottom right.
- Commits as Shmubbier. No Claude attribution, no em dashes, no emojis in docs or commits. Short docs.
- Build: `JAVA_HOME="/c/Program Files/Android/Android Studio/jbr" ./gradlew :app:testDebugUnitTest :app:assembleDebug`
- Install: `ANDROID_SERIAL=R5CW71QQAGW adb install -r app/build/outputs/apk/debug/app-debug.apk` (Git Bash: `MSYS_NO_PATHCONV=1`).
- No new Gradle dependencies. System font only.
- Weather off by default; INTERNET and ACCESS_COARSE_LOCATION used only by weather.
- Device tests only while OpenCover is in front (check `dumpsys window displays | grep mCurrentFocus` before every input batch; stop if another app is in front).

## Review Focus

1. No network, Open-Meteo down, or bad JSON: weather keeps the last value (greyed when old), no crash, no retry storm (Task 2 `parseRejectsGarbage`, Task 3 fetch wrapped in runCatching).
2. Location permission denied or revoked later: weather stays off / hides, no SecurityException (Task 3 checks permission before reading location).
3. No last-known location yet (fresh boot): weather shows nothing rather than a 0,0 forecast (Task 3 returns null without a location).
4. Saved prefs from older versions or unknown ids: order loads cleanly, new widgets appended (Task 1 tests).
5. 12/24-hour setting and long dates must not overlap the camera cutout or the quick-app row (device check in Task 3 for every style).

---

### Task 1: Clock style and widget prefs

**Files:**
- Create: `app/src/main/java/dev/opencover/home/HomePrefs.kt`
- Test: `app/src/test/java/dev/opencover/home/HomePrefsTest.kt`

**Interfaces:**
- Produces: `enum ClockStyle { CURRENT, BIG, STACKED, ANALOG, GLANCE }`, `enum HomeWidget { BATTERY, ALARM, WEATHER }`, `parseOrder(saved: String?): List<HomeWidget>`, `List<HomeWidget>.movedBy(w: HomeWidget, by: Int): List<HomeWidget>`, `object HomePrefs { clock: ClockStyle; order: List<HomeWidget>; off: Set<HomeWidget>; fahrenheit: Boolean; load(ctx); setClock(ctx, s); setOrder(ctx, l); setShown(ctx, w, on); setFahrenheit(ctx, f); fun shown(): List<HomeWidget> }`

- [ ] **Step 1: Failing tests**

```kotlin
package dev.opencover.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePrefsTest {
    @Test fun defaultOrder() = assertEquals(HomeWidget.entries.toList(), parseOrder(null))
    @Test fun unknownDroppedMissingAppended() =
        assertEquals(listOf(HomeWidget.WEATHER, HomeWidget.BATTERY, HomeWidget.ALARM), parseOrder("WEATHER,SUNSET,BATTERY,WEATHER"))
    @Test fun moveUpAndDown() {
        val l = HomeWidget.entries.toList() // BATTERY, ALARM, WEATHER
        assertEquals(listOf(HomeWidget.BATTERY, HomeWidget.WEATHER, HomeWidget.ALARM), l.movedBy(HomeWidget.WEATHER, -1))
        assertEquals(listOf(HomeWidget.ALARM, HomeWidget.BATTERY, HomeWidget.WEATHER), l.movedBy(HomeWidget.BATTERY, 1))
    }
    @Test fun moveClampsAtEnds() {
        val l = HomeWidget.entries.toList()
        assertEquals(l, l.movedBy(HomeWidget.BATTERY, -1))
        assertEquals(l, l.movedBy(HomeWidget.WEATHER, 1))
    }
}
```

- [ ] **Step 2: Run, expect FAIL**

Run: `./gradlew :app:testDebugUnitTest --tests dev.opencover.home.HomePrefsTest`
Expected: compilation failure, `HomeWidget` unresolved.

- [ ] **Step 3: Implement**

```kotlin
package dev.opencover.home

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class ClockStyle { CURRENT, BIG, STACKED, ANALOG, GLANCE }

/** Declaration order is the default order; names are saved, so never rename one. */
enum class HomeWidget { BATTERY, ALARM, WEATHER }

/** Saved order: unknown and repeated ids dropped, widgets added in later versions appended. */
fun parseOrder(saved: String?): List<HomeWidget> {
    val known = saved?.split(',')?.mapNotNull { id -> HomeWidget.entries.find { it.name == id } }?.distinct().orEmpty()
    return known + (HomeWidget.entries - known.toSet())
}

/** [w] moved [by] places, clamped to the list. */
fun List<HomeWidget>.movedBy(w: HomeWidget, by: Int): List<HomeWidget> {
    val from = indexOf(w)
    val to = (from + by).coerceIn(0, size - 1)
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** Settings > Home, as Compose state so Home redraws on change. */
object HomePrefs {
    var clock by mutableStateOf(ClockStyle.CURRENT)
        private set
    var order by mutableStateOf(HomeWidget.entries.toList())
        private set
    var off by mutableStateOf(setOf(HomeWidget.WEATHER))
        private set
    var fahrenheit by mutableStateOf(false)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        clock = ClockStyle.entries.find { it.name == p.getString("clock", null) } ?: ClockStyle.CURRENT
        order = parseOrder(p.getString("widgets", null))
        off = p.getString("off", null)?.let { s -> s.split(',').mapNotNull { id -> HomeWidget.entries.find { it.name == id } }.toSet() }
            ?: setOf(HomeWidget.WEATHER) // weather is opt-in
        fahrenheit = p.getBoolean("fahrenheit", false)
    }

    fun shown() = order.filter { it !in off }

    fun setClock(ctx: Context, s: ClockStyle) { clock = s; prefs(ctx).edit().putString("clock", s.name).apply() }
    fun setOrder(ctx: Context, l: List<HomeWidget>) { order = l; prefs(ctx).edit().putString("widgets", l.joinToString(",") { it.name }).apply() }
    fun setShown(ctx: Context, w: HomeWidget, on: Boolean) {
        off = if (on) off - w else off + w
        prefs(ctx).edit().putString("off", off.joinToString(",") { it.name }).apply()
    }
    fun setFahrenheit(ctx: Context, f: Boolean) { fahrenheit = f; prefs(ctx).edit().putBoolean("fahrenheit", f).apply() }
}
```

- [ ] **Step 4: Run, expect PASS** (4 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/home/HomePrefs.kt app/src/test/java/dev/opencover/home/HomePrefsTest.kt
git commit -m "Add home clock style and widget prefs"
```

---

### Task 2: Weather logic

**Files:**
- Create: `app/src/main/java/dev/opencover/home/Weather.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/dev/opencover/home/WeatherTest.kt`

**Interfaces:**
- Produces: `enum Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }`, `skyFor(code: Int): Sky`, `data class Reading(tempC: Double, code: Int, at: Long)`, `parseReading(json: String, now: Long): Reading?`, `stale(r: Reading, now: Long): Boolean` (older than 2 h), `due(r: Reading?, now: Long): Boolean` (none or older than 30 min), `degrees(tempC: Double, fahrenheit: Boolean): String`, `object Weather { reading: Reading? (Compose state); load(ctx); fetch(ctx): Boolean (blocking) }`

- [ ] **Step 1: Failing tests**

```kotlin
package dev.opencover.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherTest {
    @Test fun skyCodes() {
        assertEquals(Sky.CLEAR, skyFor(0))
        assertEquals(Sky.PARTLY, skyFor(2))
        assertEquals(Sky.CLOUDY, skyFor(3))
        assertEquals(Sky.FOG, skyFor(45))
        assertEquals(Sky.DRIZZLE, skyFor(53))
        assertEquals(Sky.RAIN, skyFor(63))
        assertEquals(Sky.RAIN, skyFor(81))
        assertEquals(Sky.SNOW, skyFor(73))
        assertEquals(Sky.SNOW, skyFor(86))
        assertEquals(Sky.THUNDER, skyFor(95))
        assertEquals(Sky.CLOUDY, skyFor(42)) // unknown codes fall back
    }
    @Test fun parsesOpenMeteo() {
        val json = """{"latitude":14.6,"current":{"time":"2026-10-13T09:00","interval":900,"temperature_2m":28.4,"weather_code":3}}"""
        assertEquals(Reading(28.4, 3, 1000L), parseReading(json, 1000L))
    }
    @Test fun parseRejectsGarbage() {
        assertNull(parseReading("not json", 0))
        assertNull(parseReading("""{"error":true,"reason":"x"}""", 0))
    }
    @Test fun staleAfterTwoHours() {
        val r = Reading(20.0, 0, 0)
        assertFalse(stale(r, 2 * 3600_000L))
        assertTrue(stale(r, 2 * 3600_000L + 1))
    }
    @Test fun dueEveryHalfHour() {
        assertTrue(due(null, 0))
        assertFalse(due(Reading(20.0, 0, 0), 30 * 60_000L - 1))
        assertTrue(due(Reading(20.0, 0, 0), 30 * 60_000L))
    }
    @Test fun degreesRounded() {
        assertEquals("28°", degrees(28.4, fahrenheit = false))
        assertEquals("83°", degrees(28.4, fahrenheit = true))
        assertEquals("-3°", degrees(-2.6, fahrenheit = false))
    }
}
```

- [ ] **Step 2: Run, expect FAIL** (`skyFor` unresolved)

- [ ] **Step 3: Implement**

```kotlin
package dev.opencover.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.roundToInt

enum class Sky { CLEAR, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, SNOW, THUNDER }

/** WMO weather codes as used by Open-Meteo. */
fun skyFor(code: Int) = when (code) {
    0 -> Sky.CLEAR
    1, 2 -> Sky.PARTLY
    45, 48 -> Sky.FOG
    in 51..57 -> Sky.DRIZZLE
    in 61..67, in 80..82 -> Sky.RAIN
    in 71..77, 85, 86 -> Sky.SNOW
    in 95..99 -> Sky.THUNDER
    else -> Sky.CLOUDY
}

data class Reading(val tempC: Double, val code: Int, val at: Long)

/** Open-Meteo "current" block; null for anything else. */
fun parseReading(json: String, now: Long): Reading? = runCatching {
    val c = JSONObject(json).getJSONObject("current")
    Reading(c.getDouble("temperature_2m"), c.getInt("weather_code"), now)
}.getOrNull()

fun stale(r: Reading, now: Long) = now - r.at > 2 * 3600_000L
fun due(r: Reading?, now: Long) = r == null || now - r.at >= 30 * 60_000L
fun degrees(tempC: Double, fahrenheit: Boolean) = "${(if (fahrenheit) tempC * 9 / 5 + 32 else tempC).roundToInt()}°"

/** Opt-in weather: the only part of OpenCover that uses the internet. */
object Weather {
    var reading by mutableStateOf<Reading?>(null)
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("home", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val p = prefs(ctx)
        if (p.contains("w_at")) reading = Reading(p.getFloat("w_temp", 0f).toDouble(), p.getInt("w_code", 3), p.getLong("w_at", 0))
    }

    /** Blocking; call off the main thread. False without permission, location or network. */
    fun fetch(ctx: Context): Boolean {
        if (ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return false
        val loc = runCatching {
            ctx.getSystemService(LocationManager::class.java).getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        }.getOrNull() ?: return false // no fix yet: show nothing rather than 0,0
        // Rough coordinates only (about 1 km).
        val url = "https://api.open-meteo.com/v1/forecast?latitude=%.2f&longitude=%.2f&current=temperature_2m,weather_code"
            .format(java.util.Locale.US, loc.latitude, loc.longitude)
        val body = runCatching {
            (URL(url).openConnection() as HttpURLConnection).run {
                connectTimeout = 8000; readTimeout = 8000
                try { inputStream.bufferedReader().readText() } finally { disconnect() }
            }
        }.getOrNull() ?: return false
        val r = parseReading(body, System.currentTimeMillis()) ?: return false
        reading = r
        prefs(ctx).edit().putFloat("w_temp", r.tempC.toFloat()).putInt("w_code", r.code).putLong("w_at", r.at).apply()
        return true
    }
}
```

Manifest (with the other permissions):

```xml
    <!-- Opt-in weather only (Settings > Home). -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
```

- [ ] **Step 4: Run, expect PASS** (6 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/home/Weather.kt app/src/test/java/dev/opencover/home/WeatherTest.kt app/src/main/AndroidManifest.xml
git commit -m "Add opt-in weather logic"
```

---

### Task 3: Clock styles and widgets on Home

**Files:**
- Modify: `app/src/main/java/dev/opencover/ui/HomeScreen.kt` (replace the private `Clock`)
- Modify: `app/src/main/java/dev/opencover/ui/CoverActivity.kt` (load prefs at start)

**Interfaces:**
- Consumes: Task 1 `HomePrefs`, `ClockStyle`, `HomeWidget`; Task 2 `Weather`, `skyFor`, `Sky`, `stale`, `due`, `degrees`.
- Produces: `@Composable fun ClockFace(style: ClockStyle, modifier: Modifier)` (also used for previews in Task 4).

- [ ] **Step 1: Load prefs**

In `CoverActivity.onCreate`, before `setContent`: `HomePrefs.load(this); Weather.load(this)` (imports `dev.opencover.home.HomePrefs`, `dev.opencover.home.Weather`).

- [ ] **Step 2: Replace the clock**

In `HomeScreen`, replace `Clock(Modifier.align(Alignment.TopStart).padding(20.dp))` with:

```kotlin
        val style = HomePrefs.clock
        ClockFace(
            style,
            if (style == ClockStyle.BIG || style == ClockStyle.ANALOG) Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
            else Modifier.align(Alignment.TopStart).padding(20.dp),
        )
        WeatherUpdates()
```

Delete the old private `Clock` and add:

```kotlin
/** The current minute, ticking on the minute. */
@Composable
private fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = LocalDateTime.now()
        }
    }
    return now
}

private val shadow = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f))

/** One widget as shown on Home. */
private data class Chip(val icon: ImageVector, val text: String, val dim: Boolean = false)

/** Visible widgets with something to show, in the chosen order. */
@Composable
private fun rememberChips(): List<Pair<HomeWidget, Chip>> {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { tick++ } }
        ctx.registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(r) }
    }
    val reading = Weather.reading
    val now = rememberNow()
    return remember(tick, reading, now, HomePrefs.order, HomePrefs.off, HomePrefs.fahrenheit) {
        HomePrefs.shown().mapNotNull { w ->
            when (w) {
                HomeWidget.BATTERY -> {
                    val bm = ctx.getSystemService(BatteryManager::class.java)
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    Chip(if (bm.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd, "$level%")
                }
                HomeWidget.ALARM -> ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
                    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(it.triggerTime), ZoneId.systemDefault())
                    Chip(Icons.Filled.Alarm, t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a")))
                }
                HomeWidget.WEATHER -> reading?.let {
                    Chip(skyIcon(skyFor(it.code)), degrees(it.tempC, HomePrefs.fahrenheit), dim = stale(it, System.currentTimeMillis()))
                }
            }?.let { w to it }
        }
    }
}

private fun skyIcon(s: Sky): ImageVector = when (s) {
    Sky.CLEAR -> Icons.Filled.WbSunny
    Sky.PARTLY -> Icons.Filled.WbCloudy
    Sky.CLOUDY -> Icons.Filled.Cloud
    Sky.FOG -> Icons.Filled.Foggy
    Sky.DRIZZLE, Sky.RAIN -> Icons.Filled.Umbrella
    Sky.SNOW -> Icons.Filled.AcUnit
    Sky.THUNDER -> Icons.Filled.Thunderstorm
}

@Composable
private fun ChipView(c: Chip, size: Int = 15) {
    val color = if (c.dim) Color.Gray else Color.White
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(c.icon, null, tint = color, modifier = Modifier.size((size + 2).dp))
        Text(" ${c.text}", color = color, fontSize = size.sp, style = shadow)
    }
}

@Composable
private fun ChipRow(chips: List<Chip>, center: Boolean = false) {
    if (chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp, if (center) Alignment.CenterHorizontally else Alignment.Start)) {
        chips.forEach { ChipView(it) }
    }
}

/** The Home clock in [style], with its widgets. */
@Composable
fun ClockFace(style: ClockStyle, modifier: Modifier) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val h24 = DateFormat.is24HourFormat(ctx)
    val time = now.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm"))
    val date = now.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    val chips = rememberChips()
    val all = chips.map { it.second }
    when (style) {
        ClockStyle.CURRENT -> Column(modifier) {
            Text(time, color = Color.White, fontSize = 64.sp, style = shadow)
            Text(date, color = Color.White, fontSize = 18.sp, style = shadow)
            Spacer(Modifier.height(6.dp))
            ChipRow(all)
        }
        ClockStyle.BIG -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, color = Color.White, fontSize = 96.sp, style = shadow)
            Text(date, color = Color.White, fontSize = 20.sp, style = shadow)
            Spacer(Modifier.height(6.dp))
            ChipRow(all, center = true)
        }
        ClockStyle.STACKED -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(now.format(DateTimeFormatter.ofPattern(if (h24) "HH" else "hh")), color = Color.White, fontSize = 88.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 84.sp, style = shadow)
                Text(now.format(DateTimeFormatter.ofPattern("mm")), color = Color.White, fontSize = 88.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 84.sp, style = shadow)
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(date, color = Color.White, fontSize = 16.sp, style = shadow)
                all.forEach { ChipView(it) }
            }
        }
        ClockStyle.ANALOG -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Analog(now, Modifier.size(150.dp))
            Spacer(Modifier.height(6.dp))
            Text(date, color = Color.White, fontSize = 16.sp, style = shadow)
            ChipRow(all, center = true)
        }
        ClockStyle.GLANCE -> Column(modifier) {
            Text(time, color = Color.White, fontSize = 56.sp, style = shadow)
            // Weather sits in the date line; the other widgets share the second line.
            val weather = chips.firstOrNull { it.first == HomeWidget.WEATHER }?.second
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = Color.White, fontSize = 18.sp, style = shadow)
                if (weather != null) { Text("  ·  ", color = Color.White, fontSize = 18.sp, style = shadow); ChipView(weather, 18) }
            }
            val rest = chips.filter { it.first != HomeWidget.WEATHER }.map { it.second }
            if (rest.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                rest.forEachIndexed { i, c ->
                    if (i > 0) Text("  ·  ", color = Color.White, fontSize = 15.sp, style = shadow)
                    ChipView(c)
                }
            }
        }
    }
}

@Composable
private fun Analog(now: LocalDateTime, modifier: Modifier) {
    DrawCanvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(Color.White.copy(alpha = 0.85f), r, c, style = Stroke(width = 3.dp.toPx()))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val o = Offset(c.x + (r * 0.88f * sin(a)).toFloat(), c.y - (r * 0.88f * cos(a)).toFloat())
            drawCircle(Color.White, 2.5.dp.toPx(), o)
        }
        fun hand(turns: Double, len: Float, w: Float) {
            val a = turns * 2 * Math.PI
            drawLine(Color.White, c, Offset(c.x + (r * len * sin(a)).toFloat(), c.y - (r * len * cos(a)).toFloat()), w, StrokeCap.Round)
        }
        hand((now.hour % 12 + now.minute / 60.0) / 12, 0.5f, 6.dp.toPx())
        hand(now.minute / 60.0, 0.78f, 4.dp.toPx())
        drawCircle(Color.White, 4.dp.toPx(), c)
    }
}

/** Fetches weather while Home is showing (the screen is on), at most every 30 minutes. */
@Composable
private fun WeatherUpdates() {
    val ctx = LocalContext.current
    val on = HomeWidget.WEATHER !in HomePrefs.off
    LaunchedEffect(on) {
        while (on) {
            if (due(Weather.reading, System.currentTimeMillis())) withContext(Dispatchers.IO) { runCatching { Weather.fetch(ctx) } }
            delay(5 * 60_000L)
        }
    }
}
```

Imports to add: `android.app.AlarmManager`, `android.content.BroadcastReceiver`, `android.content.Context`, `android.content.Intent`, `android.content.IntentFilter`, `android.os.BatteryManager`, `androidx.compose.foundation.Canvas as DrawCanvas`, `androidx.compose.foundation.layout.Spacer`, `androidx.compose.foundation.layout.height`, `androidx.compose.foundation.layout.width`, `androidx.compose.material.icons.Icons`, `androidx.compose.material.icons.filled.*`, `androidx.compose.material3.Icon`, `androidx.compose.runtime.DisposableEffect`, `androidx.compose.runtime.mutableIntStateOf`, `androidx.compose.ui.geometry.Offset`, `androidx.compose.ui.graphics.StrokeCap`, `androidx.compose.ui.graphics.drawscope.Stroke`, `androidx.compose.ui.graphics.vector.ImageVector`, `androidx.compose.ui.text.font.FontWeight`, `dev.opencover.home.*`, `kotlinx.coroutines.Dispatchers`, `kotlinx.coroutines.withContext`, `java.time.Instant`, `java.time.ZoneId`, `kotlin.math.cos`, `kotlin.math.sin`.

- [ ] **Step 3: Build and device check**

Build and install. With the default style (Current), Home shows time, date and a battery chip (plus the next alarm if one is set), and nothing overlaps the camera cutout or the quick-app row. The other styles are checked through Settings in Task 4.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/dev/opencover/ui/HomeScreen.kt app/src/main/java/dev/opencover/ui/CoverActivity.kt
git commit -m "Home: clock styles and widgets"
```

---

### Task 4: Settings > Home, location prompt, README

**Files:**
- Create: `app/src/main/java/dev/opencover/ui/HomeSettings.kt`
- Modify: `app/src/main/java/dev/opencover/ui/SettingsPanel.kt`, `README.md`

**Interfaces:**
- Consumes: Task 1 `HomePrefs`, `movedBy`; Task 3 `ClockFace`.
- Produces: `@Composable fun HomeSettings(onBack: () -> Unit)`.

- [ ] **Step 1: HomeSettings**

```kotlin
package dev.opencover.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.home.ClockStyle
import dev.opencover.home.HomePrefs
import dev.opencover.home.HomeWidget
import dev.opencover.home.movedBy

private val styleNames = mapOf(
    ClockStyle.CURRENT to "Current", ClockStyle.BIG to "Big", ClockStyle.STACKED to "Stacked",
    ClockStyle.ANALOG to "Analog", ClockStyle.GLANCE to "At a Glance",
)
private val widgetNames = mapOf(HomeWidget.BATTERY to "Battery", HomeWidget.ALARM to "Next alarm", HomeWidget.WEATHER to "Weather")

/** Settings > Home: clock style and widgets. */
@Composable
fun HomeSettings(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { HomePrefs.setShown(ctx, HomeWidget.WEATHER, true); note = null }
        else note = "Weather needs location; it stays off."
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Home", color = Color.White, fontSize = 20.sp)
        Text("Clock style", color = Color.Gray, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockStyle.entries.forEach { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // A live preview, scaled down.
                    Box(
                        Modifier.size(150.dp, 100.dp).clipToBounds()
                            .border(2.dp, if (s == HomePrefs.clock) Color(0xFF8AB4F8) else Color.DarkGray, RoundedCornerShape(10.dp))
                            .clickable { HomePrefs.setClock(ctx, s) },
                    ) {
                        Box(Modifier.size(450.dp, 300.dp).graphicsLayer {
                            scaleX = 1 / 3f; scaleY = 1 / 3f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        }) { ClockFace(s, Modifier.padding(16.dp)) }
                    }
                    Text(styleNames.getValue(s), color = Color.White, fontSize = 12.sp)
                }
            }
        }
        Text("Widgets", color = Color.Gray, fontSize = 13.sp)
        HomePrefs.order.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(widgetNames.getValue(w), color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { HomePrefs.setOrder(ctx, HomePrefs.order.movedBy(w, -1)) }) { Text("▲") }
                TextButton(onClick = { HomePrefs.setOrder(ctx, HomePrefs.order.movedBy(w, 1)) }) { Text("▼") }
                Switch(checked = w !in HomePrefs.off, onCheckedChange = { on ->
                    val needsLocation = w == HomeWidget.WEATHER && on &&
                        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    if (needsLocation) askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    else HomePrefs.setShown(ctx, w, on)
                })
            }
            if (w == HomeWidget.WEATHER && w !in HomePrefs.off) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fahrenheit", color = Color.White, modifier = Modifier.weight(1f).padding(start = 16.dp))
                Switch(checked = HomePrefs.fahrenheit, onCheckedChange = { HomePrefs.setFahrenheit(ctx, it) })
            }
        }
        Text(note ?: "Weather is the only feature that uses the internet: it sends rough coordinates to Open-Meteo.",
            color = Color.Gray, fontSize = 11.sp)
        TextButton(onClick = onBack) { Text("Back") }
    }
}
```

- [ ] **Step 2: Settings button**

In `SettingsPanel`, next to `var experimental by remember { mutableStateOf(false) }` add `var homeSettings by remember { mutableStateOf(false) }` and:

```kotlin
    if (homeSettings) {
        HomeSettings(onBack = { homeSettings = false })
        return
    }
```

Add `Button(onClick = { homeSettings = true }) { Text("Home") }` before `Button(onClick = onSamsungWidgets)`.

- [ ] **Step 3: README**

Change the Home line under "What works" to:

```markdown
- Home with wallpaper, clock and 4 shortcuts. Clock styles (Current, Big, Stacked, Analog, At a Glance) and widgets (battery, next alarm, weather) in Settings > Home. Swipe left for notifications, right for apps, up for media, down for quick settings.
```

Replace "- OpenCover has no internet permission. Nothing leaves your phone." with:

```markdown
- Only the opt-in weather widget uses the internet: it sends rough coordinates (about 1 km) to
  Open-Meteo every 30 minutes at most, while the screen is on. Nothing else leaves your phone.
```

- [ ] **Step 4: Build, install, device check**

Settings > Home: tap each of the five previews; Home matches. Reorder battery and alarm; Home order follows. Turn weather on: the location prompt appears on the cover; allow it; within a minute weather shows (or set a stale reading by waiting is not needed). Deny path: revoke with `adb shell pm revoke dev.opencover android.permission.ACCESS_COARSE_LOCATION`, turn weather off and on: prompt, deny, note shown, weather stays off. Check every style for cutout and quick-row overlap. Restore the user's choice afterwards (style Current unless they changed it).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/dev/opencover/ui/HomeSettings.kt app/src/main/java/dev/opencover/ui/SettingsPanel.kt README.md
git commit -m "Settings > Home: clock styles, widgets, weather opt-in"
```
