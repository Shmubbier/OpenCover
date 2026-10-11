package dev.opencover.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryTest {
    private val d = Dictionary.parse("the\t222\nthere\t180\nthey\t190\nthem\t185\nthermal\t90\nLondon\t150\nI\t200\nhello\t160\n")

    @Test fun mostCommonFirst() = assertEquals(listOf("the", "they", "them"), d.complete("the"))
    @Test fun limit() = assertEquals(listOf("the", "they"), d.complete("th", 2))
    @Test fun capitalizedPrefix() = assertEquals(listOf("The", "They", "Them"), d.complete("The"))
    @Test fun allCapsPrefix() = assertEquals(listOf("THE", "THEY", "THEM"), d.complete("THE"))
    @Test fun keepsProperNounCase() = assertEquals(listOf("London"), d.complete("lon"))
    @Test fun noMatch() = assertEquals(emptyList<String>(), d.complete("xyz"))
    @Test fun emptyPrefix() = assertEquals(emptyList<String>(), d.complete(""))
    @Test fun parseSkipsBadLines() {
        val p = Dictionary.parse("ok\t5\nbroken\nalso\tx\n")
        assertEquals(listOf("ok" to 5), p.all)
    }
}
