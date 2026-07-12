package dev.tclawyered.app.pipeline

import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.core.Prompts
import dev.tclawyered.app.llm.LlmClient
import dev.tclawyered.app.llm.LlmConfig
import dev.tclawyered.app.model.GenuineCheck

/**
 * Authenticity validation (F-05) — port of validator.js. A failed validation is
 * NOT a genuine verdict; on any error we default to not-genuine so a broken
 * check never results in a hive upload.
 */
class Validator(private val llm: LlmClient) {

    suspend fun validate(url: String, text: String, config: LlmConfig): GenuineCheck {
        val excerptChars = Constants.Tokens.VALIDATION_EXCERPT * Constants.Tokens.CHARS_PER_TOKEN
        val excerpt = text.take(excerptChars)
        return try {
            val result = llm.call(config, Prompts.authenticity(url, excerpt))
            SummaryMapper.genuineCheck(SummaryMapper.parse(result.text))
        } catch (e: Exception) {
            GenuineCheck(genuine = false, confidence = 0, reason = "Validation error: ${e.message}")
        }
    }

    companion object {
        /** Gate for hive upload: genuine AND confidence >= threshold. */
        fun passesUploadGate(check: GenuineCheck): Boolean =
            check.genuine && check.confidence >= Constants.AUTHENTICITY_CONFIDENCE_THRESHOLD
    }
}
