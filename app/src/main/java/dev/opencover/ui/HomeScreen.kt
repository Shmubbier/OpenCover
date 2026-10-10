package dev.opencover.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.layout.Home
import dev.opencover.layout.QuickApp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(home: Home, wallpaper: ImageBitmap?, a: LauncherActions, onEditSlot: (Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            Image(wallpaper, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Clock(Modifier.align(Alignment.TopStart).padding(20.dp))
        // Bottom left, lifted above the camera cutout (bottom right) so slots 3 and 4 aren't hidden.
        Row(
            Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.displayCutout).padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            home.quick.forEachIndexed { slot, q -> QuickSlot(q, a) { onEditSlot(slot) } }
        }
    }
}

@Composable
private fun QuickSlot(q: QuickApp?, a: LauncherActions, onEdit: () -> Unit) {
    val icon = q?.let { a.repo.icon(it.pkg, it.activity, it.user) }
    Box(
        Modifier.size(52.dp).pointerInput(q) {
            detectTapGestures(
                onTap = { if (q == null) onEdit() else a.launch(q.pkg, q.activity, q.user) },
                onLongPress = { onEdit() },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        else Box(
            Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Color.White, fontSize = 22.sp) }
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L) // wake on the minute
            now = LocalDateTime.now()
        }
    }
    val time = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm"
    val shadow = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f))
    Column(modifier) {
        Text(now.format(DateTimeFormatter.ofPattern(time)), color = Color.White, fontSize = 64.sp, style = shadow)
        Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d")), color = Color.White, fontSize = 18.sp, style = shadow)
    }
}
