package dev.opencover.keyboard

/** A text field's text and selection ([start] <= [end]). Pure, so every edit is unit tested. */
data class EditState(val text: String, val start: Int, val end: Int) {
    init {
        require(start in 0..end && end <= text.length)
    }

    private fun at(t: String, c: Int) = EditState(t, c, c)

    fun insert(s: String) = at(text.substring(0, start) + s + text.substring(end), start + s.length)

    fun backspace(): EditState {
        if (start != end) return insert("")
        if (start == 0) return this
        val from = Character.offsetByCodePoints(text, start, -1) // a whole emoji, not half
        return at(text.removeRange(from, start), from)
    }

    /** Deletes the spaces before the cursor, then the word before them. */
    fun deleteWord(): EditState {
        if (start != end) return insert("")
        var i = start
        while (i > 0 && text[i - 1].isWhitespace()) i--
        while (i > 0 && !text[i - 1].isWhitespace()) i--
        return at(text.removeRange(i, start), i)
    }

    /** Collapses any selection toward [by]'s direction, then moves; clamped to the text. */
    fun moveCursor(by: Int) = at(text, ((if (by < 0) start else end) + by).coerceIn(0, text.length))

    /** Characters a suggestion replaces: the swiped word if the cursor is still right after it, else the current word. */
    fun replaceCount(swiped: String?) =
        if (swiped != null && text.substring(0, start).endsWith(swiped)) swiped.length else currentWord().length

    /** Letters and apostrophes just before the cursor. */
    fun currentWord() = text.substring(0, start).takeLastWhile { it.isLetter() || it == '\'' }
}
