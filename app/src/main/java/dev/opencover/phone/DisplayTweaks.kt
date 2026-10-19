package dev.opencover.phone

import dev.opencover.COVER_DISPLAY
import dev.opencover.quick.ShizukuShell

/** Display presets through Shizuku: cover density, font size, and the inner screen's size while mirroring. */
object DisplayTweaks {
    const val MIN_DENSITY = 120
    const val MAX_DENSITY = 640
    const val MIN_FONT = 0.5f
    const val MAX_FONT = 2.0f

    /** Inner-screen sizes offered for mirroring: label to "WxH". The cover is almost square, so taller looks smaller. */
    val MIRROR_SIZES = listOf("2:3" to "1080x1600", "3:4" to "1080x1440", "1:1" to "1080x1080", "9:16" to "1080x1920")

    /** The cover's density now (the override if set). Blocking. */
    fun coverDensity(): Int? = ShizukuShell.output("wm", "density", "-d", "$COVER_DISPLAY")?.let(::parseDensity)

    /** null resets to the cover's own density. Blocking. */
    fun setCoverDensity(dpi: Int?): Boolean =
        ShizukuShell.run("wm", "density", dpi?.coerceIn(MIN_DENSITY, MAX_DENSITY)?.toString() ?: "reset", "-d", "$COVER_DISPLAY")

    /** The system font size (the whole phone, not just the cover). Blocking. */
    fun fontScale(): Float? = ShizukuShell.output("settings", "get", "system", "font_scale")?.trim()?.toFloatOrNull()

    fun setFontScale(scale: Float): Boolean =
        ShizukuShell.run("settings", "put", "system", "font_scale", "%.2f".format(java.util.Locale.ROOT, scale.coerceIn(MIN_FONT, MAX_FONT)))
}

/** "Physical density: 340\nOverride density: 280" -> 280 (the override wins). */
fun parseDensity(out: String): Int? {
    fun read(label: String) = Regex("$label density: (\\d+)").find(out)?.groupValues?.get(1)?.toIntOrNull()
    return read("Override") ?: read("Physical")
}

/** "1080x1600" -> the shell commands that set the inner screen to it at [density] before mirroring; null if malformed. */
fun innerScreenScript(size: String, density: Int): String? {
    val (w, h) = Regex("""^(\d{3,4})x(\d{3,4})$""").find(size)?.destructured ?: return null
    return "wm size ${w}x$h; wm density ${density.coerceIn(DisplayTweaks.MIN_DENSITY, DisplayTweaks.MAX_DENSITY)}; "
}

/** Puts the inner screen back after mirroring. */
const val INNER_SCREEN_RESET = "wm size reset; wm density reset"
