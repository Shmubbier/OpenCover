package dev.opencover.mirror

import dev.opencover.watcher.withService
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Plain rectangle so the math stays testable without Android's Rect. */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

/** Fit [srcW]x[srcH] inside [dstW]x[dstH] preserving aspect, centred. */
fun letterbox(srcW: Int, srcH: Int, dstW: Int, dstH: Int): Box {
    val scale = minOf(dstW.toFloat() / srcW, dstH.toFloat() / srcH)
    val w = (srcW * scale).toInt()
    val h = (srcH * scale).toInt()
    val left = (dstW - w) / 2
    val top = (dstH - h) / 2
    return Box(left, top, left + w, top + h)
}

/** Where the mirror sits inside [dstW]x[dstH] once turned [quarterTurns] clockwise quarter turns. */
fun mirrorBox(srcW: Int, srcH: Int, dstW: Int, dstH: Int, quarterTurns: Int): Box =
    if (quarterTurns % 2 == 0) letterbox(srcW, srcH, dstW, dstH) else letterbox(srcH, srcW, dstW, dstH)

/**
 * Map a touch at ([x],[y]) in view space to source coordinates, or null if outside the [box].
 * [box] is where the (already turned) mirror is drawn; [quarterTurns] is how far it is turned clockwise.
 */
fun toSource(x: Float, y: Float, box: Box, srcW: Int, srcH: Int, quarterTurns: Int = 0): Pair<Float, Float>? {
    if (x < box.left || x > box.right || y < box.top || y > box.bottom) return null
    val bw = (box.right - box.left).toFloat()
    val bh = (box.bottom - box.top).toFloat()
    if (bw <= 0f || bh <= 0f) return null
    val nx = (x - box.left) / bw
    val ny = (y - box.top) / bh
    val (a, b) = when (((quarterTurns % 4) + 4) % 4) {
        1 -> ny to 1 - nx
        2 -> 1 - nx to 1 - ny
        3 -> 1 - ny to nx
        else -> nx to ny
    }
    return Pair(a * srcW, b * srcH)
}

private const val TAP_SLOP_PX = 12f
private const val LONG_PRESS_MS = 400L

/** `input` arguments that replay a finished stroke on the inner display: tap, long-press, or a straight swipe. */
fun inputArgs(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): List<String> {
    val still = hypot(x2 - x1, y2 - y1) < TAP_SLOP_PX
    fun px(v: Float) = v.roundToInt().toString()
    return if (still && ms < LONG_PRESS_MS) listOf("input", "-d", "0", "tap", px(x1), px(y1))
    else listOf("input", "-d", "0", "swipe", px(x1), px(y1), px(if (still) x1 else x2), px(if (still) y1 else y2), ms.toString())
}

/** The accessibility list after a session: OpenCover back on only if mirroring switched it off. Null: leave it. */
fun restoredAccessibility(now: String?, me: String, wasOn: Boolean): String? = if (wasOn) withService(now, me, on = true) else null

private const val MIN_DURATION_MS = 1L
private const val MAX_DURATION_MS = 60_000L

/** Gesture duration from first to last point timestamps, clamped. */
fun strokeDuration(firstMs: Long, lastMs: Long): Long =
    (lastMs - firstMs).coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
