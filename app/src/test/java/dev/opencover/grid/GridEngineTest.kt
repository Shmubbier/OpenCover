package dev.opencover.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridEngineTest {
    private val occ = mapOf("a" to Cell(0, 0, 2, 2))
    private val none = emptyMap<String, Cell>()

    @Test fun placesInFreeSpace() = assertTrue(GridEngine.canPlace(occ, Cell(2, 0, 2, 2)))
    @Test fun rejectsOverlap() = assertFalse(GridEngine.canPlace(occ, Cell(1, 1, 1, 1)))
    @Test fun touchingEdgesIsNotOverlap() = assertTrue(GridEngine.canPlace(occ, Cell(2, 1, 1, 1)))
    @Test fun ignoresItselfWhenMoving() = assertTrue(GridEngine.canPlace(occ, Cell(1, 1, 2, 2), ignoreId = "a"))

    @Test fun rejectsOutOfBounds() {
        assertFalse(GridEngine.canPlace(none, Cell(3, 0, 2, 1)))
        assertFalse(GridEngine.canPlace(none, Cell(0, 3, 1, 2)))
        assertFalse(GridEngine.canPlace(none, Cell(-1, 0, 1, 1)))
        assertFalse(GridEngine.canPlace(none, Cell(0, 0, 0, 1)))
    }

    @Test fun firstFreeSpotIsRowMajor() = assertEquals(Cell(2, 0, 1, 1), GridEngine.firstFreeSpot(occ, 1, 1))
    @Test fun firstFreeSpotFindsRoomForWideItem() = assertEquals(Cell(0, 2, 4, 2), GridEngine.firstFreeSpot(occ, 4, 2))

    @Test fun fullPageHasNoSpot() {
        val full = (0 until 16).associate { "$it" to Cell(it % 4, it / 4, 1, 1) }
        assertNull(GridEngine.firstFreeSpot(full, 1, 1))
    }

    @Test fun tooBigHasNoSpot() = assertNull(GridEngine.firstFreeSpot(none, 5, 1))
    @Test fun cellSpanPrefersTargetCells() = assertEquals(2, cellSpan(targetCells = 2, minDp = 10, cellDp = 88))
    @Test fun cellSpanRoundsMinSizeUp() = assertEquals(2, cellSpan(targetCells = 0, minDp = 89, cellDp = 88))

    @Test fun cellSpanClampsToGrid() {
        assertEquals(4, cellSpan(targetCells = 0, minDp = 1000, cellDp = 88))
        assertEquals(4, cellSpan(targetCells = 6, minDp = 0, cellDp = 88))
        assertEquals(1, cellSpan(targetCells = 0, minDp = 0, cellDp = 88))
    }
}
