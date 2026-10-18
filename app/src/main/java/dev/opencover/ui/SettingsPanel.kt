package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import dev.opencover.keyboard.KeyboardPrefs
import dev.opencover.media.MediaPrefs
import dev.opencover.nav.NavPrefs
import dev.opencover.nav.NavStyle
import androidx.compose.material3.FilterChip
import dev.opencover.watcher.CoverAccessibility

@Composable
fun SettingsPanel(
    keepAwake: Boolean,
    popups: Boolean,
    onPopups: (Boolean) -> Unit,
    onKeepAwake: (Boolean) -> Unit,
    onSamsungWidgets: () -> Unit,
    onSetup: () -> Unit,
    onWallpaper: () -> Unit,
    onBanking: () -> Unit,
    topEdge: Boolean,
    onTopEdge: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val ctx = LocalContext.current
    var experimental by remember { mutableStateOf(false) }
    var homeSettings by remember { mutableStateOf(false) }
    var phoneSettings by remember { mutableStateOf(false) }
    var lockSettings by remember { mutableStateOf(false) }
    var edgeSettings by remember { mutableStateOf(false) }
    if (edgeSettings) {
        EdgeSettings(onBack = { edgeSettings = false })
        return
    }
    if (lockSettings) {
        LockSettings(onBack = { lockSettings = false })
        return
    }
    if (phoneSettings) {
        PhoneSettings(onBack = { phoneSettings = false })
        return
    }
    if (homeSettings) {
        HomeSettings(onBack = { homeSettings = false })
        return
    }
    if (experimental) {
        ExperimentalPanel(topEdge, onTopEdge, onBack = { experimental = false })
        return
    }
    var classic by remember { mutableStateOf(MediaPrefs.classic(ctx)) }
    var graded by remember { mutableStateOf(MediaPrefs.graded(ctx)) }
    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", color = Color.White, fontSize = 20.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Keep cover screen awake", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = keepAwake, onCheckedChange = onKeepAwake)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notification pop-ups", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = popups, onCheckedChange = onPopups)
        }
        var nav by remember { mutableStateOf(NavPrefs.enabled(ctx)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OpenCover navigation bar", color = Color.White)
                Text("Home goes straight to OpenCover instead of Samsung's cover home.", color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = nav, onCheckedChange = {
                nav = it
                NavPrefs.setEnabled(ctx, it)
                CoverAccessibility.instance?.apply { refreshScope(); updateNavBar() }
            })
        }
        if (nav) {
            var style by remember { mutableStateOf(NavPrefs.style(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Style", color = Color.White, modifier = Modifier.weight(1f))
                listOf(NavStyle.BUTTONS to "Buttons", NavStyle.GESTURES to "Gestures").forEach { (s, label) ->
                    FilterChip(selected = style == s, onClick = {
                        style = s
                        NavPrefs.setStyle(ctx, s)
                        CoverAccessibility.instance?.updateNavBar()
                    }, label = { Text(label) })
                }
            }
            if (style == NavStyle.GESTURES) Text("Swipe up: Home. Swipe up and hold: Recents. Swipe in from a side edge: Back.",
                color = Color.Gray, fontSize = 11.sp)
        }
        Text("Media player", color = Color.Gray, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Classic layout", color = Color.White)
                Text("Buttons and an app pill, shown on tap, instead of gestures.", color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = classic, onCheckedChange = { classic = it; MediaPrefs.setClassic(ctx, it) })
        }
        if (!classic) Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Swipe up for shuffle and repeat", color = Color.White)
                Text("Off: corner buttons. On: short swipe up switches app, further shuffles, all the way repeats.",
                    color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = graded, onCheckedChange = { graded = it; MediaPrefs.setGraded(ctx, it) })
        }
        Button(onClick = { homeSettings = true }) { Text("Home") }
        Button(onClick = { phoneSettings = true }) { Text("Phone") }
        Button(onClick = { lockSettings = true }) { Text("Lock screen") }
        Button(onClick = { edgeSettings = true }) { Text("Edge lighting") }
        Button(onClick = onSamsungWidgets) { Text("Open Samsung widgets") }
        Button(onClick = onSetup) { Text("Setup & permissions") }
        Button(onClick = onWallpaper) { Text("Set wallpaper (image or GIF)") }
        Button(onClick = onBanking) { Text("Banking apps") }
        Button(onClick = { experimental = true }) { Text("Experimental") }
        TextButton(onClick = onClose) { Text("Close") }
    }
}

/** Features that work but have known rough edges; all off by default. */
@Composable
private fun ExperimentalPanel(topEdge: Boolean, onTopEdge: (Boolean) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var typing by remember { mutableStateOf(KeyboardPrefs.enabled(ctx)) }
    var suggest by remember { mutableStateOf(KeyboardPrefs.suggestions(ctx)) }
    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Experimental", color = Color.White, fontSize = 20.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("OpenCover keyboard", color = Color.White)
                Text("Replaces Samsung's keyboard on the cover. Known issues: chat boxes don't move up for it, and it can stay open after swiping back.",
                    color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = typing, onCheckedChange = {
                typing = it
                KeyboardPrefs.setEnabled(ctx, it)
                CoverAccessibility.instance?.refreshScope()
            })
        }
        if (typing) Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show suggestions", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = suggest, onCheckedChange = { suggest = it; KeyboardPrefs.setSuggestions(ctx, it) })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Top-edge priority", color = Color.White)
                Text("Replaces Samsung's pull-down panel with ours. Its panel still flashes and blocks touches for ~2 s. Lets OpenCover read the window list.",
                    color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = topEdge, onCheckedChange = onTopEdge)
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}
