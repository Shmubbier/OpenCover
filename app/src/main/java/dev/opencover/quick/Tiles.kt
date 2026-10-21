package dev.opencover.quick

import android.content.Context
import kotlin.math.max

/** What a tile needs before it can switch. LATER: not working yet, shown grey. */
enum class Need { NONE, SHIZUKU, SECURE_SETTINGS, NOTIFICATION_ACCESS, LATER }

/** Declaration order is the default order. Ids (names) are saved, so never rename one. */
enum class Tile(val label: String, val need: Need) {
    WIFI("Wi-Fi", Need.SHIZUKU),
    BLUETOOTH("Bluetooth", Need.SHIZUKU),
    FLASHLIGHT("Flashlight", Need.NONE),
    ROTATION("Rotate", Need.SHIZUKU), // an action: turns the last cover app a quarter turn
    SOUND("Sound", Need.NONE),
    DND("Do not disturb", Need.NOTIFICATION_ACCESS),
    BATTERY_SAVER("Battery saver", Need.SHIZUKU),
    LOCATION("Location", Need.SECURE_SETTINGS),
    MOBILE_DATA("Mobile data", Need.SHIZUKU),
    AIRPLANE("Airplane", Need.SHIZUKU),
    NFC("NFC", Need.SHIZUKU),
    DARK_MODE("Dark mode", Need.SHIZUKU),
    KEEP_AWAKE("Keep awake", Need.NONE),
    MIRROR("Mirror", Need.NONE), // Shizuku adds live touch and hides OpenCover for banking apps; not needed
}

const val COLS = 4
const val ROWS = 2
const val PAGE_SIZE = COLS * ROWS

/** Null (never saved) = default. Unknown and repeated ids are dropped; tiles added later start hidden. */
fun parseTiles(saved: String?): List<Tile> =
    saved?.split(',')?.mapNotNull { id -> Tile.entries.find { it.name == id } }?.distinct() ?: Tile.entries.toList()

fun formatTiles(tiles: List<Tile>) = tiles.joinToString(",") { it.name }

fun hiddenTiles(shown: List<Tile>) = Tile.entries - shown.toSet()

fun List<Tile>.moved(from: Int, to: Int): List<Tile> = toMutableList().apply { add(to, removeAt(from)) }

/** Where a tile dragged by [dCols] columns and [dRows] rows lands. Stays on its page and in range. */
fun dropIndex(index: Int, dCols: Int, dRows: Int, size: Int): Int {
    val page = index / PAGE_SIZE
    val col = (index % COLS + dCols).coerceIn(0, COLS - 1)
    val row = (index % PAGE_SIZE / COLS + dRows).coerceIn(0, ROWS - 1)
    return (page * PAGE_SIZE + row * COLS + col).coerceAtMost(size - 1)
}

/** Tile pages, plus the "+" page in edit mode when something is hidden. */
fun pageCount(shown: Int, editing: Boolean, hidden: Int) =
    max(1, (shown + PAGE_SIZE - 1) / PAGE_SIZE) + if (editing && hidden > 0) 1 else 0

enum class Ringer {
    RING, VIBRATE, MUTE;

    /** Mute needs DND access; without it the cycle is Ring, Vibrate. */
    fun next(canMute: Boolean) = when (this) {
        RING -> VIBRATE
        VIBRATE -> if (canMute) MUTE else RING
        MUTE -> RING
    }
}

/** With no tiles left there is nothing to long-press, so stay in edit mode. */
fun inEditMode(editing: Boolean, shown: Int) = editing || shown == 0

fun usable(need: Need, has: Set<Need>) = need == Need.NONE || need != Need.LATER && need in has

object QuickPrefs {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("quick", Context.MODE_PRIVATE)
    fun tiles(ctx: Context) = parseTiles(prefs(ctx).getString("tiles", null))
    fun setTiles(ctx: Context, tiles: List<Tile>) = prefs(ctx).edit().putString("tiles", formatTiles(tiles)).apply()
}
