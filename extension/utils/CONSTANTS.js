/**
 * Single source of truth for hardcoded values. No URLs or magic numbers live
 * anywhere else in the codebase (per project no-go list).
 */

/** Hive backend base URL. Override at build time if self-hosting. */
export const HIVE_BASE_URL = 'https://api.tclawyered.dev';

/** LLM provider API endpoints. User keys only — never our keys. */
export const PROVIDER_ENDPOINTS = {
  anthropic: 'https://api.anthropic.com/v1/messages',
  openai: 'https://api.openai.com/v1/chat/completions',
  gemini: 'https://generativelanguage.googleapis.com/v1beta/models',
  openrouter: 'https://openrouter.ai/api/v1/chat/completions',
  openaiTts: 'https://api.openai.com/v1/audio/speech',
};

/** Optional OpenRouter attribution headers (used for their app leaderboard). */
export const OPENROUTER_REFERER = 'https://github.com/tc-lawyered/tc-lawyered';
export const OPENROUTER_TITLE = 'T&C Lawyered';

/** Anthropic API version header value. */
export const ANTHROPIC_VERSION = '2023-06-01';

/**
 * Have I Been Pwned public breach directory. This endpoint needs no API key.
 * We download the full list and match the site's domain LOCALLY — the domain
 * the user is visiting is never sent to HIBP.
 */
export const HIBP_BREACHES_URL = 'https://haveibeenpwned.com/api/v3/breaches';

/** Cache lifetimes for the data-safety lookups. */
export const HIBP_CACHE_TTL_MS = 24 * 60 * 60 * 1000; // refresh breach list daily
export const TRACK_RECORD_TTL_MS = 30 * 24 * 60 * 60 * 1000; // AI track record: 30 days
/** Only show AI-reported actions at or above this confidence. */
export const TRACK_RECORD_MIN_CONFIDENCE = 70;

/** Supported providers and their selectable models. */
export const PROVIDER_MODELS = {
  anthropic: ['claude-haiku-4-5', 'claude-sonnet-4-6'],
  openai: ['gpt-4o-mini', 'gpt-4o'],
  gemini: ['gemini-1.5-flash', 'gemini-1.5-pro'],
  // OpenRouter aggregates many models behind one OpenAI-compatible API.
  // `:free` models cost nothing but are rate-limited and may be retired —
  // verify the current list at https://openrouter.ai/models and edit freely.
  openrouter: [
    'deepseek/deepseek-chat-v3-0324:free',
    'meta-llama/llama-3.3-70b-instruct:free',
    'google/gemini-2.0-flash-exp:free',
    'anthropic/claude-3.5-sonnet',
    'openai/gpt-4o-mini',
  ],
};

/** Policy types we recognise. */
export const POLICY_TYPES = {
  PRIVACY: 'privacy_policy',
  TERMS: 'terms_of_service',
};

/** Chunking thresholds (token estimates). */
export const TOKENS = {
  CHARS_PER_TOKEN: 4,
  SINGLE_CALL_MAX: 8000,
  CHUNK_SIZE: 4000,
  CHUNK_OVERLAP: 200,
  VALIDATION_EXCERPT: 2000,
};

/** Authenticity gate: must be genuine AND confidence >= this to upload. */
export const AUTHENTICITY_CONFIDENCE_THRESHOLD = 85;

/** Minimum visible words before a text block is considered a policy candidate. */
export const MIN_POLICY_WORDS = 2000;
export const MIN_MODAL_WORDS = 1000;

/** Subdomains stripped during domain normalization. */
export const STRIPPED_SUBDOMAINS = ['www', 'accounts', 'legal', 'policies', 'help', 'support'];

/** Snapshot retention before auto-prune (ms). 12 months. */
export const SNAPSHOT_TTL_MS = 365 * 24 * 60 * 60 * 1000;

/**
 * A cached summary older than this is re-checked and re-summarized on the next
 * visit (2 months). Keeps summaries current as models and public track records
 * change, even when the policy text itself is unchanged.
 */
export const RECHECK_TTL_MS = 60 * 24 * 60 * 60 * 1000;

/** Network timeout for any single fetch (ms). */
export const FETCH_TIMEOUT_MS = 30000;

/** Hive lookup timeout — fail fast, offline-first (ms). */
export const HIVE_TIMEOUT_MS = 2000;

/** Internal message types passed between content / SW / popup. */
export const MSG = {
  POLICY_DETECTED: 'POLICY_DETECTED',
  GET_SUMMARY: 'GET_SUMMARY',
  SUMMARY_READY: 'SUMMARY_READY',
  SUMMARY_ERROR: 'SUMMARY_ERROR',
  GET_HISTORY: 'GET_HISTORY',
  TEST_PROVIDER: 'TEST_PROVIDER',
  REPROCESS: 'REPROCESS',
  CRAWL_PROGRESS: 'CRAWL_PROGRESS',
  CHECK_REPUTATION: 'CHECK_REPUTATION',
};

/** Auto-crawl safety bounds (content/crawler.js). */
export const CRAWL = {
  MAX_TOGGLES: 120, // never click more than this many sections (incl. nested)
  MAX_TOTAL_MS: 90000, // hard wall-clock cap for the whole crawl
  SETTLE_QUIET_MS: 300, // consider DOM settled after this much quiet
  SETTLE_MAX_MS: 1500, // but wait no longer than this per click
  HOLD_OPEN_MS: 280, // keep each section open this long so it's perceptible
  MIN_FINAL_WORDS: 50, // below this, treat the crawl as "nothing found"
};

/**
 * Attribute marking DOM injected by the extension itself (progress overlay).
 * The extractor skips anything inside it so our own UI never pollutes capture.
 */
export const UI_MARKER_ATTR = 'data-tc-lawyered-ui';

/** Change severity levels (drives badge colour). */
export const SEVERITY = {
  NONE: 'none',
  LOW: 'low',
  MEDIUM: 'medium',
  HIGH: 'high',
};

/** Plain-English definitions shown via the ⓘ toggle on each summary card. */
export const GLOSSARY = {
  keyRisks:
    'The parts most likely to work against you — things you probably would not agree ' +
    'to if you read the fine print.',
  dataCollected: 'The specific information this service gathers about you.',
  thirdPartySharing: 'Who else receives your data besides this company.',
  userRights: 'What you are actually allowed to do about your data, often under laws like the GDPR or CCPA.',
  dataSafety:
    'This company’s real-world track record with your data. Known breaches come from ' +
    'the Have I Been Pwned database (matched on your device). Reported fines and ' +
    'controversies are AI-generated from public reports and may be incomplete or ' +
    'outdated — always verify before relying on them.',
};

/** Badge colours by severity. */
export const SEVERITY_COLORS = {
  none: '#34a853',
  low: '#fbbc04',
  medium: '#fa7b17',
  high: '#ea4335',
};
