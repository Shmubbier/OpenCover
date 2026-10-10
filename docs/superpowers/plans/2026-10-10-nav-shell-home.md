# Navigation Shell and Home Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Swipe layout around a new Home screen: notifications left, apps pages right, media up, quick settings down.

**Architecture:** One horizontal pager (Notifications, Home, Apps 1..n) plus finger-tracking pull panels chosen by a pure `panelFor` function. Home settings live in `layout.json`; the wallpaper is a separate file.

**Tech Stack:** Kotlin, Jetpack Compose (foundation pager, Animatable), org.json, JUnit.

**Spec:** `docs/superpowers/specs/2026-10-10-nav-shell-home-design.md`

## Global Constraints

- Commits as Shmubbier (repo-local config), no Claude attribution, no em dashes or emojis.
- Push with `git -c credential.helper= -c 'credential.helper=!gh auth git-credential' push`.
- Runtime dependencies unchanged (Kotlin, Compose, activity-compose).
- Cover display 1, 748x720. Quick slots: 4, bottom left.
- `layout.json` stays version 1; `home` is optional.
- adb: `ANDROID_SERIAL=R5CW71QQAGW`. Build: `JAVA_HOME=C:\Program Files\Android\Android Studio\jbr`.

## Review Focus

1. Old `layout.json` without `home` keeps all pages. Test `oldFileWithoutHomeLoadsEmptySlots` (Task 1).
2. Malformed `home` keeps all pages. Test `malformedHomeKeepsPages` (Task 1).
3. Uninstalled quick-slot app clears only that profile's slot. Tests in Task 1.
4. Removing the apps page you're on lands on the previous page, never past Home. Test `removingFirstAppsPageLandsOnHome` (Task 2).
5. Home gesture while a panel, overlay or edit mode is open returns to a clean Home. Device check in Task 4.

---

### Task 1: Home model, JSON and pruning

**Files:**
- Modify: `app/src/main/java/dev/opencover/layout/Layout.kt`, `app/src/main/java/dev/opencover/layout/LayoutJson.kt`
- Test: `app/src/test/java/dev/opencover/layout/LayoutHomeTest.kt`

**Interfaces:**
- Produces: `QuickApp(pkg, activity, user)` with `key`; `QUICK_SLOTS = 4`; `Home(quick: List<QuickApp?>)`; `Layout(pages, home = Home())`; `Layout.setQuick(slot: Int, app: QuickApp?): Layout`. `removePackage` and `pruneMissing` also clear quick slots.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.opencover.layout

import dev.opencover.grid.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LayoutHomeTest {
    private val chat0 = QuickApp("chat", "chat.Main", 0)
    private val chat95 = QuickApp("chat", "chat.Main", 95)
    private val page = Page(listOf(AppItem("a", Cell(0, 0, 1, 1), "p", "p.Main", 0)))
    private val withQuick = Layout(listOf(page)).setQuick(0, chat0).setQuick(3, chat95)

    @Test fun defaultHomeHasFourEmptySlots() = assertEquals(List(4) { null }, Layout(listOf(page)).home.quick)

    @Test fun setAndClearSlot() {
        assertEquals(chat0, withQuick.home.quick[0])
        assertNull(withQuick.setQuick(0, null).home.quick[0])
    }

    @Test fun setOutOfRangeSlotIsIgnored() = assertEquals(withQuick, withQuick.setQuick(4, chat0))
    @Test fun jsonRoundTripKeepsHome() = assertEquals(withQuick, LayoutJson.decode(LayoutJson.encode(withQuick)))

    @Test fun oldFileWithoutHomeLoadsEmptySlots() =
        assertEquals(Home(), LayoutJson.decode("""{"version":1,"pages":[{"items":[]}]}""").home)

    @Test fun malformedHomeKeepsPages() {
        val l = LayoutJson.decode(
            """{"version":1,"pages":[{"items":[{"id":"a","type":"app","x":0,"y":0,"w":1,"h":1,"package":"p","activity":"p.M","user":0}]}],"home":{"quick":"oops"}}"""
        )
        assertEquals(1, l.pages[0].items.size)
        assertEquals(Home(), l.home)
    }

    @Test fun removePackageClearsOnlyThatUsersSlot() {
        val (l, _) = withQuick.removePackage("chat", user = 95)
        assertEquals(chat0, l.home.quick[0])
        assertNull(l.home.quick[3])
    }

    @Test fun pruneClearsUninstalledSlotButKeepsNotReadyProfile() {
        val (l, _) = withQuick.pruneMissing(setOf(appKey("p", "p.Main", 0)), userReady = { it == 0L }) { true }
        assertNull(l.home.quick[0])
        assertEquals(chat95, l.home.quick[3])
    }
}
```

- [ ] **Step 2: Run, expect compile failure** (`Unresolved reference 'QuickApp'`)

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.layout.LayoutHomeTest"`

