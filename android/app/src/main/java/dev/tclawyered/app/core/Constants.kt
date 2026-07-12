package dev.tclawyered.app.core

/**
 * Single source of truth for hardcoded values — the Kotlin counterpart of the
 * extension's utils/CONSTANTS.js. Keep the hive contract values in lock-step
 * with the extension and backend.
 */
object Constants {
    /** Hive backend base URL. Update at cutover to the deployed Railway URL. */
    const val HIVE_BASE_URL = "https://api.tclawyered.dev"

    /** Provider API endpoints. User keys only — never ours. */
    object Endpoints {
        const val ANTHROPIC = "https://api.anthropic.com/v1/messages"
        const val OPENAI = "https://api.openai.com/v1/chat/completions"
        const val OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"
        const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/models"
    }

    const val ANTHROPIC_VERSION = "2023-06-01"

    /** Authenticity gate: genuine AND confidence >= this to upload to the hive. */
    const val AUTHENTICITY_CONFIDENCE_THRESHOLD = 85

    /** A cached summary older than this (2 months) is re-checked on next view. */
    const val RECHECK_TTL_MS = 60L * 24 * 60 * 60 * 1000

    /** Chunking thresholds (token estimates), mirroring the extension. */
    object Tokens {
        const val CHARS_PER_TOKEN = 4
        const val SINGLE_CALL_MAX = 8000
        const val CHUNK_SIZE = 4000
        const val CHUNK_OVERLAP = 200
        const val VALIDATION_EXCERPT = 2000
    }

    /** Below this word count an OCR/extraction result is treated as "nothing found". */
    const val MIN_POLICY_WORDS = 40

    /** Subdomains stripped during domain normalization. */
    val STRIPPED_SUBDOMAINS = setOf("www", "accounts", "legal", "policies", "help", "support")

    /** Network timeout for any single request (ms). */
    const val FETCH_TIMEOUT_MS = 30_000L

    /** Hive lookup timeout — fail fast, offline-first (ms). */
    const val HIVE_TIMEOUT_MS = 2_000L
}
