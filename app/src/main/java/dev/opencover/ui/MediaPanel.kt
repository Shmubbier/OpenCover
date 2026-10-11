package dev.opencover.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.os.SystemClock
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.ui.text.style.TextOverflow
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
import kotlin.math.abs
import androidx.compose.foundation.Canvas as DrawCanvas

/** Swipe-up media player, laid out like YouTube Music's. */
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
            else -> PlayerView(p, players, onPick = { chosen = it })
        }
    }
}

@Composable
private fun PlayerView(p: Player, players: List<Player>, onPick: (String) -> Unit) {
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

    Box(Modifier.fillMaxSize()) {
        // Only the art and the song text slide when switching players; controls stay put.
        AnimatedContent(p, contentKey = { it.id }, transitionSpec = slideBetween(players), label = "art") { shown ->
            // Keyed on the bitmap itself: apps often send the new title before the new artwork.
            val bitmap = MediaHub.art(shown.id)
            val art = remember(bitmap) { bitmap?.asImageBitmap() }
            Box(Modifier.fillMaxSize()) {
                if (art != null) Image(art, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)))
            }
        }

        // Left edge: seek (top = start). One seek on release.
        // Glides to the new player's position on a switch; tiny steps during playback.
        val progress by animateFloatAsState(if (p.durationMs > 0) position.toFloat() / p.durationMs else 0f, tween(400), label = "progress")
        if (p.durationMs > 0) VerticalBar(
            fromTop = progress, fillFromTop = true, enabled = p.canSeek, live = false,
            modifier = Modifier.align(Alignment.CenterStart),
        ) { f -> seekTarget(f, p.durationMs)?.let { controls?.seekTo(it) } }

        // Right edge: media volume (top = loudest).
        VerticalBar(
            fromTop = 1f - volume.toFloat() / maxVol, fillFromTop = false, enabled = true, live = true,
            modifier = Modifier.align(Alignment.CenterEnd), avoidCutout = true,
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

        // Bottom left: title and artist run to just before the volume bar; the time sits low in the
        // lip left of the camera cutout. Slides with the art on a player switch.
        AnimatedContent(
            p, contentKey = { it.id }, transitionSpec = slideBetween(players), label = "text",
            modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 36.dp, end = 40.dp, bottom = 10.dp),
        ) { shown ->
            val q = players.find { it.id == shown.id } ?: shown
            Column {
                Text(q.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(q.artist, color = Color.LightGray, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (q.durationMs > 0) Text("${formatTime(livePosition(q, now))} / ${formatTime(q.durationMs)}", color = Color.LightGray, fontSize = 13.sp)
            }
        }
    }
}

/** Slide in from the side the new player sits on in the pill. */
private fun slideBetween(players: List<Player>): AnimatedContentTransitionScope<Player>.() -> ContentTransform = {
    val dir = if (players.indexOfFirst { it.id == targetState.id } > players.indexOfFirst { it.id == initialState.id }) 1 else -1
    slideInHorizontally { it * dir } togetherWith slideOutHorizontally { -it * dir }
}

/** Vertical slider flush to an edge. [fromTop] is the handle position, 0 = top. */
@Composable
private fun VerticalBar(
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
            .width(28.dp).padding(vertical = 16.dp).pointerInput(enabled) {
            if (!enabled) return@pointerInput
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

private enum class Glyph { PLAY, PAUSE, PREV, NEXT, SHUFFLE, REPEAT, REPEAT_ONE }

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
