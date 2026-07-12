package dev.tclawyered.app.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Thin JSON-over-HTTP helper shared by the provider adapters. Handles the POST,
 * error mapping (via providerError), and parsing the response to a JsonElement.
 * Runs the blocking OkHttp call on the IO dispatcher.
 */
class HttpJson(
    private val client: OkHttpClient,
    private val json: Json,
) {
    private val jsonMedia = "application/json".toMediaType()

    suspend fun post(url: String, headers: Headers, body: String): JsonElement =
        withContext(Dispatchers.IO) {
            val req = Request.Builder()
                .url(url)
                .headers(headers)
                .post(body.toRequestBody(jsonMedia))
                .build()
            client.newCall(req).execute().use { res ->
                val text = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw providerError(res.code, text)
                json.parseToJsonElement(text)
            }
        }
}
