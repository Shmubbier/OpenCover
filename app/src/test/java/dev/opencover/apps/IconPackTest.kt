package dev.opencover.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IconPackTest {
    @Test fun componentKeys() {
        assertEquals("com.a/com.a.Main", componentKey("ComponentInfo{com.a/com.a.Main}"))
        assertEquals("com.a/com.a.Main", componentKey("ComponentInfo{com.a/.Main}")) // relative class
        assertEquals("com.a/com.a.Main", componentKey(" ComponentInfo{ com.a/com.a.Main } "))
        assertNull(componentKey(":LAUNCHER_ACTION_APP_DRAWER")) // packs list launcher shortcuts too
        assertNull(componentKey("ComponentInfo{com.a}"))
        assertNull(componentKey("ComponentInfo{/x}"))
    }
}
