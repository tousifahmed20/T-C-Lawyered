package dev.tclawyered.app.core

/**
 * Token chunking (F-06) — the Kotlin port of background/chunker.js splitting
 * logic. Token count is estimated as chars / 4 (same as the extension). Docs
 * under the single-call limit are summarized in one pass; longer docs are split
 * into overlapping chunks, summarized per-section, then merged in a meta pass.
 */
object Chunker {
    fun estimateTokens(text: String): Int =
        Math.ceil(text.length.toDouble() / Constants.Tokens.CHARS_PER_TOKEN).toInt()

    fun needsChunking(text: String): Boolean =
        estimateTokens(text) > Constants.Tokens.SINGLE_CALL_MAX

    /** Split into ~CHUNK_SIZE-token windows with CHUNK_OVERLAP-token overlap. */
    fun split(text: String): List<String> {
        val charsPerChunk = Constants.Tokens.CHUNK_SIZE * Constants.Tokens.CHARS_PER_TOKEN
        val overlapChars = Constants.Tokens.CHUNK_OVERLAP * Constants.Tokens.CHARS_PER_TOKEN
        if (text.length <= charsPerChunk) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + charsPerChunk, text.length)
            chunks.add(text.substring(start, end))
            if (end == text.length) break
            start = end - overlapChars
        }
        return chunks
    }
}
