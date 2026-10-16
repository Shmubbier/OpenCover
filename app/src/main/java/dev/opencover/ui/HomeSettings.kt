package dev.opencover.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.home.ClockStyle
import dev.opencover.home.HomePrefs
import dev.opencover.home.HomeWidget
import dev.opencover.home.movedBy

private val styleNames = mapOf(
    ClockStyle.CURRENT to "Current", ClockStyle.BIG to "Big", ClockStyle.STACKED to "Stacked",
    ClockStyle.ANALOG to "Analog", ClockStyle.GLANCE to "At a Glance",
)
private val widgetNames = mapOf(HomeWidget.BATTERY to "Battery", HomeWidget.ALARM to "Next alarm", HomeWidget.WEATHER to "Weather")

/** Settings > Home: clock style and widgets. */
@Composable
fun HomeSettings(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { HomePrefs.setShown(ctx, HomeWidget.WEATHER, true); note = null }
        else note = "Weather needs location; it stays off."
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Home", color = Color.White, fontSize = 20.sp)
        Text("Clock style", color = Color.Gray, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockStyle.entries.forEach { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // A live preview, scaled down.
                    Box(
                        Modifier.size(150.dp, 100.dp).clipToBounds()
                            .border(2.dp, if (s == HomePrefs.clock) Color(0xFF8AB4F8) else Color.DarkGray, RoundedCornerShape(10.dp))
                            .clickable { HomePrefs.setClock(ctx, s) },
                    ) {
                        // Full Home size, drawn at a third: requiredSize ignores the 150x100 box it sits in.
                        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(450.dp, 300.dp).graphicsLayer {
                            scaleX = 1 / 3f; scaleY = 1 / 3f
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        }) { ClockFace(s, Modifier.padding(16.dp)) }
                    }
                    Text(styleNames.getValue(s), color = Color.White, fontSize = 12.sp)
                }
            }
        }
        Text("Widgets", color = Color.Gray, fontSize = 13.sp)
        HomePrefs.order.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(widgetNames.getValue(w), color = Color.White, modifier = Modifier.weight(1f))
                TextButton(onClick = { HomePrefs.setOrder(ctx, HomePrefs.order.movedBy(w, -1)) }) { Text("▲") }
                TextButton(onClick = { HomePrefs.setOrder(ctx, HomePrefs.order.movedBy(w, 1)) }) { Text("▼") }
                Switch(checked = w !in HomePrefs.off, onCheckedChange = { on ->
                    val needsLocation = w == HomeWidget.WEATHER && on &&
                        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
                    if (needsLocation) askLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                    else HomePrefs.setShown(ctx, w, on)
                })
            }
            if (w == HomeWidget.WEATHER && w !in HomePrefs.off) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Fahrenheit", color = Color.White, modifier = Modifier.weight(1f).padding(start = 16.dp))
                Switch(checked = HomePrefs.fahrenheit, onCheckedChange = { HomePrefs.setFahrenheit(ctx, it) })
            }
        }
        Text(note ?: "Weather is the only feature that uses the internet: it sends rough coordinates to Open-Meteo.",
            color = Color.Gray, fontSize = 11.sp)
        TextButton(onClick = onBack) { Text("Back") }
    }
}
