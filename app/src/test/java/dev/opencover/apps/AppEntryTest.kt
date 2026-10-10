package dev.opencover.apps

import org.junit.Assert.assertEquals
import org.junit.Test

class AppEntryTest {
    private fun e(label: String, used: Long = 0) = AppEntry(label, label.lowercase(), "x.Main", 0, used)
    private val apps = listOf(e("chrome", 5), e("Camera", 9), e("YouTube", 1), e("9GAG", 3))

    @Test fun azIgnoresCase() =
        assertEquals(listOf("9GAG", "Camera", "chrome", "YouTube"), filterApps(apps, "", Sort.AZ).map { it.label })

    @Test fun recentIsMostRecentFirst() =
        assertEquals(listOf("Camera", "chrome", "9GAG", "YouTube"), filterApps(apps, "", Sort.RECENT).map { it.label })

    @Test fun searchIsCaseInsensitiveSubstringAndTrimmed() =
        assertEquals(listOf("Camera", "chrome"), filterApps(apps, " C ", Sort.AZ).map { it.label })

    @Test fun letterIndexPointsAtFirstOfEachLetterAndGroupsNonLetters() {
        val sorted = filterApps(apps, "", Sort.AZ)
        assertEquals(mapOf('#' to 0, 'C' to 1, 'Y' to 3), letterIndex(sorted))
    }
}
