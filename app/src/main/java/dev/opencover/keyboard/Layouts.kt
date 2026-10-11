package dev.opencover.keyboard

enum class KeyKind { CHAR, SHIFT, BACKSPACE, SPACE, ENTER, PASTE, LETTERS, SYMBOLS, MORE_SYMBOLS }

/**
 * [popup] opens on long-press (its first entry is also the hint drawn on the key);
 * [hold] is a long-press action instead (paste on comma). [width] in key widths.
 */
data class Key(val label: String, val kind: KeyKind = KeyKind.CHAR, val popup: List<String> = emptyList(), val width: Float = 1f, val hold: KeyKind? = null)

/** Pages of rows; every row is at most 10 key widths and is centered. */
object Layouts {
    private val accents = mapOf(
        'a' to "àáâäãåæ", 'c' to "ç", 'e' to "éèêëē", 'i' to "íìîï", 'n' to "ñ",
        'o' to "óòôöõøœ", 's' to "ß", 'u' to "úùûü", 'y' to "ýÿ",
    )
    private fun row(chars: String, alts: String = "") = chars.mapIndexed { i, c ->
        Key(c.toString(), popup = listOfNotNull(alts.getOrNull(i)?.toString()) + accents[c].orEmpty().map { it.toString() })
    }
    private val specials = listOf(",", "?", "!", "'", "\"", ":", ";", "-", "@", "#", "&", "(", ")", "/", "…", "_")
    private val backspace = Key("⌫", KeyKind.BACKSPACE, width = 1.5f)
    private fun bottom(left: Key) = listOf(
        left, Key(",", hold = KeyKind.PASTE), Key("space", KeyKind.SPACE, width = 5f), Key(".", popup = specials), Key("↵", KeyKind.ENTER, width = 1.5f),
    )

    val letters = listOf(
        row("qwertyuiop", "1234567890"),
        row("asdfghjkl", "@#&*-+()'"),
        listOf(Key("⇧", KeyKind.SHIFT, width = 1.5f)) + row("zxcvbnm", "!\":;/?_") + backspace,
        bottom(Key("123", KeyKind.SYMBOLS, width = 1.5f)),
    )
    val symbols = listOf(
        row("1234567890"),
        row("@#$&-+()/"),
        listOf(Key("=\\<", KeyKind.MORE_SYMBOLS, width = 1.5f)) + row("*\"':;!") + Key("Paste", KeyKind.PASTE) + backspace,
        bottom(Key("ABC", KeyKind.LETTERS, width = 1.5f)),
    )
    val moreSymbols = listOf(
        row("~`|•√π÷×¶∆"),
        row("£€¥^°={}\\"),
        listOf(Key("?123", KeyKind.SYMBOLS, width = 1.5f)) + row("%©®™✓[]") + backspace,
        bottom(Key("ABC", KeyKind.LETTERS, width = 1.5f)),
    )
}
