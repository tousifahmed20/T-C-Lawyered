package dev.tclawyered.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.tclawyered.app.overlay.BubbleService

/**
 * Home screen. First slice: onboarding (free OpenRouter tour), the permission
 * gateways for the two headline capabilities (draw-over-other-apps for the
 * bubble, screen-capture consent for OCR), and a placeholder for the shared
 * payload handed over by the share sheet.
 *
 * SCOPE: the summary rendering + full pipeline are the next slice. For now a
 * shared payload just shows its detected domain/length so the flow is visible.
 */
class MainActivity : ComponentActivity() {

    private val captureConsent = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            dev.tclawyered.app.capture.ScreenCaptureService.start(this, result.resultCode, data)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedNote = intent?.takeIf { it.hasExtra(EXTRA_SHARED_PAYLOAD) }?.let {
            val isUrl = it.getBooleanExtra(EXTRA_SHARED_IS_URL, false)
            val domain = it.getStringExtra(EXTRA_SHARED_DOMAIN).orEmpty()
            val payload = it.getStringExtra(EXTRA_SHARED_PAYLOAD).orEmpty()
            if (isUrl) "Received link for: ${domain.ifEmpty { "(unknown site)" }}"
            else "Received ${payload.length} characters of policy text to summarize."
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        sharedNote = sharedNote,
                        onEnableBubble = ::enableBubble,
                        onStartCapture = ::requestCapture,
                    )
                }
            }
        }
    }

    /** Ensure "draw over other apps", then start the floating bubble. */
    private fun enableBubble() {
        if (!Settings.canDrawOverlays(this)) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        BubbleService.start(this)
    }

    /** Fire the system screen-capture consent dialog. */
    private fun requestCapture() {
        val mgr = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        captureConsent.launch(mgr.createScreenCaptureIntent())
    }

    companion object {
        const val EXTRA_SHARED_IS_URL = "shared_is_url"
        const val EXTRA_SHARED_PAYLOAD = "shared_payload"
        const val EXTRA_SHARED_DOMAIN = "shared_domain"
    }
}

@Composable
private fun HomeScreen(
    sharedNote: String?,
    onEnableBubble: () -> Unit,
    onStartCapture: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("T&C Lawyered", style = MaterialTheme.typography.headlineSmall)
        Text("You clicked agree. We actually read it.", style = MaterialTheme.typography.bodyMedium)

        if (sharedNote != null) {
            Text(sharedNote, style = MaterialTheme.typography.bodyLarge)
        }

        OpenRouterTour()

        Button(onClick = onEnableBubble, modifier = Modifier.padding(top = 8.dp)) {
            Text("Enable the floating reader bubble")
        }
        Button(onClick = onStartCapture) {
            Text("Read the current screen (screen capture)")
        }
    }
}

/** The free-setup tour, mirroring the extension's OpenRouter onboarding. */
@Composable
private fun OpenRouterTour() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("🆓 Free setup — no credit card needed", style = MaterialTheme.typography.titleMedium)
        Text(
            "T&C Lawyered runs on your OWN AI key, so nothing is billed to us — and you don't " +
                "have to pay either. OpenRouter gives you a free key with capable :free models.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val steps = listOf(
            "1. Sign up at openrouter.ai (Google or GitHub — free, no card).",
            "2. Open openrouter.ai/settings/keys and create a key (starts with sk-or-).",
            "3. Paste it in Settings, pick a :free model.",
            "4. Save, then Test to confirm it works.",
            "5. You're set — share a policy or tap the bubble to summarize.",
        )
        steps.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            ":free models cost \$0 (rate-limited). Prefer Anthropic, OpenAI, or Gemini? " +
                "Add any of them in Settings instead.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
