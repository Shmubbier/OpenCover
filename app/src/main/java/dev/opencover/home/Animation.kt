package dev.opencover.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

/**
 * An animated background (GIF or animated WebP), decoded off the main thread into [frame]. Every screen showing it
 * (Home, the lock screen, the AOD) draws that same frame, so they always agree on where the animation is.
 */
class Animation private constructor(val width: Int, val height: Int, val duration: Int, private val render: (Bitmap, Long) -> Boolean) {
    private val work = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    // ponytail: three frames in turn, so the one being written was handed to the screen two frames ago.
    private val ring = Array(3) { Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888) }
    private var next = 0
    private val copy = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** Draws the moment [time] ms in onto [out]; false if it shows the same frame as the last call. For [Video]. */
    fun renderTo(out: Bitmap, time: Long): Boolean = render(out, time)

    /** The latest frame, as Compose state; null until the first one is decoded. */
    var frame by mutableStateOf<Bitmap?>(null); private set

    /** Shows the moment [time] ms into the animation. Run on [decoder]. */
    @Synchronized fun show(time: Long) {
        if (!render(work, time)) return
        val b = ring[next].also { next = (next + 1) % ring.size }
        Canvas(b).drawBitmap(work, 0f, 0f, copy)
        frame = b
    }

    companion object {
        val decoder = Executors.newSingleThreadExecutor { Thread(it, "background-decoder") }.asCoroutineDispatcher()

        /** A GIF or animated WebP file, or null for anything else (or a broken file). */
        fun open(file: File): Animation? = try {
            val head = ByteArray(12).also { h -> file.inputStream().use { it.read(h) } }
            when {
                String(head, 0, 4, Charsets.US_ASCII) == "GIF8" -> gif(file)
                String(head, 0, 4, Charsets.US_ASCII) == "RIFF" && String(head, 8, 4, Charsets.US_ASCII) == "WEBP" -> webp(file)
                else -> null
            }
        } catch (e: Exception) {
            null
        }

        @Suppress("DEPRECATION") // Movie is the platform GIF decoder that can show any moment of the animation
        private fun gif(file: File): Animation? {
            val m = Movie.decodeFile(file.path)?.takeIf { it.duration() > 0 } ?: return null
            return Animation(m.width(), m.height(), m.duration()) { out, t ->
                m.setTime(t.toInt())
                out.eraseColor(0)
                m.draw(Canvas(out), 0f, 0f)
                true
            }
        }

        private fun webp(file: File): Animation? {
            val raf = RandomAccessFile(file, "r")
            val anim = RandomAccessFile(file, "r").use { parseWebp(it) } ?: return null
            val frames = anim.frames
            val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            val src = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
            var shown = -1
            return Animation(anim.width, anim.height, anim.duration) { out, t ->
                val target = anim.frameAt(t)
                if (target == shown) return@Animation false
                // Frames build on the ones before them: going back means starting over from the first.
                if (target < shown || shown < 0) { out.eraseColor(0); shown = -1 }
                val c = Canvas(out)
                for (i in shown + 1..target) {
                    frames.getOrNull(i - 1)?.takeIf { it.dispose }?.let { p ->
                        c.drawRect(p.x.toFloat(), p.y.toFloat(), (p.x + p.w).toFloat(), (p.y + p.h).toFloat(), clear)
                    }
                    val f = frames[i]
                    val bytes = synchronized(raf) { ByteArray(f.size).also { raf.seek(f.offset); raf.readFully(it) } }
                    val bmp = BitmapFactory.decodeByteArray(wrapFrame(bytes, f.w, f.h), 0, f.size + 30) ?: continue
                    c.drawBitmap(bmp, f.x.toFloat(), f.y.toFloat(), if (f.blend) null else src)
                    bmp.recycle()
                }
                shown = target
                true
            }
        }
    }
}

/** One frame of an animated WebP: where its image data is in the file and how it goes onto the canvas. */
data class WebpFrame(
    val offset: Long, val size: Int, val x: Int, val y: Int, val w: Int, val h: Int,
    val start: Long, val blend: Boolean, val dispose: Boolean,
)

class WebpAnimation(val width: Int, val height: Int, val frames: List<WebpFrame>, val duration: Int) {
    /** The frame showing [time] ms in, looping. */
    fun frameAt(time: Long): Int {
        val t = if (duration > 0) time % duration else 0
        var lo = 0; var hi = frames.size - 1
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (frames[mid].start <= t) lo = mid else hi = mid - 1 }
        return lo
    }
}

/** Reads an animated WebP's frame table (VP8X, then ANMF chunks); null if it isn't animated. */
fun parseWebp(f: RandomAccessFile): WebpAnimation? {
    fun le(b: ByteArray, at: Int, n: Int) = (0 until n).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }
    val hdr = ByteArray(8)
    var pos = 12L
    var w = 0; var h = 0; var time = 0L
    val frames = mutableListOf<WebpFrame>()
    while (pos + 8 <= f.length()) {
        f.seek(pos); f.readFully(hdr)
        val type = String(hdr, 0, 4, Charsets.US_ASCII)
        val size = le(hdr, 4, 4)
        when (type) {
            "VP8X" -> { val b = ByteArray(10); f.readFully(b); w = le(b, 4, 3).toInt() + 1; h = le(b, 7, 3).toInt() + 1 }
            "ANMF" -> {
                val b = ByteArray(16); f.readFully(b)
                val dur = le(b, 12, 3)
                val flags = b[15].toInt()
                frames += WebpFrame(
                    offset = pos + 24, size = (size - 16).toInt(),
                    x = le(b, 0, 3).toInt() * 2, y = le(b, 3, 3).toInt() * 2,
                    w = le(b, 6, 3).toInt() + 1, h = le(b, 9, 3).toInt() + 1,
                    start = time, blend = flags and 2 == 0, dispose = flags and 1 == 1,
                )
                // Browsers treat very short frame times as 100 ms; do the same so the timing matches.
                time += if (dur <= 10) 100 else dur
            }
        }
        pos += 8 + size + (size and 1)
    }
    if (frames.isEmpty() || w == 0) return null
    return WebpAnimation(w, h, frames, time.toInt())
}

/** A frame's image data (VP8L, VP8, or ALPH + VP8) as a stand-alone WebP file that BitmapFactory can decode. */
fun wrapFrame(data: ByteArray, w: Int, h: Int): ByteArray {
    // Always wrap in VP8X (needed when there's an ALPH chunk, harmless otherwise), so the length is fixed: 30 + data.
    val out = java.nio.ByteBuffer.allocate(30 + data.size).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    val alpha = String(data, 0, 4, Charsets.US_ASCII) == "ALPH"
    out.put("RIFF".toByteArray()).putInt(22 + data.size).put("WEBP".toByteArray())
    out.put("VP8X".toByteArray()).putInt(10).put((if (alpha) 0x10 else 0).toByte()).put(ByteArray(3))
    fun put24(v: Int) { out.put((v and 0xFF).toByte()).put((v shr 8 and 0xFF).toByte()).put((v shr 16 and 0xFF).toByte()) }
    put24(w - 1); put24(h - 1)
    out.put(data)
    return out.array()
}
