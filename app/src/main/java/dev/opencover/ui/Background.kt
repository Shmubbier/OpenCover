package dev.opencover.ui

import android.graphics.Bitmap
import android.graphics.Movie
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import dev.opencover.look.Playhead

/**
 * A background: [movie] (a GIF) drawn at [playhead], which moves only while [playing] and frames are drawn; else the
 * [still]. Paused, it shows the frame it stopped on.
 */
@Composable
fun Background(still: ImageBitmap?, movie: Movie?, playhead: Playhead, playing: Boolean, modifier: Modifier = Modifier, alpha: Float = 1f) {
    if (movie == null) {
        still?.let { Image(it, null, modifier.fillMaxSize().alpha(alpha), contentScale = ContentScale.Crop) }
        return
    }
    LaunchedEffect(playing) {
        // Only the playing screen moves the playhead; a paused one (the AOD) just shows where it is.
        if (!playing) return@LaunchedEffect
        var last = withFrameMillis { it }
        while (true) withFrameMillis { now -> playhead.frame(now - last); last = now }
    }
    // Movie can't draw straight to a hardware canvas (its frame texture goes stale): render each frame into a bitmap.
    val frame = remember(movie) { Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888) }
    val frameCanvas = remember(frame) { android.graphics.Canvas(frame) }
    Canvas(modifier.fillMaxSize().alpha(alpha)) {
        @Suppress("DEPRECATION")
        movie.setTime(playhead.at(movie.duration()).toInt())
        frame.eraseColor(0)
        @Suppress("DEPRECATION") movie.draw(frameCanvas, 0f, 0f)
        // Centre-crop the GIF onto the screen.
        val s = maxOf(size.width / movie.width(), size.height / movie.height())
        drawIntoCanvas { c ->
            val n = c.nativeCanvas
            n.save()
            n.translate((size.width - movie.width() * s) / 2, (size.height - movie.height() * s) / 2)
            n.scale(s, s)
            n.drawBitmap(frame, 0f, 0f, null)
            n.restore()
        }
    }
}
