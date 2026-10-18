package dev.opencover.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.opencover.home.Wallpaper
import dev.opencover.lock.LockPrefs
import dev.opencover.lock.Module
import dev.opencover.lock.Privacy
import dev.opencover.lock.Spot
import dev.opencover.lock.clamp
import dev.opencover.lock.iconApps
import dev.opencover.lock.privacy
import dev.opencover.media.MediaHub
import dev.opencover.notify.NotificationStore
import java.time.format.DateTimeFormatter

/** Android's lock screen notification setting, read now. */
fun lockPrivacy(ctx: Context): Privacy = privacy(
    Settings.Secure.getInt(ctx.contentResolver, "lock_screen_show_notifications", 1),
    Settings.Secure.getInt(ctx.contentResolver, "lock_screen_allow_private_notifications", 1),
)

/**
 * The AOD and lock screen face: each switched-on module at its spot. [dim]: AOD look (black, faint).
 * [locked]: lock screen extras (now-playing chip). [onMove]: editor mode, modules are dragged and report their new spot.
 */
@Composable
fun LockFace(
    layout: Map<Module, Spot>,
    dim: Boolean,
    locked: Boolean,
    onPeek: () -> Unit = {},
    modifier: Modifier = Modifier,
    onMove: ((Module, Spot) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    val wallpaper = remember(dim) { if (dim) null else Wallpaper.load(ctx) }
    val sizes = remember { mutableStateMapOf<Module, IntSize>() }
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        wallpaper?.let { Image(it, null, Modifier.fillMaxSize().alpha(0.6f), contentScale = ContentScale.Crop) }
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val density = LocalDensity.current
        layout.forEach { (module, spot) ->
            if (!spot.on && onMove == null) return@forEach
            var drag by remember(module, spot) { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
            val x = spot.x * wPx + drag.x
            val y = spot.y * hPx + drag.y
            var m = Modifier
                .offset(with(density) { x.toDp() }, with(density) { y.toDp() })
                .onSizeChanged { sizes[module] = it }
                .alpha(if (!spot.on) 0.3f else if (dim) 0.55f else 1f)
            if (onMove != null) m = m.pointerInput(module, spot) {
                detectDragGestures(
                    onDragEnd = {
                        val s = sizes[module] ?: IntSize.Zero
                        onMove(module, clamp(spot.copy(x = x / wPx, y = y / hPx), s.width / wPx, s.height / hPx))
                        drag = androidx.compose.ui.geometry.Offset.Zero
                    },
                ) { change, amount -> change.consume(); drag += amount }
            }
            Box(m) { ModuleView(module, locked || onMove != null, onPeek) }
        }
    }
}

@Composable
private fun ModuleView(module: Module, locked: Boolean, onPeek: () -> Unit) {
    val ctx = LocalContext.current
    when (module) {
        Module.CLOCK -> ClockFace(LockPrefs.clock, Modifier, widgets = false)
        Module.DATE -> Text(rememberNow().format(DateTimeFormatter.ofPattern("EEE, MMM d")), color = Color.White, fontSize = 18.sp)
        Module.BATTERY -> Battery()
        Module.ICONS -> {
            if (lockPrivacy(ctx) == Privacy.NONE) return
            val (apps, more) = iconApps(NotificationStore.notes, ctx.packageName)
            if (apps.isEmpty()) return
            Row(Modifier.clickable(onClick = onPeek).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                apps.forEach { pkg ->
                    val icon = remember(pkg) { runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(48, 48).asImageBitmap() }.getOrNull() }
                    icon?.let { Image(it, null, Modifier.size(20.dp)) }
                }
                if (more > 0) Text("+$more", color = Color.White, fontSize = 14.sp)
            }
        }
        Module.NOW_PLAYING -> if (locked) NowPlaying()
    }
}

@Composable
private fun Battery() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { tick++ } }
        ctx.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(r) }
    }
    val bm = remember { ctx.getSystemService(BatteryManager::class.java) }
    val (level, charging) = remember(tick) { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) to bm.isCharging }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd, null, tint = Color.White, modifier = Modifier.size(17.dp))
        Text(" $level%", color = Color.White, fontSize = 15.sp)
    }
}

@Composable
private fun NowPlaying() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { MediaHub.start(ctx) }
    val p = MediaHub.players.firstOrNull { it.playing } ?: MediaHub.players.firstOrNull() ?: return
    val art = remember(p.id, p.artKey) { MediaHub.art(p.id)?.asImageBitmap() }
    Row(
        Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.14f)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(6.dp)).background(Color.DarkGray), contentAlignment = Alignment.Center) {
            if (art != null) Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Filled.MusicNote, null, tint = Color.White)
        }
        Column(Modifier.widthIn(max = 160.dp)) {
            Text(p.title, color = Color.White, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(p.artist, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(32.dp).clip(CircleShape).background(Color.White).clickable {
                MediaHub.controls(p.id)?.let { if (p.playing) it.pause() else it.play() }
            },
            contentAlignment = Alignment.Center,
        ) { Icon(if (p.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", tint = Color.Black) }
    }
}
