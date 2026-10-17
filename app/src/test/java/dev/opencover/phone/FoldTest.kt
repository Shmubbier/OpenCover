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
        assertTrue(FOLD_WATCH_TAG in FOLD_WATCH_SCRIPT)
    }

    private val stopPatterns = Regex("pkill -f '([^']+)'").findAll(FOLD_WATCH_STOP).map { Regex(it.groupValues[1]) }.toList()

    @Test fun stopFindsOursAndFreeFlipsWatcher() {
        assertTrue(stopPatterns.any { it.containsMatchIn(FOLD_WATCH_SCRIPT) })
        assertTrue(stopPatterns.any { it.containsMatchIn("sh -c logcat ... done freeflip-fold-watch") })
    }

    // pkill -f also sees the shell running the stop command; matching it would kill that shell before the next pkill.
    @Test fun stopDoesNotMatchItself() = assertTrue(stopPatterns.none { it.containsMatchIn(FOLD_WATCH_STOP) })
}
