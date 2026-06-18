/**
 * Text-to-speech (F-10). Default engine is the Web Speech API — zero cost, no
 * key, offline. Optional premium path uses OpenAI's tts-1 if the user has an
 * OpenAI key configured. Runs in the popup context (has window.speechSynthesis).
 *
 * Exposes a small player with play/pause/stop/rate/voice controls. Auto-stops
 * are wired by the popup on unload/navigation.
 */
import { PROVIDER_ENDPOINTS } from '../utils/CONSTANTS.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('tts');

/** Build the spoken script from a summary: TL;DR + Key Risks by default. */
export function buildScript(summary, { full = false } = {}) {
  if (!summary) return '';
  const lines = [];
  if (summary.tldr) lines.push(`Summary. ${summary.tldr}`);
  if (summary.keyRisks?.length) {
    lines.push('Key risks.');
    summary.keyRisks.forEach((r) => lines.push(r));
  }
  if (full) {
    if (summary.dataCollected?.length) {
      lines.push('Data collected.');
      summary.dataCollected.forEach((d) => {
        // dataCollected may be {item, detail} objects or legacy strings.
        if (typeof d === 'string') lines.push(d);
        else lines.push(d.detail ? `${d.item}: ${d.detail}` : d.item);
      });
    }
    if (summary.thirdPartySharing?.length) {
      lines.push('Third party sharing.');
      summary.thirdPartySharing.forEach((t) => lines.push(t));
    }
    if (summary.userRights?.length) {
      lines.push('Your rights.');
      summary.userRights.forEach((u) => lines.push(u));
    }
  }
  return lines.join(' ');
}

/* ------------------------- browser engine ------------------------ */

export function listBrowserVoices() {
  return window.speechSynthesis ? window.speechSynthesis.getVoices() : [];
}

/**
 * Speak text with the Web Speech API.
 * @param {object} opts - { text, rate, voiceName, onend }
 */
export function speakBrowser({ text, rate = 1, voiceName = null, onend = () => {} }) {
  if (!window.speechSynthesis) {
    log.warn('Web Speech API unavailable');
    return;
  }
  stopBrowser();
  const utter = new SpeechSynthesisUtterance(text);
  utter.rate = rate;
  if (voiceName) {
    const voice = listBrowserVoices().find((v) => v.name === voiceName);
    if (voice) utter.voice = voice;
  }
  utter.onend = onend;
  window.speechSynthesis.speak(utter);
}

export function pauseBrowser() {
  window.speechSynthesis?.pause();
}

export function resumeBrowser() {
  window.speechSynthesis?.resume();
}

export function stopBrowser() {
  if (window.speechSynthesis) {
    window.speechSynthesis.cancel();
  }
}

/* ------------------------- premium engine ------------------------ */

/**
 * Synthesize speech via OpenAI TTS and return a playable Blob URL.
 * @param {object} opts - { text, apiKey, rate, voice }
 * @returns {Promise<string>} object URL for an <audio> element
 */
export async function synthesizeOpenAI({ text, apiKey, rate = 1, voice = 'alloy' }) {
  const res = await fetch(PROVIDER_ENDPOINTS.openaiTts, {
    method: 'POST',
    headers: { 'content-type': 'application/json', authorization: `Bearer ${apiKey}` },
    body: JSON.stringify({ model: 'tts-1', voice, input: text, speed: rate }),
  });
  if (!res.ok) {
    throw new Error(`TTS_ERROR: OpenAI TTS failed (${res.status}).`);
  }
  const blob = await res.blob();
  return URL.createObjectURL(blob);
}
