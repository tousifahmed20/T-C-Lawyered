/**
 * All LLM prompts live here — one place to tune behaviour, no inline strings
 * scattered across the pipeline. Every prompt demands strict JSON output.
 */
import { TOKENS } from './CONSTANTS.js';

/**
 * Instruction appended to a system prompt so the model writes string VALUES in
 * the target language while keeping JSON keys + enum values in English.
 * @param {string} [lang] - English language name, e.g. "Spanish". "English" → no-op.
 */
function langInstruction(lang) {
  if (!lang || lang === 'English') return '';
  return (
    ` Write every human-readable string value in ${lang}. ` +
    'Keep all JSON keys and enum values (e.g. "none", "low", "breach", "fine") in English.'
  );
}

/** Authenticity validation (F-05). */
export function authenticityPrompt(currentURL, textExcerpt) {
  return {
    system:
      'You are a document authenticity checker for a browser extension. ' +
      'Respond ONLY in valid JSON. No preamble. No explanation outside JSON.',
    user:
      `URL: ${currentURL}\n` +
      `Document excerpt (first ${TOKENS.VALIDATION_EXCERPT} tokens): ${textExcerpt}\n\n` +
      'Does this appear to be a genuine privacy policy or terms of service ' +
      'document for the domain in the URL?\n\n' +
      'Check for:\n' +
      '- Brand/company name in text matches or relates to the domain\n' +
      '- Coherent legal language and document structure\n' +
      '- Not a cookie consent banner, advertisement, or unrelated content\n\n' +
      'Also classify the document type — this gates whether it may be shared to a ' +
      'public cache, so be strict:\n' +
      '- "privacy"     → a privacy policy / data policy / privacy notice\n' +
      '- "terms"       → terms of service / terms of use / conditions / EULA / user agreement\n' +
      '- "other_legal" → another genuine legal/policy document (cookie policy, ' +
      'acceptable-use policy, refund/returns policy, community guidelines, data processing agreement)\n' +
      '- "not_legal"   → anything else (marketing/landing page, blog article, product ' +
      'page, cookie-consent banner, login screen, error page, or unrelated content)\n\n' +
      'Respond: { "genuine": boolean, "confidence": 0-100, ' +
      '"docType": "privacy|terms|other_legal|not_legal", "reason": "string" }',
  };
}

/** Per-chunk section summary (F-06, long-doc path). */
export function sectionSummaryPrompt(chunkText, sectionIndex, totalSections) {
  return {
    system:
      'You summarize one section of a legal document for a layperson. ' +
      'Be factual and concise. Respond ONLY in valid JSON.',
    user:
      `This is section ${sectionIndex + 1} of ${totalSections} of a Terms & Conditions / ` +
      `Privacy Policy document.\n\nSection text:\n${chunkText}\n\n` +
      'Summarize the legally meaningful points in this section in plain English. ' +
      'Respond: { "summary": "string", "points": ["string"] }',
  };
}

/** Final structured summary — shared schema for single-call and meta passes. */
const SUMMARY_SCHEMA_INSTRUCTION =
  'Respond ONLY with valid JSON matching exactly this schema:\n' +
  '{\n' +
  '  "tldr": "2-3 sentence plain English summary",\n' +
  '  "keyRisks": ["string"],\n' +
  '  "dataCollected": [\n' +
  '    { "item": "short label, e.g. Precise location", "detail": "one full sentence: what this data is, how it is collected, and what it is used for" }\n' +
  '  ],\n' +
  '  "thirdPartySharing": ["string"],\n' +
  '  "userRights": ["string"],\n' +
  '  "protectionTips": ["a specific, practical step the user can take to protect their data on THIS service — name the exact setting, toggle, or action where possible"],\n' +
  '  "examples": {\n' +
  '    "keyRisks": "one concrete example that illustrates the key risks listed above",\n' +
  '    "dataCollected": "one concrete example of data this specific service collects and how",\n' +
  '    "thirdPartySharing": "one concrete example of who this service shares data with and why",\n' +
  '    "userRights": "one concrete example of a right you can actually exercise here"\n' +
  '  }\n' +
  '}\n' +
  'For "dataCollected", be thorough: list EVERY distinct category of data the document mentions, ' +
  'each with a clear one-sentence explanation a non-lawyer understands — not just one or two words. ' +
  'For "protectionTips", give concrete actions for THIS specific service (settings to change, ' +
  'permissions to revoke, opt-outs to use). ' +
  'Each example must be ONE sentence, specific to THIS document (name the company, data, or ' +
  'third party where possible) — not a generic textbook example. If a section has no items, ' +
  'use an empty string/array. No markdown, no code fences, no text outside the JSON object.';

