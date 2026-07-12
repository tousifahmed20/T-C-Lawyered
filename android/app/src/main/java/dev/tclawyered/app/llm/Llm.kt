package dev.tclawyered.app.llm

import dev.tclawyered.app.core.Prompt

/** A ready-to-use provider config with a decrypted key (never persisted plaintext). */
data class LlmConfig(val provider: Provider, val model: String, val apiKey: String)

/** Result of a provider call. */
data class LlmResult(val text: String, val tokensUsed: Int)

/**
 * Typed provider failure. The pipeline surfaces `.message` to the user, so the
 * messages mirror the extension's providerError() codes (INVALID_API_KEY, etc.)
 * for a consistent experience across clients.
 */
class LlmException(message: String, val status: Int = 0) : Exception(message)

/** Map an HTTP status to an actionable, typed error (mirrors llm.js). */
fun providerError(status: Int, body: String): LlmException {
    val message = when {
        status == 401 || status == 403 -> "INVALID_API_KEY: Check your API key in settings."
        status == 429 -> "RATE_LIMITED: Wait a moment and try again."
        status >= 500 -> "PROVIDER_DOWN: The LLM provider returned $status. Try again later."
        else -> "LLM_ERROR: Request failed ($status). $body"
    }
    return LlmException(message, status)
}

/** Every adapter conforms to this. Shared HTTP client + JSON are injected. */
interface LlmProvider {
    suspend fun call(model: String, apiKey: String, prompt: Prompt): LlmResult
}

/**
 * Extract a JSON object string from model output. Strips ```json fences and, if
 * prose leaked in around the object, grabs the outermost {...} span. Mirrors
 * parseJsonLoose() in llm.js — the pipeline decodes the returned string.
 */
fun looseJson(raw: String): String {
    val cleaned = raw
        .replace(Regex("```json", RegexOption.IGNORE_CASE), "")
        .replace("```", "")
        .trim()
    if (cleaned.startsWith("{") && cleaned.endsWith("}")) return cleaned
    val first = cleaned.indexOf('{')
    val last = cleaned.lastIndexOf('}')
    return if (first != -1 && last > first) cleaned.substring(first, last + 1) else cleaned
}
