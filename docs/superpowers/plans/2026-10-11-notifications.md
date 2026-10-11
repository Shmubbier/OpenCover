# Notifications Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Notifications page, pop-up banner and reply sheet on the cover screen.

**Architecture:** The existing `NotificationAccess` listener turns each notification into a plain `Note` and feeds `NotificationStore` (Compose state). Pure functions decide grouping, what to show and when to pop up. The banner is a plain-View overlay on display 1; replies run in `ReplyActivity` on display 1.

**Tech Stack:** Kotlin, Compose (material3 SwipeToDismissBox), NotificationListenerService, RemoteInput, SpeechRecognizer, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-11-notifications-design.md`

## Global Constraints

- Commits as Shmubbier, no Claude attribution, no em dashes or emojis. Push with `git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push`.
- No new dependencies. No internet permission.
- Everything opens on display 1 (`COVER_DISPLAY`), never the main screen.
- adb: `ANDROID_SERIAL=R5CW71QQAGW`, `MSYS_NO_PATHCONV=1` in Git Bash. Build JDK: Android Studio `jbr`.

## Review Focus

1. Listener reconnect rebuilds the list without popping banners. `onListenerConnected` adds with `banner = false` (Task 2).
2. An updated notification replaces in place without a new banner. Test `updateReplacesWithoutBanner` (Task 1).
3. Notification removed while its banner shows: banner hides. Test `removingCurrentClearsBanner` (Task 1).
4. Silent (low importance) notifications are listed but never pop up. Test `silentIsListedWithoutBanner` (Task 1).
5. Reply action cancelled by the app: typed text kept, "Couldn't send" shown. Device check (Task 4).

---

### Task 1: Note model and rules

**Files:**
- Create: `app/src/main/java/dev/opencover/notify/Note.kt`
- Test: `app/src/test/java/dev/opencover/notify/NoteTest.kt`

**Interfaces:**
- Produces: `NoteAction(title, acceptsReply)`, `Note(key, pkg, user, title, text, time, conversation, actions, ongoing, media, summary, alerting)` with `groupKey`; `Conversation(key, pkg, title: String?, notes)` with `latest`; `shouldShow(note, ownPkg)`; `post(notes, note, ownPkg): Pair<List<Note>, Boolean>`; `remove(notes, key)`; `groupConversations(notes)`; `BannerQueue { current, more, show(note), removed(key): Boolean, hide() }`.

- [ ] **Step 1: Failing tests**

```kotlin
package dev.opencover.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoteTest {
    private fun n(key: String, pkg: String = "chat", conv: String? = null, time: Long = 1, alerting: Boolean = true) =
        Note(key, pkg, 0, "T$key", "text $key", time, conv, emptyList(), alerting = alerting)

    @Test fun groupsByConversationThenApp() {
        val g = groupConversations(listOf(n("1", conv = "Ana", time = 1), n("2", conv = "Ben", time = 2), n("3", conv = "Ana", time = 3), n("4", pkg = "mail", time = 4)))
        assertEquals(listOf("mail", "chat", "chat"), g.map { it.pkg })
        assertEquals(listOf("Ana", "Ben"), g.drop(1).map { it.title })
        assertEquals("3", g[1].latest.key)
        assertEquals(2, g[1].notes.size)
    }

    @Test fun skipsOngoingMediaSummaryAndOwn() {
        assertFalse(shouldShow(n("1").copy(ongoing = true), "me"))
        assertFalse(shouldShow(n("1").copy(media = true), "me"))
        assertFalse(shouldShow(n("1").copy(summary = true), "me"))
        assertFalse(shouldShow(n("1", pkg = "me"), "me"))
        assertTrue(shouldShow(n("1"), "me"))
    }

    @Test fun newAlertingPopsBanner() = assertTrue(post(emptyList(), n("1"), "me").second)

    @Test fun updateReplacesWithoutBanner() {
        val (l, pop) = post(listOf(n("1")), n("1").copy(text = "edited"), "me")
        assertEquals(listOf("edited"), l.map { it.text })
        assertFalse(pop)
    }

    @Test fun silentIsListedWithoutBanner() {
        val (l, pop) = post(emptyList(), n("1", alerting = false), "me")
        assertEquals(1, l.size)
        assertFalse(pop)
    }

    @Test fun skippedUpdateRemovesOldCopy() = assertTrue(post(listOf(n("1")), n("1").copy(ongoing = true), "me").first.isEmpty())
    @Test fun removeByKey() = assertEquals(listOf("2"), remove(listOf(n("1"), n("2")), "1").map { it.key })

    @Test fun burstCountsExtras() {
        val q = BannerQueue()
        q.show(n("1")); q.show(n("2")); q.show(n("3"))
        assertEquals("3", q.current?.key)
        assertEquals(2, q.more)
        q.hide()
        assertNull(q.current)
        assertEquals(0, q.more)
    }

    @Test fun removingCurrentClearsBanner() {
        val q = BannerQueue()
        q.show(n("1"))
        assertFalse(q.removed("other"))
        assertTrue(q.removed("1"))
        assertNull(q.current)
    }
}
```

- [ ] **Step 2: Run, expect compile failure.** `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.notify.*"`

