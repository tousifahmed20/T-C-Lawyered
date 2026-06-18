/**
 * SHA-256 content hashing via Web Crypto (F-03). The hash is the primary
 * lookup key for both the local DB and the hive backend, so hashing must be
 * deterministic: we normalize whitespace before digesting.
 */

/**
 * Normalize text so cosmetic whitespace differences don't change the hash.
 * @param {string} text
 * @returns {string}
 */
export function normalizeForHash(text) {
  return text.replace(/\s+/g, ' ').trim();
}

/**
 * Compute the SHA-256 hex digest of normalized text.
 * @param {string} text
 * @returns {Promise<string>} 64-char lowercase hex string
 */
export async function computeHash(text) {
  const encoder = new TextEncoder();
  const data = encoder.encode(normalizeForHash(text));
  const hashBuffer = await crypto.subtle.digest('SHA-256', data);
  const hashArray = Array.from(new Uint8Array(hashBuffer));
  return hashArray.map((b) => b.toString(16).padStart(2, '0')).join('');
}
