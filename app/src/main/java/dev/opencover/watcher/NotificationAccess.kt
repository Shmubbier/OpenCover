package dev.opencover.watcher

import android.app.NotificationManager
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dev.opencover.notify.Banner
import dev.opencover.call.CallStore
import dev.opencover.edge.EdgeLight
import dev.opencover.notify.NotificationStore
import dev.opencover.notify.toNote

/** Feeds the notifications page and pop-up banner. */
class NotificationAccess : NotificationListenerService() {
    override fun onListenerConnected() {
        instance = this
        NotificationStore.reset()
        activeNotifications?.forEach { add(it, currentRanking, banner = false) } // reconnect: no pop-ups
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) =
        add(sbn, rankingMap, banner = true)

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        CallStore.removed(this, sbn.key)
        NotificationStore.removed(sbn.key)
        Banner.removed(sbn.key)
    }

    private fun add(sbn: StatusBarNotification, ranking: RankingMap, banner: Boolean) {
        if (banner) CallStore.posted(this, sbn) // calls get the cover call screen
        val r = Ranking()
        // Respect Do Not Disturb: it suppresses peeking without lowering importance.
        val alerting = ranking.getRanking(sbn.key, r) && r.importance >= NotificationManager.IMPORTANCE_DEFAULT &&
            r.matchesInterruptionFilter() && r.suppressedVisualEffects and NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK == 0
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(sbn.user)
        val note = sbn.toNote(serial, alerting)
        if (NotificationStore.posted(this, sbn, note) && banner) {
            Banner.show(this, note)
            EdgeLight.notified(this, note.pkg)
        }
    }

    companion object {
        var instance: NotificationAccess? = null
            private set
    }
}
