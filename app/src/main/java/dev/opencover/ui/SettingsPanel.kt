package dev.opencover.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.opencover.keyboard.KeyboardPrefs
import dev.opencover.media.MediaPrefs
import dev.opencover.nav.NavPrefs
import dev.opencover.nav.NavStyle
import dev.opencover.watcher.CoverAccessibility

/** The pages under Settings, as in Lawnchair: one list of categories, each opening its own page. */
private enum class Page { HOME, LOCK, NOTIFICATIONS, NAVIGATION, PHONE, EXPERIMENTAL }

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
    val ctx = LocalContext.current
    var page by rememberSaveable { mutableStateOf<Page?>(null) }
    val back = { page = null }
    when (page) {
        Page.HOME -> return HomeSettings(onBack = back, onWallpaper = onWallpaper)
        Page.LOCK -> return LockSettings(onBack = back)
        Page.NOTIFICATIONS -> return NotificationSettings(popups, onPopups, onBack = back)
        Page.NAVIGATION -> return NavigationSettings(onBack = back)
        Page.PHONE -> return PhoneSettings(keepAwake, onKeepAwake, onBack = back)
        Page.EXPERIMENTAL -> return ExperimentalPanel(topEdge, onTopEdge, onBack = back)
        null -> {}
    }
    SettingsPage("Settings", onBack = onClose) {
        Group {
            NavRow(Icons.Filled.Home, "Home screen", "Wallpaper, clock, widgets, icons, panels") { page = Page.HOME }
            NavRow(Icons.Filled.Lock, "Lock screen & AOD", "Always on display, lock screen, layout") { page = Page.LOCK }
            NavRow(Icons.Filled.Notifications, "Notifications", "Pop-ups and edge lighting") { page = Page.NOTIFICATIONS }
            NavRow(Icons.Filled.Navigation, "Navigation & media", "Navigation bar, gestures, media player") { page = Page.NAVIGATION }
            NavRow(Icons.Filled.PhoneAndroid, "Phone & display", "Keep awake, battery, Shizuku tweaks") { page = Page.PHONE }
        }
        Group("Setup") {
            NavRow(Icons.Filled.Checklist, "Setup & permissions", "What OpenCover needs, and what's missing", onSetup)
            NavRow(Icons.Filled.AccountBalance, "Banking apps", "Apps OpenCover steps aside for", onBanking)
            NavRow(Icons.Filled.Widgets, "Samsung widgets", "Open Samsung's own cover widgets", onSamsungWidgets)
        }
        Group("More") {
            NavRow(Icons.Filled.Science, "Experimental", "Keyboard, top edge, internet calls") { page = Page.EXPERIMENTAL }
            // The inner screen is broken: answer Android's debugging prompt, which only shows there.
            NavRow(Icons.Filled.BugReport, "Approve debugging prompt", "Allows the USB or wireless debugging prompt on the inner screen") {
                val r = CoverAccessibility.instance?.approveInnerPrompt() ?: "Turn on OpenCover's accessibility first"
                android.widget.Toast.makeText(ctx, r, android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }
}

/** Settings > Notifications: pop-ups, and edge lighting (its colours on their own page). */
@Composable
private fun NotificationSettings(popups: Boolean, onPopups: (Boolean) -> Unit, onBack: () -> Unit) {
    var edge by remember { mutableStateOf(false) }
    if (edge) return EdgeSettings(onBack = { edge = false })
    SettingsPage("Notifications", onBack) {
        Group {
            SwitchRow("Notification pop-ups", "New notifications pop up over any app on the cover.", popups, onPopups)
        }
        Group {
            NavRow(null, "Edge lighting", "The edges glow in the app's colour for new notifications") { edge = true }
        }
    }
}

/** Settings > Navigation & media: OpenCover's navigation bar and the media player's gestures. */
@Composable
private fun NavigationSettings(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var nav by remember { mutableStateOf(NavPrefs.enabled(ctx)) }
    var style by remember { mutableStateOf(NavPrefs.style(ctx)) }
    var classic by remember { mutableStateOf(MediaPrefs.classic(ctx)) }
    var graded by remember { mutableStateOf(MediaPrefs.graded(ctx)) }
    SettingsPage("Navigation & media", onBack) {
        Group("Navigation bar") {
            SwitchRow("OpenCover navigation bar", "Home goes straight to OpenCover instead of Samsung's cover home.", nav) {
                nav = it
                NavPrefs.setEnabled(ctx, it)
                CoverAccessibility.instance?.apply { refreshScope(); updateNavBar() }
            }
            if (nav) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Style", color = Color.White, modifier = androidx.compose.ui.Modifier.weight(1f))
                    listOf(NavStyle.BUTTONS to "Buttons", NavStyle.GESTURES to "Gestures").forEach { (s, label) ->
                        FilterChip(selected = style == s, onClick = {
                            style = s
                            NavPrefs.setStyle(ctx, s)
                            CoverAccessibility.instance?.updateNavBar()
                        }, label = { Text(label) })
                    }
                }
                if (style == NavStyle.GESTURES) Note("Swipe up: Home. Long-press the pill (or swipe up and hold, with Samsung's buttons): Recents. Swipe in from a side edge: Back.")
            }
        }
        Group("Media player") {
            SwitchRow("Classic layout", "Buttons and an app pill, shown on tap, instead of gestures.", classic) { classic = it; MediaPrefs.setClassic(ctx, it) }
            if (!classic) SwitchRow("Swipe up for shuffle and repeat",
                "Off: corner buttons. On: short swipe up switches app, further shuffles, all the way repeats.", graded) {
                graded = it; MediaPrefs.setGraded(ctx, it)
            }
        }
    }
}

