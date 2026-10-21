package dev.opencover.phone

import dev.opencover.COVER_DISPLAY

/**
 * Shell script that pins the cover at [rotation] (Surface.ROTATION_*). The window manager never turns the cover from
 * an app's or an overlay's orientation request (nothing feeds it a sensor while the phone is folded), but it does obey
 * the user rotation once the cover is fixed to it.
 */
fun holdRotation(rotation: Int): String =
    "cmd window fixed-to-user-rotation -d $COVER_DISPLAY enabled; cmd window user-rotation -d $COVER_DISPLAY lock $rotation"

/** Clockwise quarter turns after [turns]: 0 -> 1 -> 2 -> 3 -> 0. */
fun nextTurns(turns: Int): Int = (turns + 1) % 4

/** "pkg=turns;pkg=turns" as stored in PhonePrefs. Upright (0) apps are not stored; bad entries are skipped. */
fun parseRotations(s: String?): Map<String, Int> =
    s.orEmpty().split(';').mapNotNull {
        val parts = it.split('=', limit = 2)
        val turns = parts.getOrNull(1)?.toIntOrNull()
        if (parts[0].isEmpty() || turns == null || turns !in 1..3) null else parts[0] to turns
    }.toMap()

fun encodeRotations(m: Map<String, Int>): String = m.filterValues { it in 1..3 }.entries.joinToString(";") { "${it.key}=${it.value}" }

private val ROOT_TASK = Regex("""^RootTask .*displayId=(\d+)""")
private val ACTIVITY_TYPE = Regex("""mActivityType=(\w+)""")
private val TOP_ACTIVITY = Regex("""topActivity=ComponentInfo\{([^/}]+)/""")

/**
 * The app to flip, from `am stack list` (newest root task first): the first ordinary app task on the cover whose top
 * activity's package isn't in [skip]. Null when there is none.
 */
fun flipTarget(stackList: String, skip: Set<String>): String? {
    var onCover = false
    var standard = false
    for (line in stackList.lineSequence()) {
        ROOT_TASK.find(line)?.let { onCover = it.groupValues[1] == COVER_DISPLAY.toString(); standard = false }
        ACTIVITY_TYPE.find(line)?.let { standard = it.groupValues[1] == "standard" }
        if (onCover && standard) TOP_ACTIVITY.find(line)?.groupValues?.get(1)?.let { if (it !in skip) return it }
    }
    return null
}
