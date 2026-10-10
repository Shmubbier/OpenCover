package dev.opencover.layout

import dev.opencover.grid.Cell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LayoutStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private val fallback = Layout(listOf(Page(emptyList())))
    private val sample = Layout(listOf(Page(listOf(AppItem("a", Cell(1, 1, 1, 1), "p", "p.Main", 0)))))

    private fun storeWith(content: String?): LayoutStore {
        if (content != null) File(tmp.root, "layout.json").writeText(content)
        return LayoutStore(tmp.root)
    }

    @Test fun missingFileUsesFallback() = assertEquals(fallback, storeWith(null).load { fallback })

    @Test fun saveThenLoad() {
        val s = storeWith(null)
        s.save(sample)
        s.save(sample) // overwriting an existing file must work too
        assertEquals(sample, s.load { fallback })
    }

    private fun assertFallsBack(content: String) {
        assertEquals(fallback, storeWith(content).load { fallback })
        assertTrue(File(tmp.root, "layout.bad.json").exists())
    }

    @Test fun truncatedJsonFallsBack() = assertFallsBack("""{"version":1,"pages":[{"items":[""")
    @Test fun unknownVersionFallsBack() = assertFallsBack("""{"version":2,"pages":[{"items":[]}]}""")
    @Test fun noPagesFallsBack() = assertFallsBack("""{"version":1,"pages":[]}""")

    @Test fun outOfBoundsFallsBack() = assertFallsBack(
        """{"version":1,"pages":[{"items":[{"id":"a","type":"app","x":3,"y":0,"w":2,"h":1,"package":"p","activity":"p.M","user":0}]}]}"""
    )

    @Test fun overlapFallsBack() = assertFallsBack(
        """{"version":1,"pages":[{"items":[
          {"id":"a","type":"app","x":0,"y":0,"w":2,"h":2,"package":"p","activity":"p.M","user":0},
          {"id":"b","type":"app","x":1,"y":1,"w":1,"h":1,"package":"q","activity":"q.M","user":0}]}]}"""
    )

    @Test fun unknownItemTypeFallsBack() = assertFallsBack(
        """{"version":1,"pages":[{"items":[{"id":"a","type":"folder","x":0,"y":0,"w":1,"h":1}]}]}"""
    )
}