- [ ] **Step 3: Implement `notify/Note.kt`**

```kotlin
package dev.opencover.notify

data class NoteAction(val title: String, val acceptsReply: Boolean)

/** A notification as plain data. [user] is the profile serial; [conversation] is a chat id or title, if any. */
data class Note(
    val key: String,
    val pkg: String,
    val user: Long,
    val title: String,
    val text: String,
    val time: Long,
    val conversation: String?,
    val actions: List<NoteAction>,
    val ongoing: Boolean = false,
    val media: Boolean = false,
    val summary: Boolean = false,
    val alerting: Boolean = true,
) {
    val groupKey get() = "$user/$pkg/${conversation.orEmpty()}"
}

/** [title] is the conversation title, or null to show the app name. */
data class Conversation(val key: String, val pkg: String, val title: String?, val notes: List<Note>) {
    val latest get() = notes.first()
}

fun shouldShow(n: Note, ownPkg: String) = !n.ongoing && !n.media && !n.summary && n.pkg != ownPkg

/** New list after a post, and whether it should pop a banner (new and alerting only). */
fun post(notes: List<Note>, n: Note, ownPkg: String): Pair<List<Note>, Boolean> {
    val rest = notes.filter { it.key != n.key }
    if (!shouldShow(n, ownPkg)) return rest to false
    return (rest + n) to (rest.size == notes.size && n.alerting)
}

fun remove(notes: List<Note>, key: String) = notes.filter { it.key != key }

/** Newest conversation first; notes inside newest first. */
fun groupConversations(notes: List<Note>): List<Conversation> =
    notes.sortedByDescending { it.time }.groupBy { it.groupKey }
        .map { (key, ns) -> Conversation(key, ns.first().pkg, ns.first().conversation, ns) }

/** The banner shows the newest note; notes arriving while it's up become "+N more". */
class BannerQueue {
    var current: Note? = null
        private set
    var more = 0
        private set

    fun show(n: Note) {
        if (current != null) more++
        current = n
    }

    /** True if [key] was the note on screen (the banner should hide). */
    fun removed(key: String): Boolean = (current?.key == key).also { if (it) hide() }

    fun hide() {
        current = null
        more = 0
    }
}
```

- [ ] **Step 4: Run, expect PASS** (9 tests). Then full suite `.\gradlew.bat :app:testDebugUnitTest`.

- [ ] **Step 5: Commit** `Add notification model, grouping and banner rules`

---

### Task 2: Listener, store, notifications page, open on cover

**Files:**
- Create: `app/src/main/java/dev/opencover/notify/NotificationStore.kt`, `app/src/main/java/dev/opencover/ui/NotificationsPage.kt`
- Modify: `app/src/main/java/dev/opencover/watcher/NotificationAccess.kt`, `app/src/main/java/dev/opencover/ui/Shell.kt`

**Interfaces:**
- Consumes: Task 1; `BankingMode.apps/pause`; `COVER_DISPLAY`.
- Produces: `NotificationStore { notes; reset(); posted(ctx, sbn, note): Boolean; removed(key); open(ctx, key): Boolean; act(ctx, key, index): Boolean; reply(ctx, key, index, text): Boolean; dismiss(key); popupsEnabled(ctx); setPopups(ctx, on) }`; `StatusBarNotification.toNote(serial, alerting)`; `NotificationAccess.instance`; `NotificationsPage(notes)`.

