package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import dev.opencover.phone.Fold
import dev.opencover.phone.HeatGuard
import dev.opencover.phone.PhonePrefs
import dev.opencover.quick.ShizukuShell

/** Settings > Phone: keep the cover on when opened, heat guard. */
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
