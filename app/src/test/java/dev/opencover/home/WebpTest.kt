package dev.opencover.home

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

class WebpTest {
    private fun le(v: Long, n: Int) = ByteArray(n) { (v shr (8 * it)).toByte() }
    private fun chunk(type: String, body: ByteArray) =
        type.toByteArray() + le(body.size.toLong(), 4) + body + (if (body.size % 2 == 1) byteArrayOf(0) else byteArrayOf())

    private fun webp(vararg chunks: ByteArray): File {
        val body = ByteArrayOutputStream().apply { write("WEBP".toByteArray()); chunks.forEach { write(it) } }.toByteArray()
        return File.createTempFile("webp", ".webp").apply { deleteOnExit(); writeBytes("RIFF".toByteArray() + le(body.size.toLong(), 4) + body) }
    }

    private fun anmf(x: Int, y: Int, w: Int, h: Int, dur: Int, flags: Int, data: ByteArray) = chunk(
        "ANMF", le(x / 2L, 3) + le(y / 2L, 3) + le(w - 1L, 3) + le(h - 1L, 3) + le(dur.toLong(), 3) + byteArrayOf(flags.toByte()) + data,
    )

    @Test fun readsFramesTimesAndPlacement() {
        val vp8x = chunk("VP8X", byteArrayOf(0x12, 0, 0, 0) + le(639, 3) + le(479, 3))
        val f = webp(
            vp8x, chunk("ANIM", ByteArray(6)),
            anmf(0, 0, 640, 480, 67, 2, chunk("VP8L", ByteArray(5))), // odd size: padded
            anmf(10, 20, 30, 40, 0, 1, chunk("VP8L", ByteArray(4))), // 0 ms counts as 100
            anmf(2, 4, 6, 8, 134, 0, chunk("VP8L", ByteArray(4))),
        )
        val a = RandomAccessFile(f, "r").use { parseWebp(it) }!!
        assertEquals(640, a.width); assertEquals(480, a.height)
        assertEquals(listOf(0L, 67L, 167L), a.frames.map { it.start })
        assertEquals(301, a.duration)
        a.frames[1].let { assertEquals(listOf(10, 20, 30, 40), listOf(it.x, it.y, it.w, it.h)); assertEquals(true, it.dispose); assertEquals(true, it.blend) }
        assertEquals(false, a.frames[0].blend)
        // The frame data is the VP8L chunk inside ANMF, read straight from the file.
        val d = ByteArray(a.frames[1].size).also { RandomAccessFile(f, "r").use { r -> r.seek(a.frames[1].offset); r.readFully(it) } }
        assertArrayEquals(chunk("VP8L", ByteArray(4)), d)
        assertEquals(listOf(0, 0, 1, 2, 2, 0), listOf(0L, 66L, 67L, 167L, 300L, 301L).map { a.frameAt(it) }) // loops
    }

    @Test fun stillWebpIsNotAnimated() {
        assertNull(RandomAccessFile(webp(chunk("VP8 ", ByteArray(10))), "r").use { parseWebp(it) })
    }

    @Test fun framesAreWrappedAsStandaloneFiles() {
        val data = chunk("ALPH", ByteArray(2)) + chunk("VP8 ", ByteArray(2))
        val out = wrapFrame(data, 30, 40)
        assertEquals(30 + data.size, out.size)
        assertEquals("RIFF", String(out, 0, 4)); assertEquals(out.size - 8, java.nio.ByteBuffer.wrap(out, 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int)
        assertEquals("WEBPVP8X", String(out, 8, 8)); assertEquals(0x10, out[20].toInt()) // alpha flag
        assertArrayEquals(le(29, 3) + le(39, 3), out.copyOfRange(24, 30))
    }
}
