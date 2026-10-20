package dev.opencover.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class RadioMaskTest {
    // The phone's own types without 5G, as checked on the Flip 5 (cmd phone takes it as binary).
    @Test fun maskMatchesThePhone() {
        val types = listOf(1, 2, 3, 8, 9, 10, 13, 15, 16, 19)
        assertEquals(316295L, types.fold(0L) { m, t -> m or (1L shl (t - 1)) })
        assertEquals(840583L, (types + 20).fold(0L) { m, t -> m or (1L shl (t - 1)) })
    }
}
