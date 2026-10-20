package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import dev.opencover.phone.DisplayTweaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.apps.AppRepository
import dev.opencover.phone.Fold
import dev.opencover.phone.HeatGuard
import dev.opencover.phone.PhonePrefs
import dev.opencover.phone.nextTurns
import dev.opencover.quick.ShizukuShell
import dev.opencover.watcher.CoverAccessibility

/** Settings > Phone: keep the cover on when opened, heat guard, display presets, rotation per app. */
@Composable
fun PhoneSettings(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    val prefs = remember { PhonePrefs(ctx) }
    var keep by remember { mutableStateOf(prefs.keepCoverOn) }
    var heat by remember { mutableStateOf(prefs.heatGuard) }
    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Phone", color = Color.White, fontSize = 20.sp)
        SwitchRow(
            "Keep cover on when opened",
            if (ShizukuShell.allowed()) "Needs Shizuku. Stops the cover turning off when the phone opens." else "Start Shizuku to apply.",
            keep,
        ) { keep = it; prefs.keepCoverOn = it; Fold.apply(ctx) }
        SwitchRow(
            "Heat guard",
            "Only while the hotspot or mirroring is on. Warns at 42 °C, stops both at 45 °C.",
            heat,
        ) { heat = it; prefs.heatGuard = it; HeatGuard.evaluate() }
        var deep by remember { mutableStateOf(prefs.deepSleep) }
        SwitchRow(
            "Deep sleep when idle",
            "Needs Shizuku. Puts the phone into deep doze 30 s after the screen turns off (its motion sensors can't). Saves battery; some notifications may arrive a little later.",
            deep,
        ) { deep = it; prefs.deepSleep = it; if (!it) dev.opencover.phone.DeepSleep.awake() }
        DisplaySettings(prefs)
        InnerLauncherSettings()
        AppRotations(prefs)
        TextButton(onClick = onBack) { Text("Back") }
    }
}

@Composable
internal fun SwitchRow(title: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(note, color = Color.Gray, fontSize = 11.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Each app's fixed rotation on the cover; tap to turn it a quarter turn. Turned apps are listed first. */
@Composable
private fun AppRotations(prefs: PhonePrefs) {
    val ctx = LocalContext.current
    var turns by remember { mutableStateOf(prefs.rotations) }
    val apps = remember { AppRepository(ctx).load().distinctBy { it.pkg }.sortedBy { it.label.lowercase() } }
    Text("App rotation", color = Color.White)
    Text(
        if (ShizukuShell.allowed()) "Tap an app to turn it on the cover. Apps not listed as turned stay upright."
        else "Start Shizuku to apply.",
        color = Color.Gray, fontSize = 11.sp,
    )
    apps.sortedBy { (turns[it.pkg] ?: 0) == 0 }.forEach { app ->
        val t = turns[app.pkg] ?: 0
        Row(
            Modifier.fillMaxWidth().clickable {
                prefs.setTurns(app.pkg, nextTurns(t))
                turns = prefs.rotations
                CoverAccessibility.instance?.refreshScope() // the first turned app widens what the service watches
            }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(app.label, color = Color.White, modifier = Modifier.weight(1f))
            Text(if (t == 0) "Upright" else "${t * 90}°", color = if (t == 0) Color.Gray else Color(0xFFD0BCFF))
        }
    }
}

/** Cover density and font size, and the inner screen's size while mirroring (all through Shizuku). */
@Composable
private fun DisplaySettings(prefs: PhonePrefs) {
    val scope = rememberCoroutineScope()
    var density by remember { mutableStateOf<Int?>(null) }
    var font by remember { mutableStateOf<Float?>(null) }
    var anim by remember { mutableStateOf<Float?>(null) }
    var tick by remember { mutableIntStateOf(0) } // re-reads the phone's values after a change
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { DisplayTweaks.coverDensity() to DisplayTweaks.fontScale() }.let { (d, f) -> density = d; font = f }
        anim = withContext(Dispatchers.IO) { DisplayTweaks.animationScale() }
    }
    fun apply(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { block() }; tick++ }

    Text("Display", color = Color.White)
    if (!ShizukuShell.allowed()) { Text("Start Shizuku to change these.", color = Color.Gray, fontSize = 11.sp); return }
    Stepper("Cover density", density?.toString() ?: "…",
        onMinus = { density = (density ?: 280) - 10 }, onPlus = { density = (density ?: 280) + 10 },
        onApply = { apply { DisplayTweaks.setCoverDensity(density) } }, onReset = { apply { DisplayTweaks.setCoverDensity(null) } })
    Text("Lower fits more on the cover. The cover's own is 340.", color = Color.Gray, fontSize = 11.sp)
    Stepper("Font size", font?.let { "%.2f".format(it) } ?: "…",
        onMinus = { font = ((font ?: 1f) - 0.05f).coerceAtLeast(DisplayTweaks.MIN_FONT) },
        onPlus = { font = ((font ?: 1f) + 0.05f).coerceAtMost(DisplayTweaks.MAX_FONT) },
        onApply = { font?.let { f -> apply { DisplayTweaks.setFontScale(f) } } }, onReset = { apply { DisplayTweaks.setFontScale(1f) } })
    Text("For the whole phone, inner screen too.", color = Color.Gray, fontSize = 11.sp)
    Text("Animation speed", color = Color.White)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DisplayTweaks.ANIMATION_SCALES.forEach { (label, v) ->
            FilterChip(selected = anim == v, onClick = { anim = v; apply { DisplayTweaks.setAnimationScale(v) } }, label = { Text(label) })
        }
    }
    Text("For the whole phone. 0.5x makes going home from Recents quicker.", color = Color.Gray, fontSize = 11.sp)

    var resize by remember { mutableStateOf(prefs.mirrorResize) }
    var size by remember { mutableStateOf(prefs.mirrorSize) }
    var mirrorDpi by remember { mutableIntStateOf(prefs.mirrorDensity) }
    SwitchRow(
        "Resize the inner screen for mirroring",
        "Set before mirroring starts, put back when it ends ($size at density $mirrorDpi).",
        resize,
    ) { resize = it; prefs.mirrorResize = it }
    if (resize) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DisplayTweaks.MIRROR_SIZES.forEach { (label, s) ->
                FilterChip(selected = size == s, onClick = { size = s; prefs.mirrorSize = s }, label = { Text("$label  $s") })
            }
        }
        Stepper("Inner density", "$mirrorDpi",
            onMinus = { mirrorDpi = (mirrorDpi - 20).coerceAtLeast(DisplayTweaks.MIN_DENSITY); prefs.mirrorDensity = mirrorDpi },
            onPlus = { mirrorDpi = (mirrorDpi + 20).coerceAtMost(DisplayTweaks.MAX_DENSITY); prefs.mirrorDensity = mirrorDpi })
    }
}

