package dev.opencover.keyboard

import org.junit.Assert.assertEquals
import org.junit.Test

class EditStateTest {
    private fun at(text: String, cursor: Int = text.length) = EditState(text, cursor, cursor)

    @Test fun insertAtCursor() = assertEquals(at("heXllo", 3), at("hello", 2).insert("X"))
    @Test fun insertReplacesSelection() = assertEquals(at("hXo", 2), EditState("hello", 1, 4).insert("X"))
    @Test fun backspaceDeletesCharBefore() = assertEquals(at("helo", 3), at("hello", 4).backspace())
    @Test fun backspaceAtStartIsNoOp() = assertEquals(at("hi", 0), at("hi", 0).backspace())
    @Test fun backspaceDeletesSelection() = assertEquals(at("ho", 1), EditState("hello", 1, 4).backspace())
    @Test fun backspaceRemovesWholeSurrogatePair() = assertEquals(at("a"), at("a😀").backspace())
    @Test fun deleteWordTakesWordAndTrailingSpaces() = assertEquals(at("hello "), at("hello world  ").deleteWord())
    @Test fun moveCursorClamps() {
        assertEquals(at("abc", 0), at("abc", 1).moveCursor(-5))
        assertEquals(at("abc", 3), at("abc", 1).moveCursor(9))
    }
    @Test fun moveCollapsesSelection() = assertEquals(at("hello", 0), EditState("hello", 1, 4).moveCursor(-1))
    @Test fun currentWord() {
        assertEquals("wor", at("hello wor").currentWord())
        assertEquals("", at("hello ").currentWord())
        assertEquals("don't", at("I don't").currentWord())
    }
}
