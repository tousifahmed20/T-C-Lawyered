/**
 * Provider + preference configuration (F-11). API keys are stored in
 * chrome.storage.local, encrypted at rest with AES-GCM. The plaintext key is
 * only ever decrypted transiently inside the service worker right before a call
 * and is never written back out, logged, or sent anywhere but the provider.
 */
import { PROVIDER_MODELS, SUPPORTED_LANGUAGES } from './CONSTANTS.js';
import { encrypt, decrypt } from './crypto.js';
import { createLogger } from './logger.js';

const log = createLogger('config');
const PROVIDERS_KEY = 'providers';
const PREFS_KEY = 'prefs';
const ACTIVE_KEY = 'activeProvider';
const YOUTUBE_KEY = 'youtubeApiKey';

const DEFAULT_PREFS = {
  ttsEngine: 'browser', // 'browser' | 'openai'
  ttsRate: 1,
  ttsVoice: null,
  autoSummarize: true,
  hiveEnabled: true,
  language: null, // null = auto-detect from browser locale
};

const SUPPORTED_CODES = SUPPORTED_LANGUAGES.map((l) => l.code);

/**
 * Persist a provider's settings, encrypting the API key.
 * @param {'anthropic'|'openai'|'gemini'} provider
 * @param {{ apiKey: string, model: string }} config
 */
export async function saveProvider(provider, { apiKey, model }) {
  if (!PROVIDER_MODELS[provider]) throw new Error(`Unknown provider: ${provider}`);
  const all = await readProvidersRaw();
  all[provider] = {
    apiKeyEnc: apiKey ? await encrypt(apiKey) : null,
    model: model || PROVIDER_MODELS[provider][0],
  };
  await chrome.storage.local.set({ [PROVIDERS_KEY]: all });
  log.debug(`saved provider ${provider}`);
}

/** Set which provider is active for new summarizations. */
export async function setActiveProvider(provider) {
  await chrome.storage.local.set({ [ACTIVE_KEY]: provider });
}

export async function getActiveProvider() {
  const { [ACTIVE_KEY]: active } = await chrome.storage.local.get(ACTIVE_KEY);
  return active || null;
}

/**
 * Resolve the active provider into a ready-to-use LLM config with a decrypted
 * key. Returns null if nothing is configured.
 * @returns {Promise<{ provider, model, apiKey } | null>}
 */
export async function getActiveLLMConfig() {
  const active = await getActiveProvider();
  if (!active) return null;
  return getLLMConfig(active);
}

/** Resolve a specific provider into a decrypted LLM config. */
export async function getLLMConfig(provider) {
  const all = await readProvidersRaw();
  const entry = all[provider];
  if (!entry || !entry.apiKeyEnc) return null;
  try {
    const apiKey = await decrypt(entry.apiKeyEnc);
    return { provider, model: entry.model, apiKey };
  } catch (error) {
    log.error('failed to decrypt key for', provider, error.message);
    return null;
  }
}

/** Provider metadata for the settings UI — never includes plaintext keys. */
export async function getProvidersMeta() {
  const all = await readProvidersRaw();
  const active = await getActiveProvider();
  return Object.keys(PROVIDER_MODELS).reduce((acc, provider) => {
    acc[provider] = {
      configured: Boolean(all[provider]?.apiKeyEnc),
      model: all[provider]?.model || PROVIDER_MODELS[provider][0],
      models: PROVIDER_MODELS[provider],
      active: provider === active,
    };
    return acc;
  }, {});
}

/**
 * Optional YouTube Data API key (for listing recent protection videos). Stored
 * encrypted, same as provider keys; sent only to googleapis.com.
 */
export async function saveYoutubeKey(key) {
  await chrome.storage.local.set({ [YOUTUBE_KEY]: key ? await encrypt(key) : null });
}

export async function getYoutubeKey() {
  const { [YOUTUBE_KEY]: enc } = await chrome.storage.local.get(YOUTUBE_KEY);
  if (!enc) return null;
  try {
    return await decrypt(enc);
  } catch (error) {
    log.error('failed to decrypt YouTube key:', error.message);
    return null;
  }
}

export async function hasYoutubeKey() {
  const { [YOUTUBE_KEY]: enc } = await chrome.storage.local.get(YOUTUBE_KEY);
  return Boolean(enc);
}

export async function getPrefs() {
  const { [PREFS_KEY]: prefs } = await chrome.storage.local.get(PREFS_KEY);
  return { ...DEFAULT_PREFS, ...(prefs || {}) };
}

export async function savePrefs(partial) {
  const current = await getPrefs();
  const next = { ...current, ...partial };
  await chrome.storage.local.set({ [PREFS_KEY]: next });
  return next;
}

/**
 * The effective summary language code. Uses the saved preference, else the
 * browser UI locale (privacy-safe — no IP lookup), falling back to English.
 * @returns {Promise<string>} a SUPPORTED_LANGUAGES code
 */
export async function getLanguage() {
  const prefs = await getPrefs();
  if (prefs.language && SUPPORTED_CODES.includes(prefs.language)) return prefs.language;
  const ui = (chrome.i18n?.getUILanguage?.() || 'en').toLowerCase();
  const primary = ui.split('-')[0];
  return SUPPORTED_CODES.includes(primary) ? primary : 'en';
}

/** Persist the chosen language ('auto' or empty clears it back to auto-detect). */
export async function saveLanguage(code) {
  const value = code && SUPPORTED_CODES.includes(code) ? code : null;
  await savePrefs({ language: value });
}

/** English name of a language code, for the LLM prompt. */
export function languageName(code) {
  return SUPPORTED_LANGUAGES.find((l) => l.code === code)?.name || 'English';
}

async function readProvidersRaw() {
  const { [PROVIDERS_KEY]: all } = await chrome.storage.local.get(PROVIDERS_KEY);
  return all || {};
}
