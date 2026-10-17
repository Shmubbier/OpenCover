package dev.opencover.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RotationTest {
    @Test fun turnsCycleThroughFourQuarters() {
        assertEquals(1, nextTurns(0))
        assertEquals(3, nextTurns(2))
        assertEquals(0, nextTurns(3))
    }

    @Test fun holdingFixesTheCoverToUserRotationThenLocksIt() {
        val script = holdRotation(2)
        assertTrue(script.indexOf("fixed-to-user-rotation -d 1 enabled") < script.indexOf("user-rotation -d 1 lock 2"))
    }

    @Test fun rotationsRoundTrip() {
        val m = mapOf("a.b" to 1, "c.d" to 3)
        assertEquals(m, parseRotations(encodeRotations(m)))
    }

    @Test fun uprightAndBadEntriesAreDropped() {
        assertEquals(mapOf("a" to 2), parseRotations("a=2;b=0;c=9;d=x;=1;junk"))
        assertEquals(emptyMap<String, Int>(), parseRotations(null))
        assertEquals("a=2", encodeRotations(mapOf("a" to 2, "b" to 0)))
    }

    // Trimmed from a real `am stack list` on the Flip 5 (newest root task first).
    private fun task(display: Int, type: String, pkg: String, top: String = pkg) = """
RootTask id=9 bounds=[0,0][748,720] displayId=$display userId=0
 configuration={1.0 winConfig={ mBounds=Rect(0, 0 - 748, 720) mWindowingMode=fullscreen mActivityType=$type mAlwaysOnTop=undefined}}
  taskId=9: $pkg/$pkg.Main bounds=[0,0][748,720] userId=0 visible=false topActivity=ComponentInfo{$top/$top.Top}
"""

    private val skip = setOf("dev.opencover", "com.sec.android.app.launcher")

    @Test fun targetIsNewestCoverAppNotSkipped() {
        val list = task(1, "standard", "dev.opencover") + task(1, "home", "com.android.systemui") +
            task(1, "standard", "com.facebook.orca") + task(1, "standard", "com.android.settings")
        assertEquals("com.facebook.orca", flipTarget(list, skip))
    }

    @Test fun targetUsesTopActivityPackage() =
        assertEquals("com.samsung.android.multistar", flipTarget(task(1, "standard", "com.samsung.android.goodlock", "com.samsung.android.multistar"), skip))

    @Test fun innerDisplayAndRecentsAreIgnored() {
        val list = task(0, "standard", "com.whatsapp") + task(1, "recents", "com.sec.android.app.launcher") + task(1, "standard", "com.android.settings")
        assertEquals("com.android.settings", flipTarget(list, skip))
    }

    @Test fun noTargetWhenOnlyOwnAndHome() =
        assertNull(flipTarget(task(1, "standard", "dev.opencover") + task(1, "home", "com.android.systemui"), skip))
}
