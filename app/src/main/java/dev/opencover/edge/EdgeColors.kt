package dev.opencover.edge

import kotlin.math.atan2
import kotlin.math.hypot

/** "#RRGGBB" or "RRGGBB" to an opaque colour; null if it isn't six hex digits. */
fun parseHex(s: String): Int? {
    val h = s.trim().removePrefix("#")
    if (h.length != 6) return null
    return h.toIntOrNull(16)?.let { it or (0xFF shl 24) }
}

fun formatHex(color: Int): String = "#%06X".format(color and 0xFFFFFF)

/** Per-app glow colours as saved: "pkg=RRGGBB;pkg=RRGGBB". Broken entries are dropped. */
fun parsePerApp(s: String?): Map<String, Int> = s.orEmpty().split(';').mapNotNull { e ->
    val (pkg, hex) = e.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotEmpty() } ?: return@mapNotNull null
    parseHex(hex)?.let { pkg to it }
}.toMap()

fun formatPerApp(m: Map<String, Int>): String = m.entries.joinToString(";") { (k, v) -> "$k=${formatHex(v).drop(1)}" }

/** The glow colour for [pkg]: its own colour, else the custom colour, else the colour of its icon. */
fun glowColor(pkg: String, perApp: Map<String, Int>, custom: Int?, iconColor: () -> Int): Int =
    perApp[pkg] ?: custom ?: iconColor()

/** Hue (0..360, 0 at the right, counter-clockwise) and saturation (0..1) at an offset from the wheel's centre. */
fun wheelHueSat(dx: Float, dy: Float, radius: Float): Pair<Float, Float> {
    val hue = ((Math.toDegrees(atan2(-dy, dx).toDouble()) + 360) % 360).toFloat()
    return hue to (hypot(dx, dy) / radius).coerceIn(0f, 1f)
}
