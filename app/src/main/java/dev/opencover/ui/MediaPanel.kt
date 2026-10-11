package dev.opencover.ui

import android.media.AudioManager
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.media.MediaHub
import dev.opencover.media.MediaPrefs
import dev.opencover.media.Player
import dev.opencover.media.Repeat
import dev.opencover.media.UpAction
import dev.opencover.media.defaultPlayer
import dev.opencover.media.formatTime
import dev.opencover.media.gradedAction
import dev.opencover.media.livePosition
import dev.opencover.media.nextPlayer
import dev.opencover.media.seekTarget
import dev.opencover.media.toCompat
import dev.opencover.media.volumeFor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.foundation.Canvas as DrawCanvas

/**
 * Swipe-up media player. Gestures: tap = play/pause, swipe left/right = next/previous,
 * swipe up = next playing app (or, in graded mode, further for shuffle and repeat), swipe down = close.
 */
@Composable
fun MediaPanel() {
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
            else -> {
                val onPick: (String) -> Unit = { id ->
                    chosen = id
                    MediaHub.controls(id)?.play() // switching apps plays the one now shown
                }
                if (remember { MediaPrefs.classic(ctx) }) ClassicPlayerView(p, players, onPick)
                else PlayerView(p, players, graded = remember { MediaPrefs.graded(ctx) }, onPick = onPick)
            }
        }
    }
}

