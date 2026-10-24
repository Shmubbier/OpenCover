package dev.opencover.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
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
                Modifier.fillMaxWidth().swipeRightToDismiss { c.notes.forEach { NotificationStore.dismiss(it.key) } }
                    .background(Card, RoundedCornerShape(16.dp))
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
    Column(
        Modifier.fillMaxWidth().swipeRightToDismiss { NotificationStore.dismiss(n.key) }.background(Card)
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

/**
 * Swipe right to dismiss. This is the leftmost page, so a swipe right has no page to go to; a swipe left is left alone
 * for the pager, which goes to Home. Ongoing notifications can't be cleared: the card comes back.
 */
private fun Modifier.swipeRightToDismiss(onDismiss: () -> Unit): Modifier = composed {
    val x = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var width by remember { mutableStateOf(1) }
    onSizeChanged { width = it.width }
        .graphicsLayer { translationX = x.value; alpha = 1f - (x.value / width).coerceIn(0f, 1f) * 0.7f }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var right = false
                val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
                    if (over > 0) { right = true; change.consume() }
                } ?: return@awaitEachGesture
                if (!right) return@awaitEachGesture
                val velocity = VelocityTracker().apply { addPosition(drag.uptimeMillis, drag.position) }
                horizontalDrag(drag.id) { change ->
                    velocity.addPosition(change.uptimeMillis, change.position)
                    scope.launch { x.snapTo((x.value + change.positionChange().x).coerceAtLeast(0f)) }
                    change.consume()
                }
                val gone = x.value > width * 0.35f || velocity.calculateVelocity().x > 1500f
                scope.launch {
                    if (!gone) { x.animateTo(0f); return@launch }
                    x.animateTo(width.toFloat())
                    onDismiss()
                    delay(700) // still here (not clearable): bring it back
                    x.animateTo(0f)
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
