package dev.tclawyered.app.data.safety

import android.content.Context
import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.core.Prompts
import dev.tclawyered.app.llm.LlmClient
import dev.tclawyered.app.llm.LlmConfig
import dev.tclawyered.app.model.ReportedAction
import dev.tclawyered.app.pipeline.SummaryMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * AI-reported track record (best-effort, UNVERIFIED) — port of reputation.js.
 * The prompt is constrained against fabrication and the UI labels results as
 * AI-generated. Cached per domain for 30 days to avoid repeat token spend;
 * because it costs an API call it is generated only on the fresh summarize path,
 * never on a hive/cache hit (that would break the zero-cost guarantee).
 */
class ReputationClient(context: Context, private val llm: LlmClient = LlmClient()) {
    private val cacheFile = File(context.cacheDir, "track_record.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Cached actions for a domain, or null if absent/expired. */
    suspend fun cached(domain: String): List<ReportedAction>? = withContext(Dispatchers.IO) {
        val entry = readAll()[domain] ?: return@withContext null
        if (System.currentTimeMillis() - entry.generatedAt < Constants.TRACK_RECORD_TTL_MS) {
            entry.actions
        } else {
            null
        }
    }

    /** Generate (and cache) the reported track record for a domain via the LLM. */
    suspend fun generate(domain: String, config: LlmConfig): List<ReportedAction> {
        return try {
            val result = llm.call(config, Prompts.trackRecord(domain))
            val obj = SummaryMapper.parse(result.text)
            val actions = (obj["actions"]?.jsonArray ?: return emptyList())
                .mapNotNull { el ->
                    val a = el.jsonObject
                    val summary = (a["summary"]?.jsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
                    val confidence = (a["confidence"]?.jsonPrimitive)?.intOrNull ?: 0
                    if (summary.isEmpty() || confidence < Constants.TRACK_RECORD_MIN_CONFIDENCE) return@mapNotNull null
                    val rawType = (a["type"]?.jsonPrimitive)?.content ?: "controversy"
                    ReportedAction(
                        year = (a["year"]?.jsonPrimitive)?.content?.take(4).orEmpty(),
                        type = if (rawType in TYPES) rawType else "controversy",
                        summary = summary,
                        confidence = confidence.coerceIn(0, 100),
                    )
                }
                .sortedByDescending { it.year }
            cache(domain, actions)
            actions
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun cache(domain: String, actions: List<ReportedAction>) {
        val all = readAll().toMutableMap()
        all[domain] = Entry(System.currentTimeMillis(), actions)
        runCatching {
            cacheFile.writeText(json.encodeToString(Cache.serializer(), Cache(all)))
        }
    }

    private fun readAll(): Map<String, Entry> = runCatching {
        json.decodeFromString(Cache.serializer(), cacheFile.readText()).map
    }.getOrDefault(emptyMap())

    @Serializable
    private data class Entry(val generatedAt: Long, val actions: List<ReportedAction>)

    // Wrapper so the domain->Entry map serializes cleanly to one JSON object.
    @Serializable
    private data class Cache(val map: Map<String, Entry> = emptyMap())

    companion object {
        private val TYPES = setOf("breach", "fine", "lawsuit", "controversy")
    }
}
