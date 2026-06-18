/**
 * Hive backend client (F-04, F-08). The hive is a read-heavy, content-addressed
 * cache — never a dependency. Every call here degrades gracefully: a lookup
 * failure returns "miss", an upload failure is swallowed (fire-and-forget).
 */
import { HIVE_BASE_URL, HIVE_TIMEOUT_MS, FETCH_TIMEOUT_MS } from '../utils/CONSTANTS.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('hive');

/** fetch with an abort timeout. Resolves to null on any network failure. */
async function timedFetch(url, options, timeoutMs) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (error) {
    log.warn('hive unreachable:', error.message);
    return null;
  } finally {
    clearTimeout(timer);
  }
}

/**
 * Look up a summary by content hash (F-04). Non-blocking, fails to miss.
 * @returns {Promise<{ found: boolean, summary?: object, submittedAt?: string }>}
 */
export async function lookupPolicy(hash, domain) {
  const url = `${HIVE_BASE_URL}/policy?hash=${encodeURIComponent(hash)}&domain=${encodeURIComponent(domain)}`;
  const res = await timedFetch(url, { method: 'GET' }, HIVE_TIMEOUT_MS);
  if (!res || !res.ok) return { found: false };
  try {
    const data = await res.json();
    return data.found ? data : { found: false };
  } catch {
    return { found: false };
  }
}

/**
 * Fetch the version chain for a domain + type (F-07 support).
 * @returns {Promise<{ versions: Array<{hash, parentHash, submittedAt}> }>}
 */
export async function fetchHistory(domain, type) {
  const url = `${HIVE_BASE_URL}/policy/history?domain=${encodeURIComponent(domain)}&type=${encodeURIComponent(type)}`;
  const res = await timedFetch(url, { method: 'GET' }, HIVE_TIMEOUT_MS);
  if (!res || !res.ok) return { versions: [] };
  try {
    return await res.json();
  } catch {
    return { versions: [] };
  }
}

/**
 * Upload a summary (F-08). Fire-and-forget — never throws, never blocks UI.
 * First-write-wins is enforced server-side; a 409 is a no-op success for us.
 * @returns {Promise<{ stored: boolean, reason?: string }>}
 */
export async function uploadPolicy(payload) {
  const res = await timedFetch(
    `${HIVE_BASE_URL}/policy`,
    {
      method: 'POST',
      headers: { 'content-type': 'application/json' },
      body: JSON.stringify(payload),
    },
    FETCH_TIMEOUT_MS,
  );
  if (!res) return { stored: false, reason: 'unreachable' };
  if (res.status === 409) {
    log.debug('hive already has this hash (first-write-wins)');
    return { stored: false, reason: 'hash_exists' };
  }
  if (!res.ok) return { stored: false, reason: `status_${res.status}` };
  log.debug('uploaded summary to hive');
  return { stored: true };
}
