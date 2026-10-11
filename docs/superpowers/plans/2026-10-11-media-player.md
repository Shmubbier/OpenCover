# Media Player Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swipe-up media player on Home, laid out like YouTube Music's player.

**Architecture:** `MediaHub` reads active media sessions (through the existing notification listener) with AndroidX `MediaControllerCompat`, which is the only API that exposes shuffle and repeat. Each session becomes a plain `Player`. Pure functions handle position, default player, repeat cycle and bar mapping. `MediaPanel` draws the screen with Canvas icons (no icon library).

**Tech Stack:** Kotlin, Compose, `androidx.media:media:1.7.1`, AudioManager, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-11-media-player-design.md`

## Global Constraints

- Commits as Shmubbier, no Claude attribution, no em dashes or emojis. Push with `git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push`.
- One new dependency only: `androidx.media:media:1.7.1` (shuffle and repeat need it). No internet permission.
- Volume changes `STREAM_MUSIC` only.
- adb: `ANDROID_SERIAL=R5CW71QQAGW`, `MSYS_NO_PATHCONV=1` in Git Bash.

## Review Focus

1. Unknown duration: seek bar hidden, no crash. Test `seekUnknownDurationIsNull` (Task 1).
2. Paused player: position doesn't advance. Test `pausedPositionHolds` (Task 1).
3. Selected player stops: panel falls back to the default player. `players.find { it.id == chosen } ?: defaultPlayer(players)` (Task 3), device check.
4. Notification access off: message instead of a crash (`SecurityException` caught in `MediaHub.start`), device check.
5. Scrubbing sends one seek on release, not one per pixel (Task 3 `VerticalBar(live = false)`), device check.

---

### Task 1: Player model and rules

**Files:**
- Create: `app/src/main/java/dev/opencover/media/Player.kt`
- Test: `app/src/test/java/dev/opencover/media/PlayerTest.kt`

**Interfaces:**
- Produces: `enum Repeat { OFF, ALL, ONE }` with `next()`; `Player(id, pkg, title, artist, durationMs, positionMs, updatedAt, speed, playing, shuffle: Boolean?, repeat: Repeat?, canSeek, canSkipNext, canSkipPrev)`; `livePosition(p, now)`; `defaultPlayer(players)`; `seekTarget(fromTop, durationMs): Long?`; `volumeFor(fromTop, max)`; `formatTime(ms)`.

- [ ] **Step 1: Failing tests**

```kotlin
package dev.opencover.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTest {
    private fun p(id: String, playing: Boolean = true, at: Long = 0, pos: Long = 1000, dur: Long = 10_000, speed: Float = 1f) =
        Player(id, "pkg", "t", "a", dur, pos, at, speed, playing, null, null, true, true, true)

    @Test fun playingPositionAdvances() = assertEquals(3000, livePosition(p("a", at = 100), now = 2100))
    @Test fun pausedPositionHolds() = assertEquals(1000, livePosition(p("a", playing = false, at = 100), now = 5100))
    @Test fun speedScalesPosition() = assertEquals(5000, livePosition(p("a", speed = 2f), now = 2000))
    @Test fun positionClampsToDuration() = assertEquals(10_000, livePosition(p("a"), now = 60_000))

    @Test fun defaultPrefersPlaying() =
        assertEquals("b", defaultPlayer(listOf(p("a", playing = false, at = 9), p("b", playing = true, at = 1)))?.id)

    @Test fun defaultTieGoesToMostRecent() =
        assertEquals("b", defaultPlayer(listOf(p("a", playing = false, at = 1), p("b", playing = false, at = 2)))?.id)

    @Test fun noPlayersNoDefault() = assertNull(defaultPlayer(emptyList()))

    @Test fun repeatCycles() {
        assertEquals(Repeat.ALL, Repeat.OFF.next())
        assertEquals(Repeat.ONE, Repeat.ALL.next())
        assertEquals(Repeat.OFF, Repeat.ONE.next())
    }

    @Test fun seekTopIsStartBottomIsEnd() {
        assertEquals(0L, seekTarget(0f, 10_000))
        assertEquals(10_000L, seekTarget(1f, 10_000))
        assertEquals(10_000L, seekTarget(1.5f, 10_000))
    }

    @Test fun seekUnknownDurationIsNull() = assertNull(seekTarget(0.5f, 0))

    @Test fun volumeTopIsLoudest() {
        assertEquals(15, volumeFor(0f, 15))
        assertEquals(0, volumeFor(1f, 15))
    }

    @Test fun timeFormat() {
        assertEquals("0:03", formatTime(3_400))
        assertEquals("3:43", formatTime(223_000))
    }
}
```

- [ ] **Step 2: Run, expect compile failure.** `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.media.*"`

