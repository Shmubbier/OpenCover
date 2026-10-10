package dev.opencover.grid

/** Cells per row and per column on every page. */
const val GRID = 4

data class Cell(val x: Int, val y: Int, val w: Int, val h: Int) {
    fun overlaps(o: Cell) = x < o.x + o.w && o.x < x + w && y < o.y + o.h && o.y < y + h
    fun inBounds() = x >= 0 && y >= 0 && w >= 1 && h >= 1 && x + w <= GRID && y + h <= GRID
}

object GridEngine {
    /** True if [cell] is on the grid and overlaps nothing in [occupied] except [ignoreId]. */
    fun <K> canPlace(occupied: Map<K, Cell>, cell: Cell, ignoreId: K? = null): Boolean =
        cell.inBounds() && occupied.none { (id, c) -> id != ignoreId && c.overlaps(cell) }

    /** Row-major scan for the first free w×h spot; null if none. */
    fun <K> firstFreeSpot(occupied: Map<K, Cell>, w: Int, h: Int): Cell? {
        for (y in 0..GRID - h) for (x in 0..GRID - w) {
            val c = Cell(x, y, w, h)
            if (canPlace(occupied, c)) return c
        }
        return null
    }
}

/** Cells a widget should span: its target cell count if it declares one, else its min size rounded up. */
fun cellSpan(targetCells: Int, minDp: Int, cellDp: Int): Int =
    (if (targetCells > 0) targetCells else (minDp + cellDp - 1) / cellDp).coerceIn(1, GRID)
