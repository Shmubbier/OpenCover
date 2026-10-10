package dev.opencover.ui

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.watcher.CoverAccessibility
import dev.opencover.watcher.NotificationAccess

object Permissions {
    fun accessibility(ctx: Context): Boolean {
        val me = ComponentName(ctx, CoverAccessibility::class.java)
        val enabled = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun overlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun battery(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun usage(ctx: Context) = ctx.getSystemService(AppOpsManager::class.java)
        .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) ==
        AppOpsManager.MODE_ALLOWED

    fun notificationAccess(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)
        .isNotificationListenerAccessGranted(ComponentName(ctx, NotificationAccess::class.java))

    fun notifications(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun required(ctx: Context) = accessibility(ctx) && overlay(ctx)
}

class SetupStep(val title: String, val why: String, val done: Boolean, val open: () -> Unit)

/** [onDone] is null until the required steps are granted. */
@Composable
fun Checklist(steps: List<SetupStep>, onAppInfo: () -> Unit, onDone: (() -> Unit)?) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("OpenCover setup", color = Color.White, fontSize = 20.sp)
        steps.forEach { Step(it) }
        Text(
            "Accessibility or notification access greyed out? Open App info, tap ⋮ and choose " +
                "\"Allow restricted settings\" first.",
            color = Color.Gray, fontSize = 12.sp,
        )
        Row {
            TextButton(onClick = onAppInfo) { Text("App info") }
            if (onDone != null) TextButton(onClick = onDone) { Text("Done") }
        }
    }
}

@Composable
private fun Step(s: SetupStep) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !s.done, onClick = s.open).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (s.done) "✓" else "○", color = if (s.done) Color.Green else Color.White, fontSize = 20.sp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(s.title, color = Color.White)
            Text(s.why, color = Color.Gray, fontSize = 12.sp)
        }
    }
}
