/**
 * Version diffing (F-07). When a new document hash differs from the latest
 * stored version for the same domain+type, ask the LLM to explain the change in
 * plain English and rate its severity. To stay within context limits on long
 * docs, only the leading portion of each version is diffed.
 */
import { TOKENS, SEVERITY } from '../utils/CONSTANTS.js';
import { diffPrompt } from '../utils/prompts.js';
import { callLLMJson } from './llm.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('differ');

const VALID_SEVERITIES = new Set(Object.values(SEVERITY));
// Cap each side so the diff call stays well under model context windows.
const MAX_SIDE_CHARS = TOKENS.SINGLE_CALL_MAX * TOKENS.CHARS_PER_TOKEN * 0.5;

/**
 * Produce a plain-English diff between two policy versions.
 * @param {object} args
 * @param {string} args.oldText
 * @param {string} args.newText
 * @param {string} args.domain
 * @param {string} args.policyType
 * @param {object} args.llmConfig
 * @returns {Promise<{ whatChanged: string|null, changes: string[], changesSeverity: string, tokensUsed: number }>}
 */
export async function diffVersions({ oldText, newText, domain, policyType, llmConfig }) {
  const { system, user } = diffPrompt(
    oldText.slice(0, MAX_SIDE_CHARS),
    newText.slice(0, MAX_SIDE_CHARS),
    domain,
    policyType,
  );
  try {
    const { json, tokensUsed } = await callLLMJson({
      systemPrompt: system,
      userPrompt: user,
      ...llmConfig,
    });
    const changes = (Array.isArray(json.changes) ? json.changes : []).filter(
      (c) => typeof c === 'string' && c.trim().length > 0,
    );
    return {
      whatChanged: typeof json.whatChanged === 'string' ? json.whatChanged : null,
      changes,
      changesSeverity: normalizeSeverity(json.changesSeverity),
      tokensUsed,
    };
  } catch (error) {
    log.warn('diff failed:', error.message);
    // Don't fail the whole pipeline over a diff — degrade to "no diff available".
    return { whatChanged: null, changes: [], changesSeverity: SEVERITY.NONE, tokensUsed: 0 };
  }
}

function normalizeSeverity(value) {
  return VALID_SEVERITIES.has(value) ? value : SEVERITY.NONE;
}
