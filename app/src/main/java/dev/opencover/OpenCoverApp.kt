package dev.opencover

import android.app.Application
import dev.opencover.mirror.MirrorEnv
import rikka.shizuku.Shizuku

class OpenCoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Finish a mirror session the process died in. Shizuku hands a new process its binder a moment after start,
        // so the restore waits for it (the listener fires at once if it is already here).
        Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                MirrorEnv.recover(this@OpenCoverApp)
            }
        })
    }
}
