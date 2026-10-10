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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsPanel(
    keepAwake: Boolean,
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
            Column(Modifier.weight(1f)) {
                Text("Top-edge priority (experimental)", color = Color.White)
                Text("Replaces Samsung's pull-down panel with ours. Its panel still flashes and blocks touches for ~2 s. Lets OpenCover read the window list.",
                    color = Color.Gray, fontSize = 11.sp)
            }
            Switch(checked = topEdge, onCheckedChange = onTopEdge)
        }
        Button(onClick = onSamsungWidgets) { Text("Open Samsung widgets") }
        Button(onClick = onSetup) { Text("Setup & permissions") }
        Button(onClick = onWallpaper) { Text("Set wallpaper") }
        Button(onClick = onBanking) { Text("Banking apps") }
        TextButton(onClick = onClose) { Text("Close") }
    }
}
