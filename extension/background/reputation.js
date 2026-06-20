/**
 * AI-reported track record (data-safety feature, best-effort source).
 *
 * Uses the user's own LLM to surface publicly reported fines / breaches /
 * controversies for a domain. This is explicitly UNVERIFIED — the UI labels it
 * as AI-generated and the prompt is constrained against fabrication. Results are
 * cached per domain for 30 days to avoid repeat token spend, and because this
 * costs an API call it is never fired on a hive/cache hit automatically (that
 * would break the "hive hit = zero cost" guarantee); it runs on fresh
 * summarization or on explicit user request.
 */
import { TRACK_RECORD_TTL_MS, TRACK_RECORD_MIN_CONFIDENCE } from '../utils/CONSTANTS.js';
import { trackRecordPrompt } from '../utils/prompts.js';
import { callLLMJson } from './llm.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('reputation');
const CACHE_KEY = 'track_record';

/** Cached actions for a domain, or null if absent/expired. */
export async function getCachedReportedActions(domain) {
  const { [CACHE_KEY]: all } = await chrome.storage.local.get(CACHE_KEY);
  const entry = all?.[domain];
  if (entry && Date.now() - entry.generatedAt < TRACK_RECORD_TTL_MS) return entry.actions;
  return null;
}

/**
 * Generate (and cache) the reported track record for a domain via the LLM.
 * @param {object} args - { domain, llmConfig }
 * @returns {Promise<Array<{year, type, summary, confidence}>>}
 */
export async function generateReportedActions({ domain, llmConfig, lang }) {
  const { system, user } = trackRecordPrompt(domain, lang);
  try {
    const { json } = await callLLMJson({ systemPrompt: system, userPrompt: user, ...llmConfig });
    const actions = (Array.isArray(json.actions) ? json.actions : [])
      .filter((a) => a && typeof a.summary === 'string' && Number(a.confidence) >= TRACK_RECORD_MIN_CONFIDENCE)
      .map((a) => ({
        year: String(a.year || '').slice(0, 4),
        type: ['breach', 'fine', 'controversy'].includes(a.type) ? a.type : 'controversy',
        summary: a.summary.trim(),
        confidence: clampConfidence(a.confidence),
      }))
      .sort((a, b) => (b.year || '').localeCompare(a.year || ''));
    await cacheActions(domain, actions);
    return actions;
  } catch (error) {
    log.warn('track record generation failed:', error.message);
    return [];
  }
}

async function cacheActions(domain, actions) {
  const { [CACHE_KEY]: all } = await chrome.storage.local.get(CACHE_KEY);
  await chrome.storage.local.set({
    [CACHE_KEY]: { ...(all || {}), [domain]: { generatedAt: Date.now(), actions } },
  });
}

function clampConfidence(value) {
  const n = Number(value);
  if (Number.isNaN(n)) return 0;
  return Math.max(0, Math.min(100, Math.round(n)));
}