- [ ] **Step 1: `notify/NotificationStore.kt`**

```kotlin
package dev.opencover.notify

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.COVER_DISPLAY
import dev.opencover.watcher.BankingMode
import dev.opencover.watcher.NotificationAccess

/** Current notifications (Compose state) plus the raw objects needed to act on them. */
object NotificationStore {
    var notes by mutableStateOf(emptyList<Note>())
        private set
    private val raw = HashMap<String, StatusBarNotification>()

    fun reset() {
        notes = emptyList()
        raw.clear()
    }

    /** Returns true if this should pop a banner. */
    fun posted(ctx: Context, sbn: StatusBarNotification, note: Note): Boolean {
        val (list, pop) = post(notes, note, ctx.packageName)
        notes = list
        if (list.any { it.key == note.key }) raw[note.key] = sbn else raw.remove(note.key)
        return pop
    }

    fun removed(key: String) {
        notes = remove(notes, key)
        raw.remove(key)
    }

    /** Open the notification's target on the cover screen. False if it can't. */
    fun open(ctx: Context, key: String): Boolean {
        val sbn = raw[key] ?: return false
        val pi = sbn.notification.contentIntent ?: return false
        if (sbn.packageName in BankingMode.apps(ctx)) BankingMode.pause(ctx, sbn.packageName)
        val ok = sendOnCover(ctx, pi)
        if (ok && sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) dismiss(key)
        return ok
    }

    fun act(ctx: Context, key: String, index: Int): Boolean {
        val action = raw[key]?.notification?.actions?.getOrNull(index) ?: return false
        return sendOnCover(ctx, action.actionIntent)
    }

    fun reply(ctx: Context, key: String, index: Int, text: String): Boolean {
        val action = raw[key]?.notification?.actions?.getOrNull(index) ?: return false
        val inputs = action.remoteInputs ?: return false
        val intent = Intent()
        RemoteInput.addResultsToIntent(inputs, intent, Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } })
        return try {
            action.actionIntent.send(ctx, 0, intent)
            true
        } catch (e: PendingIntent.CanceledException) {
            false
        }
    }

    fun dismiss(key: String) {
        NotificationAccess.instance?.cancelNotification(key)
    }

    private fun sendOnCover(ctx: Context, pi: PendingIntent): Boolean = try {
        val options = ActivityOptions.makeBasic()
            .setLaunchDisplayId(COVER_DISPLAY)
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
        pi.send(ctx, 0, null, null, null, null, options)
        true
    } catch (e: PendingIntent.CanceledException) {
        false
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("notifications", Context.MODE_PRIVATE)
    fun popupsEnabled(ctx: Context) = prefs(ctx).getBoolean("popups", true)
    fun setPopups(ctx: Context, on: Boolean) = prefs(ctx).edit().putBoolean("popups", on).apply()
}

/** Plain-data copy of a notification. Chat text comes from the last MessagingStyle message. */
fun StatusBarNotification.toNote(serial: Long, alerting: Boolean): Note {
    val n = notification
    val x = n.extras
    val lastMessage = x.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)?.lastOrNull() as? Bundle
    val text = lastMessage?.getCharSequence("text")
        ?: x.getCharSequence(Notification.EXTRA_BIG_TEXT)
        ?: x.getCharSequence(Notification.EXTRA_TEXT)
    return Note(
        key = key,
        pkg = packageName,
        user = serial,
        title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
        text = text?.toString().orEmpty(),
        time = postTime,
        conversation = n.shortcutId ?: x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString(),
        actions = n.actions.orEmpty().take(3).map { NoteAction(it.title?.toString().orEmpty(), !it.remoteInputs.isNullOrEmpty()) },
        ongoing = isOngoing,
        media = x.getString(Notification.EXTRA_TEMPLATE) == Notification.MediaStyle::class.java.name,
        summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
        alerting = alerting,
    )
}
```

- [ ] **Step 2: `watcher/NotificationAccess.kt`**

