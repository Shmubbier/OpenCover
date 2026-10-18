package dev.opencover.call

import android.os.Bundle
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Green = Color(0xFF2EBD59)
private val Red = Color(0xFFE5484D)

/** The cover call screen: ringing (answer, decline and their muted variants) then in-call (timer, mute, hang up). */
class CallActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        setContent {
            val call = CallStore.call
            // Answered elsewhere, missed or ended; or answered here and handed over to the app's own call screen.
            val gone = call == null || CallStore.handedOff == call.pkg
            LaunchedEffect(gone) { if (gone) finish() }
            val ctx = LocalContext.current
            LaunchedEffect(Unit) { while (true) { delay(1000); CallStore.refresh(ctx) } }
            BackHandler {} // a call screen isn't backed out of by accident
            Box(Modifier.fillMaxSize().background(Color(0xFF101114))) {
                if (call != null) when (call.phase) {
                    Phase.RINGING -> Ringing(call)
                    Phase.ONGOING -> InCall(call)
                }
            }
        }
        window.insetsController?.apply {
            hide(WindowInsets.Type.navigationBars())
            systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}

@Composable
private fun Caller(call: Call, subtitle: String) {
    val ctx = LocalContext.current
    val app = remember(call.pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(call.pkg, 0)).toString() }.getOrDefault("")
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).clip(CircleShape).background(Color(0xFF2A2C33)), contentAlignment = Alignment.Center) {
            if (call.avatar != null) Image(call.avatar.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(Icons.Filled.Person, null, tint = Color.Gray, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(call.caller, color = Color.White, fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOf(app, subtitle).filter { it.isNotEmpty() }.joinToString(" · "), color = Color.Gray, fontSize = 14.sp)
    }
}

@Composable
private fun Round(icon: ImageVector, color: Color, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(64.dp).clip(CircleShape).background(color).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(30.dp))
        }
        Text(label, color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Small(label: String, onClick: () -> Unit) {
    Text(label, color = Color.White, fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(Color(0xFF2A2C33)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun Ringing(call: Call) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().padding(top = 16.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Caller(call, if (call.video) "Incoming video call" else "Incoming call")
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
                Round(Icons.Filled.CallEnd, Red, "Decline") { CallStore.decline(ctx, muteFor30 = false) }
                Round(if (call.video) Icons.Filled.Videocam else Icons.Filled.Call, Green, "Answer") { CallStore.answer(ctx, muted = false) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Small("Decline, mute 30 min") { CallStore.decline(ctx, muteFor30 = true) }
                Small("Answer muted") { CallStore.answer(ctx, muted = true) }
            }
        }
    }
}

@Composable
private fun InCall(call: Call) {
    val ctx = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var muted by remember { mutableStateOf(CallStore.micMuted(ctx)) }
    // Follows the real microphone state: "Answer muted" mutes it a moment after the call connects.
    LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis(); muted = CallStore.micMuted(ctx) } }
    val secs = ((now - call.since) / 1000).coerceAtLeast(0)
    Column(Modifier.fillMaxSize().padding(top = 16.dp, bottom = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween) {
        Caller(call, "%d:%02d".format(secs / 60, secs % 60))
        Row(horizontalArrangement = Arrangement.spacedBy(64.dp)) {
            Round(if (muted) Icons.Filled.MicOff else Icons.Filled.Mic, if (muted) Color(0xFF8AB4F8) else Color(0xFF2A2C33),
                if (muted) "Unmute" else "Mute") { muted = !muted; CallStore.setMicMuted(ctx, muted) }
            Round(Icons.Filled.CallEnd, Red, "Hang up") { CallStore.hangUp(ctx) }
        }
    }
}