/** A value with - and + to change it, and, when given, Apply and Reset. */
@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit, onApply: (() -> Unit)? = null, onReset: (() -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        TextButton(onClick = onMinus) { Text("−", fontSize = 20.sp) }
        Text(value, color = Color.White, fontSize = 16.sp)
        TextButton(onClick = onPlus) { Text("+", fontSize = 20.sp) }
    }
    if (onApply != null || onReset != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        onApply?.let { Button(onClick = it) { Text("Apply") } }
        onReset?.let { TextButton(onClick = it) { Text("Reset") } }
    }
}

/** The phone's default home (inner screen and mirror); recommends Lawnchair, which matches OpenCover's Home. */
@Composable
private fun InnerLauncherSettings() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val homes = remember(tick) { dev.opencover.phone.InnerLauncher.installed(ctx) }
    val current = remember(tick) { dev.opencover.phone.InnerLauncher.current(ctx) }
    val hasLawnchair = homes.any { it.component.packageName in dev.opencover.phone.InnerLauncher.LAWNCHAIR }
    Text("Inner screen launcher", color = Color.White)
    Text("The phone's home, shown on the inner screen and when mirroring. The cover keeps OpenCover.", color = Color.Gray, fontSize = 11.sp)
    if (!ShizukuShell.allowed()) { Text("Start Shizuku to change it.", color = Color.Gray, fontSize = 11.sp); return }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        homes.forEach { h ->
            FilterChip(selected = current == h.component.packageName, onClick = {
                scope.launch {
                    val ok = withContext(Dispatchers.IO) { dev.opencover.phone.InnerLauncher.choose(h.component) }
                    if (!ok) toast(ctx, "Couldn't change the launcher")
                    tick++
                }
            }, label = { Text(h.label) })
        }
    }
    if (!hasLawnchair) {
        Text("Lawnchair is recommended: with Lawnicons and its icon shapes it looks like OpenCover's Home.", color = Color.Gray, fontSize = 11.sp)
        TextButton(onClick = {
            val open = { url: String -> runCatching { dev.opencover.apps.AppLauncher.start(ctx, android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }.isSuccess }
            if (!open(dev.opencover.phone.InnerLauncher.LAWNCHAIR_PLAY)) open(dev.opencover.phone.InnerLauncher.LAWNCHAIR_GITHUB)
        }) { Text("Get Lawnchair") }
    }
}
