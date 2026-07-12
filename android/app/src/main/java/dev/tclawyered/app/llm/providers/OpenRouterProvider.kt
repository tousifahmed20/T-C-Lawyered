package dev.tclawyered.app.llm.providers

import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.core.Prompt
import dev.tclawyered.app.llm.HttpJson
import dev.tclawyered.app.llm.LlmProvider
import dev.tclawyered.app.llm.LlmResult
import dev.tclawyered.app.llm.providerError
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers

/**
 * OpenRouter adapter — the free default. OpenAI-compatible Chat Completions,
 * mirroring providers/openrouter.js: no JSON-mode (many free models reject it),
 * optional attribution headers, and tolerance for upstream errors surfaced in a
 * 200 body.
 */
class OpenRouterProvider(private val http: HttpJson) : LlmProvider {

    override suspend fun call(model: String, apiKey: String, prompt: Prompt): LlmResult {
        val headers = Headers.Builder()
            .add("content-type", "application/json")
            .add("authorization", "Bearer $apiKey")
            .add("HTTP-Referer", "https://github.com/tc-lawyered/tc-lawyered")
            .add("X-Title", "T&C Lawyered")
            .build()

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", prompt.system) }
                addJsonObject { put("role", "user"); put("content", prompt.user) }
            }
        }.toString()

        val data = http.post(Constants.Endpoints.OPENROUTER, headers, body).jsonObject

        data["error"]?.jsonObject?.let { err ->
            val code = err["code"]?.jsonPrimitive?.int ?: 502
            val msg = err["message"]?.jsonPrimitive?.content ?: "OpenRouter error"
            throw providerError(code, msg)
        }

        val text = data["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.content ?: ""
        val tokens = data["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.int ?: 0
        return LlmResult(text, tokens)
    }
}
