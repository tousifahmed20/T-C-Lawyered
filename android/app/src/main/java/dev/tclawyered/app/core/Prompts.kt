package dev.tclawyered.app.core

import dev.tclawyered.app.model.PolicyType

/** A system/user prompt pair for the LLM layer. */
data class Prompt(val system: String, val user: String)

/**
 * All LLM prompts — the Kotlin port of utils/prompts.js. Every prompt demands
 * strict JSON output. Keep wording aligned with the extension so summaries are
 * consistent across clients and interchangeable in the hive.
 */
object Prompts {

    fun authenticity(currentUrl: String, textExcerpt: String) = Prompt(
        system = "You are a document authenticity checker for a browser extension. " +
            "Respond ONLY in valid JSON. No preamble. No explanation outside JSON.",
        user = "URL: $currentUrl\n" +
            "Document excerpt (first ${Constants.Tokens.VALIDATION_EXCERPT} tokens): $textExcerpt\n\n" +
            "Does this appear to be a genuine privacy policy or terms of service " +
            "document for the domain in the URL?\n\n" +
            "Check for:\n" +
            "- Brand/company name in text matches or relates to the domain\n" +
            "- Coherent legal language and document structure\n" +
            "- Not a cookie consent banner, advertisement, or unrelated content\n\n" +
            "Respond: { \"genuine\": boolean, \"confidence\": 0-100, \"reason\": \"string\" }",
    )

    fun sectionSummary(chunkText: String, sectionIndex: Int, totalSections: Int) = Prompt(
        system = "You summarize one section of a legal document for a layperson. " +
            "Be factual and concise. Respond ONLY in valid JSON.",
        user = "This is section ${sectionIndex + 1} of $totalSections of a Terms & Conditions / " +
            "Privacy Policy document.\n\nSection text:\n$chunkText\n\n" +
            "Summarize the legally meaningful points in this section in plain English. " +
            "Respond: { \"summary\": \"string\", \"points\": [\"string\"] }",
    )

    fun singleSummary(fullText: String, domain: String, type: PolicyType) = Prompt(
        system = "You are a plain-English legal summarizer for a privacy-first browser extension. " +
            "You translate Terms & Conditions and Privacy Policies for ordinary users. " +
            "Be accurate, neutral, and specific. " + SUMMARY_SCHEMA_INSTRUCTION,
        user = "Domain: $domain\nDocument type: ${type.wire}\n\nDocument:\n$fullText\n\n" +
            "Summarize this document for the user using the required JSON schema.",
    )

    fun metaSummary(sections: List<SectionResult>, domain: String, type: PolicyType): Prompt {
        val joined = sections.mapIndexed { i, s ->
            "Section ${i + 1}: ${s.summary}\nPoints: ${s.points.joinToString("; ")}"
        }.joinToString("\n\n")
        return Prompt(
            system = "You merge section-level summaries of a legal document into one coherent " +
                "plain-English summary for an ordinary user. Do not invent facts not present " +
                "in the section summaries. " + SUMMARY_SCHEMA_INSTRUCTION,
            user = "Domain: $domain\nDocument type: ${type.wire}\n\nSection summaries:\n$joined\n\n" +
                "Produce one combined summary using the required JSON schema.",
        )
    }

    fun diff(oldText: String, newText: String, domain: String, type: PolicyType) = Prompt(
        system = "You explain what changed between two versions of a legal document in plain " +
            "English for an ordinary user. Respond ONLY in valid JSON.",
        user = "Domain: $domain\nDocument type: ${type.wire}\n\n" +
            "PREVIOUS VERSION:\n$oldText\n\nNEW VERSION:\n$newText\n\n" +
            "Explain what changed between these two versions in plain English. Flag anything " +
            "that affects user rights, data collection, or third-party sharing.\n\n" +
            "Give a short overview, then a list of the specific concrete changes (each one short, " +
            "starting with a verb like \"Added\", \"Removed\", \"Now shares\", \"No longer\", \"Expanded\"). " +
            "Only list real differences; if nothing meaningful changed, use an empty list and " +
            "severity \"none\".\n\n" +
            "Respond: { \"whatChanged\": \"1-2 sentence overview\", \"changes\": [\"specific change\"], " +
            "\"changesSeverity\": \"none|low|medium|high\" }",
    )

    /** AI-reported data-safety track record for a company (port of trackRecordPrompt). */
    fun trackRecord(domain: String) = Prompt(
        system = "You report ONLY publicly documented data breaches, privacy/data-protection " +
            "regulatory fines, and major privacy controversies for the company that operates " +
            "a given domain. Include an item ONLY if you are highly confident it genuinely " +
            "happened and was widely publicly reported. Never invent, guess, or infer. When " +
            "in doubt, omit it. Respond ONLY in valid JSON.",
        user = "Company domain: $domain\n\n" +
            "List notable, publicly reported privacy or data-protection events for the company " +
            "operating this domain: data breaches, regulatory fines (e.g. GDPR, FTC), or major " +
            "privacy controversies.\n\n" +
            "For each item give: year (YYYY), type (\"breach\" | \"fine\" | \"controversy\"), a " +
            "one-sentence factual summary, and your confidence (0-100) that it really happened.\n" +
            "Only include items you are confident about. If you are not confident about any, " +
            "return an empty array. Do NOT fabricate or pad the list.\n\n" +
            "Respond: { \"actions\": [ { \"year\": \"YYYY\", \"type\": \"breach|fine|controversy\", " +
            "\"summary\": \"string\", \"confidence\": 0-100 } ] }",
    )

    data class SectionResult(val summary: String, val points: List<String>)

    private val SUMMARY_SCHEMA_INSTRUCTION =
        "Respond ONLY with valid JSON matching exactly this schema:\n" +
            "{\n" +
            "  \"tldr\": \"2-3 sentence plain English summary\",\n" +
            "  \"keyRisks\": [\"string\"],\n" +
            "  \"dataCollected\": [\n" +
            "    { \"item\": \"short label, e.g. Precise location\", \"detail\": \"one full sentence: what this data is, how it is collected, and what it is used for\" }\n" +
            "  ],\n" +
            "  \"thirdPartySharing\": [\"string\"],\n" +
            "  \"userRights\": [\"string\"],\n" +
            "  \"protectionTips\": [\"a specific, practical step the user can take to protect their data on THIS service — name the exact setting, toggle, or action where possible\"],\n" +
            "  \"examples\": {\n" +
            "    \"keyRisks\": \"one concrete example that illustrates the key risks listed above\",\n" +
            "    \"dataCollected\": \"one concrete example of data this specific service collects and how\",\n" +
            "    \"thirdPartySharing\": \"one concrete example of who this service shares data with and why\",\n" +
            "    \"userRights\": \"one concrete example of a right you can actually exercise here\"\n" +
            "  }\n" +
            "}\n" +
            "For \"dataCollected\", be thorough: list EVERY distinct category of data the document mentions, " +
            "each with a clear one-sentence explanation a non-lawyer understands — not just one or two words. " +
            "For \"protectionTips\", give concrete actions for THIS specific service (settings to change, " +
            "permissions to revoke, opt-outs to use). " +
            "Each example must be ONE sentence, specific to THIS document (name the company, data, or " +
            "third party where possible) — not a generic textbook example. If a section has no items, " +
            "use an empty string/array. No markdown, no code fences, no text outside the JSON object."
}
