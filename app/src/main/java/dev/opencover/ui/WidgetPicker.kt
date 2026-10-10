package dev.opencover.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WidgetPicker(
    providers: List<AppWidgetProviderInfo>,
    label: (AppWidgetProviderInfo) -> String,
    onPick: (AppWidgetProviderInfo) -> Unit,
    onClose: () -> Unit,
) {
    BackHandler(onBack = onClose)
    val sorted = remember(providers) { providers.map { it to label(it) }.sortedBy { it.second.lowercase() } }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xF0101010))) {
        items(sorted) { (p, name) ->
            Text(name, color = Color.White,
                modifier = Modifier.fillMaxWidth().clickable { onPick(p) }.padding(12.dp))
        }
    }
}
