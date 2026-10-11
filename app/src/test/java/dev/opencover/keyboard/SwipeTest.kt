package dev.opencover.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class SwipeTest {
    // QWERTY with 10-unit keys, like the keyboard's letter rows.
    private val centers = buildMap {
        "qwertyuiop".forEachIndexed { i, c -> put(c, Pt(i * 10f + 5, 5f)) }
        "asdfghjkl".forEachIndexed { i, c -> put(c, Pt(i * 10f + 10, 15f)) }
        "zxcvbnm".forEachIndexed { i, c -> put(c, Pt(i * 10f + 20, 25f)) }
    }
    private val dict = listOf(
        "hello" to 160, "hell" to 120, "help" to 150, "jello" to 40, "hollow" to 60, "hero" to 90,
        "the" to 222, "tie" to 100, "toe" to 80, "three" to 150, "them" to 185,
        "keyboard" to 60, "keyboards" to 30, "key" to 140,
    )

    /** A finger trail through [word]'s keys, wobbling 3 units either side. */
    private fun trail(word: String): List<Pt> {
        val keys = word.map { centers.getValue(it) }
        val out = ArrayList<Pt>()
        keys.zipWithNext().forEach { (a, b) ->
            for (s in 0 until 8) {
                val t = s / 8f
                val wob = if (s % 2 == 0) 3f else -3f
                out += Pt(a.x + (b.x - a.x) * t + wob, a.y + (b.y - a.y) * t - wob)
            }
        }
        out += keys.last()
        return out
    }

    @Test fun resampleKeepsEnds() {
        val r = Swipe.resample(listOf(Pt(0f, 0f), Pt(10f, 0f), Pt(10f, 10f)), 5)
        assertEquals(5, r.size)
        assertEquals(Pt(0f, 0f), r.first())
        assertEquals(Pt(10f, 10f), r.last())
    }
    @Test fun hello() = assertEquals("hello", Swipe.rank(trail("hello"), centers, 10f, dict).first())
    @Test fun the() = assertEquals("the", Swipe.rank(trail("the"), centers, 10f, dict).first())
    @Test fun keyboard() = assertEquals("keyboard", Swipe.rank(trail("keyboard"), centers, 10f, dict).first())
    @Test fun alternatives() = assertEquals(3, Swipe.rank(trail("hello"), centers, 10f, dict).size)
    @Test fun tooShort() = assertEquals(emptyList<String>(), Swipe.rank(listOf(Pt(0f, 0f)), centers, 10f, dict))
}
