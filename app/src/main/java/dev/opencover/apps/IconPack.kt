package dev.opencover.apps

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** An installed icon pack: any pack made for Nova, ADW or GO launchers (they share one format). */
data class IconPackInfo(val pkg: String, val label: String)

/**
 * A pack's icons, read from its appfilter.xml, which maps an app's component ("ComponentInfo{pkg/Activity}") to a
 * drawable in the pack. Apps the pack doesn't cover keep their own icon.
 */
class IconPack private constructor(private val res: Resources, private val pkg: String, private val map: Map<String, String>) {
    /** The pack's icon for this app: its exact activity, else any icon the pack has for the app's package. */
    fun icon(appPkg: String, activity: String): Drawable? {
        val name = map["$appPkg/$activity"] ?: map[appPkg] ?: return null
        val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: return null
        return runCatching { res.getDrawable(id, null) }.getOrNull()
    }

    companion object {
        private val ACTIONS = listOf(
            "org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.gau.go.launcherex.theme", "org.adw.ActivityStarter.THEMES",
        )

        fun installed(ctx: Context): List<IconPackInfo> {
            val pm = ctx.packageManager
            return ACTIONS.flatMap { pm.queryIntentActivities(Intent(it), 0) }
                .map { it.activityInfo.applicationInfo }.distinctBy { it.packageName }
                .map { IconPackInfo(it.packageName, pm.getApplicationLabel(it).toString()) }
                .sortedBy { it.label.lowercase() }
        }

        // ponytail: parsed on the calling (main) thread, a few hundred ms for a big pack, once per pick; move off-thread if it janks.
        fun load(ctx: Context, pkg: String): IconPack? = try {
            val res = ctx.packageManager.getResourcesForApplication(pkg)
            val map = HashMap<String, String>()
            // appfilter.xml is a compiled XML resource in most packs, a raw asset in some.
            val id = res.getIdentifier("appfilter", "xml", pkg)
            if (id != 0) res.getXml(id).use { readAppFilter(it, map) }
            else ctx.createPackageContext(pkg, 0).assets.open("appfilter.xml").use { input ->
                readAppFilter(Xml.newPullParser().apply { setInput(input, null) }, map)
            }
            IconPack(res, pkg, map)
        } catch (e: Exception) {
            null
        }

        private fun readAppFilter(p: XmlPullParser, into: MutableMap<String, String>) {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG || p.name != "item") continue
                val key = componentKey(p.getAttributeValue(null, "component") ?: continue) ?: continue
                val drawable = p.getAttributeValue(null, "drawable") ?: continue
                into.putIfAbsent(key, drawable)
                into.putIfAbsent(key.substringBefore('/'), drawable) // by package, for apps whose activity moved
            }
        }
    }
}

/** "ComponentInfo{pkg/.Main}" as "pkg/pkg.Main"; null if it isn't a component. */
fun componentKey(component: String): String? {
    val inner = component.substringAfter("ComponentInfo{", "").substringBefore('}').trim()
    val pkg = inner.substringBefore('/', "").takeIf { it.isNotEmpty() } ?: return null
    val cls = inner.substringAfter('/').takeIf { it.isNotEmpty() } ?: return null
    return "$pkg/${if (cls.startsWith('.')) pkg + cls else cls}"
}
