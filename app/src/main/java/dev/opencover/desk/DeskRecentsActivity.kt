package dev.opencover.desk

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import dev.opencover.ui.AppIcon

/**
 * Recents on the desk: every app window there, minimized ones too (DeX's own Recents needs the phone open, and its
 * taskbar lists no running apps, so a minimized window would otherwise be lost). Click one to bring it back, x to
 * close it, anywhere else to leave.
 */
class DeskRecentsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val windows = mutableStateOf(Desk.windows().filter { it.pkg != packageName })
        setContent {
            val none = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().background(Color(0xCC000000)).clickable(none, null) { finish() }, contentAlignment = Alignment.Center) {
                if (windows.value.isEmpty()) Text("No open windows", color = Color.White, fontSize = 18.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(32.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    windows.value.forEach { w ->
                        val label = remember(w.pkg) { runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(w.pkg, 0)).toString() }.getOrDefault(w.pkg) }
                        val icon = remember(w.pkg) { runCatching { packageManager.getApplicationIcon(w.pkg).toBitmap().asImageBitmap() }.getOrNull() }
                        Box(Modifier.size(160.dp).background(Color(0xFF2A2A2A), RoundedCornerShape(16.dp))
                            .clickable { Desk.show(w.task); finish() }) {
                            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                AppIcon(label, icon)
                                if (!w.visible) Text("Minimized", color = Color.Gray, fontSize = 11.sp)
                            }
                            Text("×", color = Color.White, fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                                .background(Color(0x33FFFFFF), CircleShape).clickable {
                                    Desk.close(w.task)
                                    windows.value = windows.value - w
                                }.padding(horizontal = 8.dp))
                        }
                    }
                }
            }
        }
    }

    // Leaving it in any way (an app opened over it, Home) closes it, as Recents does.
    override fun onStop() {
        super.onStop()
        finish()
    }
}
