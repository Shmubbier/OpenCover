package dev.opencover.lock

/** What the AOD and lock screen can show. Ids are saved, so never rename one. */
enum class Module { CLOCK, DATE, BATTERY, ICONS, NOW_PLAYING }

/** A module's top-left corner as fractions of the cover (0..1), and whether it shows. */
data class Spot(val x: Float, val y: Float, val on: Boolean)

val DEFAULT_LAYOUT: Map<Module, Spot> = mapOf(
    Module.CLOCK to Spot(0.05f, 0.06f, true),
    Module.DATE to Spot(0.05f, 0.66f, false), // most clock styles already show the date
    Module.BATTERY to Spot(0.05f, 0.50f, true),
    Module.ICONS to Spot(0.05f, 0.58f, true),
    Module.NOW_PLAYING to Spot(0.03f, 0.82f, true),
)

/** "CLOCK:0.05,0.06,1;DATE:..." Unknown or broken entries are dropped; missing modules keep their defaults. */
fun parseLayout(s: String?): Map<Module, Spot> {
    val saved = s.orEmpty().split(';').mapNotNull { entry ->
        val (id, rest) = entry.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val module = Module.entries.find { it.name == id } ?: return@mapNotNull null
        val v = rest.split(',')
        val x = v.getOrNull(0)?.toFloatOrNull()
        val y = v.getOrNull(1)?.toFloatOrNull()
        if (x == null || y == null || v.size != 3) null else module to Spot(x, y, v[2] == "1")
    }.toMap()
    return DEFAULT_LAYOUT + saved
}

fun formatLayout(m: Map<Module, Spot>): String =
    m.entries.joinToString(";") { (k, s) -> "${k.name}:${s.x},${s.y},${if (s.on) 1 else 0}" }

// Camera cutout, bottom right of the 748x720 cover: Rect(379, 654 - 748, 720).
private const val CUT_X = 379 / 748f
private const val CUT_Y = 654 / 720f

/** Keeps a module of size [w]x[h] (fractions) on screen, and lifts it above the camera cutout if it overlaps it. */
fun clamp(s: Spot, w: Float, h: Float): Spot {
    val x = s.x.coerceIn(0f, (1f - w).coerceAtLeast(0f))
    var y = s.y.coerceIn(0f, (1f - h).coerceAtLeast(0f))
    if (x + w > CUT_X && y + h > CUT_Y) y = (CUT_Y - h).coerceAtLeast(0f)
    return s.copy(x = x, y = y)
}
