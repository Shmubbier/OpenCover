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

internal val styleNames = mapOf(
    ClockStyle.CURRENT to "Current", ClockStyle.BIG to "Big", ClockStyle.STACKED to "Stacked",
    ClockStyle.ANALOG to "Analog", ClockStyle.GLANCE to "At a Glance",
)
private val widgetNames = mapOf(HomeWidget.BATTERY to "Battery", HomeWidget.ALARM to "Next alarm", HomeWidget.WEATHER to "Weather")

/** Settings > Home screen: wallpaper, clock, widgets, icons and panels. */
@Composable
fun HomeSettings(onBack: () -> Unit, onWallpaper: () -> Unit) {
    val ctx = LocalContext.current
    var note by remember { mutableStateOf<String?>(null) }
    val askLocation = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { HomePrefs.setShown(ctx, HomeWidget.WEATHER, true); note = null }
        else note = "Weather needs location; it stays off."
    }
    SettingsPage("Home screen", onBack) {
        Group("Wallpaper") {
            NavRow(null, "Set wallpaper", "An image, GIF or WebP, for Home and the lock screen", onWallpaper)
            if (dev.opencover.home.Wallpaper.source(ctx, dev.opencover.home.Slot.HOME) != null)
                NavRow(null, "Adjust position", "Drag and pinch to choose the part the cover shows") {
                    CoverActivity.current?.adjustBackground(dev.opencover.home.Slot.HOME)
                }
            Text("Animated wallpaper", color = Color.White, fontSize = 15.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                dev.opencover.home.AnimPlay.entries.forEach { a ->
                    androidx.compose.material3.FilterChip(selected = HomePrefs.animPlay == a, onClick = { HomePrefs.setAnimPlay(ctx, a) },
                        label = { Text(a.label) })
                }
            }
            Note("Plays on Home and the lock screen. First 10 s or Still only save battery.")
        }
        Group("Clock") {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ClockStyle.entries.forEach { s ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        // A live preview, scaled down.
                        Box(
                            Modifier.size(150.dp, 100.dp).clipToBounds()
                                .border(2.dp, if (s == HomePrefs.clock) Accent else Color.DarkGray, RoundedCornerShape(10.dp))
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
        }
        Group("Widgets") {
            HomePrefs.order.forEach { w ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(widgetNames.getValue(w), color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
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
            Note(note ?: "Weather is the only feature that uses the internet: it sends rough coordinates to Open-Meteo.")
        }
        Group("Icons") { IconPackSettings() }
        Group("Panels") {
            Note("Notifications, apps, drawer and quick settings.")
            PanelStyleSettings()
        }
    }
}

/** The four panel styles, each with its own opacity and blur. */
@Composable
private fun PanelStyleSettings() {
    val ctx = LocalContext.current
    val style = dev.opencover.look.LookPrefs.style
    val st = dev.opencover.look.LookPrefs.current
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        dev.opencover.look.PanelStyle.entries.forEach { s ->
            androidx.compose.material3.FilterChip(selected = s == style, onClick = { dev.opencover.look.LookPrefs.setStyle(ctx, s) },
                label = { Text(s.label) })
        }
    }
    Text(if (style == dev.opencover.look.PanelStyle.CARDS) "Card opacity" else "Opacity", color = Color.White, fontSize = 13.sp)
    androidx.compose.material3.Slider(value = st.opacity, valueRange = 0f..1f,
        onValueChange = { dev.opencover.look.LookPrefs.setSettings(ctx, style, st.copy(opacity = it)) })
    Text("Blur", color = Color.White, fontSize = 13.sp)
    androidx.compose.material3.Slider(value = st.blur, valueRange = 0f..30f,
        onValueChange = { dev.opencover.look.LookPrefs.setSettings(ctx, style, st.copy(blur = it)) })
}

/** Icons: pack, shape and themed (one colour from the wallpaper), as in Lawnchair. */
@Composable
private fun IconPackSettings() {
    val ctx = LocalContext.current
    val packs = remember { dev.opencover.apps.IconPack.installed(ctx) }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        androidx.compose.material3.FilterChip(selected = HomePrefs.iconPack == null, onClick = { HomePrefs.setIconPack(ctx, null) },
            label = { Text("App icons") })
        packs.forEach { p ->
            androidx.compose.material3.FilterChip(selected = HomePrefs.iconPack == p.pkg, onClick = { HomePrefs.setIconPack(ctx, p.pkg) },
                label = { Text(p.label) })
        }
        if (packs.none { it.pkg == dev.opencover.apps.LAWNICONS }) androidx.compose.material3.TextButton(onClick = {
            runCatching {
                dev.opencover.apps.AppLauncher.start(ctx, android.content.Intent(android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse("market://details?id=${dev.opencover.apps.LAWNICONS}")))
            }
        }) { Text("Get Lawnicons") }
    }
    Text("Any pack made for Nova or ADW launchers works. Lawnicons is made by the Lawnchair team " +
        "(github.com/lawnchairlauncher/lawnicons, Apache 2.0).", color = Color.Gray, fontSize = 11.sp)
    SwitchRow(
        "Themed icons",
        "One colour, taken from the Home wallpaper. Uses Lawnicons or the app's own themed icon; other apps keep theirs.",
        HomePrefs.themedIcons,
    ) { HomePrefs.setThemedIcons(ctx, it) }
    Text("Icon shape", color = Color.White, fontSize = 15.sp)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        dev.opencover.apps.IconShape.entries.forEach { sh ->
            androidx.compose.material3.FilterChip(selected = HomePrefs.iconShape == sh, onClick = { HomePrefs.setIconShape(ctx, sh) },
                label = { Text(sh.label) })
        }
    }
}
