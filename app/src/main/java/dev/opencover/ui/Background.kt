package dev.opencover.ui

import android.media.MediaPlayer
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import dev.opencover.home.AnimPlay
import dev.opencover.home.Animation
import dev.opencover.home.Crop
import dev.opencover.home.HomePrefs
import dev.opencover.look.Playhead
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A background: [anim] (a GIF or animated WebP) at [playhead], which moves only while [playing] and frames are drawn;
 * else the [still]. Paused, it shows the frame it stopped on. With [video] (the animation made into H.264), the phone's
 * video decoder plays it instead of the CPU. Settings > Home can play it only briefly, or not at all.
 */
@Composable
fun Background(
    still: ImageBitmap?, anim: Animation?, playhead: Playhead, playing: Boolean, modifier: Modifier = Modifier, alpha: Float = 1f,
    crop: Crop? = null, video: File? = null,
) {
    val mode = HomePrefs.animPlay
    if (anim == null || mode == AnimPlay.NEVER) {
        still?.let { Image(it, null, modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop) }
        return
    }
    // "First 10 s": plays for 10 s each time this screen starts playing, then holds its frame.
    var expired by remember { mutableStateOf(false) }
    LaunchedEffect(playing, mode) {
        expired = false
        if (playing && mode == AnimPlay.BRIEFLY) { delay(10_000); expired = true }
    }
    val play = playing && !expired
    if (video != null) {
        VideoBackground(video, anim.width, anim.height, playhead, play, crop, still, modifier, alpha)
        return
    }
    val frame = anim.frame
    if (frame == null) still?.let { Image(it, null, modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop) }
    LaunchedEffect(play) {
        // Only the playing screen moves the playhead; a paused one (the AOD) just shows where it is.
        if (!play) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) withFrameMillis { now -> playhead.frame(now - last); last = now }
    }
    // Decode the frame for the playhead's position off the main thread, skipping ahead if decoding falls behind.
    LaunchedEffect(anim) {
        snapshotFlow { playhead.at(anim.duration) }.conflate().collect { withContext(Animation.decoder) { anim.show(it) } }
    }
    if (frame == null) return
    Canvas(modifier.fillMaxSize().alpha(alpha)) {
        // The chosen part of the animation fills the screen; without one, the centre.
        val c = crop ?: Crop.centered(frame.width, frame.height, size.width, size.height)
        drawIntoCanvas { canvas ->
            val src = android.graphics.Rect(
                (c.x * frame.width).toInt(), (c.y * frame.height).toInt(),
                ((c.x + c.w) * frame.width).toInt(), ((c.y + c.h) * frame.height).toInt(),
            )
            canvas.nativeCanvas.drawBitmap(frame, src, android.graphics.RectF(0f, 0f, size.width, size.height), Smooth)
        }
    }
}

private val Smooth = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)

/**
 * [video] played by MediaPlayer onto a SurfaceView: frames go straight to the display hardware, so nothing in the app
 * redraws while it plays. Paused (another page, the AOD), the frame it stopped on is captured and drawn as a picture
 * instead (it can then be blurred, and the AOD shows it without a player). Starts from and saves back to [playhead].
 */
@Composable
private fun VideoBackground(
    video: File, w: Int, h: Int, playhead: Playhead, playing: Boolean, crop: Crop?, still: ImageBitmap?, modifier: Modifier, alpha: Float,
) {
    // Live while playing, and until the frame it paused on has been captured.
    var live by remember { mutableStateOf(false) }
    var started by remember { mutableStateOf(false) } // the first video frame is up: the picture can go
    if (playing) live = true
    val frame = playhead.stopped
    if (live) Box(Modifier.fillMaxSize().clipToBounds()) {
        val holder = remember(video) { VideoHolder(video, playhead) }
        holder.onStarted = { started = true }
        holder.onStopped = { live = false; started = false }
        holder.playing = playing
        DisposableEffect(holder) { onDispose { holder.release(); started = false } }
        AndroidView(
            factory = { ctx -> SurfaceView(ctx).also { v -> holder.view = v; v.holder.addCallback(holder) } },
            // Oversized and shifted so the chosen part of the video fills the screen.
            modifier = Modifier.layout { m, cs ->
                val c = crop ?: Crop.centered(w, h, cs.maxWidth.toFloat(), cs.maxHeight.toFloat())
                val pw = (cs.maxWidth / c.w).roundToInt()
                val ph = (cs.maxHeight / c.h).roundToInt()
                val p = m.measure(androidx.compose.ui.unit.Constraints.fixed(pw, ph))
                layout(cs.maxWidth, cs.maxHeight) { p.place((-c.x * pw).roundToInt(), (-c.y * ph).roundToInt()) }
            },
            update = { v -> v.alpha = alpha; holder.apply() },
        )
    }
    if (!(live && started)) {
        // Paused, or the video isn't up yet: the frame it stopped on, else the background's still.
        if (frame != null) Canvas(modifier.fillMaxSize().alpha(alpha)) {
            val c = crop ?: Crop.centered(frame.width, frame.height, size.width, size.height)
            drawIntoCanvas { canvas ->
                val src = android.graphics.Rect(
                    (c.x * frame.width).toInt(), (c.y * frame.height).toInt(),
                    ((c.x + c.w) * frame.width).toInt(), ((c.y + c.h) * frame.height).toInt(),
                )
                canvas.nativeCanvas.drawBitmap(frame, src, android.graphics.RectF(0f, 0f, size.width, size.height), Smooth)
            }
        } else still?.let { Image(it, null, modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop) }
    }
}

private class VideoHolder(val video: File, val playhead: Playhead) : SurfaceHolder.Callback {
    var view: SurfaceView? = null
    var player: MediaPlayer? = null
    var playing = false
    var onStarted: () -> Unit = {}
    var onStopped: () -> Unit = {}
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** Follows [playing]: pausing captures the frame shown, then lets the view go. */
    fun apply() {
        val p = player ?: return
        runCatching {
            if (playing && !p.isPlaying) p.start()
            if (!playing && p.isPlaying) { p.pause(); playhead.moveTo(p.currentPosition.toLong()); capture() }
        }
    }

    private fun capture() {
        val v = view ?: return onStopped()
        val p = player ?: return onStopped()
        val bmp = android.graphics.Bitmap.createBitmap(p.videoWidth.coerceAtLeast(1), p.videoHeight.coerceAtLeast(1), android.graphics.Bitmap.Config.ARGB_8888)
        runCatching {
            android.view.PixelCopy.request(v, bmp, { r ->
                if (r == android.view.PixelCopy.SUCCESS) playhead.stopped = bmp
                onStopped()
            }, main)
        }.onFailure { onStopped() }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        player = runCatching {
            MediaPlayer().apply {
                setDataSource(video.path)
                setDisplay(h)
                isLooping = true
                setVolume(0f, 0f)
                setOnInfoListener { _, what, _ -> if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) onStarted(); false }
                prepare()
                val d = duration
                seekTo(if (d > 0) playhead.position % d else 0L, MediaPlayer.SEEK_CLOSEST)
            }
        }.getOrNull()
        if (player == null) onStopped()
        apply()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) = release()

    fun release() {
        player?.let { p -> runCatching { playhead.moveTo(p.currentPosition.toLong()); p.release() } }
        player = null
    }
}
