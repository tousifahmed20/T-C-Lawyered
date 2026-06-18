/**
 * Settings controller (F-11, F-12). Renders provider cards, persists encrypted
 * keys + prefs, runs provider health checks, and manages local data (usage,
 * export, clear). Talks to the SW only for the live provider test.
 */
import { MSG, PROVIDER_MODELS } from '../utils/CONSTANTS.js';
import { createLogger } from '../utils/logger.js';
import {
  saveProvider,
  setActiveProvider,
  getProvidersMeta,
  getPrefs,
  savePrefs,
  saveYoutubeKey,
  hasYoutubeKey,
} from '../utils/config.js';
import { listBrowserVoices } from '../audio/tts.js';
import { exportAll, clearAll, estimateUsage } from '../storage/db.js';

const log = createLogger('settings');
const $ = (id) => document.getElementById(id);

document.addEventListener('DOMContentLoaded', init);

async function init() {
  await renderProviders();
  await renderPrefs();
  renderVoices();
  await renderUsage();
  wireDataButtons();
  await renderYoutubeStatus();
  wireYoutube();
}

/* ----------------------------- youtube --------------------------- */

async function renderYoutubeStatus() {
  $('youtubeStatus').textContent = (await hasYoutubeKey()) ? 'Saved ✓' : 'Not set';
}

function wireYoutube() {
  $('saveYoutube').addEventListener('click', async () => {
    const key = $('youtubeKey').value.trim();
    if (!key) return toast('Enter a YouTube Data API key first.');
    await saveYoutubeKey(key);
    $('youtubeKey').value = '';
    toast('YouTube key saved.');
    await renderYoutubeStatus();
  });
  $('clearYoutube').addEventListener('click', async () => {
    await saveYoutubeKey(null);
    $('youtubeKey').value = '';
    toast('YouTube key cleared.');
    await renderYoutubeStatus();
  });
}

/* --------------------------- providers --------------------------- */

async function renderProviders() {
  const meta = await getProvidersMeta();
  const root = $('providers');
  root.innerHTML = '';

  for (const provider of Object.keys(PROVIDER_MODELS)) {
    const info = meta[provider];
    const card = document.createElement('div');
    card.className = `provider${info.active ? ' active' : ''}`;
    card.innerHTML = `
      <div class="provider-head">
        <h3>${provider}</h3>
        <span class="badge ${info.configured ? 'ok' : ''}">${info.configured ? 'Configured' : 'Not set'}</span>
      </div>
      <div class="provider-fields">
        <input type="password" placeholder="API key" data-field="key" autocomplete="off" />
        <select data-field="model">
          ${info.models.map((m) => `<option value="${m}" ${m === info.model ? 'selected' : ''}>${m}</option>`).join('')}
        </select>
      </div>
      <div class="provider-actions">
        <button class="primary" data-action="save">Save</button>
        <button class="secondary" data-action="test">Test</button>
        <button class="secondary" data-action="activate">${info.active ? 'Active ✓' : 'Use this'}</button>
        <span class="test-result"></span>
      </div>
    `;

    card.querySelector('[data-action="save"]').addEventListener('click', () =>
      onSave(provider, card),
    );
    card.querySelector('[data-action="test"]').addEventListener('click', () =>
      onTest(provider, card),
    );
    card.querySelector('[data-action="activate"]').addEventListener('click', () =>
      onActivate(provider),
    );

    root.appendChild(card);
  }
}

async function onSave(provider, card) {
  const apiKey = card.querySelector('[data-field="key"]').value.trim();
  const model = card.querySelector('[data-field="model"]').value;
  if (!apiKey) return toast('Enter an API key first.');
  await saveProvider(provider, { apiKey, model });
  toast(`${provider} saved.`);
  await renderProviders();
}

async function onTest(provider, card) {
  const result = card.querySelector('.test-result');
  result.textContent = 'Testing…';
  result.style.color = 'var(--muted)';
  // The SW holds the decryption + network path; ask it to ping the provider.
  const res = await chrome.runtime.sendMessage({ type: MSG.TEST_PROVIDER, provider });
  if (res?.ok) {
    result.textContent = '✓ Working';
    result.style.color = 'var(--green)';
  } else {
    result.textContent = `✗ ${res?.error || 'Failed'}`;
    result.style.color = 'var(--red)';
  }
}

async function onActivate(provider) {
  await setActiveProvider(provider);
  toast(`${provider} is now active.`);
  await renderProviders();
}

/* ----------------------------- prefs ----------------------------- */

async function renderPrefs() {
  const prefs = await getPrefs();
  $('ttsEngine').value = prefs.ttsEngine;
  $('ttsRate').value = String(prefs.ttsRate);
  $('autoSummarize').checked = prefs.autoSummarize;
  $('hiveEnabled').checked = prefs.hiveEnabled;

  $('ttsEngine').addEventListener('change', (e) => savePrefs({ ttsEngine: e.target.value }));
  $('ttsRate').addEventListener('change', (e) => savePrefs({ ttsRate: parseFloat(e.target.value) }));
  $('ttsVoice').addEventListener('change', (e) => savePrefs({ ttsVoice: e.target.value || null }));
  $('autoSummarize').addEventListener('change', (e) => savePrefs({ autoSummarize: e.target.checked }));
  $('hiveEnabled').addEventListener('change', (e) => savePrefs({ hiveEnabled: e.target.checked }));
}

function renderVoices() {
  const select = $('ttsVoice');
  const populate = async () => {
    const prefs = await getPrefs();
    const voices = listBrowserVoices();
    select.innerHTML = '<option value="">System default</option>';
    for (const v of voices) {
      const opt = document.createElement('option');
      opt.value = v.name;
      opt.textContent = `${v.name} (${v.lang})`;
      if (prefs.ttsVoice === v.name) opt.selected = true;
      select.appendChild(opt);
    }
  };
  populate();
  if (window.speechSynthesis) window.speechSynthesis.onvoiceschanged = populate;
}

/* ------------------------------ data ----------------------------- */

async function renderUsage() {
  const { usage, quota } = await estimateUsage();
  const mb = (n) => (n / (1024 * 1024)).toFixed(1);
  const pct = quota ? Math.min(100, (usage / quota) * 100) : 0;
  $('usageFill').style.width = `${pct}%`;
  $('usageText').textContent = quota
    ? `${mb(usage)} MB used of ~${mb(quota)} MB available`
    : 'Storage estimate unavailable.';
}

function wireDataButtons() {
  $('exportBtn').addEventListener('click', onExport);
  $('clearBtn').addEventListener('click', onClear);
}

async function onExport() {
  const data = await exportAll();
  if (!data) return toast('Export failed.');
  const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `tc-lawyered-export-${Date.now()}.json`;
  a.click();
  URL.revokeObjectURL(url);
  toast('Data exported.');
}

async function onClear() {
  if (!confirm('Delete all locally stored summaries, history, and settings? This cannot be undone.')) {
    return;
  }
  await clearAll();
  toast('All local data cleared.');
  await renderUsage();
  await renderProviders();
}

/* ----------------------------- toast ----------------------------- */

let toastTimer = null;
function toast(message) {
  const el = $('toast');
  el.textContent = message;
  el.classList.remove('hidden');
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => el.classList.add('hidden'), 2600);
}

log.debug('settings ready');
