package dev.opencover.keyboard

/** Words with frequencies; prefix completion for the suggestion strip. */
class Dictionary(entries: List<Pair<String, Int>>) {
    /** Sorted by lowercase word, for prefix ranges. */
    val all: List<Pair<String, Int>> = entries.sortedBy { it.first.lowercase() }
    private val keys = all.map { it.first.lowercase() }

    /** Up to [n] words starting with [prefix] (any case), most common first, cased like [prefix]. */
    fun complete(prefix: String, n: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val p = prefix.lowercase()
        var i = keys.binarySearch(p).let { if (it < 0) -it - 1 else it }
        while (i > 0 && keys[i - 1].startsWith(p)) i-- // equal keys may sit before the hit
        val out = ArrayList<Pair<String, Int>>()
        while (i < keys.size && keys[i].startsWith(p)) out += all[i++]
        return out.sortedByDescending { it.second }.take(n).map { matchCase(it.first, prefix) }
    }

    companion object {
        /** "word<TAB>frequency" lines; anything else is skipped. */
        fun parse(text: String) = Dictionary(text.lineSequence().mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 2) null else parts[1].toIntOrNull()?.let { parts[0] to it }
        }.toList())

        fun matchCase(word: String, typed: String) = when {
            typed.length > 1 && typed.all { !it.isLetter() || it.isUpperCase() } -> word.uppercase()
            typed.first().isUpperCase() -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}
