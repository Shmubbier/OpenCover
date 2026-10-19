package dev.opencover.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import dev.opencover.home.Animation
import dev.opencover.look.Playhead
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/**
 * A background: [anim] (a GIF or animated WebP) at [playhead], which moves only while [playing] and frames are drawn;
 * else the [still]. Paused, it shows the frame it stopped on.
 */
@Composable
fun Background(still: ImageBitmap?, anim: Animation?, playhead: Playhead, playing: Boolean, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val frame = anim?.frame
    if (anim == null || frame == null) still?.let { Image(it, null, modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop) }
    if (anim == null) return
    LaunchedEffect(playing) {
        // Only the playing screen moves the playhead; a paused one (the AOD) just shows where it is.
        if (!playing) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) withFrameMillis { now -> playhead.frame(now - last); last = now }
    }
    // Decode the frame for the playhead's position off the main thread, skipping ahead if decoding falls behind.
    LaunchedEffect(anim) {
        snapshotFlow { playhead.at(anim.duration) }.conflate().collect { withContext(Animation.decoder) { anim.show(it) } }
    }
    if (frame == null) return
    Canvas(modifier.fillMaxSize().alpha(alpha)) {
        // Centre-crop onto the screen.
        val s = maxOf(size.width / frame.width, size.height / frame.height)
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save()
            n.translate((size.width - frame.width * s) / 2, (size.height - frame.height * s) / 2)
            n.scale(s, s)
            n.drawBitmap(frame, 0f, 0f, Smooth)
            n.restore()
        }
    }
}

private val Smooth = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
