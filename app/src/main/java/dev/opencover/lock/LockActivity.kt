package dev.opencover.lock

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppLauncher
import dev.opencover.notify.NotificationStore
import dev.opencover.notify.shouldShow
import dev.opencover.ui.LockFace
import dev.opencover.ui.lockPrivacy
import dev.opencover.watcher.CoverAccessibility

/** Show OpenCover's lock screen only when enabled, locked, the cover is on, and nothing but the keyguard is on top. */
fun shouldShowLock(enabled: Boolean, locked: Boolean, coverOn: Boolean, topPkg: String?, inCall: Boolean = false): Boolean =
    enabled && locked && coverOn && !inCall && topPkg == "com.android.systemui" // unknown top: keep Samsung's

/** The top cover window's package: from its content, or, when that can't be read yet, System UI known by its title. */
fun topPackage(rootPkg: String?, title: String?, systemUiLabel: String): String? =
    rootPkg ?: "com.android.systemui".takeIf { title == systemUiLabel }

/** The notification tapped in peek to open after unlock: only when unlocking on the cover. */
fun openOnUnlock(pending: String?, coverActive: Boolean): String? = pending?.takeIf { coverActive }

/** OpenCover's lock screen on the cover: shown over Samsung's keyguard; swipe up hands off to Samsung's PIN. */
class LockActivity : ComponentActivity() {
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), RECEIVER_NOT_EXPORTED)
        setContent {
            var peek by remember { mutableStateOf(false) }
            var dragged = 0f
            BackHandler { peek = false }
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragged = 0f },
                        onDragEnd = { if (dragged < -150f) unlock() },
                    ) { _, dy -> dragged += dy }
                },
            ) {
                LockFace(LockPrefs.layout, dim = false, locked = true, onPeek = { peek = true })
                if (peek) Peek(onOpen = { key -> pendingOpen = key; unlock() }, onClose = { peek = false })
            }
        }
        // No navigation bar on the lock screen; a swipe from the bottom edge still shows it for a moment.
        window.insetsController?.apply {
            hide(WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // Unlocked meanwhile (fingerprint during the wake): nothing to cover.
    override fun onResume() {
        super.onResume()
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) finish()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(unlocked) }
        super.onDestroy()
    }

    private fun unlock() {
        getSystemService(KeyguardManager::class.java).requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
            override fun onDismissCancelled() = gaveUp()
            override fun onDismissError() = gaveUp()
        })
    }

    /** PIN cancelled or no keyguard left: forget the peek tap; leave if already unlocked. */
    private fun gaveUp() {
        pendingOpen = null
        if (!getSystemService(KeyguardManager::class.java).isKeyguardLocked) finish()
    }

    companion object {
        /** A notification tapped in peek, opened on the cover once the user has unlocked. */
        @Volatile var pendingOpen: String? = null

        /** Called on wake: replaces Samsung's lock screen with ours if [shouldShowLock] says so. */
        fun maybeShow(service: CoverAccessibility, tries: Int = 3) {
            val ctx: Context = service
            val cover = ctx.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY)
            val systemUi = runCatching {
                ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo("com.android.systemui", 0)).toString()
            }.getOrDefault("System UI")
            val top = runCatching {
                service.windowsOnAllDisplays[COVER_DISPLAY]?.firstOrNull {
                    it.type == AccessibilityWindowInfo.TYPE_APPLICATION || it.type == AccessibilityWindowInfo.TYPE_SYSTEM
                }?.let { topPackage(it.root?.packageName?.toString(), it.title?.toString(), systemUi) }
            }.getOrNull()
            if (top == ctx.packageName) return // already showing
            // Still unknown just after wake: look again shortly rather than guess.
            if (top == null && tries > 0) { Handler(Looper.getMainLooper()).postDelayed({ maybeShow(service, tries - 1) }, 250); return }
            val show = shouldShowLock(
                LockPrefs.lockScreen,
                ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
                cover?.state == Display.STATE_ON,
                top,
                // Ringing or in a call (any app): the call screen must stay on the cover.
                ctx.getSystemService(AudioManager::class.java).mode.let {
                    it == AudioManager.MODE_RINGTONE || it == AudioManager.MODE_IN_CALL || it == AudioManager.MODE_IN_COMMUNICATION
                },
            )
            if (show) AppLauncher.start(ctx, Intent(ctx, LockActivity::class.java), animate = false) // replaces Samsung's at once
        }
    }
}

@androidx.compose.runtime.Composable
private fun Peek(onOpen: (String) -> Unit, onClose: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val privacy = lockPrivacy(ctx)
    val notes = NotificationStore.notes.filter { shouldShow(it, ctx.packageName) && notePrivacy(privacy, it.lockVisibility) != Privacy.NONE }
        .sortedByDescending { it.time }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
        LazyColumn(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 40.dp).clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF202124)),
        ) {
            if (privacy == Privacy.NONE || notes.isEmpty()) item { Text("No notifications", color = Color.Gray, modifier = Modifier.padding(16.dp)) }
            else items(notes, key = { it.key }) { n ->
                val app = remember(n.pkg) {
                    runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(n.pkg, 0)).toString() }.getOrDefault(n.pkg)
                }
                Column(Modifier.fillMaxWidth().clickable { onOpen(n.key) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Text(app, color = Color.Gray, fontSize = 12.sp)
                    if (notePrivacy(privacy, n.lockVisibility) == Privacy.ALL) {
                        Text(n.chatTitle ?: n.title, color = Color.White, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(n.text, color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
