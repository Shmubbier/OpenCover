package dev.opencover.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/** Home wallpaper: the picked photo center-cropped to the cover size, saved as wallpaper.jpg. */
object Wallpaper {
    private const val W = 748
    private const val H = 720

    private fun file(ctx: Context) = File(ctx.filesDir, "wallpaper.jpg")

    // ponytail: decodes on the main thread (~0.5 s for a large photo); move off-thread if it janks.
    fun save(ctx: Context, uri: Uri): Boolean = try {
        val src = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val s = maxOf(W.toFloat() / info.size.width, H.toFloat() / info.size.height)
            d.setTargetSize(
                (info.size.width * s).roundToInt().coerceAtLeast(W),
                (info.size.height * s).roundToInt().coerceAtLeast(H),
            )
        }
        val cropped = Bitmap.createBitmap(src, (src.width - W) / 2, (src.height - H) / 2, W, H)
        val tmp = File(ctx.filesDir, "wallpaper.jpg.tmp")
        FileOutputStream(tmp).use { cropped.compress(Bitmap.CompressFormat.JPEG, 90, it); it.fd.sync() }
        tmp.renameTo(file(ctx))
    } catch (e: Exception) {
        false
    }

    fun load(ctx: Context): ImageBitmap? =
        file(ctx).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.asImageBitmap() }
}