/** Features that work but have known rough edges; all off by default. */
@Composable
private fun ExperimentalPanel(topEdge: Boolean, onTopEdge: (Boolean) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var typing by remember { mutableStateOf(KeyboardPrefs.enabled(ctx)) }
    var suggest by remember { mutableStateOf(KeyboardPrefs.suggestions(ctx)) }
    var voip by remember { mutableStateOf(dev.opencover.call.CallStore.voip(ctx)) }
    SettingsPage("Experimental", onBack) {
        Group {
            SwitchRow("OpenCover keyboard",
                "Replaces Samsung's keyboard on the cover. Known issues: chat boxes don't move up for it, and it can stay open after swiping back.",
                typing) {
                typing = it
                KeyboardPrefs.setEnabled(ctx, it)
                CoverAccessibility.instance?.refreshScope()
            }
            if (typing) SwitchRow("Show suggestions", "", suggest) { suggest = it; KeyboardPrefs.setSuggestions(ctx, it) }
        }
        Group {
            SwitchRow("Internet calls on the cover",
                "Messenger, Discord, Viber and other apps' calls get the cover call screen, like phone calls. Answering hands over to the app's own call screen.",
                voip) { voip = it; dev.opencover.call.CallStore.setVoip(ctx, it) }
        }
        var desk by remember { mutableStateOf(dev.opencover.desk.Desk.running) }
        Group {
            SwitchRow("Desktop on external monitor (test)",
                "Plug a USB-C monitor in and turn this on: Chrome opens on the monitor while the phone stays free. Needs Shizuku.",
                desk) { on ->
                val service = CoverAccessibility.instance
                val msg = if (!on) { dev.opencover.desk.Desk.stop(); "Desk stopped" }
                    else service?.let { dev.opencover.desk.Desk.start(it) } ?: "Turn on OpenCover's accessibility first"
                desk = dev.opencover.desk.Desk.running
                android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
            }
        }
        Group {
            SwitchRow("Top-edge priority",
                "Replaces Samsung's pull-down panel with ours. Its panel still flashes and blocks touches for ~2 s. Lets OpenCover read the window list.",
                topEdge, onTopEdge)
        }
    }
}
