package dev.opencover.layout

import dev.opencover.apps.AppEntry
import dev.opencover.grid.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutTest {
    private fun app(id: String, cell: Cell, pkg: String = "p.$id", user: Long = 0) = AppItem(id, cell, pkg, "$pkg.Main", user)
    private val clock = WidgetItem("w", Cell(0, 2, 4, 2), widgetId = 7, provider = "com.clock/.Widget")
    private val base = Layout(listOf(Page(listOf(app("a", Cell(0, 0, 1, 1)), app("b", Cell(1, 0, 1, 1)), clock))))

    @Test fun moveToFreeCell() {
        val moved = base.moveItem(0, "a", 3, 0)!!
        assertEquals(Cell(3, 0, 1, 1), moved.pages[0].items.first { it.id == "a" }.cell)
    }

    @Test fun moveOntoOccupiedIsRejected() = assertNull(base.moveItem(0, "a", 1, 0))
    @Test fun moveOffGridIsRejected() = assertNull(base.moveItem(0, "a", 4, 0))
    @Test fun moveUnknownIdIsRejected() = assertNull(base.moveItem(0, "zzz", 2, 0))

    @Test fun resizeWidgetIntoFreeSpace() {
        val r = base.resizeItem(0, "w", 4, 1)!!
        assertEquals(Cell(0, 2, 4, 1), r.pages[0].items.first { it.id == "w" }.cell)
    }

    @Test fun resizeIntoNeighbourIsRejected() = assertNull(base.resizeItem(0, "a", 2, 1))

    @Test fun addItemUsesFirstFreeSpot() {
        val l = base.addItem(0, 1, 1) { app("c", it) }!!
        assertEquals(Cell(2, 0, 1, 1), l.pages[0].items.last().cell)
    }

    @Test fun addItemReturnsNullWhenPageFull() {
        val full = Layout(listOf(Page((0 until 16).map { app("$it", Cell(it % 4, it / 4, 1, 1)) })))
        assertNull(full.addItem(0, 1, 1) { app("x", it) })
    }

    @Test fun addPageThenAddFits() {
        val full = Layout(listOf(Page((0 until 16).map { app("$it", Cell(it % 4, it / 4, 1, 1)) })))
        val withPage = full.addPage()
        val l = withPage.addItem(withPage.pages.lastIndex, 2, 2) { app("x", it) }
        assertNotNull(l)
        assertEquals(2, l!!.pages.size)
    }

    @Test fun removePageOnlyWhenEmptyAndNotLast() {
        assertNull(base.removePage(0))                 // last page stays
        val two = base.addPage()
        assertNull(two.removePage(0))                  // not empty
        assertEquals(1, two.removePage(1)!!.pages.size)
    }

    @Test fun removeItem() = assertEquals(2, base.removeItem("a").pages[0].items.size)

    @Test fun removePackageDropsWidgetsAndReturnsIds() {
        val (l, freed) = base.removePackage("com.clock", user = 0)
        assertEquals(listOf(7), freed)
        assertTrue(l.pages[0].items.none { it.id == "w" })
    }

    @Test fun removePackageOnlyAffectsThatUser() {
        val dual = Layout(listOf(Page(listOf(
            app("main", Cell(0, 0, 1, 1), pkg = "chat", user = 0),
            app("dual", Cell(1, 0, 1, 1), pkg = "chat", user = 95),
        ))))
        val (l, _) = dual.removePackage("chat", user = 95)
        assertEquals(listOf("main"), l.pages[0].items.map { it.id })
    }

    @Test fun removePackageKeepsOtherProfilesWidget() {
        val two = Layout(listOf(Page(listOf(
            WidgetItem("mine", Cell(0, 0, 2, 2), 1, "chat/.W", user = 0),
            WidgetItem("dual", Cell(2, 0, 2, 2), 2, "chat/.W", user = 95),
        ))))
        val (l, freed) = two.removePackage("chat", user = 95)
        assertEquals(listOf("mine"), l.pages[0].items.map { it.id })
        assertEquals(listOf(2), freed)
    }

    @Test fun pruneMissingDropsUninstalledAndDeadWidgets() {
        val keys = setOf(appKey("p.a", "p.a.Main", 0))
        val (l, freed) = base.pruneMissing(keys, userReady = { true }) { false }
        assertEquals(listOf("a"), l.pages[0].items.map { it.id })
        assertEquals(listOf(7), freed)
    }

    @Test fun pruneMissingSkipsWhenNoAppsKnown() {
        val (l, freed) = base.pruneMissing(emptySet(), userReady = { true }) { false }
        assertEquals(base, l)
        assertTrue(freed.isEmpty())
    }

    @Test fun pruneMissingKeepsIconsOfProfilesNotReady() {
        val work = Layout(listOf(Page(listOf(
            app("main", Cell(0, 0, 1, 1), pkg = "gone", user = 0),
            app("work", Cell(1, 0, 1, 1), pkg = "mail", user = 10),
        ))))
        val (l, _) = work.pruneMissing(setOf("x"), userReady = { it == 0L }) { true }
        assertEquals(listOf("work"), l.pages[0].items.map { it.id })
    }

    @Test fun defaultLayoutTakesEightMostRecent() {
        val apps = (1..10).map { AppEntry("App$it", "p$it", "p$it.Main", 0, lastUsed = it.toLong()) }
        val items = defaultLayout(apps).pages.single().items.map { it as AppItem }
        assertEquals(8, items.size)
        assertEquals("p10", items[0].pkg)
        assertEquals(Cell(3, 1, 1, 1), items[7].cell)
    }

    @Test fun moveOrSwapMovesIntoFreeCell() =
        assertEquals(Cell(3, 0, 1, 1), base.moveOrSwap(0, "a", 3, 0)!!.pages[0].items.first { it.id == "a" }.cell)

    @Test fun moveOrSwapSwapsSameSizeItems() {
        val l = base.moveOrSwap(0, "a", 1, 0)!!
        assertEquals(Cell(1, 0, 1, 1), l.pages[0].items.first { it.id == "a" }.cell)
        assertEquals(Cell(0, 0, 1, 1), l.pages[0].items.first { it.id == "b" }.cell)
    }

    @Test fun moveOrSwapRejectsDifferentSizes() = assertNull(base.moveOrSwap(0, "a", 0, 2))

    @Test fun jsonRoundTrip() = assertEquals(base, LayoutJson.decode(LayoutJson.encode(base)))
}
