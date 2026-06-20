/**
 * Chunked summarization pipeline (F-06). Short docs get one call; long docs are
 * split into overlapping windows, summarized per-section, then merged in a meta
 * pass. Token counts are estimated by the char/4 heuristic — good enough to pick
 * a path without bundling a tokenizer.
 */
import { TOKENS } from '../utils/CONSTANTS.js';
import {
  singleSummaryPrompt,
  sectionSummaryPrompt,
  metaSummaryPrompt,
} from '../utils/prompts.js';
import { callLLMJson } from './llm.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('chunker');

/**
 * Estimate token count from character length.
 * @param {string} text
 * @returns {number}
 */
export function estimateTokens(text) {
  return Math.ceil(text.length / TOKENS.CHARS_PER_TOKEN);
}

/**
 * Split text into ~CHUNK_SIZE token windows with CHUNK_OVERLAP token overlap.
 * Splits on character boundaries derived from the token estimate.
 * @param {string} text
 * @returns {string[]}
 */
export function splitIntoChunks(text) {
  const chunkChars = TOKENS.CHUNK_SIZE * TOKENS.CHARS_PER_TOKEN;
  const overlapChars = TOKENS.CHUNK_OVERLAP * TOKENS.CHARS_PER_TOKEN;
  const step = chunkChars - overlapChars;
  const chunks = [];
  for (let start = 0; start < text.length; start += step) {
    chunks.push(text.slice(start, start + chunkChars));
    if (start + chunkChars >= text.length) break;
  }
  return chunks;
}

/**
 * Summarize a document into the structured summary shape (without diff fields).
 * @param {object} args
 * @param {string} args.text
 * @param {string} args.domain
 * @param {string} args.policyType
 * @param {object} args.llmConfig - { provider, model, apiKey }
 * @returns {Promise<{ summary: object, tokensUsed: number }>}
 */
export async function summarizeDocument({ text, domain, policyType, llmConfig, lang }) {
  const tokens = estimateTokens(text);
  if (tokens <= TOKENS.SINGLE_CALL_MAX) {
    log.debug(`single-call path (${tokens} tokens)`);
    const { system, user } = singleSummaryPrompt(text, domain, policyType, lang);
    const { json, tokensUsed } = await callLLMJson({
      systemPrompt: system,
      userPrompt: user,
      ...llmConfig,
    });
    return { summary: shapeSummary(json), tokensUsed };
  }

  log.debug(`chunked path (${tokens} tokens)`);
  const chunks = splitIntoChunks(text);
  let tokensUsed = 0;
  const sections = [];
  for (let i = 0; i < chunks.length; i += 1) {
    const { system, user } = sectionSummaryPrompt(chunks[i], i, chunks.length);
    const { json, tokensUsed: used } = await callLLMJson({
      systemPrompt: system,
      userPrompt: user,
      ...llmConfig,
    });
    tokensUsed += used;
    sections.push({ summary: json.summary || '', points: json.points || [] });
  }

  const { system, user } = metaSummaryPrompt(sections, domain, policyType, lang);
  const { json, tokensUsed: metaUsed } = await callLLMJson({
    systemPrompt: system,
    userPrompt: user,
    ...llmConfig,
  });
  tokensUsed += metaUsed;
  return { summary: shapeSummary(json), tokensUsed };
}

/** Coerce model output into the canonical summary field shape. */
function shapeSummary(json) {
  const ex = json.examples || {};
  return {
    tldr: typeof json.tldr === 'string' ? json.tldr : '',
    keyRisks: toStringArray(json.keyRisks),
    dataCollected: toDataItems(json.dataCollected),
    thirdPartySharing: toStringArray(json.thirdPartySharing),
    userRights: toStringArray(json.userRights),
    protectionTips: toStringArray(json.protectionTips),
    examples: {
      keyRisks: toStr(ex.keyRisks),
      dataCollected: toStr(ex.dataCollected),
      thirdPartySharing: toStr(ex.thirdPartySharing),
      userRights: toStr(ex.userRights),
    },
  };
}

function toStringArray(value) {
  if (!Array.isArray(value)) return [];
  return value.filter((v) => typeof v === 'string' && v.trim().length > 0);
}

/** Coerce dataCollected into { item, detail } objects; tolerant of plain strings. */
function toDataItems(value) {
  if (!Array.isArray(value)) return [];
  return value
    .map((v) => {
      if (typeof v === 'string') return { item: v.trim(), detail: '' };
      if (v && typeof v === 'object') return { item: toStr(v.item) || toStr(v.label), detail: toStr(v.detail) };
      return null;
    })
    .filter((v) => v && v.item);
}

function toStr(value) {
  return typeof value === 'string' ? value.trim() : '';
}
