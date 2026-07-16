package dev.tclawyered.app.pipeline

import dev.tclawyered.app.core.Domain
import dev.tclawyered.app.core.Hasher
import dev.tclawyered.app.data.SettingsRepository
import dev.tclawyered.app.data.hive.HiveClient
import dev.tclawyered.app.data.hive.UploadRequest
import dev.tclawyered.app.data.local.LocalStore
import dev.tclawyered.app.data.safety.ReputationClient
import dev.tclawyered.app.llm.LlmClient
import dev.tclawyered.app.model.GenuineCheck
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
    data class Ready(
        val summary: Summary,
        val source: String,
        val scannedAt: Long,
        val domain: String,
    ) : PipelineResult
    data object NeedsProvider : PipelineResult
    /** Doc isn't a policy/legal/financial document — blocked, but the UI offers an override. */
    data class NotApplicable(val check: GenuineCheck, val domain: String) : PipelineResult
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
    private val reputation: ReputationClient? = null,
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
            return PipelineResult.Ready(store.decodeSummary(existing), "local", existing.lastCheckedAt, domain)
        }

        // 2) Hive lookup (skipped on refresh — same hash means the same summary).
        if (hiveEnabled && !refresh) {
            val hit = withContext(Dispatchers.IO) { hive.lookup(hash, domain) }
            if (hit.found && hit.summary != null) {
                store.putSnapshot(hash, domain, type, text, hit.summary, parentHash = null)
                return PipelineResult.Ready(hit.summary, "hive", System.currentTimeMillis(), domain)
            }
        }

        // 3) Need the user's LLM key from here on.
        val config = settings.getActiveConfig()
        if (config == null) {
            // Graceful: keep showing a stale cached summary rather than erroring.
            if (existing != null) {
                return PipelineResult.Ready(store.decodeSummary(existing), "local-stale", existing.lastCheckedAt, domain)
            }
            return PipelineResult.NeedsProvider
        }

        return try {
            // 4) Classify + authenticity. Gates hive upload, and — for a brand-new
            //    document that isn't a policy/legal/financial doc — stops here instead
            //    of inventing a summary. `force` is the user's "Summarize anyway".
            val genuine = validator.validate(input.url, text, config)
            if (!input.force && existing == null && !genuine.summarizable) {
                return PipelineResult.NotApplicable(genuine, domain)
            }

            // 5) Summarize.
            val base = summarizer.summarize(text, domain, type, config)

            // 6) Diff against the latest prior version, if any. Skip entirely when
            //    the domain is blank (share-text / OCR have no URL) — otherwise all
            //    such docs share one site key and get spuriously diffed against each
            //    other. History still keeps them via the content-hash snapshot below.
            val prior = if (domain.isNotEmpty()) store.site(domain, type) else null
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

            // 7) Persist locally (always) and update the latest pointer — but only
            //    when we have a real domain to key it by (see step 6).
            store.putSnapshot(hash, domain, type, text, full, parentHash = prior?.hash)
            if (domain.isNotEmpty()) store.putSite(domain, type, hash)

            // 7b) We're already paying for an LLM call, so generate the reported
            //     track record now (cached per domain). Never on a cache/hive hit.
            if (reputation != null && domain.isNotEmpty() && reputation.cached(domain) == null) {
                runCatching { reputation?.generate(domain, config) }
            }

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

            PipelineResult.Ready(full, "fresh", System.currentTimeMillis(), domain)
        } catch (e: Exception) {
            PipelineResult.Failed(e.message ?: "Something went wrong.")
        }
    }
}
