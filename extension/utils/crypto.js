/**
 * AES-GCM 256 encryption for API keys at rest (F-11, NFR-02).
 *
 * The encryption key is derived on demand from a per-install random salt that
 * lives in chrome.storage.local. We never persist the derived CryptoKey itself,
 * and the plaintext API key only exists transiently in memory during a call.
 *
 * Note: this protects keys from casual disk inspection of IndexedDB/storage.
 * It is not a defence against an attacker who already controls the browser
 * profile — nothing in-browser can be. That is an accepted, documented limit.
 */
import { createLogger } from './logger.js';

const log = createLogger('crypto');
const SALT_KEY = 'tc_kdf_salt';
const KDF_ITERATIONS = 100000;

const encoder = new TextEncoder();
const decoder = new TextDecoder();

/** Stable per-install seed material. Combines a random salt with extension id. */
async function getSeed() {
  const stored = await chrome.storage.local.get(SALT_KEY);
  let salt = stored[SALT_KEY];
  if (!salt) {
    const raw = crypto.getRandomValues(new Uint8Array(16));
    salt = bufferToBase64(raw.buffer);
    await chrome.storage.local.set({ [SALT_KEY]: salt });
    log.debug('generated new KDF salt');
  }
  return { salt: base64ToBuffer(salt), id: chrome.runtime.id };
}

/** Derive an AES-GCM key from install seed via PBKDF2. */
async function deriveKey() {
  const { salt, id } = await getSeed();
  const baseKey = await crypto.subtle.importKey(
    'raw',
    encoder.encode(id),
    'PBKDF2',
    false,
    ['deriveKey'],
  );
  return crypto.subtle.deriveKey(
    { name: 'PBKDF2', salt, iterations: KDF_ITERATIONS, hash: 'SHA-256' },
    baseKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt', 'decrypt'],
  );
}

/**
 * Encrypt a plaintext string. Returns a base64 payload of iv + ciphertext.
 * @param {string} plaintext
 * @returns {Promise<string>}
 */
export async function encrypt(plaintext) {
  const key = await deriveKey();
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv },
    key,
    encoder.encode(plaintext),
  );
  const combined = new Uint8Array(iv.length + ciphertext.byteLength);
  combined.set(iv, 0);
  combined.set(new Uint8Array(ciphertext), iv.length);
  return bufferToBase64(combined.buffer);
}

/**
 * Decrypt a base64 payload produced by {@link encrypt}.
 * @param {string} payload
 * @returns {Promise<string>}
 */
export async function decrypt(payload) {
  const key = await deriveKey();
  const combined = new Uint8Array(base64ToBuffer(payload));
  const iv = combined.slice(0, 12);
  const ciphertext = combined.slice(12);
  const plaintext = await crypto.subtle.decrypt({ name: 'AES-GCM', iv }, key, ciphertext);
  return decoder.decode(plaintext);
}

function bufferToBase64(buffer) {
  const bytes = new Uint8Array(buffer);
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary);
}

function base64ToBuffer(base64) {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i += 1) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}
