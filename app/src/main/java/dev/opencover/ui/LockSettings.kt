package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.home.ClockStyle
import dev.opencover.lock.LockPrefs
import dev.opencover.lock.Module
import dev.opencover.lock.aodReady
import dev.opencover.watcher.CoverAccessibility
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import dev.opencover.quick.ShizukuShell
import androidx.compose.runtime.rememberCoroutineScope
import android.provider.Settings

private val moduleNames = mapOf(
    Module.CLOCK to "Clock", Module.DATE to "Date", Module.BATTERY to "Battery",
    Module.ICONS to "Notification icons", Module.NOW_PLAYING to "Now playing",
)

/** Settings > Lock screen: OpenCover AOD and lock screen switches, clock style, layout editor. */
@Composable
fun LockSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var editing by remember { mutableStateOf(false) }
    if (editing) { LayoutEditor(onDone = { editing = false }); return }
    BackHandler(onBack = onBack)
    Column(
        Modifier.fillMaxSize().background(Color(0xF0101010)).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Lock screen", color = Color.White, fontSize = 20.sp)
        SwitchRow("OpenCover AOD",
            if (aodReady(ctx)) "Shown while the phone sleeps." else "Needs Samsung's Always On Display set to Always, tap to show off. See Setup.",
            LockPrefs.aod) { LockPrefs.setAod(ctx, it) }
        SwitchRow("OpenCover lock screen", "Swipe up for Samsung's PIN. Samsung's quick panel still pulls down.",
            LockPrefs.lockScreen) { LockPrefs.setLockScreen(ctx, it); CoverAccessibility.instance?.refreshScope(); CoverActivity.current?.applyLockScreen() }
        // Samsung's "Stay on Lock screen" for fingerprints, inverted: off means a fingerprint on the power key keeps you on
        // the lock screen (swipe up then opens without the PIN). A secure setting, so it goes through Shizuku.
        var straightHome by remember {
            mutableStateOf(Settings.Secure.getInt(ctx.contentResolver, "fingerprint_stay_on_lock_screen", 0) == 0)
        }
        val scope = rememberCoroutineScope()
        SwitchRow("Fingerprint opens Home",
            if (!ShizukuShell.allowed()) "Needs Shizuku." else if (straightHome) "Power key with your fingerprint goes straight to Home."
            else "Power key with your fingerprint stays on the lock screen; swipe up to open, no PIN.",
            straightHome) { on ->
            scope.launch(Dispatchers.IO) {
                if (ShizukuShell.run("settings", "put", "secure", "fingerprint_stay_on_lock_screen", if (on) "0" else "1")) straightHome = on
            }
        }
        Text("Clock style", color = Color.Gray, fontSize = 13.sp)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClockStyle.entries.forEach { s ->
                FilterChip(selected = s == LockPrefs.clock, onClick = { LockPrefs.setClock(ctx, s) }, label = { Text(styleNames.getValue(s)) })
            }
        }
        Button(onClick = { editing = true }) { Text("Edit layout") }
        Text("Background (an image, or an animated GIF or WebP; the AOD shows the frame the lock screen stopped on)", color = Color.Gray, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { CoverActivity.current?.pickBackground(dev.opencover.home.Slot.LOCK) }) { Text("Pick image, GIF or WebP") }
            if (dev.opencover.home.Wallpaper.lockStill != null) TextButton(onClick = { dev.opencover.home.Wallpaper.clearLock(ctx) }) { Text("Use Home's") }
        }
        TextButton(onClick = onBack) { Text("Back") }
    }
}

/** The cover-sized face: drag modules, switch them on or off, then Lock to save. */
@Composable
private fun LayoutEditor(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(LockPrefs.layout) }
    var selected by remember { mutableStateOf<Module?>(null) }
    BackHandler(onBack = onDone) // leaving without Lock drops the changes
    Box(Modifier.fillMaxSize()) {
        LockFace(draft, dim = false, locked = true, onMove = { m, s -> draft = draft + (m to s) }, selected = selected, onSelect = { selected = it })
        Row(
            Modifier.fillMaxWidth().background(Color(0xCC000000)).horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Module.entries.forEach { m ->
                val s = draft.getValue(m)
                FilterChip(selected = s.on, onClick = {
                    draft = draft + (m to s.copy(on = !s.on))
                    if (!s.on) selected = m // just switched on: select it
                }, label = { Text(moduleNames.getValue(m)) })
            }
            Button(onClick = { LockPrefs.setLayout(ctx, draft); onDone() }) { Text("Lock") }
        }
    }
}
