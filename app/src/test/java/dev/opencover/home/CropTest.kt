package dev.opencover.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropTest {
    private val sw = 748f
    private val sh = 720f

    // A part chosen on a 16:9 monitor, shown on a 16:10 one: the monitor's shape, same centre, nothing stretched.
    @Test fun refitKeepsTheShapeAndCentre() {
        val chosen = Crop(0.25f, 0.25f, 0.5f, 0.5f) // a 16:9 part of a 1920x1080 image
        val c = refit(chosen, 1920, 1080, 1920f, 1200f)
        assertEquals(1920f / 1200f, (c.w * 1920) / (c.h * 1080), 1e-3f)
        assertEquals(0.5f, c.x + c.w / 2, 1e-4f); assertEquals(0.5f, c.y + c.h / 2, 1e-4f)
        // An ultrawide part of the whole image: it can't grow past the image, so it stays inside it.
        val wide = refit(Crop(0f, 0f, 1f, 1f), 1920, 1080, 3440f, 1440f)
        assert(wide.x >= 0f && wide.y >= 0f && wide.x + wide.w <= 1.0001f && wide.y + wide.h <= 1.0001f)
        assertEquals(3440f / 1440f, (wide.w * 1920) / (wide.h * 1080), 1e-3f)
    }

    // A 16:9 image on the near-square cover: centred, full height, the middle part across.
    @Test fun centredFillsTheScreen() {
        val c = Crop.centered(1920, 1080, sw, sh)
        assertEquals(0f, c.y, 1e-4f); assertEquals(1f, c.h, 1e-4f)
        assertEquals(0.5f, c.x + c.w / 2, 1e-4f)
        assertEquals(sw / sh, (c.w * 1920) / (c.h * 1080), 1e-3f) // the cover's shape
    }

    // Dragging can't reveal an empty edge, and zooming out stops at filling the screen.
    @Test fun neverShowsEmptyEdges() {
        val p = moved(placeCentered(1920, 1080, sw, sh), 0.2f, 0f, 0f, 5000f, 5000f, 1920, 1080, sw, sh)
        val c = cropOf(p, 1920, 1080, sw, sh)
        assertEquals(0f, c.x, 1e-4f); assertEquals(0f, c.y, 1e-4f); assertEquals(1f, c.h, 1e-4f)
    }

    // Zooming in on a point keeps it under the fingers.
    @Test fun pinchKeepsThePoint() {
        val p0 = placeCentered(1000, 1000, sw, sh)
        val p = moved(p0, 2f, 374f, 360f, 0f, 0f, 1000, 1000, sw, sh)
        val before = (374f - p0.x) / p0.scale
        val after = (374f - p.x) / p.scale
        assertEquals(before, after, 1e-3f)
    }

    @Test fun cropRoundTrips() {
        val p = moved(placeCentered(640, 480, sw, sh), 2.5f, 100f, 600f, -30f, 20f, 640, 480, sw, sh)
        val c = cropOf(p, 640, 480, sw, sh)
        assertEquals(c, Crop.decode(c.encode()))
        val back = placementOf(c, 640, 480, sw, sh)
        assertEquals(p.scale, back.scale, 1e-3f); assertEquals(p.x, back.x, 0.1f); assertEquals(p.y, back.y, 0.1f)
        assertNull(Crop.decode("0.5,0.5,0.9,0.1")) // runs off the image
        assertNull(Crop.decode("junk"))
    }
}
