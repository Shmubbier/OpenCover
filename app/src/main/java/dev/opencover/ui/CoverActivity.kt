package dev.opencover.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.opencover.COVER_DISPLAY
import dev.opencover.apps.AppLauncher
import dev.opencover.watcher.CoverWatcher
import dev.opencover.widgets.WidgetHost

class CoverActivity : ComponentActivity() {
    private lateinit var widgets: WidgetHost

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The main screen is broken: if we were opened anywhere else, move to the cover.
        if (display?.displayId != COVER_DISPLAY) {
            AppLauncher.start(this, Intent(this, CoverActivity::class.java))
            finish()
            return
        }
        widgets = WidgetHost(this) { id, info, _ ->
            Toast.makeText(this, "PROBE widget $id added: ${info.provider}", Toast.LENGTH_LONG).show()
            Log.i("OCPROBE", "added $id ${info.provider}")
        }
        startForegroundService(Intent(this, CoverWatcher::class.java))
        // PROBE (removed in Task 9): adb ... --es probeWidget <pkg/cls>
        intent.getStringExtra("probeWidget")?.let { flat ->
            widgets.find(flat)?.let { widgets.begin(it, 0) } ?: Log.e("OCPROBE", "no provider $flat")
        }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("OpenCover", color = Color.White)
            }
        }
    }

    @Deprecated("Widget flows need startActivityForResult with display options")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!widgets.onResult(requestCode, resultCode)) super.onActivityResult(requestCode, resultCode, data)
    }
}
