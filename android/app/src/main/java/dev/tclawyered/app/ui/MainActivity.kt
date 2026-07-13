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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.tclawyered.app.content.PolicyFetcher
import dev.tclawyered.app.data.SettingsRepository
import dev.tclawyered.app.data.hive.HiveClient
import dev.tclawyered.app.data.local.LocalStore
import dev.tclawyered.app.data.safety.ReputationClient
import dev.tclawyered.app.model.PolicyType
import dev.tclawyered.app.overlay.BubbleService
import dev.tclawyered.app.pipeline.PipelineResult
import dev.tclawyered.app.pipeline.PolicyInput
import dev.tclawyered.app.pipeline.SummarizePipeline

/**
 * Home + summarize screen. Hosts the onboarding tour and the permission gateways
 * for the two capture surfaces, and — when text arrives from the share sheet —
 * runs the full [SummarizePipeline] and renders the result.
 *
 * SCOPE (Slice 3): the shared-TEXT path is wired end-to-end. Fetching a shared
 * URL's page (and the bubble→capture→OCR wiring) is Slice 4.
 */
class MainActivity : ComponentActivity() {

    private val store by lazy { LocalStore(applicationContext) }
    private val settings by lazy { SettingsRepository(applicationContext) }
    private val hive by lazy { HiveClient() }
    private val fetcher by lazy { PolicyFetcher() }
    private val reputation by lazy { ReputationClient(applicationContext) }
    private val pipeline by lazy {
        SummarizePipeline(store, settings, hive, lifecycleScope, reputation)
    }

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

        val hasShare = intent?.hasExtra(EXTRA_SHARED_PAYLOAD) == true
        val isUrl = intent?.getBooleanExtra(EXTRA_SHARED_IS_URL, false) == true
        val payload = intent?.getStringExtra(EXTRA_SHARED_PAYLOAD).orEmpty()
        val url = intent?.getStringExtra(EXTRA_SHARED_DOMAIN).orEmpty()
        val openSettings = { startActivity(Intent(this, SettingsActivity::class.java)) }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val openHistory = { startActivity(Intent(this, HistoryActivity::class.java)) }
                    when {
                        // Shared policy text → summarize it directly.
                        hasShare && !isUrl && payload.isNotBlank() -> SummarizeScreen(
                            pipeline = pipeline,
                            loadInput = {
                                PolicyInput(payload, url, PolicyType.guess(payload, url))
                            },
                            loadingText = "Reading and summarizing… up to a minute for a new document.",
                            onOpenSettings = openSettings,
                        )
                        // Shared link → fetch the page, extract text, then summarize.
                        hasShare && isUrl && payload.isNotBlank() -> SummarizeScreen(
                            pipeline = pipeline,
                            loadInput = {
                                val text = fetcher.fetchText(payload)
                                PolicyInput(text, payload, PolicyType.guess(text, payload))
                            },
                            loadingText = "Fetching the page and summarizing…",
                            onOpenSettings = openSettings,
                        )
                        else -> HomeScreen(
                            sharedNote = null,
                            onEnableBubble = ::enableBubble,
                            onStartCapture = ::requestCapture,
                            onOpenSettings = openSettings,
                            onOpenHistory = openHistory,
                        )
                    }
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
private fun SummarizeScreen(
    pipeline: SummarizePipeline,
    loadInput: suspend () -> PolicyInput,
    loadingText: String,
    onOpenSettings: () -> Unit,
) {
    // null = still running; otherwise the pipeline outcome.
    var result by remember { mutableStateOf<PipelineResult?>(null) }
    LaunchedEffect(Unit) {
        result = try {
            pipeline.run(loadInput())
        } catch (e: Exception) {
            PipelineResult.Failed(e.message ?: "Something went wrong.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("T&C Lawyered", style = MaterialTheme.typography.headlineSmall)
        when (val r = result) {
            null -> Text(loadingText)
            is PipelineResult.Ready -> {
                SummaryView(r.summary, r.source, r.scannedAt)
                DataSafetyView(r.domain)
            }
            is PipelineResult.NeedsProvider -> {
                Text("Add an AI key to summarize new documents. OpenRouter gives you a free one — no card needed.")
                Button(onClick = onOpenSettings) { Text("Open Settings") }
            }
            is PipelineResult.Failed -> Text(humanizeError(r.message))
        }
    }
}

private fun humanizeError(message: String): String = when {
    message.startsWith("INVALID_API_KEY") -> "Your API key was rejected. Check it in Settings."
    message.startsWith("RATE_LIMITED") -> "Provider rate limit hit. Wait a moment and retry."
    message.startsWith("TIMEOUT") -> "The request timed out. Try again."
    message.startsWith("NETWORK") -> "Couldn't reach the provider. Check your connection."
    message.startsWith("EMPTY_TEXT") -> "No policy text was found to summarize."
    message.startsWith("FETCH_FAILED") -> "Couldn't open that page. Try sharing the selected text instead."
    message.startsWith("FETCH_EMPTY") -> "That page didn't have enough readable text. Try selecting the policy text and sharing it."
    else -> message
}

@Composable
private fun HomeScreen(
    sharedNote: String?,
    onEnableBubble: () -> Unit,
    onStartCapture: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
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

        Button(onClick = onOpenSettings, modifier = Modifier.padding(top = 8.dp)) {
            Text("Settings — add your AI key")
        }
        Button(onClick = onEnableBubble) {
            Text("Enable the floating reader bubble")
        }
        Button(onClick = onStartCapture) {
            Text("Read the current screen (screen capture)")
        }
        Button(onClick = onOpenHistory) {
            Text("History")
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
