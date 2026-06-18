/**
 * Provider-agnostic LLM interface (F-11). Every provider adapter conforms to:
 *   call({ systemPrompt, userPrompt, model, apiKey, signal }) -> { text, tokensUsed }
 *
 * This module owns:
 *  - routing to the right adapter
 *  - request timeout via AbortController
 *  - mapping HTTP errors to actionable, typed Error messages
 *  - tolerant JSON extraction from model output
 */
import { FETCH_TIMEOUT_MS } from '../utils/CONSTANTS.js';
import { createLogger } from '../utils/logger.js';
import { call as anthropic } from './providers/anthropic.js';
import { call as openai } from './providers/openai.js';
import { call as gemini } from './providers/gemini.js';
import { call as openrouter } from './providers/openrouter.js';

const log = createLogger('llm');

const ADAPTERS = { anthropic, openai, gemini, openrouter };

/**
 * Build a typed provider error from an HTTP status. Adapters throw this; the
 * pipeline surfaces the `.message` to the user as a clean fallback.
 * @param {number} status
 * @param {string} body
 * @returns {Error}
 */
export function providerError(status, body) {
  let message;
  if (status === 401 || status === 403) {
    message = 'INVALID_API_KEY: Check your API key in settings.';
  } else if (status === 429) {
    message = 'RATE_LIMITED: Wait a moment and try again.';
  } else if (status >= 500) {
    message = `PROVIDER_DOWN: The LLM provider returned ${status}. Try again later.`;
  } else {
    message = `LLM_ERROR: Request failed (${status}). ${body}`;
  }
  const err = new Error(message);
  err.status = status;
  return err;
}

/**
 * Call the configured LLM provider with a prompt pair.
 * @param {object} args
 * @param {string} args.systemPrompt
 * @param {string} args.userPrompt
 * @param {'anthropic'|'openai'|'gemini'|'openrouter'} args.provider
 * @param {string} args.model
 * @param {string} args.apiKey
 * @returns {Promise<{ text: string, tokensUsed: number }>}
 */
export async function callLLM({ systemPrompt, userPrompt, provider, model, apiKey }) {
  const adapter = ADAPTERS[provider];
  if (!adapter) throw new Error(`CONFIG_ERROR: Unknown provider "${provider}".`);
  if (!apiKey) throw new Error('CONFIG_ERROR: No API key configured. Add one in settings.');

  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
  try {
    const result = await adapter({
      systemPrompt,
      userPrompt,
      model,
      apiKey,
      signal: controller.signal,
    });
    log.debug(`${provider}/${model} ok — ${result.tokensUsed} tokens`);
    return result;
  } catch (error) {
    if (error.name === 'AbortError') {
      throw new Error('TIMEOUT: The LLM request took too long. Try again.');
    }
    throw error;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Call the LLM and parse its output as JSON. Tolerant of code fences and
 * surrounding prose that some models add despite instructions.
 * @returns {Promise<{ json: object, tokensUsed: number }>}
 */
export async function callLLMJson(args) {
  const { text, tokensUsed } = await callLLM(args);
  return { json: parseJsonLoose(text), tokensUsed };
}

/**
 * Extract a JSON object from model text. Strips ```json fences and grabs the
 * outermost {...} span if extra prose leaked in.
 * @param {string} text
 * @returns {object}
 */
export function parseJsonLoose(text) {
  if (!text || typeof text !== 'string') {
    throw new Error('PARSE_ERROR: Empty response from LLM.');
  }
  const cleaned = text.replace(/```json\s*/gi, '').replace(/```/g, '').trim();
  try {
    return JSON.parse(cleaned);
  } catch {
    const first = cleaned.indexOf('{');
    const last = cleaned.lastIndexOf('}');
    if (first !== -1 && last > first) {
      try {
        return JSON.parse(cleaned.slice(first, last + 1));
      } catch {
        /* fall through */
      }
    }
    throw new Error('PARSE_ERROR: LLM did not return valid JSON.');
  }
}
