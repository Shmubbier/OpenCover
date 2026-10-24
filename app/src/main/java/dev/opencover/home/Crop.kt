package dev.opencover.home

/** The part of a background image the cover shows, as fractions of the image (0..1). */
data class Crop(val x: Float, val y: Float, val w: Float, val h: Float) {
    fun encode() = "$x,$y,$w,$h"

    companion object {
        fun decode(s: String?): Crop? {
            val v = s?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 4 } ?: return null
            val c = Crop(v[0], v[1], v[2], v[3])
            val ok = c.w > 0f && c.h > 0f && c.x >= 0f && c.y >= 0f && c.x + c.w <= 1.0001f && c.y + c.h <= 1.0001f
            return c.takeIf { ok }
        }

        /** The centred crop of a [iw]x[ih] image that fills a [sw]x[sh] screen (what was shown before cropping existed). */
        fun centered(iw: Int, ih: Int, sw: Float, sh: Float): Crop = cropOf(placeCentered(iw, ih, sw, sh), iw, ih, sw, sh)
    }
}

/** An image on the crop screen: drawn [scale] times its size, its top-left corner at [x],[y] on the screen. */
data class Placement(val scale: Float, val x: Float, val y: Float)

/** The smallest scale at which a [iw]x[ih] image still covers a [sw]x[sh] screen: no empty edges. */
fun fillScale(iw: Int, ih: Int, sw: Float, sh: Float): Float = maxOf(sw / iw, sh / ih)

const val MAX_ZOOM = 6f

/** [p] kept within zoom limits and covering the whole screen. */
fun clamp(p: Placement, iw: Int, ih: Int, sw: Float, sh: Float): Placement {
    val min = fillScale(iw, ih, sw, sh)
    val s = p.scale.coerceIn(min, min * MAX_ZOOM)
    return Placement(s, p.x.coerceIn(sw - iw * s, 0f), p.y.coerceIn(sh - ih * s, 0f))
}

fun placeCentered(iw: Int, ih: Int, sw: Float, sh: Float): Placement {
    val s = fillScale(iw, ih, sw, sh)
    return Placement(s, (sw - iw * s) / 2, (sh - ih * s) / 2)
}

/** [p] after a pinch by [zoom] around ([cx],[cy]) and a drag by [dx],[dy]. */
fun moved(p: Placement, zoom: Float, cx: Float, cy: Float, dx: Float, dy: Float, iw: Int, ih: Int, sw: Float, sh: Float): Placement {
    val min = fillScale(iw, ih, sw, sh)
    val s = (p.scale * zoom).coerceIn(min, min * MAX_ZOOM)
    val k = s / p.scale // the point under the fingers stays under them
    return clamp(Placement(s, cx - (cx - p.x) * k + dx, cy - (cy - p.y) * k + dy), iw, ih, sw, sh)
}

/** What a [sw]x[sh] screen shows of the image at [p]. */
fun cropOf(p: Placement, iw: Int, ih: Int, sw: Float, sh: Float): Crop =
    Crop((-p.x / p.scale) / iw, (-p.y / p.scale) / ih, (sw / p.scale) / iw, (sh / p.scale) / ih)

/** Where the image goes on a [sw]x[sh] screen to show [c] (the inverse of [cropOf], adjusted to fill this screen). */
fun placementOf(c: Crop, iw: Int, ih: Int, sw: Float, sh: Float): Placement {
    val s = maxOf(sw / (c.w * iw), sh / (c.h * ih))
    return clamp(Placement(s, -c.x * iw * s, -c.y * ih * s), iw, ih, sw, sh)
}
