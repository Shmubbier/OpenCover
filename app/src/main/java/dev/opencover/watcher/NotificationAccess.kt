package dev.opencover.watcher

import android.app.NotificationManager
import android.os.UserManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
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
        NotificationStore.removed(sbn.key)
    }

    private fun add(sbn: StatusBarNotification, ranking: RankingMap, banner: Boolean) {
        val r = Ranking()
        val alerting = ranking.getRanking(sbn.key, r) && r.importance >= NotificationManager.IMPORTANCE_DEFAULT
        val serial = getSystemService(UserManager::class.java).getSerialNumberForUser(sbn.user)
        val note = sbn.toNote(serial, alerting)
        NotificationStore.posted(this, sbn, note) // banner hookup arrives in Task 3
    }

    companion object {
        var instance: NotificationAccess? = null
            private set
    }
}