- [ ] **Step 3: Implement `media/Player.kt`**

```kotlin
package dev.opencover.media

import kotlin.math.roundToInt

enum class Repeat {
    OFF, ALL, ONE;

    fun next() = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/**
 * One active media session as plain data. [updatedAt] is the elapsed-realtime of [positionMs].
 * [shuffle] and [repeat] are null when the app doesn't support them. [durationMs] <= 0 means unknown.
 */
data class Player(
    val id: String,
    val pkg: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val playing: Boolean,
    val shuffle: Boolean?,
    val repeat: Repeat?,
    val canSeek: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrev: Boolean,
)

/** Position now, advancing between app updates while playing. */
fun livePosition(p: Player, now: Long): Long {
    val pos = if (p.playing) p.positionMs + ((now - p.updatedAt) * p.speed).toLong() else p.positionMs
    return if (p.durationMs > 0) pos.coerceIn(0, p.durationMs) else pos.coerceAtLeast(0)
}

/** The one playing, else the most recently updated. */
fun defaultPlayer(players: List<Player>): Player? =
    players.filter { it.playing }.maxByOrNull { it.updatedAt } ?: players.maxByOrNull { it.updatedAt }

/** Seek bar: [fromTop] 0 = start, 1 = end. Null if the duration is unknown. */
fun seekTarget(fromTop: Float, durationMs: Long): Long? =
    if (durationMs <= 0) null else (fromTop.coerceIn(0f, 1f) * durationMs).toLong()

/** Volume bar: top = loudest. */
fun volumeFor(fromTop: Float, max: Int): Int = ((1f - fromTop.coerceIn(0f, 1f)) * max).roundToInt()

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
```

- [ ] **Step 4: Run, expect PASS** (12 tests), then the full suite.

- [ ] **Step 5: Commit** `Add media player model and rules`

---

### Task 2: MediaHub

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/main/java/dev/opencover/media/MediaHub.kt`

**Interfaces:**
- Consumes: `NotificationAccess` component; Task 1 `Player`, `Repeat`.
- Produces: `MediaHub { players; start(ctx): Boolean; stop(); art(id): Bitmap?; controls(id): TransportControls? }`, plus `Repeat.toCompat()`.

- [ ] **Step 1: Dependency.** In `app/build.gradle.kts` dependencies add:
```kotlin
    implementation("androidx.media:media:1.7.1") // MediaControllerCompat: shuffle and repeat
```

- [ ] **Step 2: `media/MediaHub.kt`**

```kotlin
package dev.opencover.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.watcher.NotificationAccess

/** Active media sessions as [Player]s (Compose state). Started while the media panel is open. */
object MediaHub {
    var players by mutableStateOf(emptyList<Player>())
        private set
    private val controllers = LinkedHashMap<String, MediaControllerCompat>()
    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private lateinit var appCtx: Context

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { rebuild(it.orEmpty()) }

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = refresh()
        override fun onShuffleModeChanged(shuffleMode: Int) = refresh()
        override fun onRepeatModeChanged(repeatMode: Int) = refresh()
        override fun onSessionReady() = refresh() // shuffle and repeat become readable here
        override fun onSessionDestroyed() = refresh()
    }

    /** False if notification access (needed to see media sessions) isn't granted. */
    fun start(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(ctx, NotificationAccess::class.java)
        return try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, listener)
            manager = m
            rebuild(m.getActiveSessions(listener))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        manager = null
        controllers.values.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        players = emptyList()
    }

    fun art(id: String): Bitmap? = controllers[id]?.metadata?.run {
        getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
            ?: getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)
    }

    fun controls(id: String) = controllers[id]?.transportControls

    private fun rebuild(sessions: List<MediaController>) {
        controllers.values.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        sessions.forEach { s ->
            val c = MediaControllerCompat(appCtx, MediaSessionCompat.Token.fromToken(s.sessionToken))
            c.registerCallback(callback, handler)
            controllers[s.sessionToken.hashCode().toString()] = c
        }
        refresh()
    }

    private fun refresh() {
        players = controllers.mapNotNull { (id, c) -> c.toPlayer(id) }
    }
}

