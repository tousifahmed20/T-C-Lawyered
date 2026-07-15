package dev.tclawyered.app.data

import android.net.Uri

/**
 * "How to protect your data" YouTube links (protection feature) — the Kotlin port
 * of the extension's videos.js. This is the no-key path: a YouTube search deep-link
 * that always works. (Real per-video listings via the YouTube Data API key are the
 * extension's keyed path; add here once a YT key field exists in Settings.)
 *
 * The only thing that leaves the device is a generic search query — never identity.
 */
object Videos {
    /** A protection-focused YouTube search deep-link. Company may be blank. */
    fun buildYoutubeSearchUrl(company: String): String {
        val prefix = company.trim().ifEmpty { "" }
        val q = (if (prefix.isEmpty()) "" else "$prefix ") + "privacy settings protect your data"
        return "https://www.youtube.com/results?search_query=${Uri.encode(q)}"
    }

    /** company = domain minus its TLD, matching the extension (duckduckgo.com → duckduckgo). */
    fun companyFromDomain(domain: String): String =
        domain.replace(Regex("\\.[a-z.]+$", RegexOption.IGNORE_CASE), "")
}