- [ ] **Step 3: Implement in `Layout.kt`**

Replace `data class Layout(val pages: List<Page>)` with:

```kotlin
data class QuickApp(val pkg: String, val activity: String, val user: Long) {
    val key get() = appKey(pkg, activity, user)
}

const val QUICK_SLOTS = 4

data class Home(val quick: List<QuickApp?> = List(QUICK_SLOTS) { null })

data class Layout(val pages: List<Page>, val home: Home = Home())

fun Layout.setQuick(slot: Int, app: QuickApp?): Layout =
    if (slot !in 0 until QUICK_SLOTS) this
    else copy(home = home.copy(quick = home.quick.toMutableList().also { it[slot] = app }))

private fun Layout.clearQuick(drop: (QuickApp) -> Boolean) =
    copy(home = home.copy(quick = home.quick.map { q -> q?.takeUnless(drop) }))
```

Replace `removePackage` and `pruneMissing` with:

```kotlin
/** An app was uninstalled for [user]: drop its icons, quick slots and widgets for that user. */
fun Layout.removePackage(pkg: String, user: Long): Pair<Layout, List<Int>> {
    val (l, freed) = retain {
        when (it) {
            is AppItem -> !(it.pkg == pkg && it.user == user)
            is WidgetItem -> !(it.providerPkg == pkg && it.user == user)
        }
    }
    return l.clearQuick { it.pkg == pkg && it.user == user } to freed
}

/** Drops icons and quick slots for apps no longer installed, and widgets that no longer exist.
 *  Does nothing if [installedKeys] is empty: an empty app list means loading failed, not "no apps".
 *  Items of a profile that isn't [userReady] (locked, paused, or its list came back empty) are kept. */
fun Layout.pruneMissing(
    installedKeys: Set<String>,
    userReady: (Long) -> Boolean,
    widgetExists: (Int) -> Boolean,
): Pair<Layout, List<Int>> {
    if (installedKeys.isEmpty()) return this to emptyList()
    val (l, freed) = retain {
        when (it) {
            is AppItem -> it.key in installedKeys || !userReady(it.user)
            is WidgetItem -> widgetExists(it.widgetId)
        }
    }
    return l.clearQuick { it.key !in installedKeys && userReady(it.user) } to freed
}
```

- [ ] **Step 4: Implement in `LayoutJson.kt`**

In `encode`, add after `.put("pages", ...)`:

```kotlin
        .put("home", JSONObject().put("quick", JSONArray(l.home.quick.map { q ->
            q?.let { JSONObject().put("package", it.pkg).put("activity", it.activity).put("user", it.user) } ?: JSONObject.NULL
        })))
```

In `decode`, change `return Layout(pages)` to `return Layout(pages, decodeHome(root.optJSONObject("home")))` and add:

```kotlin
    /** Missing or malformed: empty slots. Never fails the whole file. */
    private fun decodeHome(o: JSONObject?): Home = try {
        val arr = o?.getJSONArray("quick") ?: return Home()
        Home(List(QUICK_SLOTS) { i ->
            arr.optJSONObject(i)?.let { QuickApp(it.getString("package"), it.getString("activity"), it.getLong("user")) }
        })
    } catch (e: Exception) {
        Home()
    }
```

- [ ] **Step 5: Run all unit tests, expect PASS**

Run: `.\gradlew.bat :app:testDebugUnitTest`

- [ ] **Step 6: Commit** `Add Home quick slots to the layout model`

---

### Task 2: Shell page mapping and panel choice