private fun MediaControllerCompat.toPlayer(id: String): Player? {
    val s = playbackState ?: return null
    val m = metadata
    val a = s.actions
    fun has(action: Long) = a and action != 0L
    return Player(
        id = id,
        pkg = packageName,
        title = m?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
            ?: m?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
        artist = m?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
        durationMs = m?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0,
        positionMs = s.position,
        updatedAt = s.lastPositionUpdateTime,
        speed = s.playbackSpeed,
        playing = s.state == PlaybackStateCompat.STATE_PLAYING,
        shuffle = if (has(PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE)) shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE else null,
        repeat = if (has(PlaybackStateCompat.ACTION_SET_REPEAT_MODE)) when (repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> Repeat.ONE
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> Repeat.ALL
            else -> Repeat.OFF
        } else null,
        canSeek = has(PlaybackStateCompat.ACTION_SEEK_TO),
        canSkipNext = has(PlaybackStateCompat.ACTION_SKIP_TO_NEXT),
        canSkipPrev = has(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS),
    )
}

fun Repeat.toCompat() = when (this) {
    Repeat.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
    Repeat.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
    Repeat.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
}
```

- [ ] **Step 3: Build.** `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`. Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit** `Add MediaHub reading active media sessions`

---

### Task 3: Media panel

**Files:**
- Create: `app/src/main/java/dev/opencover/ui/MediaPanel.kt`
- Modify: `app/src/main/java/dev/opencover/ui/Shell.kt`

**Interfaces:**
- Consumes: Tasks 1 and 2.
- Produces: `MediaPanel(onClose: () -> Unit)`.

- [ ] **Step 1: `ui/MediaPanel.kt`**

```kotlin
package dev.opencover.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.os.SystemClock
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.media.MediaHub
import dev.opencover.media.Player
import dev.opencover.media.Repeat
import dev.opencover.media.defaultPlayer
import dev.opencover.media.formatTime
import dev.opencover.media.livePosition
import dev.opencover.media.seekTarget
import dev.opencover.media.toCompat
import dev.opencover.media.volumeFor
import kotlinx.coroutines.delay
import androidx.compose.foundation.Canvas as DrawCanvas

/** Swipe-up media player, laid out like YouTube Music's. */
@Composable
fun MediaPanel(onClose: () -> Unit) {
    val ctx = LocalContext.current
    var hasAccess by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        hasAccess = MediaHub.start(ctx)
        onDispose { MediaHub.stop() }
    }
    val players = MediaHub.players
    var chosen by remember { mutableStateOf<String?>(null) }
    // A stopped choice falls back to the default player.
    val p = players.find { it.id == chosen } ?: defaultPlayer(players)
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        when {
            !hasAccess -> Text("Turn on Notification access in Setup", color = Color.Gray)
            p == null -> Text("Nothing playing", color = Color.Gray)
            else -> PlayerView(p, players, onPick = { chosen = it }, onClose = onClose)
        }
    }
}