/** Single-call summary for short docs (F-06, <= 8k tokens). */
export function singleSummaryPrompt(fullText, domain, policyType, lang) {
  return {
    system:
      'You are a plain-English legal summarizer for a privacy-first browser extension. ' +
      'You translate Terms & Conditions and Privacy Policies for ordinary users. ' +
      'Be accurate, neutral, and specific. ' +
      SUMMARY_SCHEMA_INSTRUCTION +
      langInstruction(lang),
    user:
      `Domain: ${domain}\nDocument type: ${policyType}\n\nDocument:\n${fullText}\n\n` +
      'Summarize this document for the user using the required JSON schema.',
  };
}

/** Meta pass combining section summaries into the final structured output. */
export function metaSummaryPrompt(sectionSummaries, domain, policyType, lang) {
  const joined = sectionSummaries
    .map((s, i) => `Section ${i + 1}: ${s.summary}\nPoints: ${(s.points || []).join('; ')}`)
    .join('\n\n');
  return {
    system:
      'You merge section-level summaries of a legal document into one coherent ' +
      'plain-English summary for an ordinary user. Do not invent facts not present ' +
      'in the section summaries. ' +
      SUMMARY_SCHEMA_INSTRUCTION +
      langInstruction(lang),
    user:
      `Domain: ${domain}\nDocument type: ${policyType}\n\nSection summaries:\n${joined}\n\n` +
      'Produce one combined summary using the required JSON schema.',
  };
}

/**
 * Reported data-safety track record for a company (data-safety feature).
 * Heavily constrained to discourage fabrication — false claims about real
 * companies are a liability, so the model is told to return nothing when unsure.
 */
export function trackRecordPrompt(domain, lang) {
  return {
    system:
      'You report ONLY publicly documented data breaches, privacy/data-protection ' +
      'regulatory fines, and major privacy controversies for the company that operates ' +
      'a given domain. Include an item ONLY if you are highly confident it genuinely ' +
      'happened and was widely publicly reported. Never invent, guess, or infer. When ' +
      'in doubt, omit it. Respond ONLY in valid JSON.' +
      langInstruction(lang),
    user:
      `Company domain: ${domain}\n\n` +
      'List notable, publicly reported privacy or data-protection events for the company ' +
      'operating this domain: data breaches, regulatory fines (e.g. GDPR, FTC), or major ' +
      'privacy controversies.\n\n' +
      'For each item give: year (YYYY), type ("breach" | "fine" | "controversy"), a ' +
      'one-sentence factual summary, and your confidence (0-100) that it really happened.\n' +
      'Only include items you are confident about. If you are not confident about any, ' +
      'return an empty array. Do NOT fabricate or pad the list.\n\n' +
      'Respond: { "actions": [ { "year": "YYYY", "type": "breach|fine|controversy", ' +
      '"summary": "string", "confidence": 0-100 } ] }',
  };
}

/** Plain-English diff between two policy versions (F-07). */
export function diffPrompt(oldText, newText, domain, policyType, lang) {
  return {
    system:
      'You explain what changed between two versions of a legal document in plain ' +
      'English for an ordinary user. Respond ONLY in valid JSON.' +
      langInstruction(lang),
    user:
      `Domain: ${domain}\nDocument type: ${policyType}\n\n` +
      `PREVIOUS VERSION:\n${oldText}\n\nNEW VERSION:\n${newText}\n\n` +
      'Explain what changed between these two versions in plain English. Flag anything ' +
      'that affects user rights, data collection, or third-party sharing.\n\n' +
      'Give a short overview, then a list of the specific concrete changes (each one short, ' +
      'starting with a verb like "Added", "Removed", "Now shares", "No longer", "Expanded"). ' +
      'Only list real differences; if nothing meaningful changed, use an empty list and ' +
      'severity "none".\n\n' +
      'Respond: { "whatChanged": "1-2 sentence overview", "changes": ["specific change", ...], ' +
      '"changesSeverity": "none|low|medium|high" }',
  };
}
