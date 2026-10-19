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
 * Backgrounds, as Compose state: a still for each slot (an animation's first frame, used for blurred panels too) and,
 * for a GIF or animated WebP, its animation. Stills are cropped to the cover; animations are kept as they are.
 */
object Wallpaper {
    private const val W = 748
    private const val H = 720

    var homeStill by mutableStateOf<ImageBitmap?>(null); private set
    var homeAnim by mutableStateOf<Animation?>(null); private set
    var lockStill by mutableStateOf<ImageBitmap?>(null); private set
    /** The lock screen's animation, or its own copy of Home's: the two play at different moments. */
    var lockAnim by mutableStateOf<Animation?>(null); private set

    private fun still(ctx: Context, s: Slot) = File(ctx.filesDir, "${s.base}.jpg")
    // The name stays .gif for files saved before WebP was supported; the content decides the format.
    private fun anim(ctx: Context, s: Slot) = File(ctx.filesDir, "${s.base}.gif")

    fun loadAll(ctx: Context) {
        homeStill = load(ctx, Slot.HOME); homeAnim = animation(ctx, Slot.HOME)
        lockStill = load(ctx, Slot.LOCK)
        lockAnim = animation(ctx, Slot.LOCK) ?: if (lockStill == null) animation(ctx, Slot.HOME) else null
    }

    // ponytail: decodes on the main thread (~0.5 s for a large photo); move off-thread if it janks.
    fun save(ctx: Context, uri: Uri, slot: Slot = Slot.HOME): Boolean = try {
        // Keep the file as it is if it animates (GIF or animated WebP).
        val raw = File(ctx.filesDir, "${slot.base}.anim.tmp")
        ctx.contentResolver.openInputStream(uri)!!.use { input -> FileOutputStream(raw).use { input.copyTo(it); it.fd.sync() } }
        if (Animation.open(raw) != null) raw.renameTo(anim(ctx, slot)) else { raw.delete(); anim(ctx, slot).delete() }
        // The still: a photo, or an animation's first frame (ImageDecoder decodes that one).
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = maxOf(W.toFloat() / info.size.width, H.toFloat() / info.size.height)
            d.setTargetSize(
                (info.size.width * s).roundToInt().coerceAtLeast(W),
                (info.size.height * s).roundToInt().coerceAtLeast(H),
            )
        }
        val cropped = Bitmap.createBitmap(src, (src.width - W) / 2, (src.height - H) / 2, W, H)
        val tmp = File(ctx.filesDir, "${slot.base}.jpg.tmp")
        FileOutputStream(tmp).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it); it.fd.sync() }
        tmp.renameTo(still(ctx, slot)).also { loadAll(ctx) }
    } catch (e: Exception) {
        false
    }

    /** The lock screen goes back to Home's background. */
    fun clearLock(ctx: Context) {
        still(ctx, Slot.LOCK).delete(); anim(ctx, Slot.LOCK).delete()
        loadAll(ctx)
    }

    fun load(ctx: Context, slot: Slot = Slot.HOME): ImageBitmap? =
        still(ctx, slot).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }

    private fun animation(ctx: Context, slot: Slot): Animation? = anim(ctx, slot).takeIf { it.exists() }?.let { Animation.open(it) }
}
