package dev.opencover.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin

/** Icon shapes, as in Lawnchair. Ids are saved, so never rename one. */
enum class IconShape(val label: String) {
    SYSTEM("System"), CIRCLE("Circle"), SQUIRCLE("Squircle"), ROUNDED("Rounded square"), SQUARE("Square"), TEARDROP("Teardrop");

    /** The outline at [s] px; null for [SYSTEM] (each icon keeps the shape the system gives it). */
    fun path(s: Float): Path? = when (this) {
        SYSTEM -> null
        CIRCLE -> Path().apply { addOval(RectF(0f, 0f, s, s), Path.Direction.CW) }
        ROUNDED -> Path().apply { addRoundRect(RectF(0f, 0f, s, s), s * 0.22f, s * 0.22f, Path.Direction.CW) }
        SQUARE -> Path().apply { addRoundRect(RectF(0f, 0f, s, s), s * 0.06f, s * 0.06f, Path.Direction.CW) }
        TEARDROP -> Path().apply {
            val r = s / 2; val tip = s * 0.12f // round everywhere but the bottom-right corner
            addRoundRect(RectF(0f, 0f, s, s), floatArrayOf(r, r, r, r, tip, tip, r, r), Path.Direction.CW)
        }
        SQUIRCLE -> Path().apply { // superellipse |x|^4 + |y|^4 = 1
            val r = s / 2
            for (i in 0..64) {
                val t = 2 * Math.PI * i / 64
                val x = r + r * sign(cos(t)) * abs(cos(t)).pow(0.5)
                val y = r + r * sign(sin(t)) * abs(sin(t)).pow(0.5)
                if (i == 0) moveTo(x.toFloat(), y.toFloat()) else lineTo(x.toFloat(), y.toFloat())
            }
            close()
        }
    }
}

/**
 * An app icon drawn at [px]. Themed: [glyph] (one colour) tinted [fg] on a [bg] backdrop in [shape]. Otherwise an
 * adaptive [icon] is cut to [shape]; any other icon is drawn as it is.
 */
fun renderIcon(icon: Drawable, glyph: Drawable?, shape: IconShape, themed: Boolean, fg: Int, bg: Int, px: Int): Bitmap {
    val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val s = px.toFloat()
    if (themed && glyph != null) {
        val outline = shape.path(s) ?: IconShape.CIRCLE.path(s)!!
        c.drawPath(outline, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = bg })
        c.clipPath(outline)
        glyph.mutate().colorFilter = PorterDuffColorFilter(fg, PorterDuff.Mode.SRC_IN)
        drawLayer(c, glyph, px)
        return bmp
    }
    val outline = shape.path(s)
    if (outline != null && icon is AdaptiveIconDrawable) {
        c.clipPath(outline)
        icon.background?.let { drawLayer(c, it, px, adaptive = true) }
        icon.foreground?.let { drawLayer(c, it, px, adaptive = true) }
        return bmp
    }
    icon.setBounds(0, 0, px, px)
    icon.draw(c)
    return bmp
}

/**
 * Draws a layer filling the icon. An adaptive icon's layers are 108 units with the middle 72 shown, so they are drawn
 * a quarter larger on each side; a glyph from an icon pack (already sized to its icon) fills the icon as it is.
 */
private fun drawLayer(c: Canvas, d: Drawable, px: Int, adaptive: Boolean = d is AdaptiveIconDrawable) {
    if (d is AdaptiveIconDrawable) { (d.monochrome ?: d.foreground)?.let { drawLayer(c, it, px, adaptive = true) }; return }
    val out = if (adaptive) px / 4 else 0
    d.setBounds(-out, -out, px + out, px + out)
    d.draw(c)
}

/** A light glyph colour and a dark backdrop colour in the wallpaper's hue (from its average colour [avg]). */
fun themedColors(avg: Int): Pair<Int, Int> {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(avg, it) }
    val fg = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.1f, 0.25f), 0.96f))
    val bg = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.3f, 0.55f), 0.26f))
    return fg to bg
}
