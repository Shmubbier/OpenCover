package dev.opencover

import android.app.Application
import dev.opencover.mirror.MirrorEnv
import rikka.shizuku.Shizuku

class OpenCoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Finish a mirror session the process died in: at once (most of it needs no Shizuku), and again once Shizuku
        // hands this process its binder, for the parts that need it.
        MirrorEnv.recover(this)
        Shizuku.addBinderReceivedListenerSticky(object : Shizuku.OnBinderReceivedListener {
            override fun onBinderReceived() {
                Shizuku.removeBinderReceivedListener(this)
                MirrorEnv.recover(this@OpenCoverApp)
            }
        })
    }
}