@Composable
private fun PlayerView(p: Player, players: List<Player>, graded: Boolean, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val controls = MediaHub.controls(p.id)
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(p.id, p.playing) {
        while (p.playing) {
            now = SystemClock.elapsedRealtime()
            delay(500)
        }
    }
    val audio = remember { ctx.getSystemService(AudioManager::class.java) }
    val maxVol = remember { audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }
    var volume by remember { mutableIntStateOf(audio.getStreamVolume(AudioManager.STREAM_MUSIC)) }
    val position = livePosition(p, now)
    val next = nextPlayer(players, p.id)

    var drag by remember { mutableStateOf(Offset.Zero) } // live finger travel while swiping
    var trackDir by remember { mutableIntStateOf(1) }      // 1 = next (slides in from the right), -1 = previous
    var upAction by remember { mutableStateOf<UpAction?>(null) } // graded mode: what release will do

    // The gesture handler below outlives recompositions: read the latest player, not the first one.
    val cur by rememberUpdatedState(p)
    val curNext by rememberUpdatedState(next)
    val view = LocalView.current
    val pulse = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    fun playPause() {
        val c = MediaHub.controls(cur.id)
        if (cur.playing) c?.pause() else c?.play()
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP) // light tick
        scope.launch { pulse.snapTo(0f); pulse.animateTo(1f, tween(450)) }
    }

    fun toggleShuffle() = cur.shuffle?.let { on ->
        MediaHub.controls(cur.id)?.setShuffleMode(if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL)
    }
    fun cycleRepeat() = cur.repeat?.let { MediaHub.controls(cur.id)?.setRepeatMode(it.next().toCompat()) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight.toFloat()
        val swipe = with(LocalDensity.current) { 60.dp.toPx() }
        Box(
            Modifier.fillMaxSize().pointerInput(p.id, players.size, graded) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var total = Offset.Zero
                    var mode = 0 // 0 = tap so far, 1 = sideways, 2 = up, -1 = not ours (down closes the panel)
                    var tapped = false
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (c.isConsumed) { mode = -1; break } // an edge bar took it
                        if (!c.pressed) { tapped = mode == 0; break }
                        total += c.positionChange()
                        if (mode == 0 && total.getDistance() > viewConfiguration.touchSlop) {
                            mode = when {
                                abs(total.x) > abs(total.y) -> 1
                                total.y < 0 -> 2
                                else -> -1
                            }
                            if (mode == -1) break
                        }
                        if (mode > 0) {
                            c.consume()
                            drag = total
                            if (mode == 2 && graded) upAction = gradedAction(-total.y / h, curNext != null, cur.shuffle != null, cur.repeat != null)
                        }
                    }
                    when {
                        tapped -> playPause()
                        mode == 1 && total.x <= -swipe && cur.canSkipNext -> { trackDir = 1; MediaHub.controls(cur.id)?.skipToNext() }
                        mode == 1 && total.x >= swipe && cur.canSkipPrev -> { trackDir = -1; MediaHub.controls(cur.id)?.skipToPrevious() }
                        mode == 2 && graded -> when (upAction) {
                            UpAction.SWITCH -> curNext?.let { onPick(it.id) }
                            UpAction.SHUFFLE -> toggleShuffle()
                            UpAction.REPEAT -> cycleRepeat()
                            null -> {}
                        }
                        mode == 2 && total.y <= -swipe -> curNext?.let { onPick(it.id) }
                    }
                    drag = Offset.Zero
                    upAction = null
                }
            },
        ) {
            // Art: nudges with sideways swipes; on swipe up (style A) the next app's art rises from below.
            val lift = if (!graded && drag.y < 0 && next != null) drag.y else 0f
            AnimatedContent(
                p, contentKey = ::trackKey, transitionSpec = changeAnimation { trackDir }, label = "art",
                modifier = Modifier.graphicsLayer { translationX = drag.x * 0.3f; translationY = lift },
            ) { shown -> Art(players.find { it.id == shown.id } ?: shown) }
            if (lift < 0f && next != null) Box(Modifier.graphicsLayer { translationY = h + lift }) { Art(next) }

            // Song text, low in the lip left of the camera cutout; runs to just before the volume bar.
            AnimatedContent(
                p, contentKey = ::trackKey, transitionSpec = changeAnimation { trackDir }, label = "text",
                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 36.dp, end = 40.dp, bottom = 10.dp),
            ) { shown ->
                val q = players.find { it.id == shown.id } ?: shown
                Column {
                    Text(q.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(q.artist, color = Color.LightGray, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (q.durationMs > 0) Text("${formatTime(livePosition(q, now))} / ${formatTime(q.durationMs)}", color = Color.LightGray, fontSize = 13.sp)
                }
            }

            // Play/pause feedback: a soft white pulse that grows and fades from the middle.
            if (pulse.value < 1f) Box(
                Modifier.align(Alignment.Center).size(160.dp).graphicsLayer {
                    val s = 0.4f + 0.8f * pulse.value
                    scaleX = s; scaleY = s; alpha = (1f - pulse.value) * 0.45f
                }.background(Color.White, CircleShape)
            )

            // Graded mode: show what releasing will do.
            upAction?.let { action ->
                Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(64.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                        when (action) {
                            UpAction.SWITCH -> Text("App", color = Color.White, fontSize = 14.sp)
                            UpAction.SHUFFLE -> IconButton(Glyph.SHUFFLE, Color.White) {}
                            UpAction.REPEAT -> IconButton(Glyph.REPEAT, Color.White) {}
                        }
                    }
                    Text(
                        when (action) {
                            UpAction.SWITCH -> "Next app"
                            UpAction.SHUFFLE -> if (p.shuffle == true) "Shuffle off" else "Shuffle on"
                            UpAction.REPEAT -> "Repeat ${p.repeat?.next()?.name?.lowercase().orEmpty()}"
                        },
                        color = Color.White, fontSize = 13.sp,
                    )
                }
            }
        }

        // Left edge: seek (top = start). Glides on a switch; one seek on release.
        val progress by animateFloatAsState(if (p.durationMs > 0) position.toFloat() / p.durationMs else 0f, tween(400), label = "progress")
        if (p.durationMs > 0) VerticalBar(
            fromTop = progress, fillFromTop = true, enabled = p.canSeek, live = false,
            modifier = Modifier.align(Alignment.CenterStart),
        ) { f -> seekTarget(f, p.durationMs)?.let { controls?.seekTo(it) } }

        // Right edge: media volume (top = loudest), clear of the camera cutout.
        VerticalBar(
            fromTop = 1f - volume.toFloat() / maxVol, fillFromTop = false, enabled = true, live = true,
            modifier = Modifier.align(Alignment.CenterEnd), avoidCutout = true,
        ) { f ->
            volume = volumeFor(f, maxVol)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }

        // Style A: repeat top-left by the seek bar, shuffle top-right by the volume bar.
        if (!graded) {
            p.repeat?.let { r ->
                Box(Modifier.align(Alignment.TopStart).padding(start = 30.dp, top = 6.dp)) {
                    IconButton(if (r == Repeat.ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, if (r == Repeat.OFF) Color.Gray else Color.White, size = 40) { cycleRepeat() }
                }
            }
            p.shuffle?.let { on ->
                Box(Modifier.align(Alignment.TopEnd).padding(end = 34.dp, top = 6.dp)) {
                    IconButton(Glyph.SHUFFLE, if (on) Color.White else Color.Gray, size = 40) { toggleShuffle() }
                }
            }
        }
    }
}

