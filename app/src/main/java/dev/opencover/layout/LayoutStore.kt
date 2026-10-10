package dev.opencover.layout

import java.io.File

/** Reads and writes filesDir/layout.json. A bad file is kept as layout.bad.json for inspection. */
class LayoutStore(private val dir: File) {
    private val file = File(dir, "layout.json")

    fun load(fallback: () -> Layout): Layout {
        if (!file.exists()) return fallback()
        return try {
            LayoutJson.decode(file.readText())
        } catch (e: Exception) {
            val bad = File(dir, "layout.bad.json")
            bad.delete()
            file.renameTo(bad)
            fallback()
        }
    }

    /** Write to a temp file, then rename over the original so a crash never leaves half a file. */
    fun save(l: Layout) {
        val tmp = File(dir, "layout.json.tmp")
        tmp.writeText(LayoutJson.encode(l))
        if (!tmp.renameTo(file)) { // Windows JVM (unit tests) can't rename over an existing file
            file.delete()
            tmp.renameTo(file)
        }
    }
}