```kotlin
package dev.opencover.watcher

import android.app.NotificationManager
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.opencover.notify.NotificationStore
import dev.opencover.notify.toNote

/** Feeds the notifications page and pop-up banner. */
class NotificationAccess : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        NotificationStore.reset()
        activeNotifications?.forEach { add(it, currentRanking, banner = false) } // reconnect: no pop-ups
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) =
        add(sbn, rankingMap, banner = true)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationStore.removed(sbn.key)
    }

    private fun add(sbn: StatusBarNotification, ranking: RankingMap, banner: Boolean) {
        val r = Ranking()
        val alerting = ranking.getRanking(sbn.key, r) && r.importance >= NotificationManager.IMPORTANCE_DEFAULT
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(sbn.user)
        val note = sbn.toNote(serial, alerting)
        NotificationStore.posted(this, sbn, note) // banner hookup arrives in Task 3
    }

    companion object {
        var instance: NotificationAccess? = null
            private set
    }
}
```

- [ ] **Step 3: `ui/NotificationsPage.kt`**

```kotlin
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.notify.Note
import dev.opencover.notify.NotificationStore
import dev.opencover.notify.groupConversations

private val Card = Color(0xFF1E1E1E)

@Composable
fun NotificationsPage(notes: List<Note>) {
    val ctx = LocalContext.current
    val groups = remember(notes) { groupConversations(notes) }
    var expanded by remember { mutableStateOf(setOf<String>()) }
    if (groups.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No notifications", color = Color.Gray) }
        return
    }
    LazyColumn(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                } else c.notes.forEach { NoteRow(it) }
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

/** Reply actions open the reply sheet (Task 4); others fire directly. */
fun runAction(ctx: Context, n: Note, index: Int) {
    if (!NotificationStore.act(ctx, n.key, index)) toast(ctx, "Couldn't do that")
}

fun appLabel(ctx: Context, pkg: String): String = try {
    ctx.packageManager.run { getApplicationLabel(getApplicationInfo(pkg, 0)).toString() }
} catch (e: Exception) {
    pkg
}

fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()
```

- [ ] **Step 4: Shell page 0.** In `Shell.kt`, replace `NOTIFICATIONS_PAGE -> Placeholder("Notifications")` with `NOTIFICATIONS_PAGE -> NotificationsPage(NotificationStore.notes)` and import `dev.opencover.notify.NotificationStore`.

- [ ] **Step 5: Build, install.** `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`, then `.\scripts\run.ps1 -NoBuild`.

