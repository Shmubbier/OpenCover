package dev.opencover.lock

/** What the AOD and lock screen can show. Ids are saved, so never rename one. */
enum class Module { CLOCK, DATE, BATTERY, ICONS, NOW_PLAYING }

/** A module's top-left corner as fractions of the cover (0..1), whether it shows, and its size (1 = normal). */
data class Spot(val x: Float, val y: Float, val on: Boolean, val scale: Float = 1f)

const val MIN_SCALE = 0.5f
const val MAX_SCALE = 2.5f

/** [s] after a pinch by [zoom], kept between [MIN_SCALE] and [MAX_SCALE]. */
fun resized(s: Spot, zoom: Float): Spot = s.copy(scale = (s.scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE))

val DEFAULT_LAYOUT: Map<Module, Spot> = mapOf(
    Module.CLOCK to Spot(0.05f, 0.06f, true),
    Module.DATE to Spot(0.05f, 0.66f, false), // most clock styles already show the date
    Module.BATTERY to Spot(0.05f, 0.50f, true),
    Module.ICONS to Spot(0.05f, 0.58f, true),
    Module.NOW_PLAYING to Spot(0.03f, 0.78f, true), // clear of the camera cutout
)

/** "CLOCK:0.05,0.06,1,1.0;DATE:..." (size optional). Unknown or broken entries are dropped; missing modules keep their defaults. */
fun parseLayout(s: String?): Map<Module, Spot> {
    val saved = s.orEmpty().split(';').mapNotNull { entry ->
        val (id, rest) = entry.split(':', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val module = Module.entries.find { it.name == id } ?: return@mapNotNull null
        val v = rest.split(',')
        val x = v.getOrNull(0)?.toFloatOrNull()
        val y = v.getOrNull(1)?.toFloatOrNull()
        val ok = x != null && y != null && x in 0f..1f && y in 0f..1f // NaN and Infinity fail the range check
        val scale = if (v.size == 4) v[3].toFloatOrNull()?.takeIf { it in MIN_SCALE..MAX_SCALE } else 1f
        if (!ok || v.size !in 3..4 || scale == null) null else module to Spot(x!!, y!!, v[2] == "1", scale)
    }.toMap()
    return DEFAULT_LAYOUT + saved
}

fun formatLayout(m: Map<Module, Spot>): String =
    m.entries.joinToString(";") { (k, s) -> "${k.name}:${s.x},${s.y},${if (s.on) 1 else 0},${s.scale}" }

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

/** Where a module at [s] lands after a drag of [dx],[dy] px on a [wPx]x[hPx] cover; [w],[h] are its size as fractions. */
fun dropped(s: Spot, dx: Float, dy: Float, wPx: Float, hPx: Float, w: Float, h: Float): Spot =
    clamp(s.copy(x = s.x + dx / wPx, y = s.y + dy / hPx), w, h)
