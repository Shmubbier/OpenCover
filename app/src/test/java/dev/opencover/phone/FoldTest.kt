package dev.opencover.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FoldTest {
    @Test fun keepingTheCoverOnHoldsTent() =
        assertEquals(listOf("cmd", "device_state", "state", "1"), foldStateArgs(true))

    @Test fun turningItOffGivesTheStateBack() =
        assertEquals(listOf("cmd", "device_state", "state", "reset"), foldStateArgs(false))

    @Test fun watcherReassertsOnlyOnTheSensorStates() {
        assertTrue("identifier=0" in FOLD_WATCH_SCRIPT && "identifier=3" in FOLD_WATCH_SCRIPT)
        assertTrue("identifier=1" !in FOLD_WATCH_SCRIPT) // its own commit must not loop
        assertTrue(FOLD_WATCH_TAG in FOLD_WATCH_SCRIPT && FOLD_WATCH_TAG in FOLD_WATCH_STOP)
        assertTrue("freeflip-fold-watch" in FOLD_WATCH_STOP) // FreeFlip's old watcher must not hold the state too
    }
}
