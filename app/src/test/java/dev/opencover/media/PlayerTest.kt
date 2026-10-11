package dev.opencover.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerTest {
    private fun p(id: String, playing: Boolean = true, at: Long = 0, pos: Long = 1000, dur: Long = 10_000, speed: Float = 1f) =
        Player(id, "pkg", "t", "a", dur, pos, at, speed, playing, null, null, true, true, true)

    @Test fun playingPositionAdvances() = assertEquals(3000, livePosition(p("a", at = 100), now = 2100))
    @Test fun pausedPositionHolds() = assertEquals(1000, livePosition(p("a", playing = false, at = 100), now = 5100))
    @Test fun speedScalesPosition() = assertEquals(5000, livePosition(p("a", speed = 2f), now = 2000))
    @Test fun positionClampsToDuration() = assertEquals(10_000, livePosition(p("a"), now = 60_000))

    @Test fun defaultPrefersPlaying() =
        assertEquals("b", defaultPlayer(listOf(p("a", playing = false, at = 9), p("b", playing = true, at = 1)))?.id)

    @Test fun defaultTieGoesToMostRecent() =
        assertEquals("b", defaultPlayer(listOf(p("a", playing = false, at = 1), p("b", playing = false, at = 2)))?.id)

    @Test fun noPlayersNoDefault() = assertNull(defaultPlayer(emptyList()))

    @Test fun repeatCycles() {
        assertEquals(Repeat.ALL, Repeat.OFF.next())
        assertEquals(Repeat.ONE, Repeat.ALL.next())
        assertEquals(Repeat.OFF, Repeat.ONE.next())
    }

    @Test fun seekTopIsStartBottomIsEnd() {
        assertEquals(0L, seekTarget(0f, 10_000))
        assertEquals(10_000L, seekTarget(1f, 10_000))
        assertEquals(10_000L, seekTarget(1.5f, 10_000))
    }

    @Test fun seekUnknownDurationIsNull() = assertNull(seekTarget(0.5f, 0))

    @Test fun volumeTopIsLoudest() {
        assertEquals(15, volumeFor(0f, 15))
        assertEquals(0, volumeFor(1f, 15))
    }

    @Test fun timeFormat() {
        assertEquals("0:03", formatTime(3_400))
        assertEquals("3:43", formatTime(223_000))
    }
}
