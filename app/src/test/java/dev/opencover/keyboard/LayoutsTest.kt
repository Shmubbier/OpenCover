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
}
