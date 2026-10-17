package dev.opencover

import android.app.Application
import dev.opencover.mirror.MirrorEnv

class OpenCoverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        MirrorEnv.recover(this) // finish a mirror session the process died in
    }
}
