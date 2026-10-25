package dev.opencover.desk

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import dev.opencover.home.Crop
import dev.opencover.home.Slot
import dev.opencover.home.Video
import dev.opencover.home.Wallpaper
import java.io.File
import kotlin.math.roundToInt

/**
 * The desk's background: its own picked image, GIF, WebP or video, or else Home's background (the whole picked
 * file, not the cover's cut), with the part of it the monitor shows ([crop], 16:9; centred when unset).
 */
object DeskBackground {
    const val ASPECT = 16f / 9f

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("desk", Context.MODE_PRIVATE)
    // The picked file as it is; the content decides what it is (named like Wallpaper's kept file).
    private fun own(ctx: Context) = File(ctx.filesDir, "desk_wallpaper.gif")

    fun hasOwn(ctx: Context) = own(ctx).exists()

    /** The file the desk shows. */
    fun source(ctx: Context): File? = own(ctx).takeIf { it.exists() }
        ?: Wallpaper.source(ctx, Slot.HOME) ?: File(ctx.filesDir, "wallpaper.jpg").takeIf { it.exists() }

    /** The part shown, for the current source. */
    fun crop(ctx: Context): Crop? = Crop.decode(prefs(ctx).getString(if (hasOwn(ctx)) "own_crop" else "home_crop", null))

    fun setCrop(ctx: Context, c: Crop) = prefs(ctx).edit().putString(if (hasOwn(ctx)) "own_crop" else "home_crop", c.encode()).apply()

    /** Makes [picked] (a file in the cache) the desk's own background, showing [c]. */
    fun saveOwn(ctx: Context, picked: File, c: Crop): Boolean = runCatching {
        val tmp = File(ctx.filesDir, "desk_wallpaper.gif.tmp")
        picked.inputStream().use { input -> tmp.outputStream().use { input.copyTo(it); it.fd.sync() } }
        File(own(ctx).path + ".mp4").delete()
        tmp.renameTo(own(ctx)).also { picked.delete() }
    }.getOrDefault(false).also { if (it) prefs(ctx).edit().putString("own_crop", c.encode()).apply() }

    /** Back to Home's background. */
    fun clearOwn(ctx: Context) {
        own(ctx).delete(); File(own(ctx).path + ".mp4").delete()
        prefs(ctx).edit().remove("own_crop").apply()
    }

    fun isVideo(f: File) = runCatching {
        MediaMetadataRetriever().use { it.setDataSource(f.path); it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" }
    }.getOrDefault(false)

    /** The picture of [f] (a video's or an animation's first frame), for the crop screen, at most about 1500 px. */
    fun preview(f: File): Bitmap? =
        if (isVideo(f)) runCatching { MediaMetadataRetriever().use { it.setDataSource(f.path); it.getFrameAtTime(0) } }.getOrNull()
        else Wallpaper.preview(f)

    /**
     * The background as a video, for an animated one: the file itself if it is a video, else the H.264 made from the
     * animation ([makeVideo]). Null for a still, or while the video is still being made.
     */
    fun video(ctx: Context): File? {
        val f = source(ctx) ?: return null
        return if (isVideo(f)) f else Video.of(f)
    }

    /** Makes the H.264 for an animated own background (blocking, a few seconds; Home's makes its own). */
    fun makeVideo(ctx: Context) {
        val f = own(ctx).takeIf { it.exists() && !isVideo(it) && Video.of(it) == null } ?: return
        if (dev.opencover.home.Animation.open(f) != null) Video.make(f)
    }

    /** The shown part as a [w]x[h] picture. */
    fun picture(ctx: Context, w: Int, h: Int): Bitmap? {
        val f = source(ctx) ?: return null
        val full = (if (isVideo(f)) preview(f) else runCatching {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE }
        }.getOrNull()) ?: return null
        val c = crop(ctx) ?: Crop.centered(full.width, full.height, w.toFloat(), h.toFloat())
        val src = android.graphics.Rect((c.x * full.width).roundToInt(), (c.y * full.height).roundToInt(),
            ((c.x + c.w) * full.width).roundToInt(), ((c.y + c.h) * full.height).roundToInt())
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { out ->
            android.graphics.Canvas(out).drawBitmap(full, src, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        }
    }
}
