package dev.opencover.notify

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.opencover.apps.AppLauncher
import dev.opencover.ui.toast

/** Reply sheet on the cover screen: type or speak, then send through the notification's reply action. */
class ReplyActivity : ComponentActivity() {
    private var recognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val key = intent.getStringExtra(KEY) ?: return finish()
        val index = intent.getIntExtra(INDEX, 0)
        val note = NotificationStore.notes.find { it.key == key } ?: return finish()
        val canSpeak = SpeechRecognizer.isRecognitionAvailable(this) &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ReplySheet(note, canSpeak, onMic = ::listen) { text ->
                    if (NotificationStore.reply(this, key, index, text)) finish() else toast(this, "Couldn't send")
                }
            }
        }
    }

    private fun listen(onHeard: (String) -> Unit) {
        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onResults(results: Bundle) {
                    results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let(onHeard)
                }
                override fun onError(error: Int) = toast(this@ReplyActivity, "Didn't catch that")
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM))
        }
    }

    override fun onDestroy() {
        recognizer?.destroy()
        super.onDestroy()
    }

    companion object {
        private const val KEY = "key"
        private const val INDEX = "index"

        fun open(ctx: Context, key: String, index: Int) =
            AppLauncher.start(ctx, Intent(ctx, ReplyActivity::class.java).putExtra(KEY, key).putExtra(INDEX, index))
    }
}

@Composable
private fun ReplySheet(note: Note, canSpeak: Boolean, onMic: ((String) -> Unit) -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().background(Color.Black).imePadding().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(note.title, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(note.text, color = Color.LightGray, fontSize = 14.sp, maxLines = 3)
        Spacer(Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text("Reply") }, modifier = Modifier.weight(1f))
            if (canSpeak) TextButton(onClick = { onMic { heard -> text = if (text.isBlank()) heard else "$text $heard" } }) { Text("Mic") }
            TextButton(onClick = { onSend(text) }, enabled = text.isNotBlank()) { Text("Send") }
        }
    }
}
