package dev.tclawyered.app.pipeline

import dev.tclawyered.app.core.Domain
import dev.tclawyered.app.core.Hasher
import dev.tclawyered.app.data.SettingsRepository
import dev.tclawyered.app.data.hive.HiveClient
import dev.tclawyered.app.data.hive.UploadRequest
import dev.tclawyered.app.data.local.LocalStore
import dev.tclawyered.app.llm.LlmClient
import dev.tclawyered.app.model.PolicyType
import dev.tclawyered.app.model.Severity
import dev.tclawyered.app.model.Summary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant

/** Input to the pipeline: raw policy text plus where it came from. */
data class PolicyInput(
    val text: String,
    val url: String,
    val policyType: PolicyType,
    val force: Boolean = false,
)

/** Outcome the UI renders. */
sealed interface PipelineResult {
    data class Ready(val summary: Summary, val source: String, val scannedAt: Long) : PipelineResult
    data object NeedsProvider : PipelineResult
    data class Failed(val message: String) : PipelineResult
}

/**
 * The orchestrator (F-01…F-08) — the Kotlin port of service-worker.js
 * onPolicyDetected. Same order: local cache → hive → validate → summarize →
 * diff → persist → gated upload, with the 2-month staleness re-check and
 * graceful degradation to a cached summary when no provider is available.
 *
 * `bgScope` runs the fire-and-forget hive upload so it never blocks the result.
 */
class SummarizePipeline(
    private val store: LocalStore,
    private val settings: SettingsRepository,
    private val hive: HiveClient,
    private val bgScope: CoroutineScope,
    llm: LlmClient = LlmClient(),
) {
    private val validator = Validator(llm)
    private val summarizer = Summarizer(llm)
    private val differ = Differ(llm)

    suspend fun run(input: PolicyInput): PipelineResult {
        val text = input.text.trim()
        if (text.isEmpty()) return PipelineResult.Failed("EMPTY_TEXT: No document text found.")

        val type = input.policyType
        val domain = Domain.fromUrl(input.url).ifEmpty { Domain.normalize(input.url) }
        val hiveEnabled = settings.hiveEnabled()
        val hash = withContext(Dispatchers.Default) { Hasher.computeHash(text) }

        val existing = store.snapshot(hash)
        val stale = existing != null && store.isStale(existing)
        val refresh = input.force || stale

        // 1) Fresh local hit → render instantly.
        if (existing != null && !refresh) {
            return PipelineResult.Ready(store.decodeSummary(existing), "local", existing.lastCheckedAt)
        }

        // 2) Hive lookup (skipped on refresh — same hash means the same summary).
        if (hiveEnabled && !refresh) {
            val hit = withContext(Dispatchers.IO) { hive.lookup(hash, domain) }
            if (hit.found && hit.summary != null) {
                store.putSnapshot(hash, domain, type, text, hit.summary, parentHash = null)
                return PipelineResult.Ready(hit.summary, "hive", System.currentTimeMillis())
            }
        }

        // 3) Need the user's LLM key from here on.
        val config = settings.getActiveConfig()
        if (config == null) {
            // Graceful: keep showing a stale cached summary rather than erroring.
            if (existing != null) {
                return PipelineResult.Ready(store.decodeSummary(existing), "local-stale", existing.lastCheckedAt)
            }
            return PipelineResult.NeedsProvider
        }

        return try {
            // 4) Authenticity (gates hive upload, not local summarize).
            val genuine = validator.validate(input.url, text, config)

            // 5) Summarize.
            val base = summarizer.summarize(text, domain, type, config)

            // 6) Diff against the latest prior version, if any.
            val prior = store.site(domain, type)
            var whatChanged: String? = null
            var changes: List<String> = emptyList()
            var severity = Severity.NONE
            var previousSeenAt: Long? = null
            if (prior != null && prior.hash != hash) {
                store.snapshot(prior.hash)?.let { priorSnap ->
                    val d = differ.diff(priorSnap.rawText, text, domain, type, config)
                    whatChanged = d.whatChanged
                    changes = d.changes
                    severity = d.severity
                    previousSeenAt = prior.lastSeen
                }
            }

            val full = base.copy(
                whatChanged = whatChanged,
                changeList = changes,
                changesSeverity = if (whatChanged != null) severity.wire else null,
                previousSeenAt = previousSeenAt,
                genuineCheck = genuine,
            )

            // 7) Persist locally (always) and update the latest pointer.
            store.putSnapshot(hash, domain, type, text, full, parentHash = prior?.hash)
            store.putSite(domain, type, hash)

            // 8) Render now; upload to the hive in the background (gated, fire-and-forget).
            if (hiveEnabled && Validator.passesUploadGate(genuine)) {
                bgScope.launch(Dispatchers.IO) {
                    runCatching {
                        hive.upload(
                            UploadRequest(
                                domain = domain,
                                policyType = type.wire,
                                hash = hash,
                                parentHash = prior?.hash,
                                summary = full,
                                submittedAt = Instant.now().toString(),
                            ),
                        )
                    }
                }
            }

            PipelineResult.Ready(full, "fresh", System.currentTimeMillis())
        } catch (e: Exception) {
            PipelineResult.Failed(e.message ?: "Something went wrong.")
        }
    }
}
