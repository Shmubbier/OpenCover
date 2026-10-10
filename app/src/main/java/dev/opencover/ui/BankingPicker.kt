package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.apps.AppEntry

/** Pick apps that refuse to run while OpenCover's accessibility service is on. */
@Composable
fun BankingPicker(apps: List<AppEntry>, selected: Set<String>, onChange: (Set<String>) -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    val byPackage = remember(apps) { apps.distinctBy { it.pkg }.sortedBy { it.label.lowercase() } }
    Column(Modifier.fillMaxSize().background(Color(0xF0101010))) {
        Text(
            "Banking apps: OpenCover steps aside while these are open.",
            color = Color.Gray, fontSize = 12.sp, modifier = Modifier.padding(12.dp),
        )
        LazyColumn {
            items(byPackage, key = { it.pkg }) { app ->
                val on = app.pkg in selected
                Row(
                    Modifier.fillMaxWidth().clickable { onChange(if (on) selected - app.pkg else selected + app.pkg) }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = on, onCheckedChange = null)
                    Text(app.label, color = Color.White)
                }
            }
        }
    }
}
