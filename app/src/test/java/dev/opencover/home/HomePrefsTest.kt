package dev.opencover.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomePrefsTest {
    @Test fun defaultOrder() = assertEquals(HomeWidget.entries.toList(), parseOrder(null))
    @Test fun unknownDroppedMissingAppended() =
        assertEquals(listOf(HomeWidget.WEATHER, HomeWidget.BATTERY, HomeWidget.ALARM), parseOrder("WEATHER,SUNSET,BATTERY,WEATHER"))
    @Test fun moveUpAndDown() {
        val l = HomeWidget.entries.toList() // BATTERY, ALARM, WEATHER
        assertEquals(listOf(HomeWidget.BATTERY, HomeWidget.WEATHER, HomeWidget.ALARM), l.movedBy(HomeWidget.WEATHER, -1))
        assertEquals(listOf(HomeWidget.ALARM, HomeWidget.BATTERY, HomeWidget.WEATHER), l.movedBy(HomeWidget.BATTERY, 1))
    }
    @Test fun moveClampsAtEnds() {
        val l = HomeWidget.entries.toList()
        assertEquals(l, l.movedBy(HomeWidget.BATTERY, -1))
        assertEquals(l, l.movedBy(HomeWidget.WEATHER, 1))
    }
}
