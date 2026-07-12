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
import java.net.URLEncoder

/**
 * Google Gemini generateContent adapter (mirrors providers/gemini.js). No system
 * role — the system prompt goes via systemInstruction; JSON output is forced via
 * responseMimeType. The key is a query parameter (Gemini's scheme).
 */
class GeminiProvider(private val http: HttpJson) : LlmProvider {

    override suspend fun call(model: String, apiKey: String, prompt: Prompt): LlmResult {
        val url = "${Constants.Endpoints.GEMINI}/$model:generateContent?key=" +
            URLEncoder.encode(apiKey, "UTF-8")
        val headers = Headers.Builder().add("content-type", "application/json").build()

        val body = buildJsonObject {
            put("systemInstruction", buildJsonObject {
                putJsonArray("parts") { addJsonObject { put("text", prompt.system) } }
            })
            putJsonArray("contents") {
                addJsonObject {
                    put("role", "user")
                    putJsonArray("parts") { addJsonObject { put("text", prompt.user) } }
                }
            }
            put("generationConfig", buildJsonObject {
                put("responseMimeType", "application/json")
                put("maxOutputTokens", 2048)
            })
        }.toString()

        val data = http.post(url, headers, body).jsonObject

        val text = data["candidates"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("content")?.jsonObject?.get("parts")?.jsonArray.orEmpty()
            .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
        val tokens = data["usageMetadata"]?.jsonObject
            ?.get("totalTokenCount")?.jsonPrimitive?.int ?: 0
        return LlmResult(text, tokens)
    }
}

private fun kotlinx.serialization.json.JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> =
    this ?: emptyList()