**Files:**
- Create: `app/src/main/java/dev/opencover/ui/ShellPages.kt`
- Test: `app/src/test/java/dev/opencover/ui/ShellPagesTest.kt`

**Interfaces:**
- Produces: `NOTIFICATIONS_PAGE = 0`, `HOME_PAGE = 1`, `FIRST_APPS_PAGE = 2`, `pagerPageCount(appsPages)`, `layoutPageFor(pagerPage): Int?`, `pageAfterRemoving(pagerPage)`, `enum Edge { TOP, BOTTOM }`, `enum Panel(edge) { MEDIA, QUICK_SETTINGS, DRAWER }`, `panelFor(pagerPage, up): Panel?`.

- [ ] **Step 1: Write the failing tests**

```kotlin
package dev.opencover.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellPagesTest {
    @Test fun pageCountAddsNotificationsAndHome() = assertEquals(5, pagerPageCount(3))
    @Test fun appsPagesMapToLayoutPages() = assertEquals(0, layoutPageFor(FIRST_APPS_PAGE))
    @Test fun homeAndNotificationsAreNotLayoutPages() {
        assertNull(layoutPageFor(HOME_PAGE))
        assertNull(layoutPageFor(NOTIFICATIONS_PAGE))
    }
    @Test fun removingLaterAppsPageGoesBackOne() = assertEquals(3, pageAfterRemoving(4))
    @Test fun removingFirstAppsPageLandsOnHome() = assertEquals(HOME_PAGE, pageAfterRemoving(FIRST_APPS_PAGE))
    @Test fun homeSwipesOpenMediaAndQuickSettings() {
        assertEquals(Panel.MEDIA, panelFor(HOME_PAGE, up = true))
        assertEquals(Panel.QUICK_SETTINGS, panelFor(HOME_PAGE, up = false))
    }
    @Test fun appsPageSwipeUpOpensDrawerOnly() {
        assertEquals(Panel.DRAWER, panelFor(FIRST_APPS_PAGE + 1, up = true))
        assertNull(panelFor(FIRST_APPS_PAGE, up = false))
    }
    @Test fun notificationsPageHasNoPanels() = assertNull(panelFor(NOTIFICATIONS_PAGE, up = true))
}
```

- [ ] **Step 2: Run, expect compile failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "dev.opencover.ui.ShellPagesTest"`

- [ ] **Step 3: Implement `ShellPages.kt`**

```kotlin
package dev.opencover.ui

const val NOTIFICATIONS_PAGE = 0
const val HOME_PAGE = 1
const val FIRST_APPS_PAGE = 2

fun pagerPageCount(appsPages: Int) = FIRST_APPS_PAGE + appsPages

/** Layout page index for a pager page, or null for Notifications and Home. */
fun layoutPageFor(pagerPage: Int): Int? = (pagerPage - FIRST_APPS_PAGE).takeIf { it >= 0 }

/** After removing the apps page you're on: the page before it, never past Home. */
fun pageAfterRemoving(pagerPage: Int) = maxOf(HOME_PAGE, pagerPage - 1)

enum class Edge { TOP, BOTTOM }

enum class Panel(val edge: Edge) { MEDIA(Edge.BOTTOM), QUICK_SETTINGS(Edge.TOP), DRAWER(Edge.BOTTOM) }

/** Which panel a vertical swipe opens on [pagerPage], or null. */
fun panelFor(pagerPage: Int, up: Boolean): Panel? = when {
    pagerPage == HOME_PAGE -> if (up) Panel.MEDIA else Panel.QUICK_SETTINGS
    pagerPage >= FIRST_APPS_PAGE && up -> Panel.DRAWER
    else -> null
}
```

- [ ] **Step 4: Run, expect PASS** (8 tests)

- [ ] **Step 5: Commit** `Add shell page mapping and panel rules`

---

### Task 3: Shell, Home screen, wallpaper

**Files:**
- Delete: `app/src/main/java/dev/opencover/ui/LauncherScreen.kt`
- Create: `app/src/main/java/dev/opencover/ui/Shell.kt`, `app/src/main/java/dev/opencover/ui/HomeScreen.kt`, `app/src/main/java/dev/opencover/home/Wallpaper.kt`
- Modify: `app/src/main/java/dev/opencover/ui/SettingsPanel.kt`, `app/src/main/java/dev/opencover/ui/CoverActivity.kt`

**Interfaces:**
- Consumes: Task 1 `Home`, `QuickApp`, `setQuick`; Task 2 everything; existing `GridPage`, `AppDrawer`, `WidgetPicker`, `AppIcon`.
- Produces: `Shell(layout, apps, a, prompt, wallpaper: ImageBitmap?, homeRequests: Int)`; `LauncherActions` gains `setQuick: (slot: Int, AppEntry?) -> Unit` and `pickWallpaper: () -> Unit`; `Wallpaper.save(ctx, uri): Boolean`, `Wallpaper.load(ctx): ImageBitmap?`; `SettingsPanel` gains `onWallpaper`.

- [ ] **Step 1: `home/Wallpaper.kt`**

```kotlin
package dev.opencover.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Home wallpaper: the picked photo center-cropped to the cover size, saved as wallpaper.jpg. */
object Wallpaper {
    private const val W = 748
    private const val H = 720

