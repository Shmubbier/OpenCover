package dev.opencover.quick

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TilesTest {
    @Test fun missingSaveGivesDefault() = assertEquals(Tile.entries.toList(), parseTiles(null))
    @Test fun emptySaveStaysEmpty() = assertEquals(emptyList<Tile>(), parseTiles(""))
    @Test fun unknownAndDuplicateIdsDropped() =
        assertEquals(listOf(Tile.WIFI, Tile.NFC), parseTiles("WIFI,TELEPORT,NFC,WIFI"))
    @Test fun roundTrip() {
        val t = listOf(Tile.SOUND, Tile.WIFI, Tile.DND)
        assertEquals(t, parseTiles(formatTiles(t)))
    }
    @Test fun tilesNotSavedStayHidden() {
        val shown = parseTiles("WIFI")
        assertEquals(Tile.entries.size - 1, hiddenTiles(shown).size)
        assertFalse(Tile.WIFI in hiddenTiles(shown))
    }
    @Test fun moveForwardAndBack() {
        val t = listOf(Tile.WIFI, Tile.BLUETOOTH, Tile.FLASHLIGHT)
        assertEquals(listOf(Tile.BLUETOOTH, Tile.FLASHLIGHT, Tile.WIFI), t.moved(0, 2))
        assertEquals(listOf(Tile.FLASHLIGHT, Tile.WIFI, Tile.BLUETOOTH), t.moved(2, 0))
    }
    @Test fun dropStaysOnPageAndInRange() {
        assertEquals(5, dropIndex(0, 1, 1, 13))   // one right, one down
        assertEquals(3, dropIndex(0, 9, 0, 13))   // clamped to last column
        assertEquals(8, dropIndex(8, -2, -1, 13)) // page 2 stays on page 2
        assertEquals(12, dropIndex(8, 3, 1, 13))  // past the end lands on the last tile
    }
    @Test fun pages() {
        assertEquals(1, pageCount(0, editing = false, hidden = 13))
        assertEquals(2, pageCount(13, editing = false, hidden = 0))
        assertEquals(3, pageCount(13, editing = true, hidden = 1))
        assertEquals(2, pageCount(13, editing = true, hidden = 0))
    }
    @Test fun ringerCycles() {
        assertEquals(Ringer.VIBRATE, Ringer.RING.next(canMute = true))
        assertEquals(Ringer.MUTE, Ringer.VIBRATE.next(canMute = true))
        assertEquals(Ringer.RING, Ringer.MUTE.next(canMute = true))
    }
    @Test fun ringerSkipsMuteWithoutAccess() = assertEquals(Ringer.RING, Ringer.VIBRATE.next(canMute = false))
    @Test fun noTilesForcesEditMode() {
        assertTrue(inEditMode(editing = false, shown = 0)) // otherwise nothing is left to long-press
        assertFalse(inEditMode(editing = false, shown = 3))
        assertTrue(inEditMode(editing = true, shown = 3))
    }
    @Test fun laterTilesNeverUsable() =
        assertFalse(usable(Need.LATER, Need.entries.toSet()))
    @Test fun usableNeeds() {
        assertTrue(usable(Need.NONE, emptySet()))
        assertFalse(usable(Need.SHIZUKU, setOf(Need.SECURE_SETTINGS)))
        assertTrue(usable(Need.SHIZUKU, setOf(Need.SHIZUKU)))
    }

    @Test fun holdingATileOpensItsSettingsPage() {
        assertEquals("android.settings.TETHER_SETTINGS", settingsAction(Tile.HOTSPOT))
        assertEquals("android.settings.WIFI_SETTINGS", settingsAction(Tile.WIFI))
        assertEquals(null, settingsAction(Tile.MIRROR)) // OpenCover's own: nothing to open
    }
}
