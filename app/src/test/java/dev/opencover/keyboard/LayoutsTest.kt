package dev.opencover.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutsTest {
    private val pages = listOf(Layouts.letters, Layouts.symbols, Layouts.moreSymbols)

    @Test fun rowsFitTenKeyWidths() = pages.flatten().forEach { row -> assertTrue(row.sumOf { it.width.toDouble() } <= 10.0) }
    @Test fun lettersHaveAllLetters() = assertEquals(
        ('a'..'z').toSet(),
        Layouts.letters.flatten().filter { it.kind == KeyKind.CHAR && it.label[0].isLetter() }.map { it.label[0] }.toSet(),
    )

    private fun key(label: String) = Layouts.letters.flatten().first { it.label == label }

    @Test fun popupStartsWithOldAlternate() {
        val alts = mapOf("q" to "1", "p" to "0", "a" to "@", "l" to "'", "z" to "!", "m" to "_")
        alts.forEach { (k, alt) -> assertEquals(alt, key(k).popup.first()) }
    }
    @Test fun vowelsOfferAccents() = assertTrue("é" in key("e").popup && "ñ" in key("n").popup)
    @Test fun periodOffersSpecials() = assertEquals(listOf(",", "?", "!", "'"), key(".").popup.take(4))
}
