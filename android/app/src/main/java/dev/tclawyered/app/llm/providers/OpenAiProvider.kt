package dev.tclawyered.app.llm.providers

import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.core.Prompt
import dev.tclawyered.app.llm.HttpJson
import dev.tclawyered.app.llm.LlmProvider
import dev.tclawyered.app.llm.LlmResult
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
 * OpenAI Chat Completions adapter (mirrors providers/openai.js). Uses JSON mode
 * (response_format) to coerce structured output where supported.
 */
class OpenAiProvider(private val http: HttpJson) : LlmProvider {

    override suspend fun call(model: String, apiKey: String, prompt: Prompt): LlmResult {
        val headers = Headers.Builder()
            .add("content-type", "application/json")
            .add("authorization", "Bearer $apiKey")
            .build()

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            putJsonArray("messages") {
                addJsonObject { put("role", "system"); put("content", prompt.system) }
                addJsonObject { put("role", "user"); put("content", prompt.user) }
            }
            put("response_format", buildJsonObject { put("type", "json_object") })
        }.toString()

        val data = http.post(Constants.Endpoints.OPENAI, headers, body).jsonObject
        val text = data["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?.jsonPrimitive?.content ?: ""
        val tokens = data["usage"]?.jsonObject?.get("total_tokens")?.jsonPrimitive?.int ?: 0
        return LlmResult(text, tokens)
    }
}
