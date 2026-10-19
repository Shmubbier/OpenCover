package dev.opencover.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.opencover.edge.averageColor
import dev.opencover.home.Wallpaper
import dev.opencover.look.LookPrefs
import dev.opencover.look.PanelStyle

private val SheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)

/**
 * The mask of the notifications page, the apps pages, the app drawer and quick settings, in the chosen panel style:
 * frosted glass, a Lawnchair-style scrim sheet, a sheet tinted with the wallpaper's colour, or a floating card. The
 * wallpaper itself is behind everything (Shell blurs it as a page or panel covers Home).
 */
@Composable
fun PanelBackdrop(content: @Composable () -> Unit) {
    val style = LookPrefs.style
    val s = LookPrefs.current
    val still = Wallpaper.homeStill
    Box(Modifier.fillMaxSize()) {
        when (style) {
            PanelStyle.FROSTED -> Box(Modifier.fillMaxSize().background(Color(0xFF0E0E14).copy(alpha = s.opacity))) { content() }
            PanelStyle.SCRIM -> Box(Modifier.fillMaxSize().padding(top = 10.dp).background(Color(0xFF0A0A0C).copy(alpha = s.opacity), SheetShape)) { content() }
            PanelStyle.TINT -> {
                val tint = remember(still) { tintOf(still) }
                Box(Modifier.fillMaxSize().padding(top = 10.dp).background(tint.copy(alpha = s.opacity), SheetShape)) { content() }
            }
            PanelStyle.CARDS -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)).padding(10.dp)) {
                Box(Modifier.fillMaxSize().background(Color(0xFF1E1E24).copy(alpha = s.opacity), RoundedCornerShape(18.dp))) { content() }
            }
        }
    }
}

/** A dark sheet colour in the wallpaper's hue, for the Material You tint style. */
private fun tintOf(still: ImageBitmap?): Color {
    val avg = still?.let {
        val small = android.graphics.Bitmap.createScaledBitmap(it.asAndroidBitmap(), 24, 24, true)
        IntArray(24 * 24).also { px -> small.getPixels(px, 0, 24, 0, 0, 24, 24) }.let { px -> averageColor(px, 0xFF34245A.toInt()) }
    } ?: 0xFF34245A.toInt()
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(avg, it) }
    hsv[1] = hsv[1].coerceIn(0.35f, 0.6f); hsv[2] = 0.3f
    return Color(android.graphics.Color.HSVToColor(hsv))
}
