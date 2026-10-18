package dev.opencover.call

import android.app.Activity
import android.os.Bundle

/** Debug only: `--es phase ongoing` for the in-call screen, ringing otherwise. */
class DemoCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallStore.demo(this, if (intent.getStringExtra("phase") == "ongoing") Phase.ONGOING else Phase.RINGING)
        CallStore.show(this)
        finish()
    }
}
