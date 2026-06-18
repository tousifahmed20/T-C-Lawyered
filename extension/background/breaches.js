/**
 * Breach history via Have I Been Pwned (data-safety feature, factual source).
 *
 * Privacy-preserving by design: we download HIBP's *entire* public breach list
 * and match the site's domain LOCALLY. The domain the user is visiting is never
 * sent to HIBP — the only outbound request is for a public, static JSON list.
 * The list is cached for a day. Every failure degrades to "no data".
 */
import { HIBP_BREACHES_URL, HIBP_CACHE_TTL_MS } from '../utils/CONSTANTS.js';
import { normalizeDomain } from '../utils/domain.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('breaches');
const CACHE_KEY = 'hibp_breaches';

/** Load (and cache) the full HIBP breach directory, slimmed to what we render. */
async function loadBreachList() {
  const { [CACHE_KEY]: cached } = await chrome.storage.local.get(CACHE_KEY);
  if (cached && Date.now() - cached.fetchedAt < HIBP_CACHE_TTL_MS) {
    return cached.breaches;
  }
  try {
    const res = await fetch(HIBP_BREACHES_URL, { headers: { accept: 'application/json' } });
    if (!res.ok) throw new Error(`HIBP returned ${res.status}`);
    const data = await res.json();
    const slim = data.map((b) => ({
      name: b.Name,
      title: b.Title,
      domain: b.Domain,
      date: b.BreachDate, // YYYY-MM-DD
      count: b.PwnCount,
      dataClasses: b.DataClasses || [],
      verified: b.IsVerified,
    }));
    await chrome.storage.local.set({ [CACHE_KEY]: { fetchedAt: Date.now(), breaches: slim } });
    log.debug(`cached ${slim.length} HIBP breaches`);
    return slim;
  } catch (error) {
    log.warn('HIBP fetch failed:', error.message);
    return cached?.breaches || []; // stale-but-useful, or empty
  }
}

/**
 * Known breaches for a normalized domain, newest first.
 * @param {string} domain
 * @returns {Promise<Array<object>>}
 */
export async function getBreachesForDomain(domain) {
  if (!domain) return [];
  const list = await loadBreachList();
  return list
    .filter((b) => b.domain && normalizeDomain(b.domain) === domain)
    .sort((a, b) => (b.date || '').localeCompare(a.date || ''));
}
