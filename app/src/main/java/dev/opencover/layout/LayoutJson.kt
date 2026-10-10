package dev.opencover.layout

import dev.opencover.grid.Cell
import org.json.JSONArray
import org.json.JSONObject

object LayoutJson {
    fun encode(l: Layout): String = JSONObject()
        .put("version", 1)
        .put("pages", JSONArray(l.pages.map { p -> JSONObject().put("items", JSONArray(p.items.map(::encodeItem))) }))
        .toString()

    private fun encodeItem(i: Item): JSONObject {
        val o = JSONObject().put("id", i.id)
            .put("x", i.cell.x).put("y", i.cell.y).put("w", i.cell.w).put("h", i.cell.h)
        return when (i) {
            is AppItem -> o.put("type", "app").put("package", i.pkg).put("activity", i.activity).put("user", i.user)
            is WidgetItem -> o.put("type", "widget").put("widgetId", i.widgetId).put("provider", i.provider).put("user", i.user)
        }
    }

    /** Throws on anything malformed or invalid; LayoutStore turns that into a fallback. */
    fun decode(s: String): Layout {
        val root = JSONObject(s)
        require(root.getInt("version") == 1) { "unknown version" }
        val pagesJson = root.getJSONArray("pages")
        val pages = (0 until pagesJson.length()).map { pi ->
            val arr = pagesJson.getJSONObject(pi).getJSONArray("items")
            val items = (0 until arr.length()).map { decodeItem(arr.getJSONObject(it)) }
            items.forEachIndexed { a, ia ->
                require(ia.cell.inBounds()) { "item ${ia.id} off the grid" }
                items.drop(a + 1).forEach { ib -> require(!ia.cell.overlaps(ib.cell)) { "items ${ia.id}/${ib.id} overlap" } }
            }
            Page(items)
        }
        require(pages.isNotEmpty()) { "no pages" }
        return Layout(pages)
    }

    private fun decodeItem(o: JSONObject): Item {
        val c = Cell(o.getInt("x"), o.getInt("y"), o.getInt("w"), o.getInt("h"))
        val id = o.getString("id")
        return when (val type = o.getString("type")) {
            "app" -> AppItem(id, c, o.getString("package"), o.getString("activity"), o.getLong("user"))
            "widget" -> WidgetItem(id, c, o.getInt("widgetId"), o.getString("provider"), o.optLong("user", 0))
            else -> throw IllegalArgumentException("unknown item type $type")
        }
    }
}