    private fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    // ponytail: decodes on the main thread (~0.5 s for a large photo); move off-thread if it janks.
    fun save(ctx: Context, uri: Uri): Boolean = try {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = maxOf(W.toFloat() / info.size.width, H.toFloat() / info.size.height)
            d.setTargetSize(
                (info.size.width * s).roundToInt().coerceAtLeast(W),
                (info.size.height * s).roundToInt().coerceAtLeast(H),
            )
        }
        val cropped = Bitmap.createBitmap(src, (src.width - W) / 2, (src.height - H) / 2, W, H)
        val tmp = File(ctx.filesDir, "wallpaper.jpg.tmp")
        FileOutputStream(tmp).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it); it.fd.sync() }
        tmp.renameTo(file(ctx))
    } catch (e: Exception) {
        false
    }

    fun load(ctx: Context): ImageBitmap? =
        file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
}
```

- [ ] **Step 2: `ui/HomeScreen.kt`**

```kotlin
package dev.opencover.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.layout.Home
import dev.opencover.layout.QuickApp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@Composable
fun HomeScreen(home: Home, wallpaper: ImageBitmap?, a: LauncherActions, onEditSlot: (Int) -> Unit) {
    Box(Modifier.fillMaxSize()) {
        if (wallpaper != null) {
            Image(wallpaper, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Clock(Modifier.align(Alignment.TopStart).padding(20.dp))
        // Bottom left: the camera cutout is bottom right.
        Row(Modifier.align(Alignment.BottomStart).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            home.quick.forEachIndexed { slot, q -> QuickSlot(q, a) { onEditSlot(slot) } }
        }
    }
}

@Composable
private fun QuickSlot(q: QuickApp?, a: LauncherActions, onEdit: () -> Unit) {
    val icon = q?.let { a.repo.icon(it.pkg, it.activity, it.user) }
    Box(
        Modifier.size(52.dp).pointerInput(q) {
            detectTapGestures(
                onTap = { if (q == null) onEdit() else a.launch(q.pkg, q.activity, q.user) },
                onLongPress = { onEdit() },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.fillMaxSize())
        else Box(
            Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text("+", color = Color.White, fontSize = 22.sp) }
    }
}

@Composable
private fun Clock(modifier: Modifier) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(60_000L - System.currentTimeMillis() % 60_000L) // wake on the minute
            now = LocalDateTime.now()
        }
    }
    val time = if (DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm"
    val shadow = TextStyle(shadow = Shadow(Color.Black, blurRadius = 8f))
    Column(modifier) {
        Text(now.format(DateTimeFormatter.ofPattern(time)), color = Color.White, fontSize = 64.sp, style = shadow)
        Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d")), color = Color.White, fontSize = 18.sp, style = shadow)
    }
}
```

- [ ] **Step 3: `SettingsPanel.kt`**

Add parameter `onWallpaper: () -> Unit,` after `onSetup`, and a button after "Setup & permissions":

```kotlin
        Button(onClick = onWallpaper) { Text("Set wallpaper") }
```

- [ ] **Step 4: Replace `LauncherScreen.kt` with `Shell.kt`**

`git rm app/src/main/java/dev/opencover/ui/LauncherScreen.kt`, then create `Shell.kt`:

```kotlin
package dev.opencover.ui

import android.appwidget.AppWidgetProviderInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import dev.opencover.apps.AppEntry
import dev.opencover.apps.AppRepository
import dev.opencover.layout.Item
import dev.opencover.layout.Layout
import dev.opencover.layout.addPage
import dev.opencover.layout.removePage
import dev.opencover.watcher.KeepAwake
import dev.opencover.widgets.WidgetHost
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class LauncherActions(
    val repo: AppRepository,
    val widgets: WidgetHost,
    val launch: (pkg: String, activity: String, user: Long) -> Unit,
    val change: (Layout) -> Unit,
    val addApp: (AppEntry, page: Int) -> Unit,
    val remove: (Item) -> Unit,
    val addWidget: (AppWidgetProviderInfo, page: Int) -> Unit,
    val samsungWidgets: () -> Unit,
    val setKeepAwake: (Boolean) -> Unit,
    val openSetup: () -> Unit,
    val setQuick: (slot: Int, AppEntry?) -> Unit,
    val pickWallpaper: () -> Unit,
)

@Composable
fun EditBar(
    onAddPage: () -> Unit,
    onRemovePage: (() -> Unit)?,
    onDone: () -> Unit,
    extra: @Composable RowScope.() -> Unit = {},
) {
    // Done first so it can never be pushed off the narrow cover screen; the rest scrolls if needed.
    Row(Modifier.fillMaxWidth().background(Color(0xCC202020)).horizontalScroll(rememberScrollState())) {
        TextButton(onClick = onDone) { Text("Done") }
        extra()
        TextButton(onClick = onAddPage) { Text("+ Page") }
        TextButton(onClick = { onRemovePage?.invoke() }, enabled = onRemovePage != null) { Text("− Page") }
    }
}

/** Shown when something doesn't fit on the current page. */
class PageFullPrompt(val confirm: () -> Unit, val cancel: () -> Unit)

private sealed interface Overlay {
    data object Widgets : Overlay
    data object Settings : Overlay
    data class PickSlot(val slot: Int) : Overlay
}

@Composable
fun Shell(
    layout: Layout,
    apps: List<AppEntry>,
    a: LauncherActions,
    prompt: PageFullPrompt?,
    wallpaper: ImageBitmap?,
    homeRequests: Int,
) {
    var editing by remember { mutableStateOf(false) }
    var overlay by remember { mutableStateOf<Overlay?>(null) }
    var panel by remember { mutableStateOf<Panel?>(null) }
    var slotMenu by remember { mutableStateOf<Int?>(null) }
    val reveal = remember { Animatable(0f) } // 0 = panel hidden, 1 = fully shown
    val scope = rememberCoroutineScope()
    val pageCount by rememberUpdatedState(pagerPageCount(layout.pages.size))
    val pager = rememberPagerState(initialPage = HOME_PAGE) { pageCount }
    val labels = remember(apps) { apps.associate { it.key to it.label } }
    var keepAwake by remember { mutableStateOf(KeepAwake.isOn) }

    fun closePanel() {
        scope.launch { reveal.animateTo(0f); panel = null }
    }

    // Home gesture, unlock or fold: back to a clean Home.
    LaunchedEffect(homeRequests) {
        editing = false
        overlay = null
        slotMenu = null
        panel = null
        reveal.snapTo(0f)
        pager.scrollToPage(HOME_PAGE)
    }
    BackHandler(enabled = editing) { editing = false }
    BackHandler(enabled = panel != null) { closePanel() }

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        val heightPx = constraints.maxHeight.toFloat()
        Column(Modifier.fillMaxSize()) {
            if (editing) {
                val removed = layoutPageFor(pager.currentPage)?.let { layout.removePage(it) }
                EditBar(
                    onAddPage = { a.change(layout.addPage()) },
                    onRemovePage = removed?.let { l ->
                        {
                            a.change(l)
                            scope.launch { pager.scrollToPage(pageAfterRemoving(pager.currentPage)) }
                        }
                    },
                    onDone = { editing = false },
                    extra = {
                        TextButton(onClick = { overlay = Overlay.Widgets }) { Text("+ Widget") }
                        TextButton(onClick = { overlay = Overlay.Settings }) { Text("Settings") }
                    },
                )
            }
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f).pointerInput(editing) {
                    if (editing) return@pointerInput
                    var total = 0f
                    // Pull a panel in from the edge; it follows the finger and snaps at 25%.
                    detectVerticalDragGestures(
                        onDragStart = { total = 0f },
                        onDragEnd = { if (reveal.value > 0.25f) scope.launch { reveal.animateTo(1f) } else closePanel() },
                        onDragCancel = { closePanel() },
                    ) { change, dy ->
                        total += dy
                        val p = panel ?: panelFor(pager.currentPage, up = total < 0)?.also { panel = it }
                            ?: return@detectVerticalDragGestures
                        change.consume()
                        val shown = if (p.edge == Edge.BOTTOM) -total else total
                        scope.launch { reveal.snapTo((shown / heightPx).coerceIn(0f, 1f)) }
                    }
                },
            ) { i ->
                when (i) {
                    NOTIFICATIONS_PAGE -> Placeholder("Notifications")
                    HOME_PAGE -> HomeScreen(layout.home, wallpaper, a) { slot ->
                        if (layout.home.quick[slot] == null) overlay = Overlay.PickSlot(slot) else slotMenu = slot
                    }
                    else -> GridPage(layout, layoutPageFor(i)!!, editing, labels, a, onLongPress = { editing = true })
                }
            }
        }

        panel?.let { p ->
            val sign = if (p.edge == Edge.BOTTOM) 1 else -1
            Box(
                Modifier.fillMaxSize()
                    .offset { IntOffset(0, ((1f - reveal.value) * heightPx * sign).roundToInt()) }
                    .background(Color(0xF0101010))
                    // Drag back toward the edge to close, wherever the content doesn't scroll itself.
                    .pointerInput(p) {
                        var total = 0f
                        detectVerticalDragGestures(
                            onDragStart = { total = 0f },
                            onDragEnd = { if (reveal.value < 0.75f) closePanel() else scope.launch { reveal.animateTo(1f) } },
                            onDragCancel = { scope.launch { reveal.animateTo(1f) } },
                        ) { change, dy ->
                            total += dy
                            val hiding = if (p.edge == Edge.BOTTOM) total else -total
                            if (hiding <= 0f) return@detectVerticalDragGestures
                            change.consume()
                            scope.launch { reveal.snapTo((1f - hiding / heightPx).coerceIn(0f, 1f)) }
                        }
                    }
            ) {
                when (p) {
                    Panel.DRAWER -> AppDrawer(
                        apps = apps,
                        icon = { a.repo.icon(it.pkg, it.activity, it.user) },
                        onLaunch = { closePanel(); a.launch(it.pkg, it.activity, it.user) },
                        onAddToHome = { closePanel(); a.addApp(it, layoutPageFor(pager.currentPage) ?: 0) },
                        onClose = { closePanel() },
                        hint = "Long-press an app to add it to this page",
                    )
                    Panel.MEDIA -> Placeholder("Media player")
                    Panel.QUICK_SETTINGS -> Placeholder("Quick settings")
                }
            }
        }

        when (val o = overlay) {
            Overlay.Widgets -> {
                val pm = LocalContext.current.packageManager
                WidgetPicker(
                    providers = remember { a.widgets.providers() },
                    label = { p -> "${p.activityInfo.applicationInfo.loadLabel(pm)} · ${p.loadLabel(pm)}" },
                    onPick = { overlay = null; a.addWidget(it, layoutPageFor(pager.currentPage) ?: 0) },
                    onClose = { overlay = null },
                )
            }
            Overlay.Settings -> SettingsPanel(
                keepAwake = keepAwake,
                onKeepAwake = { keepAwake = it; a.setKeepAwake(it) },
                onSamsungWidgets = { overlay = null; editing = false; a.samsungWidgets() },
                onSetup = { overlay = null; editing = false; a.openSetup() },
                onWallpaper = { overlay = null; a.pickWallpaper() },
                onClose = { overlay = null },
            )
            is Overlay.PickSlot -> AppDrawer(
                apps = apps,
                icon = { a.repo.icon(it.pkg, it.activity, it.user) },
                onLaunch = { overlay = null; a.setQuick(o.slot, it) },
                onAddToHome = { overlay = null; a.setQuick(o.slot, it) },
                onClose = { overlay = null },
                hint = "Tap an app for this shortcut",
            )
            null -> {}
        }

        slotMenu?.let { slot ->
            AlertDialog(
                onDismissRequest = { slotMenu = null },
                text = { Text("Shortcut") },
                confirmButton = { TextButton(onClick = { slotMenu = null; overlay = Overlay.PickSlot(slot) }) { Text("Change") } },
                dismissButton = { TextButton(onClick = { slotMenu = null; a.setQuick(slot, null) }) { Text("Remove") } },
            )
        }
        prompt?.let { p ->
            AlertDialog(
                onDismissRequest = p.cancel,
                text = { Text("This page is full. Add it on a new page?") },
                confirmButton = { TextButton(onClick = p.confirm) { Text("Add page") } },
                dismissButton = { TextButton(onClick = p.cancel) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun Placeholder(title: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = Color.White, fontSize = 20.sp)
        Text("Coming in a later update", color = Color.Gray, fontSize = 12.sp)
    }
}
```

- [ ] **Step 5: `CoverActivity.kt`**

1. Fields: `private var wallpaper by mutableStateOf<ImageBitmap?>(null)` and `private var homeRequests by mutableIntStateOf(0)`.
2. In `onCreate` after `repo = AppRepository(this)`: `wallpaper = Wallpaper.load(this)`.
3. Add to `LauncherActions(...)`:
   ```kotlin
            setQuick = { slot, e -> update(layout.setQuick(slot, e?.let { QuickApp(it.pkg, it.activity, it.user) })) },
            pickWallpaper = ::pickWallpaper,
   ```
4. In `setContent`, replace `LauncherScreen(layout, apps, actions, prompt)` with `Shell(layout, apps, actions, prompt, wallpaper, homeRequests)`.
5. Add:
   ```kotlin
    // Every launch through AppLauncher.start (Home gesture, unlock, fold) lands here: go to Home.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeRequests++
    }

    private fun pickWallpaper() {
        try {
            startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_WALLPAPER, AppLauncher.options())
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open the photo picker", Toast.LENGTH_SHORT).show()
        }
    }
   ```
6. In `onActivityResult`, before the widget line:
   ```kotlin
        if (requestCode == REQ_WALLPAPER) {
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) {
                if (Wallpaper.save(this, uri)) wallpaper = Wallpaper.load(this)
                else Toast.makeText(this, "Couldn't use that photo", Toast.LENGTH_SHORT).show()
            }
            return
        }
   ```
7. Companion: `private const val REQ_WALLPAPER = 21`.
8. Imports: `android.provider.MediaStore`, `android.widget.Toast`, `androidx.compose.ui.graphics.ImageBitmap`, `dev.opencover.home.Wallpaper`, `dev.opencover.layout.QuickApp`, `dev.opencover.layout.setQuick`.

- [ ] **Step 6: Build and run unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, all tests pass.

- [ ] **Step 7: Commit** `Add navigation shell, Home screen and wallpaper`

---

### Task 4: Device check and README

- [ ] **Step 1: Install** with `.\scripts\run.ps1`, then ask the user to check:
  1. Opens on Home (clock, date, 4 empty slots bottom left).
  2. Swipe left: Notifications placeholder. Swipe right: apps pages.
  3. Home swipe up: media placeholder follows the finger; swipe down closes it. Home swipe down: quick settings placeholder.
  4. Apps page swipe up: drawer. Back closes it.
  5. Tap an empty slot, pick an app; tap it launches; long-press offers Change or Remove.
  6. Edit mode > Settings > Set wallpaper: picker opens on the cover; photo shows on Home.
  7. With the drawer or edit mode open on an apps page, swipe Home: lands on a clean Home. Same after lock and unlock.
  8. Remove the apps page you're on: you land on the previous page or Home.

- [ ] **Step 2: README** under "What works", replace the first two bullets with:

```markdown
- Home with wallpaper, clock and 4 shortcuts. Swipe left for notifications, right for apps, up for media, down for quick settings.
- Apps pages: put apps and widgets anywhere on a 4x4 grid. Swipe up for the app drawer.
```

- [ ] **Step 3: Commit** `Update README for the new layout`
