package dev.opencover.watcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BankingRulesTest {
    private val me = "dev.opencover/dev.opencover.watcher.CoverAccessibility"
    private val other = "com.x/com.x.Svc"

    @Test fun turningOffRemovesOnlyUs() = assertEquals(other, withService("$other:$me", me, on = false))
    @Test fun turningOnAppendsUs() = assertEquals("$other:$me", withService(other, me, on = true))
    @Test fun turningOnTwiceDoesNotDuplicate() = assertEquals(me, withService(me, me, on = true))
    @Test fun emptyOrNullListWorks() {
        assertEquals(me, withService(null, me, on = true))
        assertEquals("", withService(me, me, on = false))
    }

    private val banking = setOf("com.gcash")
    private val launchable = setOf("com.android.chrome", "com.gcash")
    private fun resume(pkg: String, cls: String = "$pkg.Main") =
        shouldResume("com.gcash", pkg, cls, "dev.opencover", banking) { it in launchable }

    @Test fun stillInBankingAppStaysPaused() = assertFalse(resume("com.gcash"))
    @Test fun samsungHomeResumes() = assertTrue(resume("com.android.systemui", "com.android.systemui.subscreen.SubHomeActivity"))
    @Test fun openCoverResumes() = assertTrue(resume("dev.opencover"))
    @Test fun anotherAppResumes() = assertTrue(resume("com.android.chrome"))
    @Test fun nonLaunchableHelperStaysPaused() = assertFalse(resume("com.google.android.gms"))
    @Test fun otherSystemUiWindowStaysPaused() = assertFalse(resume("com.android.systemui", "com.android.systemui.Dialog"))
}
