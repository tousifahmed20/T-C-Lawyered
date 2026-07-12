package dev.tclawyered.app.core

import java.security.MessageDigest

/**
 * SHA-256 content hashing (F-03) — the Kotlin port of background/hasher.js.
 *
 * The hash is the shared lookup key across the extension, this app, and the
 * hive, so it MUST be byte-for-byte identical to the JS implementation:
 * collapse all whitespace runs to a single space, trim, UTF-8 encode, digest,
 * lowercase hex. Any drift here silently breaks cross-client cache hits.
 */
object Hasher {
    private val whitespace = Regex("\\s+")

    /** Collapse cosmetic whitespace so trivial differences don't change the hash. */
    fun normalizeForHash(text: String): String =
        text.replace(whitespace, " ").trim()

    /** 64-char lowercase hex SHA-256 of the normalized text. */
    fun computeHash(text: String): String {
        val data = normalizeForHash(text).toByteArray(Charsets.UTF_8)
        val digest = MessageDigest.getInstance("SHA-256").digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
