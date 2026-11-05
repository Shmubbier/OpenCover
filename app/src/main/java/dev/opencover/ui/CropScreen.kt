package dev.opencover.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.home.Crop
import dev.opencover.home.cropOf
import dev.opencover.home.moved
import dev.opencover.home.placeCentered
import dev.opencover.home.placementOf
import kotlin.math.roundToInt

/**
 * Pick the part of a background the cover shows: the screen is the frame, drag and pinch the image under it.
 * [image] is the picture (an animation's first frame); [start] the crop to begin from, else centred. With [aspect]
 * (width / height, for another screen such as a monitor) the frame is that shape, centred, the rest dimmed.
 */
@Composable
fun CropScreen(
    image: Bitmap, start: Crop?, onDone: (Crop) -> Unit, onCancel: () -> Unit,
    aspect: Float? = null, hint: String = "Drag and pinch to choose what the cover shows",
) {
    BackHandler(onBack = onCancel)
    val bmp = remember(image) { image.asImageBitmap() }
    val iw = image.width
    val ih = image.height
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val full = constraints.maxWidth.toFloat() to constraints.maxHeight.toFloat()
        // The frame: the whole screen, or the widest [aspect] box that fits, centred; positions below are inside it.
        val sw = if (aspect == null) full.first else minOf(full.first, full.second * aspect)
        val sh = if (aspect == null) full.second else sw / aspect
        val ox = (full.first - sw) / 2
        val oy = (full.second - sh) / 2
        var p by remember(image, sw, sh) {
            mutableStateOf(start?.let { placementOf(it, iw, ih, sw, sh) } ?: placeCentered(iw, ih, sw, sh))
        }
        Canvas(
            Modifier.fillMaxSize().pointerInput(image, sw, sh) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    p = moved(p, zoom, centroid.x - ox, centroid.y - oy, pan.x, pan.y, iw, ih, sw, sh)
                }
            },
        ) {
            drawImage(
                bmp,
                dstOffset = IntOffset((p.x + ox).roundToInt(), (p.y + oy).roundToInt()),
                dstSize = IntSize((iw * p.scale).roundToInt(), (ih * p.scale).roundToInt()),
                filterQuality = FilterQuality.Medium,
            )
            if (aspect != null) { // dim what falls outside the frame
                val dim = Color(0xB3000000)
                drawRect(dim, size = androidx.compose.ui.geometry.Size(size.width, oy))
                drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(0f, oy + sh), size = androidx.compose.ui.geometry.Size(size.width, size.height - oy - sh))
                drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(0f, oy), size = androidx.compose.ui.geometry.Size(ox, sh))
                drawRect(dim, topLeft = androidx.compose.ui.geometry.Offset(ox + sw, oy), size = androidx.compose.ui.geometry.Size(size.width - ox - sw, sh))
            }
        }
        Text(
            hint,
            color = Color.White, fontSize = 13.sp,
            modifier = Modifier.align(Alignment.TopCenter).background(Color(0x99000000)).padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0x99000000)).navigationBarsPadding().padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            TextButton(onClick = { p = placeCentered(iw, ih, sw, sh) }) { Text("Centre") }
            Button(onClick = { onDone(cropOf(p, iw, ih, sw, sh)) }) { Text("Set") }
        }
    }
}
