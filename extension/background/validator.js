/**
 * Authenticity validation (F-05). Before summarizing/uploading, the LLM judges
 * whether the document is a genuine policy for the domain. Documents that fail
 * are still summarized locally but never uploaded to the hive — this prevents
 * poisoning the shared cache with fake or misidentified content.
 */
import { AUTHENTICITY_CONFIDENCE_THRESHOLD, TOKENS } from '../utils/CONSTANTS.js';
import { authenticityPrompt } from '../utils/prompts.js';
import { callLLMJson } from './llm.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('validator');

/**
 * @typedef {object} GenuineCheck
 * @property {boolean} genuine
 * @property {number} confidence  0-100
 * @property {string} reason
 */

/**
 * Validate document authenticity.
 * @param {object} args
 * @param {string} args.url
 * @param {string} args.text
 * @param {object} args.llmConfig - { provider, model, apiKey }
 * @returns {Promise<GenuineCheck>}
 */
export async function validateAuthenticity({ url, text, llmConfig }) {
  const excerptChars = TOKENS.VALIDATION_EXCERPT * TOKENS.CHARS_PER_TOKEN;
  const excerpt = text.slice(0, excerptChars);
  const { system, user } = authenticityPrompt(url, excerpt);

  try {
    const { json } = await callLLMJson({ systemPrompt: system, userPrompt: user, ...llmConfig });
    const check = {
      genuine: Boolean(json.genuine),
      confidence: clampConfidence(json.confidence),
      reason: typeof json.reason === 'string' ? json.reason : 'No reason provided.',
    };
    log.debug('authenticity:', check);
    return check;
  } catch (error) {
    // A validation failure is not a genuine=false verdict — surface it so the
    // pipeline can decide. Default to not-genuine so we never upload on error.
    log.warn('validation failed:', error.message);
    return { genuine: false, confidence: 0, reason: `Validation error: ${error.message}` };
  }
}

/**
 * Gate for hive upload: genuine AND confidence >= threshold.
 * @param {GenuineCheck} check
 * @returns {boolean}
 */
export function passesUploadGate(check) {
  return check.genuine === true && check.confidence >= AUTHENTICITY_CONFIDENCE_THRESHOLD;
}

function clampConfidence(value) {
  const n = Number(value);
  if (Number.isNaN(n)) return 0;
  return Math.max(0, Math.min(100, Math.round(n)));
}
