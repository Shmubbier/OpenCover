package dev.opencover.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.os.SystemClock
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.media.MediaHub
import dev.opencover.media.Player
import dev.opencover.media.Repeat
import dev.opencover.media.formatTime
import dev.opencover.media.livePosition
import dev.opencover.media.seekTarget
import dev.opencover.media.toCompat
import dev.opencover.media.volumeFor
import kotlinx.coroutines.delay

/** Classic layout (Settings > Media player): buttons and an app pill, shown on tap. */
@Composable
internal fun ClassicPlayerView(p: Player, players: List<Player>, onPick: (String) -> Unit) {
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
    var trackDir by remember { mutableIntStateOf(1) } // 1 = next, -1 = previous

    var controlsShown by remember { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    fun touched() { lastTouch = SystemClock.elapsedRealtime() }
    LaunchedEffect(controlsShown, lastTouch) {
        if (controlsShown) { delay(4000); controlsShown = false }
    }

    Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { controlsShown = !controlsShown; touched() } }) {
        // Only the art and the song text slide; controls stay put.
        AnimatedContent(p, contentKey = ::trackKey, transitionSpec = slideBetween(players) { trackDir }, label = "art") { shown ->
            Art(players.find { it.id == shown.id } ?: shown)
        }

        // Left edge: seek (top = start). Right edge: media volume (top = loudest).
        val progress by animateFloatAsState(if (p.durationMs > 0) position.toFloat() / p.durationMs else 0f, tween(400), label = "progress")
        if (p.durationMs > 0) VerticalBar(
            fromTop = progress, fillFromTop = true, enabled = p.canSeek, live = false,
            modifier = Modifier.align(Alignment.CenterStart),
        ) { f -> seekTarget(f, p.durationMs)?.let { controls?.seekTo(it) } }
        VerticalBar(
            fromTop = 1f - volume.toFloat() / maxVol, fillFromTop = false, enabled = true, live = true,
            modifier = Modifier.align(Alignment.CenterEnd), avoidCutout = true,
        ) { f ->
            volume = volumeFor(f, maxVol)
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
        }

        // Pill and buttons appear on tap and fade out after 4 s without a touch.
        AnimatedVisibility(controlsShown && players.size > 1, Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier.padding(top = 10.dp).background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                players.forEach { other ->
                    val icon = remember(other.pkg) { appIcon(ctx, other.pkg) }
                    Box(
                        Modifier.size(40.dp)
                            .background(if (other.id == p.id) Color(0xFF7A1F1F) else Color.Transparent, RoundedCornerShape(50))
                            .clickable { touched(); onPick(other.id) },
                        contentAlignment = Alignment.Center,
                    ) { if (icon != null) Image(icon, contentDescription = other.pkg, modifier = Modifier.size(26.dp)) }
                }
            }
        }

        // Five fixed slots: shuffle, previous, play/pause, next, repeat. Skips grey out when not allowed.
        AnimatedVisibility(controlsShown, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                val slot = Modifier.weight(1f)
                Box(slot, contentAlignment = Alignment.Center) {
                    p.shuffle?.let { on ->
                        IconButton(Glyph.SHUFFLE, if (on) Color.White else Color.Gray) {
                            touched()
                            controls?.setShuffleMode(if (on) PlaybackStateCompat.SHUFFLE_MODE_NONE else PlaybackStateCompat.SHUFFLE_MODE_ALL)
                        }
                    }
                }
                Box(slot, contentAlignment = Alignment.Center) {
                    IconButton(Glyph.PREV, Color.White, enabled = p.canSkipPrev) { touched(); trackDir = -1; controls?.skipToPrevious() }
                }
                Box(slot, contentAlignment = Alignment.Center) {
                    IconButton(if (p.playing) Glyph.PAUSE else Glyph.PLAY, Color.White, size = 64) {
                        touched()
                        if (p.playing) controls?.pause() else controls?.play()
                    }
                }
                Box(slot, contentAlignment = Alignment.Center) {
                    IconButton(Glyph.NEXT, Color.White, enabled = p.canSkipNext) { touched(); trackDir = 1; controls?.skipToNext() }
                }
                Box(slot, contentAlignment = Alignment.Center) {
                    p.repeat?.let { r ->
                        IconButton(if (r == Repeat.ONE) Glyph.REPEAT_ONE else Glyph.REPEAT, if (r == Repeat.OFF) Color.Gray else Color.White) {
                            touched()
                            controls?.setRepeatMode(r.next().toCompat())
                        }
                    }
                }
            }
        }

        AnimatedContent(
            p, contentKey = ::trackKey, transitionSpec = slideBetween(players) { trackDir }, label = "text",
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

/** App switch: slide from the side the new app sits on in the pill. New song: slide by skip direction. */
private fun slideBetween(players: List<Player>, trackDir: () -> Int): AnimatedContentTransitionScope<Player>.() -> ContentTransform = {
    val dir = if (initialState.id == targetState.id) trackDir()
    else if (players.indexOfFirst { it.id == targetState.id } > players.indexOfFirst { it.id == initialState.id }) 1 else -1
    slideInHorizontally { it * dir } togetherWith slideOutHorizontally { -it * dir }
}

private fun appIcon(ctx: android.content.Context, pkg: String): ImageBitmap? = try {
    val d = ctx.packageManager.getApplicationIcon(pkg)
    val px = (26 * ctx.resources.displayMetrics.density).toInt()
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also { d.setBounds(0, 0, px, px); d.draw(Canvas(it)) }.asImageBitmap()
} catch (e: Exception) {
    null
}
