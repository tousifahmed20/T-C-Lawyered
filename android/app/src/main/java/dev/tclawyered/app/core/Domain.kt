package dev.tclawyered.app.core

/**
 * Domain normalization (F-02) — the Kotlin port of utils/domain.js. Collapses
 * accounts./legal./www. etc. to the root identity domain so all of a company's
 * policy hosts map to one key. Conservative multi-part TLD handling (co.uk …).
 */
object Domain {
    private val MULTI_PART_TLDS = setOf(
        "co.uk", "org.uk", "gov.uk", "ac.uk", "co.jp", "com.au", "co.nz", "co.in", "com.br",
    )

    fun normalize(hostname: String?): String {
        if (hostname.isNullOrBlank()) return ""
        var host = hostname.lowercase().trim()
        if (host.startsWith(".")) host = host.substring(1)

        val labels = host.split(".").filter { it.isNotEmpty() }.toMutableList()
        if (labels.size <= 2) return labels.joinToString(".")

        // Drop explicitly stripped leading subdomains first.
        while (labels.size > 2 && Constants.STRIPPED_SUBDOMAINS.contains(labels[0])) {
            labels.removeAt(0)
        }

        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (MULTI_PART_TLDS.contains(lastTwo) && labels.size >= 3) {
            labels.takeLast(3).joinToString(".")
        } else {
            labels.takeLast(2).joinToString(".")
        }
    }

    /** Extract a normalized domain from a full URL, or "" if it isn't a URL. */
    fun fromUrl(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return try {
            normalize(java.net.URI(url.trim()).host)
        } catch (_: Exception) {
            ""
        }
    }
}
