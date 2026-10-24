package dev.opencover.mirror

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TouchLineTest {
    @Test fun roundTrip() {
        val t = parseTouchLine(touchLine('P', 1, listOf(Triple(0, 10f, 20f), Triple(3, 30.5f, 40f))))!!
        assertEquals(5 or (1 shl 8), t.action) // ACTION_POINTER_DOWN for the second finger
        assertArrayEquals(intArrayOf(0, 3), t.ids)
        assertArrayEquals(floatArrayOf(10f, 30.5f), t.xs, 0f)
        assertEquals(0, parseTouchLine("D 0 0:1:2")!!.action)
    }

    @Test fun rejectsJunk() {
        assertNull(parseTouchLine("D 0"))
        assertNull(parseTouchLine("X 0 0:1:2"))
        assertNull(parseTouchLine("D 2 0:1:2")) // no third finger
        assertNull(parseTouchLine("D 0 0:a:2"))
    }
}
