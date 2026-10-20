package dev.opencover.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Home's background and the lock screen's (which falls back to Home's). */
enum class Slot(val base: String) { HOME("wallpaper"), LOCK("lock_wallpaper") }

/**
 * Backgrounds, as Compose state: a still for each slot (the chosen part of the image, or of an animation's first frame,
 * used for blurred panels too) and, for a GIF or animated WebP, its animation with the part to show ([homeCrop]).
 * The picked file is kept as it is, so the part shown can be changed later.
 */
object Wallpaper {
    private const val W = 748
    private const val H = 720

    var homeStill by mutableStateOf<ImageBitmap?>(null); private set
    var homeAnim by mutableStateOf<Animation?>(null); private set
    var lockStill by mutableStateOf<ImageBitmap?>(null); private set
    /** The lock screen's animation, or its own copy of Home's: the two play at different moments. */
    var lockAnim by mutableStateOf<Animation?>(null); private set
    /** The part of each slot's image shown; null: centred (saved before cropping existed). */
    var homeCrop by mutableStateOf<Crop?>(null); private set
    var lockCrop by mutableStateOf<Crop?>(null); private set

    private fun still(ctx: Context, s: Slot) = File(ctx.filesDir, "${s.base}.jpg")
    // The picked file as it is. Named .gif from before WebP and stills were kept; the content decides the format.
    private fun anim(ctx: Context, s: Slot) = File(ctx.filesDir, "${s.base}.gif")
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("wallpaper", Context.MODE_PRIVATE)

    fun loadAll(ctx: Context) {
        homeStill = load(ctx, Slot.HOME); homeAnim = animation(ctx, Slot.HOME)
        lockStill = load(ctx, Slot.LOCK)
        lockAnim = animation(ctx, Slot.LOCK) ?: if (lockStill == null) animation(ctx, Slot.HOME) else null
        homeCrop = Crop.decode(prefs(ctx).getString("crop_${Slot.HOME.base}", null))
        lockCrop = Crop.decode(prefs(ctx).getString("crop_${Slot.LOCK.base}", null))
    }

    /** The crop the lock screen's animation uses (Home's when it borrows Home's). */
    val lockAnimCrop get() = if (lockStill != null) lockCrop else homeCrop

    /** A picked image waiting to be cropped: copied here, not yet the background. Null if it can't be read. */
    fun import(ctx: Context, uri: Uri, slot: Slot): File? = try {
        val f = File(ctx.cacheDir, "${slot.base}.pick")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(f).use { input.copyTo(it) } }
        f.takeIf { preview(it) != null }
    } catch (e: Exception) {
        null
    }

    /** The slot's kept file, to crop it again; null for stills saved before cropping existed. */
    fun source(ctx: Context, slot: Slot): File? = anim(ctx, slot).takeIf { it.exists() }

    /** The image (an animation's first frame), at most about 1500 px, for the crop screen. */
    fun preview(f: File): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = minOf(1f, 1500f / maxOf(info.size.width, info.size.height))
            d.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
        }
    } catch (e: Exception) {
        null
    }

    /** Makes [f] the slot's background, showing the [crop] part of it. */
    // ponytail: decodes on the main thread (~0.5 s for a large photo); move off-thread if it janks.
    fun save(ctx: Context, f: File, slot: Slot, crop: Crop): Boolean = try {
        // The still: the cropped part, at the cover's size, decoded no bigger than needed.
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = minOf(1f, maxOf(W / (crop.w * info.size.width), H / (crop.h * info.size.height)))
            d.setTargetSize((info.size.width * s).roundToInt().coerceAtLeast(1), (info.size.height * s).roundToInt().coerceAtLeast(1))
        }
        val x = (crop.x * src.width).roundToInt().coerceIn(0, src.width - 1)
        val y = (crop.y * src.height).roundToInt().coerceIn(0, src.height - 1)
        val part = Bitmap.createBitmap(src, x, y,
            (crop.w * src.width).roundToInt().coerceIn(1, src.width - x), (crop.h * src.height).roundToInt().coerceIn(1, src.height - y))
        val cropped = Bitmap.createScaledBitmap(part, W, H, true)
        val tmp = File(ctx.filesDir, "${slot.base}.jpg.tmp")
        FileOutputStream(tmp).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it); it.fd.sync() }
        // Keep the picked file as it is (unless it is already the kept one being cropped again).
        if (f != anim(ctx, slot)) {
            val keep = File(ctx.filesDir, "${slot.base}.gif.tmp")
            f.inputStream().use { input -> FileOutputStream(keep).use { input.copyTo(it); it.fd.sync() } }
            keep.renameTo(anim(ctx, slot))
            f.delete()
        }
        prefs(ctx).edit().putString("crop_${slot.base}", crop.encode()).commit()
        tmp.renameTo(still(ctx, slot)).also { loadAll(ctx) }
    } catch (e: Exception) {
        false
    }

    /** The lock screen goes back to Home's background. */
    fun clearLock(ctx: Context) {
        still(ctx, Slot.LOCK).delete(); anim(ctx, Slot.LOCK).delete()
        prefs(ctx).edit().remove("crop_${Slot.LOCK.base}").apply()
        loadAll(ctx)
    }

    fun load(ctx: Context, slot: Slot = Slot.HOME): ImageBitmap? =
        still(ctx, slot).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }

    private fun animation(ctx: Context, slot: Slot): Animation? = anim(ctx, slot).takeIf { it.exists() }?.let { Animation.open(it) }
}
