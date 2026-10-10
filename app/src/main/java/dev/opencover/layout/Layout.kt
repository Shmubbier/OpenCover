package dev.opencover.layout

import dev.opencover.apps.AppEntry
import dev.opencover.grid.Cell
import dev.opencover.grid.GRID
import dev.opencover.grid.GridEngine
import java.util.UUID

sealed interface Item {
    val id: String
    val cell: Cell
}

data class AppItem(
    override val id: String,
    override val cell: Cell,
    val pkg: String,
    val activity: String,
    val user: Long,
) : Item {
    val key get() = appKey(pkg, activity, user)
}

data class WidgetItem(
    override val id: String,
    override val cell: Cell,
    val widgetId: Int,
    val provider: String,
    /** Serial number of the provider's profile; layouts saved before this field load as 0 (main user). */
    val user: Long = 0,
) : Item {
    val providerPkg get() = provider.substringBefore('/')
}

data class Page(val items: List<Item>)
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

fun appKey(pkg: String, activity: String, user: Long) = "$user/$pkg/$activity"
fun newId() = UUID.randomUUID().toString()

fun Item.withCell(c: Cell): Item = when (this) {
    is AppItem -> copy(cell = c)
    is WidgetItem -> copy(cell = c)
}

fun Page.occupied(): Map<String, Cell> = items.associate { it.id to it.cell }

private fun Layout.withPage(i: Int, p: Page) = copy(pages = pages.toMutableList().also { it[i] = p })

private fun Layout.reshape(page: Int, id: String, f: (Cell) -> Cell): Layout? {
    val p = pages.getOrNull(page) ?: return null
    val item = p.items.find { it.id == id } ?: return null
    val target = f(item.cell)
    if (!GridEngine.canPlace(p.occupied(), target, ignoreId = id)) return null
    return withPage(page, p.copy(items = p.items.map { if (it.id == id) it.withCell(target) else it }))
}

/** Null means rejected (off the grid or overlapping); keep the old layout. */
fun Layout.moveItem(page: Int, id: String, x: Int, y: Int) = reshape(page, id) { it.copy(x = x, y = y) }
fun Layout.resizeItem(page: Int, id: String, w: Int, h: Int) = reshape(page, id) { it.copy(w = w, h = h) }

/** Drag-and-drop: move into free space, or swap with a same-sized item sitting exactly at (x, y). */
fun Layout.moveOrSwap(page: Int, id: String, x: Int, y: Int): Layout? {
    moveItem(page, id, x, y)?.let { return it }
    val p = pages.getOrNull(page) ?: return null
    val moving = p.items.find { it.id == id } ?: return null
    val other = p.items.find { it.id != id && it.cell.x == x && it.cell.y == y } ?: return null
    if (other.cell.w != moving.cell.w || other.cell.h != moving.cell.h) return null
    return withPage(page, p.copy(items = p.items.map {
        when (it.id) {
            id -> it.withCell(other.cell)
            other.id -> it.withCell(moving.cell)
            else -> it
        }
    }))
}

/** Places a w×h item at the first free spot on [page]; null if the page has no room. */
fun Layout.addItem(page: Int, w: Int, h: Int, make: (Cell) -> Item): Layout? {
    val p = pages.getOrNull(page) ?: return null
    val spot = GridEngine.firstFreeSpot(p.occupied(), w, h) ?: return null
    return withPage(page, p.copy(items = p.items + make(spot)))
}

fun Layout.addPage() = copy(pages = pages + Page(emptyList()))

/** Only an empty page can be removed, and the last page always stays. */
fun Layout.removePage(page: Int): Layout? =
    if (pages.size > 1 && pages.getOrNull(page)?.items?.isEmpty() == true) {
        copy(pages = pages.filterIndexed { i, _ -> i != page })
    } else null

fun Layout.removeItem(id: String) = copy(pages = pages.map { p -> p.copy(items = p.items.filter { it.id != id }) })

/** Keeps items matching [keep]. Returns the new layout and the widget ids of dropped widgets. */
fun Layout.retain(keep: (Item) -> Boolean): Pair<Layout, List<Int>> {
    val dropped = pages.flatMap { it.items }.filterNot(keep)
    val ids = dropped.map { it.id }.toSet()
    val l = copy(pages = pages.map { p -> p.copy(items = p.items.filter { it.id !in ids }) })
    return l to dropped.filterIsInstance<WidgetItem>().map { it.widgetId }
}

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

/** First-run layout: one page with the 8 most recently used apps. */
fun defaultLayout(apps: List<AppEntry>): Layout {
    val top = apps.sortedByDescending { it.lastUsed }.take(2 * GRID)
    val items = top.mapIndexed { i, a -> AppItem(newId(), Cell(i % GRID, i / GRID, 1, 1), a.pkg, a.activity, a.user) }
    return Layout(listOf(Page(items)))
}
