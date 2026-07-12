package dev.tclawyered.app.llm

import dev.tclawyered.app.core.Constants
import dev.tclawyered.app.core.Prompt
import dev.tclawyered.app.llm.providers.AnthropicProvider
import dev.tclawyered.app.llm.providers.GeminiProvider
import dev.tclawyered.app.llm.providers.OpenAiProvider
import dev.tclawyered.app.llm.providers.OpenRouterProvider
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/**
 * Provider-agnostic LLM entry point (F-11) — the Kotlin counterpart of llm.js.
 * Owns the shared HTTP client + timeout, routes to the right adapter, and maps
 * network failures to the same typed messages the extension uses.
 */
class LlmClient(
    client: OkHttpClient = defaultClient(),
    json: Json = Json { ignoreUnknownKeys = true },
) {
    private val http = HttpJson(client, json)

    private val adapters: Map<Provider, LlmProvider> = mapOf(
        Provider.OPENROUTER to OpenRouterProvider(http),
        Provider.ANTHROPIC to AnthropicProvider(http),
        Provider.OPENAI to OpenAiProvider(http),
        Provider.GEMINI to GeminiProvider(http),
    )

    /** Call the configured provider with a prompt pair. Throws [LlmException]. */
    suspend fun call(config: LlmConfig, prompt: Prompt): LlmResult {
        if (config.apiKey.isBlank()) {
            throw LlmException("CONFIG_ERROR: No API key configured. Add one in settings.")
        }
        val adapter = adapters[config.provider]
            ?: throw LlmException("CONFIG_ERROR: Unknown provider \"${config.provider.id}\".")
        return try {
            adapter.call(config.model, config.apiKey, prompt)
        } catch (e: LlmException) {
            throw e
        } catch (e: InterruptedIOException) {
            throw LlmException("TIMEOUT: The LLM request took too long. Try again.")
        } catch (e: IOException) {
            throw LlmException("NETWORK: Could not reach the provider. Check your connection.")
        } catch (e: kotlinx.serialization.SerializationException) {
            throw LlmException("PARSE_ERROR: The provider returned an unexpected response.")
        }
    }

    /**
     * Minimal health check for the settings "Test" button — a tiny prompt.
     * Returns null on success, or the error message to show the user.
     */
    suspend fun test(config: LlmConfig): String? = try {
        call(config, Prompt(system = "Reply with OK.", user = "Say OK."))
        null
    } catch (e: LlmException) {
        e.message
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(Constants.FETCH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }
}
