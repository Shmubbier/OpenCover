package dev.opencover.ui

import android.content.Intent
import android.os.Bundle
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

class CoverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The main screen is broken: if we were opened anywhere else, move to the cover.
        if (display?.displayId != COVER_DISPLAY) {
            AppLauncher.start(this, Intent(this, CoverActivity::class.java))
            finish()
            return
        }
        setContent {
            Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
                Text("OpenCover", color = Color.White)
            }
        }
    }
}