@Composable
internal fun Art(player: Player) {
    // artKey changes when the artwork does (it often arrives after the new title).
    val art = remember(player.id, player.artKey) { MediaHub.art(player.id)?.asImageBitmap() }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (art != null) Image(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
    }
}

/** Same player and same song = no animation; this key changes on either. */
internal fun trackKey(p: Player) = "${p.id}|${p.title}|${p.artist}"

/** Another app's art rises from below; a new song in the same app slides in sideways. */
private fun changeAnimation(dir: () -> Int): AnimatedContentTransitionScope<Player>.() -> ContentTransform = {
    if (initialState.id != targetState.id) slideInVertically { it } togetherWith slideOutVertically { -it / 3 }
    else slideInHorizontally { it * dir() } togetherWith slideOutHorizontally { -it * dir() }
}

/** Vertical slider flush to an edge. [fromTop] is the handle position, 0 = top. */
@Composable
internal fun VerticalBar(
    fromTop: Float,
    fillFromTop: Boolean,
    enabled: Boolean,
    live: Boolean,
    modifier: Modifier,
    avoidCutout: Boolean = false,
    onChange: (Float) -> Unit,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    // After release, hold the chosen spot until the app reports it (or 2 s pass), so it doesn't
    // snap back to the old position first.
    var held by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(held) { if (held != null) { delay(2000); held = null } }
    if (held != null && abs(fromTop - held!!) < 0.02f) held = null
    val shown = (dragging ?: held ?: fromTop).coerceIn(0f, 1f)
    fun commit(f: Float) {
        if (!live) held = f
        onChange(f)
    }
    BoxWithConstraints(
        modifier.fillMaxHeight().then(if (avoidCutout) Modifier.windowInsetsPadding(WindowInsets.displayCutout) else Modifier)
            .width(28.dp).padding(vertical = 16.dp).graphicsLayer { alpha = if (enabled) 1f else 0.4f }.pointerInput(enabled) {
            // Disabled (e.g. the app doesn't allow seeking): still swallow the drag, or it would close the panel.
            if (!enabled) { detectVerticalDragGestures { change, _ -> change.consume() }; return@pointerInput }
            detectVerticalDragGestures(
                onDragStart = { dragging = it.y / size.height },
                onDragEnd = { dragging?.let(::commit); dragging = null },
                onDragCancel = { dragging = null },
            ) { change, _ ->
                change.consume()
                val f = (change.position.y / size.height).coerceIn(0f, 1f)
                dragging = f
                if (live) onChange(f)
            }
        }.pointerInput(enabled) {
            if (enabled) detectTapGestures { commit((it.y / size.height).coerceIn(0f, 1f)) }
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

internal enum class Glyph { PLAY, PAUSE, PREV, NEXT, SHUFFLE, REPEAT, REPEAT_ONE }

/** Simple drawn icons, so no icon library is needed. */
@Composable
internal fun IconButton(glyph: Glyph, tint: Color, size: Int = 44, enabled: Boolean = true, onClick: () -> Unit) {
    val color = if (enabled) tint else Color.Gray.copy(alpha = 0.6f)
    Box(Modifier.size(size.dp).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
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
            }
        }
    }
}
