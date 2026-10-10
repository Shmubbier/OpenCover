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
