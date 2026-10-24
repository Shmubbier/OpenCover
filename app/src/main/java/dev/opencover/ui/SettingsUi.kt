package dev.opencover.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Lawnchair's settings look: a page title with a back arrow, then rounded cards of related settings under small headings.
internal val Accent = Color(0xFFD0BCFF)
private val Card = Color(0xFF1E1E22)
private val PageBg = Color(0xFF101010)

/** A settings page: back arrow and title, then [content] scrolling below. Back closes it. */
@Composable
internal fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().background(PageBg).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
            Text(title, color = Color.White, fontSize = 22.sp)
        }
        content()
    }
}

/** A card of related settings, with an optional small heading above it. */
@Composable
internal fun Group(heading: String? = null, content: @Composable ColumnScope.() -> Unit) {
    if (heading != null) Text(heading, color = Accent, fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, top = 14.dp, bottom = 6.dp))
    else Box(Modifier.size(10.dp))
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Card).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

/** A row that opens a page: icon, title, one-line summary, chevron. */
@Composable
internal fun NavRow(icon: ImageVector?, title: String, summary: String?, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) Box(Modifier.size(36.dp).background(Accent.copy(alpha = 0.18f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = Accent, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(start = if (icon != null) 14.dp else 0.dp)) {
            Text(title, color = Color.White, fontSize = 16.sp)
            if (summary != null) Text(summary, color = Color.Gray, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Color.Gray)
    }
}

/** A setting with an on/off switch; tapping the text flips it too. */
@Composable
internal fun SwitchRow(title: String, note: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title, color = Color.White, fontSize = 15.sp)
            if (note.isNotEmpty()) Text(note, color = Color.Gray, fontSize = 12.sp)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Small grey text under a setting. */
@Composable
internal fun Note(text: String) = Text(text, color = Color.Gray, fontSize = 12.sp)
