package dev.opencover.media

import kotlin.math.roundToInt

enum class Repeat {
    OFF, ALL, ONE;

    fun next() = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }
}

/**
 * One active media session as plain data. [updatedAt] is the elapsed-realtime of [positionMs].
 * [shuffle] and [repeat] are null when the app doesn't support them. [durationMs] <= 0 means unknown.
 */
data class Player(
    val id: String,
    val pkg: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val positionMs: Long,
    val updatedAt: Long,
    val speed: Float,
    val playing: Boolean,
    val shuffle: Boolean?,
    val repeat: Repeat?,
    val canSeek: Boolean,
    val canSkipNext: Boolean,
    val canSkipPrev: Boolean,
)

/** Position now, advancing between app updates while playing. */
fun livePosition(p: Player, now: Long): Long {
    val pos = if (p.playing) p.positionMs + ((now - p.updatedAt) * p.speed).toLong() else p.positionMs
    return if (p.durationMs > 0) pos.coerceIn(0, p.durationMs) else pos.coerceAtLeast(0)
}

/** The one playing, else the most recently updated. */
fun defaultPlayer(players: List<Player>): Player? =
    players.filter { it.playing }.maxByOrNull { it.updatedAt } ?: players.maxByOrNull { it.updatedAt }

/** Seek bar: [fromTop] 0 = start, 1 = end. Null if the duration is unknown. */
fun seekTarget(fromTop: Float, durationMs: Long): Long? =
    if (durationMs <= 0) null else (fromTop.coerceIn(0f, 1f) * durationMs).toLong()

/** Volume bar: top = loudest. */
fun volumeFor(fromTop: Float, max: Int): Int = ((1f - fromTop.coerceIn(0f, 1f)) * max).roundToInt()

fun formatTime(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
