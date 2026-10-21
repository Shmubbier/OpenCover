package dev.opencover.mirror

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import dev.opencover.quick.ShizukuShell

/**
 * Translucent activity that asks for the system screen-capture consent.
 * On accept: starts MirrorService then opens MirrorActivity on the cover.
 */
class ConsentActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val captureIntent = mpm.createScreenCaptureIntent()
        startActivityForResult(captureIntent, RC)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC || resultCode != RESULT_OK || data == null) { finish(); return }

        val serviceIntent = Intent(this, MirrorService::class.java)
            .putExtra(MirrorService.KEY_RESULT_CODE, resultCode)
            .putExtra(MirrorService.KEY_DATA, data)
        startForegroundService(serviceIntent)
        finish()
    }

    companion object { private const val RC = 1 }
}
