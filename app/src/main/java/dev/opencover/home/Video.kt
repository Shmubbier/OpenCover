package dev.opencover.home

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * An animated background turned into an H.264 video once, when it is set: decoding a lossless WebP frame takes about
 * 10 ms of CPU (30% of a core at 30 fps), while the phone's video decoder plays H.264 for almost nothing.
 */
object Video {
    private const val FPS = 30
    private const val TAG = "OpenCoverVideo"

    /** The video made from the animation in [anim]; null until made. */
    fun of(anim: File): File? = File(anim.path + ".mp4").takeIf { it.exists() && it.lastModified() >= anim.lastModified() }

    /** Makes the video for [anim] (blocking, a few seconds). False if this phone can't. */
    fun make(anim: File): Boolean {
        val a = Animation.open(anim) ?: return false
        // Even sizes for the encoder.
        val w = a.width and 1.inv()
        val h = a.height and 1.inv()
        if (w < 16 || h < 16) return false
        val tmp = File(anim.path + ".mp4.tmp")
        val started = System.currentTimeMillis()
        var codec: MediaCodec? = null
        var muxer: MediaMuxer? = null
        return try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, (w * h * FPS / 4).coerceAtLeast(1_000_000)) // high: a wallpaper should look clean
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also { codec = it }
            enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            val mux = MediaMuxer(tmp.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
            val frame = Bitmap.createBitmap(a.width, a.height, Bitmap.Config.ARGB_8888)
            val px = IntArray(a.width * a.height)
            val count = maxOf(1, (a.duration.toLong() * FPS / 1000).toInt())
            val info = MediaCodec.BufferInfo()
            var track = -1
            var sent = 0
            var done = false
            while (!done) {
                if (sent <= count) {
                    val i = enc.dequeueInputBuffer(10_000)
                    if (i >= 0) {
                        if (sent == count) {
                            enc.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        } else {
                            val t = sent * 1000L / FPS
                            a.renderTo(frame, t) // unchanged frames are left as they were
                            frame.getPixels(px, 0, a.width, 0, 0, a.width, a.height)
                            toYuv(px, a.width, enc.getInputImage(i)!!, w, h)
                            enc.queueInputBuffer(i, 0, w * h * 3 / 2, t * 1000, 0)
                        }
                        sent++
                    }
                }
                val o = enc.dequeueOutputBuffer(info, 10_000)
                when {
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = mux.addTrack(enc.outputFormat); mux.start() }
                    o >= 0 -> {
                        val buf = enc.getOutputBuffer(o)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0 && track >= 0) {
                            mux.writeSampleData(track, buf, info)
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                    }
                }
            }
            mux.stop()
            Log.i(TAG, "made ${w}x$h, $count frames, in ${System.currentTimeMillis() - started} ms")
            tmp.renameTo(File(anim.path + ".mp4"))
        } catch (e: Exception) {
            Log.w(TAG, "couldn't make the video", e)
            tmp.delete()
            false
        } finally {
            runCatching { codec?.stop() }; runCatching { codec?.release() }; runCatching { muxer?.release() }
        }
    }

    /** ARGB pixels into the encoder's YUV 4:2:0 image (BT.601, studio range), whatever its plane layout. */
    private fun toYuv(px: IntArray, stride: Int, img: android.media.Image, w: Int, h: Int) {
        val y = img.planes[0]; val u = img.planes[1]; val v = img.planes[2]
        val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
        for (r in 0 until h) {
            for (c in 0 until w) {
                val p = px[r * stride + c]
                val R = p shr 16 and 0xFF; val G = p shr 8 and 0xFF; val B = p and 0xFF
                yb.put(r * y.rowStride + c * y.pixelStride, (((66 * R + 129 * G + 25 * B + 128) shr 8) + 16).toByte())
                if (r % 2 == 0 && c % 2 == 0) {
                    val ui = (r / 2) * u.rowStride + (c / 2) * u.pixelStride
                    val vi = (r / 2) * v.rowStride + (c / 2) * v.pixelStride
                    ub.put(ui, (((-38 * R - 74 * G + 112 * B + 128) shr 8) + 128).toByte())
                    vb.put(vi, (((112 * R - 94 * G - 18 * B + 128) shr 8) + 128).toByte())
                }
            }
        }
    }
}
