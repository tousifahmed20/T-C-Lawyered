package dev.tclawyered.app.content

/**
 * Minimal HTML → visible-text extractor — the mobile counterpart of the
 * extension's content/extractor.js. Regex-based (no jsoup dependency): drops
 * scripts/styles/comments, strips tags, decodes the common entities, and
 * collapses whitespace. Good enough to feed the summarizer; not a full parser.
 */
object HtmlExtractor {
    private val scriptStyle = Regex(
        "<(script|style|noscript|template|svg)[^>]*>.*?</\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val comments = Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL)
    private val blockBreaks = Regex("(?i)</(p|div|li|h[1-6]|section|article|br)\\s*>|<br\\s*/?>")
    private val tags = Regex("<[^>]+>")
    private val whitespaceRuns = Regex("[ \\t\\x0B\\f\\r]+")
    private val blankLines = Regex("\\n{3,}")

    fun extract(html: String): String {
        var s = html
        s = scriptStyle.replace(s, " ")
        s = comments.replace(s, " ")
        s = blockBreaks.replace(s, "\n")
        s = tags.replace(s, " ")
        s = decodeEntities(s)
        s = whitespaceRuns.replace(s, " ")
        s = s.lineSequence().map { it.trim() }.joinToString("\n")
        s = blankLines.replace(s, "\n\n")
        return s.trim()
    }

    private fun decodeEntities(text: String): String = text
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&mdash;", "—")
        .replace("&ndash;", "–")
        .replace(Regex("&#(\\d+);")) { m ->
            m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
        }
}
