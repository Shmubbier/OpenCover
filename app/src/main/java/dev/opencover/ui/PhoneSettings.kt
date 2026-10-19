package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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

/** Settings > Phone: keep the cover on when opened, heat guard, rotation per app. */
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