- [ ] **Step 6: Device check 2 (open on cover).** Ask the user to have someone message them on Messenger (or send a test from another app), then on the cover: swipe to Notifications, expand the card, tap the message. Expected: Messenger opens on the cover (`adb shell "dumpsys activity activities | grep topResumedActivity"` shows it under Display #1). If it opens on display 0: stop and report; `sendOnCover` options are not honored and the spec needs a new approach.

- [ ] **Step 7: Commit** `Add notifications page fed by the notification listener`

---

### Task 3: Pop-up banner

**Files:**
- Create: `app/src/main/java/dev/opencover/notify/Banner.kt`
- Modify: `app/src/main/java/dev/opencover/watcher/NotificationAccess.kt`, `app/src/main/java/dev/opencover/ui/SettingsPanel.kt`, `app/src/main/java/dev/opencover/ui/Shell.kt`, `app/src/main/java/dev/opencover/ui/CoverActivity.kt`

**Interfaces:**
- Consumes: `BannerQueue`, `NotificationStore`, `runAction`.
- Produces: `Banner.show(ctx, note)`, `Banner.removed(key)`, `Banner.hideNow()`; `LauncherActions.popups: () -> Boolean`, `setPopups: (Boolean) -> Unit`; `SettingsPanel(popups, onPopups)`.

- [ ] **Step 1: `notify/Banner.kt`** (plain Views: an overlay window has no Compose lifecycle owner)

```kotlin
package dev.opencover.notify

import android.app.KeyguardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.opencover.COVER_DISPLAY
import dev.opencover.ui.runAction
import dev.opencover.ui.toast

/** Pop-up over any app on the cover screen. Hides after 5 s; tap opens, swipe up hides. */
object Banner {
    private val handler = Handler(Looper.getMainLooper())
    private val queue = BannerQueue()
    private var view: LinearLayout? = null
    private val autoHide = Runnable { hideNow() }

    fun show(ctx: Context, note: Note) {
        if (!NotificationStore.popupsEnabled(ctx) || !Settings.canDrawOverlays(ctx)) return
        if (ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        val display = ctx.getSystemService(DisplayManager::class.java).getDisplay(COVER_DISPLAY) ?: return
        if (display.state != Display.STATE_ON) return
        queue.show(note)
        removeView()
        val wctx = ctx.applicationContext.createDisplayContext(display)
            .createWindowContext(LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        val v = build(wctx, note, queue.more)
        val params = LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            LayoutParams.TYPE_APPLICATION_OVERLAY, LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP }
        wctx.getSystemService(WindowManager::class.java).addView(v, params)
        view = v
        handler.removeCallbacks(autoHide)
        handler.postDelayed(autoHide, 5000)
    }

    fun removed(key: String) {
        if (queue.removed(key)) hideNow()
    }

    fun hideNow() {
        handler.removeCallbacks(autoHide)
        removeView()
        queue.hide()
    }

    private fun removeView() {
        view?.let { it.context.getSystemService(WindowManager::class.java).removeView(it) }
        view = null
    }

    private fun build(ctx: Context, note: Note, more: Int): LinearLayout {
        fun dp(n: Int) = (n * ctx.resources.displayMetrics.density).toInt()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
            background = GradientDrawable().apply { setColor(0xF0202020.toInt()); cornerRadius = dp(18).toFloat() }
            addView(TextView(ctx).apply {
                text = note.title; setTextColor(Color.WHITE); textSize = 16f; typeface = Typeface.DEFAULT_BOLD
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(TextView(ctx).apply {
                text = note.text; setTextColor(0xFFCCCCCC.toInt()); textSize = 14f
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            })
            if (more > 0) addView(TextView(ctx).apply { text = "+$more more"; setTextColor(Color.GRAY); textSize = 12f })
            if (note.actions.isNotEmpty()) addView(LinearLayout(ctx).apply {
                note.actions.forEachIndexed { i, act ->
                    addView(Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
                        text = act.title; setTextColor(0xFF8AB4F8.toInt()); isAllCaps = false
                        setOnClickListener { hideNow(); runAction(ctx, note, i) }
                    })
                }
            })
            setOnClickListener {
                hideNow()
                if (!NotificationStore.open(ctx, note.key)) toast(ctx, "Couldn't open on the cover screen")
            }
            var downY = 0f
            setOnTouchListener { _, e ->
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> { downY = e.rawY; false }
                    MotionEvent.ACTION_UP -> if (downY - e.rawY > dp(40)) { hideNow(); true } else false
                    else -> false
                }
            }
        }
    }
}
```

- [ ] **Step 2: Hook up.** In `NotificationAccess.add`, replace the posted line with:
```kotlin
        if (NotificationStore.posted(this, sbn, note) && banner) Banner.show(this, note)
```
and in `onNotificationRemoved` add `Banner.removed(sbn.key)`. Import `dev.opencover.notify.Banner`.

- [ ] **Step 3: Settings switch.** `SettingsPanel`: add params `popups: Boolean, onPopups: (Boolean) -> Unit` and, after the keep-awake row:
```kotlin
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Notification pop-ups", color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = popups, onCheckedChange = onPopups)
        }
```
`LauncherActions`: add `val popups: () -> Boolean, val setPopups: (Boolean) -> Unit`. `Shell`: `var popups by remember { mutableStateOf(a.popups()) }` and pass `popups = popups, onPopups = { popups = it; a.setPopups(it) }`. `CoverActivity`: `popups = { NotificationStore.popupsEnabled(this) }, setPopups = { NotificationStore.setPopups(this, it) }`.

- [ ] **Step 4: Build, install, device check 1 (banner).**
```bash
adb shell cmd notification post -S bigtext -t "OpenCover test" test1 "Banner check"
```
With the cover unlocked on another app, screenshot within 2 s: banner at the top. Ask the user to tap it once (nothing to open for a shell notification: expect "Couldn't open" toast), post again and swipe it up (hides), post again and wait (gone after 5 s). Post 3 quickly: "+2 more".

- [ ] **Step 5: Commit** `Add notification pop-up banner`

---

### Task 4: Reply sheet with voice

**Files:**
- Create: `app/src/main/java/dev/opencover/notify/ReplyActivity.kt`
- Modify: `app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/opencover/ui/NotificationsPage.kt`, `app/src/main/java/dev/opencover/ui/Checklist.kt`, `app/src/main/java/dev/opencover/ui/CoverActivity.kt`

**Interfaces:**
- Consumes: `NotificationStore.notes/reply`, `AppLauncher.start`.
- Produces: `ReplyActivity.open(ctx, key, index)`; `Permissions.mic(ctx)`.

- [ ] **Step 1: `notify/ReplyActivity.kt`**

```kotlin
package dev.opencover.notify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.apps.AppLauncher
import dev.opencover.ui.toast

/** Reply sheet on the cover screen: type or speak, then send through the notification's reply action. */
class ReplyActivity : ComponentActivity() {
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(KEY) ?: return finish()
        val index = intent.getIntExtra(INDEX, 0)
        val note = NotificationStore.notes.find { it.key == key } ?: return finish()
        val canSpeak = SpeechRecognizer.isRecognitionAvailable(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ReplySheet(note, canSpeak, onMic = ::listen) { text ->
                    if (NotificationStore.reply(this, key, index, text)) finish() else toast(this, "Couldn't send")
                }
            }
        }
    }

    private fun listen(onHeard: (String) -> Unit) {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onHeard)
                }
                override fun onError(error: Int) = toast(this@ReplyActivity, "Didn't catch that")
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY = "key"
        private const val INDEX = "index"

        fun open(ctx: Context, key: String, index: Int) =
            AppLauncher.start(ctx, Intent(ctx, ReplyActivity::class.java).putExtra(KEY, key).putExtra(INDEX, index))
    }
}

@Composable
private fun ReplySheet(note: Note, canSpeak: Boolean, onMic: ((String) -> Unit) -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Color.Black).imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(note.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(note.text, color = Color.LightGray, fontSize = 14.sp, maxLines = 3)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Reply") }, modifier = Modifier.weight(1f))
            if (canSpeak) TextButton(onClick = { onMic { heard -> text = if (text.isBlank()) heard else "$text $heard" } }) { Text("Mic") }
            TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("Send") }
        }
    }
}
```

- [ ] **Step 2: Manifest.** Add `<uses-permission android:name="android.permission.RECORD_AUDIO" />` and inside `<application>`:
```xml
        <activity
            android:name=".notify.ReplyActivity"
            android:exported="false"
            android:excludeFromRecents="true"
            android:windowSoftInputMode="adjustResize" />
```

- [ ] **Step 3: Route reply actions.** In `NotificationsPage.kt`, change `runAction` to:
```kotlin
/** Reply actions open the reply sheet; others fire directly. */
fun runAction(ctx: Context, n: Note, index: Int) {
    if (n.actions.getOrNull(index)?.acceptsReply == true) ReplyActivity.open(ctx, n.key, index)
    else if (!NotificationStore.act(ctx, n.key, index)) toast(ctx, "Couldn't do that")
}
```
Import `dev.opencover.notify.ReplyActivity`.

- [ ] **Step 4: Microphone checklist row.** `Permissions`: `fun mic(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED`. In `CoverActivity.setupSteps()`, before the Notifications row:
```kotlin
        SetupStep("Microphone (optional)", "Voice replies to notifications",
            Permissions.mic(this)) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2) },
```

- [ ] **Step 5: Build, install, device check 3 (voice).** Open Setup, grant Microphone, then ask the user to reply to a Messenger message: once typed, once by voice. Expected: the sheet opens on the cover, Mic fills the box, Send delivers and closes the sheet. Then ask them to reply to a message they already read on another device (reply action often cancelled): expected "Couldn't send" and the text stays.

- [ ] **Step 6: Commit** `Add reply sheet with voice input`

---

### Task 5: Device pass and docs

- [ ] **Step 1:** Find Samsung's cover-screen notification pop-up setting with the user (Settings > Notifications, or Settings > Cover screen) and record its path in the README.
- [ ] **Step 2:** Ask the user to check: Messenger thread grouping, an action button (e.g. Mark as read), swipe a row away (gone everywhere), tap opens on the cover, banner over another app, pop-ups switch off stops banners, no banner while locked.
- [ ] **Step 3: README** "What works", add:
```markdown
- Notifications: swipe left from Home. Grouped by conversation, with actions, swipe to dismiss, and replies by text or voice. New ones pop up over any app.
```
and under Install: `Turn off Samsung's own cover notification pop-ups (<path from Step 1>) to avoid doubles.`
- [ ] **Step 4: Commit** `Update README for notifications`
