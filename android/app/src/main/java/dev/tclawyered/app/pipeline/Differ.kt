package dev.tclawyered.app.pipeline

import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.core.Prompts
import dev.tclawyered.app.llm.LlmClient
import dev.tclawyered.app.llm.LlmConfig
import dev.tclawyered.app.model.PolicyType
import dev.tclawyered.app.model.Severity
import kotlinx.serialization.json.jsonPrimitive

/** Result of a version diff. */
data class DiffResult(
    val whatChanged: String?,
    val changes: List<String>,
    val severity: Severity,
)

/**
 * Version diffing (F-07) — port of differ.js. Each side is truncated to stay
 * within model context. A diff failure degrades to "no diff", never failing the
 * whole pipeline.
 */
class Differ(private val llm: LlmClient) {

    private val maxSideChars =
        (Constants.Tokens.SINGLE_CALL_MAX * Constants.Tokens.CHARS_PER_TOKEN * 0.5).toInt()

    suspend fun diff(
        oldText: String,
        newText: String,
        domain: String,
        type: PolicyType,
        config: LlmConfig,
    ): DiffResult = try {
        val result = llm.call(
            config,
            Prompts.diff(oldText.take(maxSideChars), newText.take(maxSideChars), domain, type),
        )
        val obj = SummaryMapper.parse(result.text)
        val whatChanged = (obj["whatChanged"]?.jsonPrimitive)?.takeIf { it.isString }?.content
        val changes = (obj["changes"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { p -> p.isString }?.content?.trim() }
            .filter { it.isNotEmpty() }
        val severity = Severity.fromWire((obj["changesSeverity"]?.jsonPrimitive)?.content)
        DiffResult(whatChanged, changes, severity)
    } catch (_: Exception) {
        DiffResult(null, emptyList(), Severity.NONE)
    }

    private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
        this ?: emptyList()
}
