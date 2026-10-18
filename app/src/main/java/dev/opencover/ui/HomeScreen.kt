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
import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas as DrawCanvas
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import dev.opencover.home.*
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
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
        val style = HomePrefs.clock
        ClockFace(
            style,
            if (style == ClockStyle.BIG || style == ClockStyle.ANALOG) Modifier.align(Alignment.TopCenter).padding(top = 20.dp)
            else Modifier.align(Alignment.TopStart).padding(20.dp),
        )
        WeatherUpdates()
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

/** The current minute, ticking on the minute. */
@Composable
internal fun rememberNow(): LocalDateTime {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L)
            now = LocalDateTime.now()
        }
    }
    return now
}

private val shadow = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f))

/** One widget as shown on Home. */
private data class Chip(val icon: ImageVector, val text: String, val dim: Boolean = false)

/** Visible widgets with something to show, in the chosen order. */
@Composable
private fun rememberChips(): List<Pair<HomeWidget, Chip>> {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) { tick++ } }
        ctx.registerReceiver(r, IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        }, Context.RECEIVER_NOT_EXPORTED)
        onDispose { ctx.unregisterReceiver(r) }
    }
    val reading = Weather.reading
    val now = rememberNow()
    return remember(tick, reading, now, HomePrefs.order, HomePrefs.off, HomePrefs.fahrenheit) {
        HomePrefs.shown().mapNotNull { w ->
            when (w) {
                HomeWidget.BATTERY -> {
                    val bm = ctx.getSystemService(BatteryManager::class.java)
                    val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                    Chip(if (bm.isCharging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd, "$level%")
                }
                HomeWidget.ALARM -> ctx.getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
                    val t = LocalDateTime.ofInstant(Instant.ofEpochMilli(it.triggerTime), ZoneId.systemDefault())
                    Chip(Icons.Filled.Alarm, t.format(DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm a")))
                }
                HomeWidget.WEATHER -> reading?.let {
                    Chip(skyIcon(skyFor(it.code)), degrees(it.tempC, HomePrefs.fahrenheit), dim = stale(it, System.currentTimeMillis()))
                }
            }?.let { w to it }
        }
    }
}

private fun skyIcon(s: Sky): ImageVector = when (s) {
    Sky.CLEAR -> Icons.Filled.WbSunny
    Sky.PARTLY -> Icons.Filled.WbCloudy
    Sky.CLOUDY -> Icons.Filled.Cloud
    Sky.FOG -> Icons.Filled.Dehaze
    Sky.DRIZZLE, Sky.RAIN -> Icons.Filled.Umbrella
    Sky.SNOW -> Icons.Filled.AcUnit
    Sky.THUNDER -> Icons.Filled.Thunderstorm
}

@Composable
private fun ChipView(c: Chip, size: Int = 15) {
    val color = if (c.dim) Color.Gray else Color.White
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(c.icon, null, tint = color, modifier = Modifier.size((size + 2).dp))
        Text(" ${c.text}", color = color, fontSize = size.sp, style = shadow)
    }
}

@Composable
private fun ChipRow(chips: List<Chip>, center: Boolean = false) {
    if (chips.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp, if (center) Alignment.CenterHorizontally else Alignment.Start)) {
        chips.forEach { ChipView(it) }
    }
}

/** The Home clock in [style], with its widgets unless [widgets] is false (the lock screen has its own modules). */
@Composable
fun ClockFace(style: ClockStyle, modifier: Modifier, widgets: Boolean = true) {
    val ctx = LocalContext.current
    val now = rememberNow()
    val h24 = DateFormat.is24HourFormat(ctx)
    val time = now.format(DateTimeFormatter.ofPattern(if (h24) "HH:mm" else "h:mm"))
    val date = now.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    val chips = if (widgets) rememberChips() else emptyList()
    val all = chips.map { it.second }
    when (style) {
        ClockStyle.CURRENT -> Column(modifier) {
            Text(time, color = Color.White, fontSize = 64.sp, style = shadow)
            Text(date, color = Color.White, fontSize = 18.sp, style = shadow)
            Spacer(Modifier.height(6.dp))
            ChipRow(all)
        }
        ClockStyle.BIG -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(time, color = Color.White, fontSize = 96.sp, style = shadow)
            Text(date, color = Color.White, fontSize = 20.sp, style = shadow)
            Spacer(Modifier.height(6.dp))
            ChipRow(all, center = true)
        }
        ClockStyle.STACKED -> Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(now.format(DateTimeFormatter.ofPattern(if (h24) "HH" else "hh")), color = Color.White, fontSize = 88.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 84.sp, style = shadow)
                Text(now.format(DateTimeFormatter.ofPattern("mm")), color = Color.White, fontSize = 88.sp,
                    fontWeight = FontWeight.Bold, lineHeight = 84.sp, style = shadow)
            }
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(date, color = Color.White, fontSize = 16.sp, style = shadow)
                all.forEach { ChipView(it) }
            }
        }
        ClockStyle.ANALOG -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
            Analog(now, Modifier.size(150.dp))
            Spacer(Modifier.height(6.dp))
            Text(date, color = Color.White, fontSize = 16.sp, style = shadow)
            ChipRow(all, center = true)
        }
        ClockStyle.GLANCE -> Column(modifier) {
            Text(time, color = Color.White, fontSize = 56.sp, style = shadow)
            // Weather sits in the date line; the other widgets share the second line.
            val weather = chips.firstOrNull { it.first == HomeWidget.WEATHER }?.second
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(date, color = Color.White, fontSize = 18.sp, style = shadow)
                if (weather != null) { Text("  ·  ", color = Color.White, fontSize = 18.sp, style = shadow); ChipView(weather, 18) }
            }
            val rest = chips.filter { it.first != HomeWidget.WEATHER }.map { it.second }
            if (rest.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                rest.forEachIndexed { i, c ->
                    if (i > 0) Text("  ·  ", color = Color.White, fontSize = 15.sp, style = shadow)
                    ChipView(c)
                }
            }
        }
    }
}

@Composable
private fun Analog(now: LocalDateTime, modifier: Modifier) {
    DrawCanvas(modifier) {
        val r = size.minDimension / 2
        val c = center
        drawCircle(Color.White.copy(alpha = 0.85f), r, c, style = Stroke(width = 3.dp.toPx()))
        for (i in 0 until 12) {
            val a = Math.toRadians(i * 30.0)
            val o = Offset(c.x + (r * 0.88f * sin(a)).toFloat(), c.y - (r * 0.88f * cos(a)).toFloat())
            drawCircle(Color.White, 2.5.dp.toPx(), o)
        }
        fun hand(turns: Double, len: Float, w: Float) {
            val a = turns * 2 * Math.PI
            drawLine(Color.White, c, Offset(c.x + (r * len * sin(a)).toFloat(), c.y - (r * len * cos(a)).toFloat()), w, StrokeCap.Round)
        }
        hand((now.hour % 12 + now.minute / 60.0) / 12, 0.5f, 6.dp.toPx())
        hand(now.minute / 60.0, 0.78f, 4.dp.toPx())
        drawCircle(Color.White, 4.dp.toPx(), c)
    }
}

/** Fetches weather only while OpenCover is on screen, at most every 30 minutes. */
@Composable
private fun WeatherUpdates() {
    val ctx = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val on = HomeWidget.WEATHER !in HomePrefs.off
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        // The activity is only stopped (not destroyed) when the screen turns off; pause with it.
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                if (due(Weather.reading, System.currentTimeMillis())) withContext(Dispatchers.IO) { runCatching { Weather.fetch(ctx) } }
                delay(5 * 60_000L)
            }
        }
    }
}
