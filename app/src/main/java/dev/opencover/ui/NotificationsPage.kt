package dev.opencover.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.notify.Note
import dev.opencover.notify.NotificationStore
import dev.opencover.notify.ReplyActivity
import dev.opencover.notify.groupConversations

private val Card = Color(0xFF1E1E1E)
private val PULL_TO_CLEAR = 72.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsPage(notes: List<Note>) {
    val ctx = LocalContext.current
    val groups = remember(notes) { groupConversations(notes) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No notifications", color = Color.Gray) }
        return
    }
    // Pull down past the threshold and release to clear all, like refreshing a browser page.
    val pull = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = false,
        onRefresh = { NotificationStore.clearAll() },
        state = pull,
        indicator = {
            // A clear-all badge in the gap the list leaves: grows with the pull, turns red when release will clear.
            val f = pull.distanceFraction
            if (f > 0f) Column(
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp).graphicsLayer { alpha = f.coerceAtMost(1f) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(36.dp).graphicsLayer { val s = 0.6f + 0.4f * f.coerceAtMost(1f); scaleX = s; scaleY = s }
                        .background(if (f >= 1f) Color(0xFFB00020) else Color(0xFF444444), CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text("✕", color = Color.White, fontSize = 18.sp) }
                Text(if (f >= 1f) "Release to clear all" else "Pull to clear all", color = Color.White, fontSize = 11.sp)
            }
        },
    ) {
    LazyColumn(
        // The list slides down with the finger, up to the threshold distance and a bit beyond.
        Modifier.fillMaxSize().graphicsLayer { translationY = pull.distanceFraction * PULL_TO_CLEAR.toPx() }.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(groups, key = { it.key }) { c ->
            val open = c.key in expanded
            Column(
                Modifier.fillMaxWidth().background(Card, RoundedCornerShape(16.dp))
                    .clickable { expanded = if (open) expanded - c.key else expanded + c.key }
                    .padding(12.dp),
            ) {
                Text(c.title ?: appLabel(ctx, c.pkg), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (!open) {
                    Text(c.latest.text, color = Color.LightGray, fontSize = 13.sp, maxLines = 2)
                    if (c.notes.size > 1) Text("${c.notes.size} notifications", color = Color.Gray, fontSize = 11.sp)
                } else c.notes.forEach { key(it.key) { NoteRow(it) } } // swipe state follows the notification
            }
        }
    }
}
}

@Composable
private fun NoteRow(n: Note) {
    val ctx = LocalContext.current
    // Only expanded rows swipe; swiping elsewhere on this page still changes pages.
    val state = rememberSwipeToDismissBoxState(confirmValueChange = {
        if (it != SwipeToDismissBoxValue.Settled) NotificationStore.dismiss(n.key)
        true
    })
    SwipeToDismissBox(state, backgroundContent = {}) {
        Column(
            Modifier.fillMaxWidth().background(Card)
                .clickable { if (!NotificationStore.open(ctx, n.key)) toast(ctx, "Couldn't open on the cover screen") }
                .padding(vertical = 6.dp),
        ) {
            Text(n.title, color = Color.White, fontSize = 13.sp, maxLines = 1)
            Text(n.text, color = Color.LightGray, fontSize = 13.sp)
            if (n.actions.isNotEmpty()) Row {
                n.actions.forEachIndexed { i, act ->
                    TextButton(onClick = { runAction(ctx, n, i) }) { Text(act.title, maxLines = 1) }
                }
            }
        }
    }
}

/** Reply actions open the reply sheet; others fire directly. */
fun runAction(ctx: Context, n: Note, index: Int) {
    if (n.actions.getOrNull(index)?.acceptsReply == true) ReplyActivity.open(ctx, n.key, index)
    else if (!NotificationStore.act(ctx, n.key, index)) toast(ctx, "Couldn't do that")
}

fun appLabel(ctx: Context, pkg: String): String = try {
    ctx.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
} catch (e: Exception) {
    pkg
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
