package dev.opencover.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.opencover.watcher.NotificationAccess

/** Active media sessions as [Player]s (Compose state). Started while the media panel is open. */
object MediaHub {
    var players by mutableStateOf(emptyList<Player>())
        private set
    private val controllers = LinkedHashMap<String, MediaControllerCompat>()
    private val handler = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private lateinit var appCtx: Context

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { rebuild(it.orEmpty()) }

    private val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) = refresh()
        override fun onMetadataChanged(metadata: MediaMetadataCompat?) = refresh()
        override fun onShuffleModeChanged(shuffleMode: Int) = refresh()
        override fun onRepeatModeChanged(repeatMode: Int) = refresh()
        override fun onSessionReady() = refresh() // shuffle and repeat become readable here
        override fun onSessionDestroyed() = refresh()
    }

    /** False if notification access (needed to see media sessions) isn't granted. */
    fun start(ctx: Context): Boolean {
        appCtx = ctx.applicationContext
        val m = ctx.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(ctx, NotificationAccess::class.java)
        return try {
            m.addOnActiveSessionsChangedListener(sessionsChanged, listener)
            manager = m
            rebuild(m.getActiveSessions(listener))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        manager = null
        controllers.values.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        players = emptyList()
    }

    fun art(id: String): Bitmap? = controllers[id]?.metadata?.run {
        getBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART)
            ?: getBitmap(MediaMetadataCompat.METADATA_KEY_ART)
            ?: getBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON)
    }

    fun controls(id: String) = controllers[id]?.transportControls

    private fun rebuild(sessions: List<MediaController>) {
        controllers.values.forEach { it.unregisterCallback(callback) }
        controllers.clear()
        sessions.forEach { s ->
            val c = MediaControllerCompat(appCtx, MediaSessionCompat.Token.fromToken(s.sessionToken))
            c.registerCallback(callback, handler)
            controllers[s.sessionToken.hashCode().toString()] = c
        }
        refresh()
    }

    private fun refresh() {
        players = controllers.mapNotNull { (id, c) -> c.toPlayer(id) }
    }
}

private fun MediaControllerCompat.toPlayer(id: String): Player? {
    val s = playbackState ?: return null
    val m = metadata
    val a = s.actions
    fun has(action: Long) = a and action != 0L
    return Player(
        id = id,
        pkg = packageName,
        title = m?.getString(MediaMetadataCompat.METADATA_KEY_TITLE)
            ?: m?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE).orEmpty(),
        artist = m?.getString(MediaMetadataCompat.METADATA_KEY_ARTIST)
            ?: m?.getString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty(),
        durationMs = m?.getLong(MediaMetadataCompat.METADATA_KEY_DURATION) ?: 0,
        positionMs = s.position,
        updatedAt = s.lastPositionUpdateTime,
        speed = s.playbackSpeed,
        playing = s.state == PlaybackStateCompat.STATE_PLAYING,
        shuffle = if (has(PlaybackStateCompat.ACTION_SET_SHUFFLE_MODE)) shuffleMode != PlaybackStateCompat.SHUFFLE_MODE_NONE else null,
        repeat = if (has(PlaybackStateCompat.ACTION_SET_REPEAT_MODE)) when (repeatMode) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> Repeat.ONE
            PlaybackStateCompat.REPEAT_MODE_ALL, PlaybackStateCompat.REPEAT_MODE_GROUP -> Repeat.ALL
            else -> Repeat.OFF
        } else null,
        canSeek = has(PlaybackStateCompat.ACTION_SEEK_TO),
        canSkipNext = has(PlaybackStateCompat.ACTION_SKIP_TO_NEXT),
        canSkipPrev = has(PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS),
    )
}

fun Repeat.toCompat() = when (this) {
    Repeat.OFF -> PlaybackStateCompat.REPEAT_MODE_NONE
    Repeat.ALL -> PlaybackStateCompat.REPEAT_MODE_ALL
    Repeat.ONE -> PlaybackStateCompat.REPEAT_MODE_ONE
}
