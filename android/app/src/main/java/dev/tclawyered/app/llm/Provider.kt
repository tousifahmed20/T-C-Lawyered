package dev.tclawyered.app.llm

/**
 * Supported LLM providers and their selectable models — the Kotlin counterpart
 * of PROVIDER_MODELS in the extension's CONSTANTS.js. OpenRouter is listed first
 * because its `:free` models are the recommended zero-cost default on mobile.
 */
enum class Provider(val id: String, val label: String, val models: List<String>) {
    OPENROUTER(
        "openrouter",
        "OpenRouter",
        listOf(
            "deepseek/deepseek-chat-v3-0324:free",
            "meta-llama/llama-3.3-70b-instruct:free",
            "google/gemini-2.0-flash-exp:free",
            "anthropic/claude-3.5-sonnet",
            "openai/gpt-4o-mini",
        ),
    ),
    ANTHROPIC("anthropic", "Anthropic", listOf("claude-haiku-4-5", "claude-sonnet-4-6")),
    OPENAI("openai", "OpenAI", listOf("gpt-4o-mini", "gpt-4o")),
    GEMINI("gemini", "Gemini", listOf("gemini-1.5-flash", "gemini-1.5-pro"));

    val defaultModel: String get() = models.first()

    /** OpenRouter's `:free` tier — surfaced as "Free · Recommended" in settings. */
    val isFreeRecommended: Boolean get() = this == OPENROUTER

    companion object {
        fun fromId(id: String?): Provider? = entries.firstOrNull { it.id == id }
    }
}