@Composable
private fun PlayerView(p: Player, players: List<Player>, onPick: (String) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val controls = MediaHub.controls(p.id)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(p.id, p.playing) {
        while (p.playing) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val art = remember(p.id, p.title) { MediaHub.art(p.id)?.asImageBitmap() }
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val position = livePosition(p, now)

    Box(Modifier.fillMaxSize()) {
        if (art != null) Image(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))

        // Left edge: seek (top = start). One seek on release.
        if (p.durationMs > 0) VerticalBar(
            fromTop = position.toFloat() / p.durationMs, fillFromTop = true, enabled = p.canSeek, live = false,
            modifier = Modifier.align(Alignment.CenterStart),
        ) { f -> seekTarget(f, p.durationMs)?.let { controls?.seekTo(it) } }

        // Right edge: media volume (top = loudest).
        VerticalBar(
            fromTop = 1f - volume.toFloat() / maxVol, fillFromTop = false, enabled = true, live = true,
            modifier = Modifier.align(Alignment.CenterEnd),
        ) { f ->
            volume = volumeFor(f, maxVol)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }

        // Top pill: switch between active players.
        if (players.size > 1) Row(
            Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            players.forEach { other ->
                val icon = remember(other.pkg) { appIcon(ctx, other.pkg) }
                Box(
                    Modifier.size(40.dp)
                        .background(if (other.id == p.id) Color(0xFF7A1F1F) else Color.Transparent, RoundedCornerShape(50))
                        .clickable { onPick(other.id) },
                    contentAlignment = Alignment.Center,
                ) { if (icon != null) Image(icon, contentDescription = other.pkg, modifier = Modifier.size(26.dp)) }
            }
        }

        // Center controls.
        Row(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 48.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically,
        ) {
            p.shuffle?.let { on ->
                IconButton(Glyph.SHUFFLE, if (on) Color.White else Color.Gray) {
                    controls?.setShuffleMode(if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL)
                }
            }
            if (p.canSkipPrev) IconButton(Glyph.PREV, Color.White) { controls?.skipToPrevious() }
            IconButton(if (p.playing) Glyph.PAUSE else Glyph.PLAY, Color.White, size = 64) {
                if (p.playing) controls?.pause() else controls?.play()
            }
            if (p.canSkipNext) IconButton(Glyph.NEXT, Color.White) { controls?.skipToNext() }
            p.repeat?.let { r ->
                IconButton(if (r == Repeat.ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, if (r == Repeat.OFF) Color.Gray else Color.White) {
                    controls?.setRepeatMode(r.next().toCompat())
                }
            }
        }

        // Bottom: title, artist, time, close.
        Row(
            Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 36.dp, end = 36.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f)) {
                Text(p.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                Text(p.artist, color = Color.LightGray, fontSize = 15.sp, maxLines = 1)
            }
            if (p.durationMs > 0) Text(
                "${formatTime(position)} / ${formatTime(p.durationMs)}",
                color = Color.LightGray, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(Glyph.CLOSE, Color.White, size = 36, onClick = onClose)
        }
    }
}

/** Vertical slider flush to an edge. [fromTop] is the handle position, 0 = top. */
@Composable
private fun VerticalBar(
    fromTop: Float,
    fillFromTop: Boolean,
    enabled: Boolean,
    live: Boolean,
    modifier: Modifier,
    onChange: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shown = (dragging ?: fromTop).coerceIn(0f, 1f)
    BoxWithConstraints(
        modifier.fillMaxHeight().width(28.dp).padding(vertical = 16.dp).pointerInput(enabled) {
            if (!enabled) return@pointerInput
            detectVerticalDragGestures(
                onDragStart = { dragging = it.y / size.height },
                onDragEnd = { dragging?.let(onChange); dragging = null },
                onDragCancel = { dragging = null },
            ) { change, _ ->
                change.consume()
                val f = (change.position.y / size.height).coerceIn(0f, 1f)
                dragging = f
                if (live) onChange(f)
            }
        }.pointerInput(enabled) {
            if (enabled) detectTapGestures { onChange((it.y / size.height).coerceIn(0f, 1f)) }
        },
    ) {
        DrawCanvas(Modifier.fillMaxSize()) {
            val x = size.width / 2
            val w = 6.dp.toPx()
            drawRoundRect(Color.White.copy(alpha = 0.25f), Offset(x - w / 2, 0f), Size(w, size.height), CornerRadius(w))
            val y = shown * size.height
            val (top, h) = if (fillFromTop) 0f to y else y to size.height - y
            drawRoundRect(Color.White, Offset(x - w / 2, top), Size(w, h), CornerRadius(w))
            drawCircle(Color.White, radius = 7.dp.toPx(), center = Offset(x, y))
        }
    }
}

private enum class Glyph { PLAY, PAUSE, PREV, NEXT, SHUFFLE, REPEAT, REPEAT_ONE, CLOSE }

/** Simple drawn icons, so no icon library is needed. */
@Composable
private fun IconButton(glyph: Glyph, color: Color, size: Int = 44, onClick: () -> Unit) {
    Box(Modifier.size(size.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        DrawCanvas(Modifier.size((size * 0.7f).dp)) {
            val s = this.size.width
            val stroke = Stroke(width = s * 0.08f, cap = StrokeCap.Round)
            fun tri(vararg pts: Pair<Float, Float>) = drawPath(Path().apply {
                moveTo(pts[0].first * s, pts[0].second * s)
                pts.drop(1).forEach { lineTo(it.first * s, it.second * s) }
                close()
            }, color)
            fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
                drawLine(color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), stroke.width, StrokeCap.Round)
            when (glyph) {
                Glyph.PLAY -> tri(0.3f to 0.18f, 0.3f to 0.82f, 0.82f to 0.5f)
                Glyph.PAUSE -> {
                    drawRoundRect(color, Offset(0.24f * s, 0.16f * s), Size(0.16f * s, 0.68f * s), CornerRadius(0.06f * s))
                    drawRoundRect(color, Offset(0.6f * s, 0.16f * s), Size(0.16f * s, 0.68f * s), CornerRadius(0.06f * s))
                }
                Glyph.NEXT -> { tri(0.22f to 0.2f, 0.22f to 0.8f, 0.66f to 0.5f); line(0.74f, 0.2f, 0.74f, 0.8f) }
                Glyph.PREV -> { tri(0.78f to 0.2f, 0.78f to 0.8f, 0.34f to 0.5f); line(0.26f, 0.2f, 0.26f, 0.8f) }
                Glyph.SHUFFLE -> {
                    line(0.15f, 0.3f, 0.8f, 0.72f); line(0.15f, 0.72f, 0.8f, 0.3f)
                    line(0.8f, 0.3f, 0.66f, 0.28f); line(0.8f, 0.3f, 0.76f, 0.43f)
                    line(0.8f, 0.72f, 0.66f, 0.74f); line(0.8f, 0.72f, 0.76f, 0.59f)
                }
                Glyph.REPEAT, Glyph.REPEAT_ONE -> {
                    drawRoundRect(color, Offset(0.15f * s, 0.3f * s), Size(0.7f * s, 0.42f * s), CornerRadius(0.14f * s), style = stroke)
                    line(0.62f, 0.3f, 0.52f, 0.2f); line(0.62f, 0.3f, 0.52f, 0.4f)
                    if (glyph == Glyph.REPEAT_ONE) line(0.5f, 0.42f, 0.5f, 0.6f)
                }
                Glyph.CLOSE -> {
                    // Collapse arrows, like YouTube Music's exit-fullscreen corner icon.
                    line(0.2f, 0.4f, 0.4f, 0.4f); line(0.4f, 0.4f, 0.4f, 0.2f)
                    line(0.6f, 0.2f, 0.6f, 0.4f); line(0.6f, 0.4f, 0.8f, 0.4f)
                    line(0.2f, 0.6f, 0.4f, 0.6f); line(0.4f, 0.6f, 0.4f, 0.8f)
                    line(0.6f, 0.8f, 0.6f, 0.6f); line(0.6f, 0.6f, 0.8f, 0.6f)
                }
            }
        }
    }
}

private fun appIcon(ctx: android.content.Context, pkg: String): ImageBitmap? = try {
    val d = ctx.packageManager.getApplicationIcon(pkg)
    val px = (26 * ctx.resources.displayMetrics.density).toInt()
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, px, px); d.draw(Canvas(it)) }.asImageBitmap()
} catch (e: Exception) {
    null
}
```

- [ ] **Step 2: Shell.** Replace `Panel.MEDIA -> Placeholder("Media player")` with `Panel.MEDIA -> MediaPanel(onClose = { closePanel() })`.

- [ ] **Step 3: Build, install.** `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`, then `.\scripts\run.ps1 -NoBuild`.

- [ ] **Step 4: Device check.** Start YouTube Music playing, swipe up on Home, screenshot the cover. Ask the user to check: play/pause, next, previous, shuffle, repeat (off, all, one), scrub on the left edge (one jump on release), volume on the right edge, close with the corner icon. Then start a second player (e.g. a Chrome video) and switch in the pill; stop one and confirm the panel falls back; stop all: "Nothing playing".

- [ ] **Step 5: Commit** `Add swipe-up media player panel`

---

### Task 4: Docs

- [ ] **Step 1: README** "What works", add:
```markdown
- Media player: swipe up on Home. Seek on the left edge, volume on the right, shuffle and repeat, switch between playing apps.
```
- [ ] **Step 2: Commit** `Update README for the media player`
