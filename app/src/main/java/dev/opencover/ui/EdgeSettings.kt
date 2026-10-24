package dev.opencover.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.edge.EdgeLight
import dev.opencover.edge.EdgePrefs
import dev.opencover.edge.formatHex
import dev.opencover.edge.parseHex
import dev.opencover.edge.wheelHueSat
import kotlin.math.cos
import kotlin.math.sin

/** Settings > Edge lighting: switches, the glow colour (each app's own or one custom), and colours per app. */
@Composable
fun EdgeSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var enabled by remember { mutableStateOf(EdgePrefs.enabled(ctx)) }
    var wake by remember { mutableStateOf(EdgePrefs.wake(ctx)) }
    var custom by remember { mutableStateOf(EdgePrefs.custom(ctx)) }
    var perApp by remember { mutableStateOf(EdgePrefs.perApp(ctx)) }
    var editing by remember { mutableStateOf<String?>(null) } // "" = the custom colour, else a package
    var picking by remember { mutableStateOf(false) }
    val labels = remember { launchableApps(ctx) }

    editing?.let { target ->
        val start = if (target.isEmpty()) custom ?: 0xFF8AB4F8.toInt() else perApp[target] ?: EdgeLight.appColor(ctx, target)
        ColorEditor(
            title = if (target.isEmpty()) "Glow colour" else labels[target] ?: target,
            initial = start,
            onDone = { c ->
                if (target.isEmpty()) { custom = c; EdgePrefs.setCustom(ctx, c) }
                else { perApp = perApp + (target to c); EdgePrefs.setPerApp(ctx, perApp) }
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }
    if (picking) {
        AppList(labels.filterKeys { it !in perApp }, onPick = { picking = false; editing = it }, onClose = { picking = false })
        return
    }

    SettingsPage("Edge lighting", onBack) {
        Group {
            SwitchRow("Edge lighting", "The edges glow for new notifications.", enabled) { enabled = it; EdgePrefs.setEnabled(ctx, it) }
            SwitchRow("Wake the screen", "With the screen off or on the AOD: wake, glow, sleep again. Needs Shizuku.", wake) {
                wake = it; EdgePrefs.setWake(ctx, it)
            }
        }
        Group("Glow colour") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = custom == null, onClick = { custom = null; EdgePrefs.setCustom(ctx, null) }, label = { Text("App colour") })
                FilterChip(selected = custom != null, onClick = { editing = "" }, label = { Text("Custom") })
                custom?.let { Swatch(it) { editing = "" } }
            }
        }
        Group("Per-app colours") {
            Note("These win over the glow colour.")
            perApp.entries.sortedBy { labels[it.key] ?: it.key }.forEach { (pkg, c) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Swatch(c) { editing = pkg }
                    Text(labels[pkg] ?: pkg, color = Color.White, modifier = Modifier.weight(1f).clickable { editing = pkg })
                    TextButton(onClick = { perApp = perApp - pkg; EdgePrefs.setPerApp(ctx, perApp) }) { Text("Remove") }
                }
            }
            NavRow(null, "Add app", null) { picking = true }
        }
    }
}

@Composable
private fun Swatch(color: Int, onClick: () -> Unit) {
    Box(Modifier.size(28.dp).clip(CircleShape).background(Color(color)).border(1.dp, Color.Gray, CircleShape).clickable(onClick = onClick))
}

/** Installed apps with a launcher entry: package to label. */
private fun launchableApps(ctx: android.content.Context): Map<String, String> {
    val pm = ctx.packageManager
    return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .associate { it.activityInfo.packageName to it.loadLabel(pm).toString() }
        .toList().sortedBy { it.second.lowercase() }.toMap()
}

@Composable
private fun AppList(apps: Map<String, String>, onPick: (String) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxSize().background(Color(0xF0101010))) {
        Text("Pick an app", color = Color.Gray, fontSize = 13.sp, modifier = Modifier.padding(12.dp))
        LazyColumn {
            items(apps.entries.toList(), key = { it.key }) { (pkg, label) ->
                Text(label, color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(12.dp))
            }
        }
    }
}

/** Colour wheel (hue and saturation), brightness slider and hex field, kept in step; Try shows the glow on the cover. */
@Composable
private fun ColorEditor(title: String, initial: Int, onDone: (Int) -> Unit, onCancel: () -> Unit) {
    BackHandler(onBack = onCancel)
    val start = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(initial, it) } }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var sat by remember { mutableFloatStateOf(start[1]) }
    var value by remember { mutableFloatStateOf(start[2]) }
    val color = android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    var hex by remember { mutableStateOf(formatHex(initial)) }
    fun fromWheel(h: Float, s: Float) { hue = h; sat = s; hex = formatHex(android.graphics.Color.HSVToColor(floatArrayOf(h, s, value))) }

    Column(Modifier.fillMaxSize().background(Color(0xF0101010)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, color = Color.White, fontSize = 16.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val pick: (Offset, Float) -> Unit = { p, r -> wheelHueSat(p.x - r, p.y - r, r).let { (h, s) -> fromWheel(h, s) } }
            Canvas(
                Modifier.size(150.dp)
                    .pointerInput(Unit) { detectTapGestures { pick(it, size.width / 2f) } }
                    .pointerInput(Unit) { detectDragGestures { ch, _ -> pick(ch.position, size.width / 2f) } },
            ) {
                val r = size.minDimension / 2
                // Hue around the circle (counter-clockwise from the right), white towards the centre.
                val hues = (0..6).map { Color(android.graphics.Color.HSVToColor(floatArrayOf((360 - it * 60f) % 360, 1f, 1f))) }
                drawCircle(Brush.sweepGradient(hues, center), r)
                drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r), r)
                drawCircle(Color.Black.copy(alpha = 1 - value), r) // brightness
                val a = Math.toRadians(hue.toDouble())
                val dot = Offset(center.x + (cos(a) * sat * r).toFloat(), center.y - (sin(a) * sat * r).toFloat())
                drawCircle(Color.White, 7.dp.toPx(), dot)
                drawCircle(Color(color), 5.dp.toPx(), dot)
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(width = 120.dp, height = 32.dp).clip(CircleShape).background(Color(color)))
                OutlinedTextField(
                    value = hex, singleLine = true, modifier = Modifier.size(width = 150.dp, height = 56.dp),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    onValueChange = { t ->
                        hex = t.take(7)
                        parseHex(hex)?.let { c ->
                            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
                            hue = hsv[0]; sat = hsv[1]; value = hsv[2]
                        }
                    },
                )
                Text("Brightness", color = Color.Gray, fontSize = 11.sp)
                Slider(value = value, onValueChange = { value = it; hex = formatHex(android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, it))) },
                    modifier = Modifier.size(width = 150.dp, height = 24.dp))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { EdgeLight.preview(color) }) { Text("Try") }
            TextButton(onClick = onCancel) { Text("Cancel") }
            Button(onClick = { onDone(color) }) { Text("Save") }
        }
    }
}

