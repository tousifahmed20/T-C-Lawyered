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
 * Anthropic Messages API adapter (mirrors providers/anthropic.js). Unlike the
 * extension we don't send the browser-access header — a native OkHttp call has
 * no CORS, so it isn't needed.
 */
class AnthropicProvider(private val http: HttpJson) : LlmProvider {

    override suspend fun call(model: String, apiKey: String, prompt: Prompt): LlmResult {
        val headers = Headers.Builder()
            .add("content-type", "application/json")
            .add("x-api-key", apiKey)
            .add("anthropic-version", Constants.ANTHROPIC_VERSION)
            .build()

        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", 2048)
            put("system", prompt.system)
            putJsonArray("messages") {
                addJsonObject { put("role", "user"); put("content", prompt.user) }
            }
        }.toString()

        val data = http.post(Constants.Endpoints.ANTHROPIC, headers, body).jsonObject

        val text = data["content"]?.jsonArray.orEmpty()
            .filter { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }

        val usage = data["usage"]?.jsonObject
        val tokens = (usage?.get("input_tokens")?.jsonPrimitive?.int ?: 0) +
            (usage?.get("output_tokens")?.jsonPrimitive?.int ?: 0)
        return LlmResult(text, tokens)
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
