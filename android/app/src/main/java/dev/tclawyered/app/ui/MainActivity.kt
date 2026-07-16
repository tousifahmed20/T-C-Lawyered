package dev.tclawyered.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import dev.tclawyered.app.ui.theme.TcButton
import dev.tclawyered.app.ui.theme.TcTheme
import dev.tclawyered.app.ui.theme.ThemeMode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
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
 * for the capture surfaces, and runs the full [SummarizePipeline] on demand.
 *
 * Entry points that converge here: the in-app URL bar, the share sheet (text or
 * link), and the floating bubble (screen capture / auto-scroll).
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
            val themeMode by settings.themeMode().collectAsState(initial = "system")
            TcTheme(ThemeMode.from(themeMode)) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val openHistory = { startActivity(Intent(this, HistoryActivity::class.java)) }
                    var urlToRead by remember { mutableStateOf<String?>(null) }
                    when {
                        // In-app URL bar → fetch that page and summarize (no screen scrolling needed).
                        urlToRead != null -> {
                            val target = urlToRead!!
                            BackHandler { urlToRead = null }
                            SummarizeScreen(
                                pipeline = pipeline,
                                loadInput = {
                                    val text = fetcher.fetchText(target)
                                    PolicyInput(text, target, PolicyType.guess(text, target))
                                },
                                loadingText = "Fetching the page and summarizing…",
                                onOpenSettings = openSettings,
                            )
                        }
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
                            onSubmitUrl = { urlToRead = normalizeUrl(it) },
                            onEnableBubble = ::enableBubble,
                            onStartCapture = ::requestCapture,
                            onStopReader = { dev.tclawyered.app.control.StopReceiver.stopAll(this) },
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
    // Cache the loaded input so "Summarize anyway" re-runs without re-fetching.
    var loaded by remember { mutableStateOf<PolicyInput?>(null) }
    var force by remember { mutableStateOf(false) }
    LaunchedEffect(force) {
        result = null
        result = try {
            val input = loaded ?: loadInput().also { loaded = it }
            pipeline.run(input.copy(force = force))
        } catch (e: Exception) {
            PipelineResult.Failed(e.message ?: "Something went wrong.")
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("T&C Lawyered", style = MaterialTheme.typography.headlineMedium)
        when (val r = result) {
            null -> Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(loadingText)
            }
            is PipelineResult.Ready -> {
                SummaryView(r.summary, r.source, r.scannedAt)
                DataSafetyView(r.domain)
            }
            is PipelineResult.NeedsProvider -> {
                Text("Add an AI key to summarize new documents. OpenRouter gives you a free one — no card needed.")
                TcButton("Open Settings", onOpenSettings, Modifier.fillMaxWidth())
            }
            is PipelineResult.NotApplicable -> {
                Text(
                    "This doesn't look like a privacy policy, terms, or a legal or financial " +
                        "document — so there's nothing to summarize.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (r.check.reason.isNotBlank()) {
                    Text(r.check.reason, style = MaterialTheme.typography.bodySmall)
                }
                TcButton("Summarize anyway", { force = true }, Modifier.fillMaxWidth())
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

/** Accept "example.com/privacy" as well as a full URL; default to https. */
private fun normalizeUrl(input: String): String {
    val trimmed = input.trim()
    return if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) {
        trimmed
    } else {
        "https://$trimmed"
    }
}

@Composable
private fun HomeScreen(
    sharedNote: String?,
    onSubmitUrl: (String) -> Unit,
    onEnableBubble: () -> Unit,
    onStartCapture: () -> Unit,
    onStopReader: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("T&C Lawyered", style = MaterialTheme.typography.headlineMedium)
        Text("You clicked agree. We actually read it.", style = MaterialTheme.typography.bodyMedium)

        if (sharedNote != null) {
            Text(sharedNote, style = MaterialTheme.typography.bodyLarge)
        }

        // Read a policy by link — no on-screen scrolling needed.
        var url by remember { mutableStateOf("") }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Read a policy by link", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Paste a Terms or Privacy URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
                TcButton(
                    text = "Read this URL",
                    onClick = { if (url.isNotBlank()) onSubmitUrl(url) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = url.isNotBlank(),
                )
            }
        }

        // Read whatever policy is on the screen right now.
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Read what's on your screen", style = MaterialTheme.typography.titleMedium)
                TcButton(
                    text = "Enable the reader bubble",
                    onClick = onEnableBubble,
                    modifier = Modifier.fillMaxWidth(),
                )
                TcButton(
                    text = "Read the current screen",
                    onClick = onStartCapture,
                    modifier = Modifier.fillMaxWidth(),
                )
                // The off switch (also on the notification's Stop action): drops the
                // bubble, screen capture, any in-progress read, and the overlay.
                TcButton(
                    text = "Stop the reader",
                    onClick = onStopReader,
                    modifier = Modifier.fillMaxWidth(),
                    container = MaterialTheme.colorScheme.surfaceVariant,
                    content = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) { OpenRouterTour() }
        }

        // Utility nav — two equal-width buttons, same 56dp height as the rest.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TcButton(
                text = "Settings",
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TcButton(
                text = "History",
                onClick = onOpenHistory,
                modifier = Modifier.weight(1f),
                container = MaterialTheme.colorScheme.surfaceVariant,
                content = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
                "have to pay either. OpenRouter gives you a free key with capable free models.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val steps = listOf(
            "1. Sign up at openrouter.ai (Google or GitHub — free, no card).",
            "2. Open openrouter.ai/settings/keys and create a key (starts with sk-or-).",
            "3. Paste it in Settings, pick a model whose name ends in \":free\".",
            "4. Save, then Test to confirm it works.",
            "5. You're set — share a policy or tap the bubble to summarize.",
        )
        steps.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Free models cost \$0 (rate-limited). Prefer Anthropic, OpenAI, or Gemini? " +
                "Add any of them in Settings instead.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
