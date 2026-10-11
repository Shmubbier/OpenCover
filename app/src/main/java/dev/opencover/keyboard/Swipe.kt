package dev.opencover.keyboard

import kotlin.math.hypot
import kotlin.math.ln

data class Pt(val x: Float, val y: Float)

/** Swipe typing: compare the finger's trail with each word's path through the key centers. */
object Swipe {
    private const val N = 32

    /** [n] points evenly spaced along [path]. */
    fun resample(path: List<Pt>, n: Int = N): List<Pt> {
        val seg = path.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }
        val total = seg.sum()
        if (seg.isEmpty() || total == 0f) return List(n) { path.first() }
        val out = ArrayList<Pt>(n)
        var i = 0
        var walked = 0f
        for (k in 0 until n) {
            val target = total * k / (n - 1)
            while (i < seg.size - 1 && walked + seg[i] < target) walked += seg[i++]
            val t = if (seg[i] == 0f) 0f else ((target - walked) / seg[i]).coerceIn(0f, 1f)
            val a = path[i]
            val b = path[i + 1]
            out += Pt(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        }
        return out
    }

    // ponytail: linear scan of the word list per swipe; index by (first, last) letter if it feels slow.
    /** Up to [n] words for [trail] over letter keys at [centers] ([keyWidth] px), best first. */
    fun rank(trail: List<Pt>, centers: Map<Char, Pt>, keyWidth: Float, dict: List<Pair<String, Int>>, n: Int = 3): List<String> {
        if (trail.size < 2 || centers.isEmpty() || dict.isEmpty()) return emptyList()
        fun near(p: Pt) = centers.filterValues { hypot(it.x - p.x, it.y - p.y) <= keyWidth }.keys
        val starts = near(trail.first())
        val ends = near(trail.last())
        val t = resample(trail)
        val maxF = ln(dict.maxOf { it.second } + 1.0)
        return dict.asSequence()
            .filter { (w, _) ->
                val l = w.lowercase()
                l.length >= 2 && l.first() in starts && l.last() in ends && l.all { it in centers }
            }
            .map { (w, f) ->
                val l = w.lowercase()
                // Shape, plus how far the trail starts and ends from the word's first and last keys.
                val ends = (dist(trail.first(), centers.getValue(l.first())) + dist(trail.last(), centers.getValue(l.last()))) / keyWidth
                w to cost(t, path(l, centers), keyWidth) + 0.5 * ends + 0.3 * (1 - ln(f + 1.0) / maxF)
            }
            .sortedBy { it.second }
            .map { it.first }
            .distinctBy { it.lowercase() }
            .take(n)
            .toList()
    }

    /** The word's ideal trail: key centers in order, repeated letters once. */
    private fun path(word: String, centers: Map<Char, Pt>): List<Pt> {
        val keys = ArrayList<Char>()
        word.forEach { if (keys.lastOrNull() != it) keys += it }
        return resample(keys.map { centers.getValue(it) })
    }

    private fun dist(a: Pt, b: Pt) = hypot(a.x - b.x, a.y - b.y).toDouble()

    /** Mean distance between matching points, in key widths. */
    private fun cost(a: List<Pt>, b: List<Pt>, keyWidth: Float) =
        a.indices.sumOf { hypot(a[it].x - b[it].x, a[it].y - b[it].y).toDouble() } / a.size / keyWidth
}
