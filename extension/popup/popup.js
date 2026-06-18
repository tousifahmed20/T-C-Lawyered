/**
 * Popup controller (F-09). Pulls the current tab's pipeline state from the
 * service worker, renders the structured summary, wires the audio player
 * (F-10), and powers the History tab. No business logic lives here — the SW
 * owns the pipeline; the popup is a thin view.
 */
import { MSG, SEVERITY, GLOSSARY } from '../utils/CONSTANTS.js';
import { createLogger } from '../utils/logger.js';
import { normalizeDomain } from '../utils/domain.js';
import { getPrefs, getLLMConfig } from '../utils/config.js';
import {
  buildScript,
  speakBrowser,
  pauseBrowser,
  resumeBrowser,
  stopBrowser,
  synthesizeOpenAI,
} from '../audio/tts.js';

const log = createLogger('popup');
const $ = (id) => document.getElementById(id);

let activeTabId = null;
let activeTabUrl = null;
let currentSummary = null;
let currentMeta = null;
let currentDataSafety = null;
let currentProtection = null;
let audioEl = null;

document.addEventListener('DOMContentLoaded', init);

async function init() {
  wireTabs();
  wireButtons();
  wireHelp();
  const [tab] = await chrome.tabs.query({ active: true, currentWindow: true });
  activeTabId = tab?.id ?? null;
  activeTabUrl = tab?.url ?? null;

  // Clear the change badge now that the user has opened the popup (F-13).
  if (activeTabId != null) chrome.action.setBadgeText({ tabId: activeTabId, text: '' });

  await loadState();

  // Live updates while the pipeline runs.
  chrome.runtime.onMessage.addListener((msg) => {
    if (msg.tabId != null && msg.tabId !== activeTabId) return;
    if (msg.type === MSG.SUMMARY_READY) loadState();
    if (msg.type === MSG.SUMMARY_ERROR) showError(msg.error);
    if (msg.type === MSG.CRAWL_PROGRESS) onCrawlProgress(msg);
  });
}

async function loadState() {
  const res = await chrome.runtime.sendMessage({ type: MSG.GET_SUMMARY, tabId: activeTabId });
  if (!res?.ok) return showEmpty();

  currentMeta = res.meta || null;
  currentDataSafety = res.dataSafety || null;
  currentProtection = res.protection || null;
  switch (res.status) {
    case 'working':
      return showLoading();
    case 'ready':
      currentSummary = res.summary;
      return renderSummary(res.summary, res.meta);
    case 'error':
      return showError(res.error);
    default:
      return showEmpty();
  }
}

/* ----------------------------- rendering ------------------------- */

function show(...ids) {
  ['loading', 'empty', 'summary', 'errorBox'].forEach((id) => $(id).classList.add('hidden'));
  ids.forEach((id) => $(id).classList.remove('hidden'));
}

function showLoading() {
  show('loading');
}

function showEmpty() {
  show('empty');
}

function showError(message) {
  show('errorBox');
  $('errorMsg').textContent = humanizeError(message);
}

function renderSummary(summary, meta) {
  if (!summary) return showEmpty();
  // A crawl (if any) has produced its result — clear its progress UI.
  $('crawlProgress').classList.add('hidden');
  $('autoReadBtn').disabled = false;
  show('summary');

  $('tldr').textContent = summary.tldr || '—';
  fillList('keyRisks', summary.keyRisks);
  fillDataCollected(summary.dataCollected);
  fillList('thirdPartySharing', summary.thirdPartySharing);
  fillList('userRights', summary.userRights);

  // Content-relevant examples beneath each section.
  const examples = summary.examples || {};
  fillExample('keyRisks', examples.keyRisks);
  fillExample('dataCollected', examples.dataCollected);
  fillExample('thirdPartySharing', examples.thirdPartySharing);
  fillExample('userRights', examples.userRights);

  // What changed (diff vs the previous version).
  renderChangeCard(summary);

  // Data safety (breaches + reported track record).
  renderDataSafety(currentDataSafety);

  // How to protect your data here (tips + recent videos).
  renderProtect(summary, currentProtection);

  // Footer.
  const gc = summary.genuineCheck;
  $('genuineNote').textContent = gc
    ? `Authenticity: ${gc.confidence}%`
    : '';
  if (meta?.url) $('fullPolicyLink').href = meta.url;
}

/** Render the Protect Your Data card: actionable tips + recent short videos. */
function renderProtect(summary, protection) {
  const card = $('protectCard');
  const tips = (summary && summary.protectionTips) || [];
  const hasDomain = Boolean(protection?.domain);
  if (tips.length === 0 && !hasDomain) {
    card.classList.add('hidden');
    return;
  }
  card.classList.remove('hidden');

  // Tips.
  const tipsEl = $('protectTips');
  tipsEl.innerHTML = '';
  if (tips.length === 0) {
    tipsEl.innerHTML = '<li class="ds-none">No specific tips found for this policy.</li>';
  } else {
    for (const tip of tips) {
      const li = document.createElement('li');
      li.textContent = tip;
      tipsEl.appendChild(li);
    }
  }

  // Videos.
  const videoList = $('videoList');
  const ytLink = $('ytSearchLink');
  videoList.innerHTML = '';
  const videos = protection?.videos || [];
  $('videoSrc').textContent = protection?.sinceYear
    ? `· YouTube · since ${protection.sinceYear}`
    : '· YouTube · recent';

  if (videos.length > 0) {
    for (const v of videos) {
      const a = document.createElement('a');
      a.className = 'video';
      a.href = v.url;
      a.target = '_blank';
      a.rel = 'noopener';
      const date = v.published ? new Date(v.published).toLocaleDateString() : '';
      a.innerHTML =
        (v.thumb ? `<img src="${escapeHtml(v.thumb)}" alt="" class="video-thumb" />` : '') +
        `<span class="video-meta"><span class="video-title">${escapeHtml(v.title)}</span>` +
        `<span class="video-sub">${escapeHtml(v.channel)}${date ? ` · ${date}` : ''}</span></span>`;
      videoList.appendChild(a);
    }
  } else if (!protection?.hasYoutubeKey) {
    videoList.innerHTML =
      '<p class="ds-none">Add a YouTube Data API key in Settings to list recent videos here, or use the link below.</p>';
  } else {
    videoList.innerHTML = '<p class="ds-none">No recent short videos found.</p>';
  }

  if (protection?.searchUrl) ytLink.href = protection.searchUrl;
}

/** Render the Data Safety card: HIBP breaches + AI-reported track record. */
function renderDataSafety(ds) {
  const card = $('dataSafetyCard');
  if (!ds || !ds.domain) {
    card.classList.add('hidden');
    return;
  }
  card.classList.remove('hidden');

  // Breaches (factual).
  const breachList = $('breachList');
  breachList.innerHTML = '';
  if (!ds.breaches || ds.breaches.length === 0) {
    breachList.innerHTML = '<li class="ds-none">No known breaches in the Have I Been Pwned database.</li>';
  } else {
    for (const b of ds.breaches) {
      const li = document.createElement('li');
      const year = (b.date || '').slice(0, 4);
      const count = b.count ? `${formatCount(b.count)} accounts` : 'unknown size';
      const classes = (b.dataClasses || []).slice(0, 4).join(', ');
      const ref = `https://haveibeenpwned.com/PwnedWebsites#${encodeURIComponent(b.name || '')}`;
      li.innerHTML =
        `<span class="ds-year">${year}</span> · ${escapeHtml(b.title || b.name)} — ${count}` +
        (classes ? `<br><span class="ds-classes">Exposed: ${escapeHtml(classes)}</span>` : '') +
        `<br><a class="ds-link" href="${ref}" target="_blank" rel="noopener">Details on HIBP →</a>`;
      breachList.appendChild(li);
    }
  }

  // Reported actions (AI, unverified).
  const reportedList = $('reportedList');
  const checkBtn = $('checkRepBtn');
  const disclaimer = $('repDisclaimer');
  reportedList.innerHTML = '';

  if (!ds.reportedAvailable) {
    // Not generated yet (e.g. hive/cache hit) — offer a manual check.
    checkBtn.classList.remove('hidden');
    disclaimer.classList.add('hidden');
    reportedList.innerHTML = '<li class="ds-none">Not checked yet.</li>';
    return;
  }

  checkBtn.classList.add('hidden');
  if (ds.reportedActions.length === 0) {
    reportedList.innerHTML = '<li class="ds-none">No widely-reported fines or controversies found.</li>';
    disclaimer.classList.add('hidden');
    return;
  }
  for (const a of ds.reportedActions) {
    const li = document.createElement('li');
    li.innerHTML =
      `<span class="ds-year">${escapeHtml(a.year)}</span> · ${escapeHtml(a.type)} — ${escapeHtml(a.summary)}` +
      `<br><a class="ds-link" href="${newsSearchUrl(ds.domain, a)}" target="_blank" rel="noopener">Verify in the news →</a>`;
    reportedList.appendChild(li);
  }
  disclaimer.classList.remove('hidden');
}

/**
 * A Google News search for a reported action. We deliberately build a SEARCH
 * link rather than trusting an LLM-supplied URL (those are routinely
 * hallucinated) — this sends the user to real, current news to verify the claim.
 */
function newsSearchUrl(domain, action) {
  const company = (domain || '').replace(/\.[a-z.]+$/i, '');
  const terms = [company, action.year, action.type, action.summary]
    .filter(Boolean)
    .join(' ')
    .split(/\s+/)
    .slice(0, 12)
    .join(' ');
  return `https://www.google.com/search?tbm=nws&q=${encodeURIComponent(terms)}`;
}

function formatCount(n) {
  if (n >= 1e9) return `${(n / 1e9).toFixed(1)}B`;
  if (n >= 1e6) return `${(n / 1e6).toFixed(1)}M`;
  if (n >= 1e3) return `${(n / 1e3).toFixed(0)}K`;
  return String(n);
}

function escapeHtml(str) {
  return String(str ?? '').replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c],
  );
}

/** Render the richer Data Collected list: bold label + explanation per item. */
function fillDataCollected(items) {
  const ul = $('dataCollected');
  ul.innerHTML = '';
  const list = Array.isArray(items) ? items : [];
  if (list.length === 0) {
    ul.innerHTML = '<li style="color:var(--muted)">Nothing notable found.</li>';
    return;
  }
  for (const it of list) {
    const item = typeof it === 'string' ? it : it.item;
    const detail = typeof it === 'string' ? '' : it.detail;
    const li = document.createElement('li');
    li.innerHTML = `<strong>${escapeHtml(item)}</strong>${detail ? ` — ${escapeHtml(detail)}` : ''}`;
    ul.appendChild(li);
  }
}

/** Show a content-relevant example under a section, or hide if none. */
function fillExample(section, text) {
  const el = $(`example-${section}`);
  if (!el) return;
  if (text && text.trim()) {
    el.innerHTML = `<span class="ex-label">For example:</span> ${escapeHtml(text)}`;
    el.classList.remove('hidden');
  } else {
    el.classList.add('hidden');
  }
}

/** Render the "What changed" card from the diff fields on the summary. */
function renderChangeCard(summary) {
  const card = $('changeCard');
  if (!summary.whatChanged) {
    card.classList.add('hidden');
    return;
  }
  card.classList.remove('hidden');
  const sev = summary.changesSeverity || SEVERITY.LOW;
  $('sevBadge').className = `sev-badge sev-${sev}`;
  $('sevLabel').textContent = `· ${sev} severity`;
  $('whatChanged').textContent = summary.whatChanged;

  const list = $('changeList');
  list.innerHTML = '';
  for (const change of summary.changeList || []) {
    const li = document.createElement('li');
    li.textContent = change;
    list.appendChild(li);
  }

  if (summary.previousSeenAt) {
    $('changeMeta').textContent = `Compared with the version seen ${new Date(summary.previousSeenAt).toLocaleDateString()}.`;
  } else {
    $('changeMeta').textContent = '';
  }
}

function fillList(id, items) {
  const ul = $(id);
  ul.innerHTML = '';
  const list = Array.isArray(items) ? items : [];
  if (list.length === 0) {
    const li = document.createElement('li');
    li.textContent = 'Nothing notable found.';
    li.style.color = 'var(--muted)';
    ul.appendChild(li);
    return;
  }
  for (const item of list) {
    const li = document.createElement('li');
    li.textContent = item;
    ul.appendChild(li);
  }
}

function humanizeError(message = '') {
  if (message.startsWith('NO_PROVIDER')) return 'Add an LLM API key in Settings to summarize new documents.';
  if (message.startsWith('INVALID_API_KEY')) return 'Your API key was rejected. Check it in Settings.';
  if (message.startsWith('RATE_LIMITED')) return 'Provider rate limit hit. Wait a moment and retry.';
  if (message.startsWith('TIMEOUT')) return 'The request timed out. Try again.';
  return message || 'Something went wrong.';
}

/* ------------------------------ tabs ----------------------------- */

/** Fill each card's help text from the glossary and toggle it on the ⓘ button. */
function wireHelp() {
  for (const el of document.querySelectorAll('[data-help-text]')) {
    el.textContent = GLOSSARY[el.dataset.helpText] || '';
  }
  for (const btn of document.querySelectorAll('.info-btn')) {
    btn.addEventListener('click', () => {
      const help = document.querySelector(`[data-help-text="${btn.dataset.help}"]`);
      if (help) help.classList.toggle('hidden');
    });
  }
}

function wireTabs() {
  document.querySelectorAll('.tab').forEach((btn) => {
    btn.addEventListener('click', () => switchTab(btn.dataset.tab));
  });
}

async function switchTab(name) {
  document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b.dataset.tab === name));
  $('tab-summary').classList.toggle('hidden', name !== 'summary');
  $('tab-history').classList.toggle('hidden', name !== 'history');
  if (name === 'history') await loadHistory();
}

async function loadHistory() {
  const listEl = $('historyList');
  listEl.innerHTML = '';
  // History is persisted in IndexedDB, so it's available from the tab's domain
  // even without a fresh scan in this session.
  const domain = currentMeta?.domain || domainFromActiveTab();
  if (!domain) {
    $('historyEmpty').classList.remove('hidden');
    return;
  }
  const res = await chrome.runtime.sendMessage({
    type: MSG.GET_HISTORY,
    domain,
    policyType: currentMeta?.policyType, // omitted → both types
  });
  const versions = res?.versions || [];
  $('historyEmpty').classList.toggle('hidden', versions.length > 0);
  for (const v of versions) {
    const li = document.createElement('li');
    const date = new Date(v.ts).toLocaleString();
    li.innerHTML = `<div class="h-date">${date}</div>`;
    const p = document.createElement('p');
    p.className = 'h-tldr';
    p.textContent = v.summary?.tldr || '(summary unavailable)';
    li.appendChild(p);
    li.addEventListener('click', () => {
      currentSummary = v.summary;
      renderSummary(v.summary, { url: v.url });
      switchTab('summary');
    });
    listEl.appendChild(li);
  }
}

/** Best-effort domain for the active tab (for History without a fresh scan). */
function domainFromActiveTab() {
  if (!activeTabUrl || !/^https?:/.test(activeTabUrl)) return null;
  try {
    return normalizeDomain(new URL(activeTabUrl).hostname);
  } catch {
    return null;
  }
}

/* ------------------------------ audio ---------------------------- */

function wireButtons() {
  $('settingsBtn').addEventListener('click', () => chrome.runtime.openOptionsPage());
  $('rescanBtn').addEventListener('click', rescan);
  $('retryBtn').addEventListener('click', rescan);
  $('autoReadBtn').addEventListener('click', startCrawl);
  $('checkRepBtn').addEventListener('click', checkReputation);
  $('playBtn').addEventListener('click', playAudio);
  $('pauseBtn').addEventListener('click', pauseAudio);
  $('stopBtn').addEventListener('click', stopAudio);
  window.addEventListener('unload', stopAudio);
}

/**
 * Auto-crawl: inject the crawler into the page to open + read every collapsible
 * section, then summarize the merged result (for "one section at a time" UIs).
 */
async function startCrawl() {
  if (activeTabId == null) return;
  const btn = $('autoReadBtn');
  btn.disabled = true;
  const progress = $('crawlProgress');
  progress.classList.remove('hidden');
  progress.textContent = 'Opening sections…';
  showLoading();
  $('loadingMsg').textContent = 'Opening and reading each section…';
  try {
    await chrome.scripting.executeScript({
      target: { tabId: activeTabId },
      files: ['content/crawler.js'],
    });
  } catch (error) {
    log.warn('crawler injection failed:', error.message);
    btn.disabled = false;
    showError('Could not auto-read this page (the page may block injected scripts).');
  }
}

async function checkReputation() {
  if (!currentDataSafety?.domain) return;
  const btn = $('checkRepBtn');
  btn.disabled = true;
  btn.textContent = 'Checking…';
  const res = await chrome.runtime.sendMessage({
    type: MSG.CHECK_REPUTATION,
    domain: currentDataSafety.domain,
    tabId: activeTabId,
  });
  btn.disabled = false;
  btn.textContent = 'Check reported violations';
  if (!res?.ok) {
    $('reportedList').innerHTML = `<li class="ds-none">${escapeHtml(humanizeError(res?.error || ''))}</li>`;
    return;
  }
  currentDataSafety.reportedActions = res.reportedActions || [];
  currentDataSafety.reportedAvailable = true;
  renderDataSafety(currentDataSafety);
}

function onCrawlProgress(msg) {
  const progress = $('crawlProgress');
  progress.classList.remove('hidden');
  if (msg.error) {
    $('autoReadBtn').disabled = false;
    showError(msg.error);
    return;
  }
  if (msg.summarizing) {
    const detail =
      msg.sections != null
        ? `${msg.sections} sections${msg.empties ? `, ${msg.empties} with no text` : ''}`
        : `${msg.processed ?? 0} sections`;
    progress.textContent = `Captured ${msg.lines} lines from ${detail} — summarizing…`;
    $('loadingMsg').textContent = 'Summarizing the assembled policy…';
    return;
  }
  const where = msg.label ? `“${msg.label}”` : `${msg.processed ?? 0} sections`;
  progress.textContent = `Reading ${where} — ${msg.lines} lines captured…`;
  if (msg.done) $('autoReadBtn').disabled = false;
}

async function rescan() {
  if (activeTabId == null) return;
  showLoading();
  // Re-inject the detector to force a fresh scan of the current page.
  try {
    await chrome.scripting.executeScript({
      target: { tabId: activeTabId },
      files: ['content/detector.js'],
    });
  } catch (error) {
    log.warn('rescan injection failed:', error.message);
    setTimeout(loadState, 500);
  }
}

async function playAudio() {
  if (!currentSummary) return;
  const prefs = await getPrefs();
  const script = buildScript(currentSummary);
  toggleAudioButtons(true);

  if (prefs.ttsEngine === 'openai') {
    const cfg = await getLLMConfig('openai');
    if (cfg?.apiKey) {
      try {
        const url = await synthesizeOpenAI({ text: script, apiKey: cfg.apiKey, rate: rate() });
        audioEl = new Audio(url);
        audioEl.onended = () => toggleAudioButtons(false);
        await audioEl.play();
        return;
      } catch (error) {
        log.warn('OpenAI TTS failed, falling back to browser voice:', error.message);
      }
    }
  }
  speakBrowser({ text: script, rate: rate(), voiceName: prefs.ttsVoice, onend: () => toggleAudioButtons(false) });
}

function pauseAudio() {
  if (audioEl) audioEl.paused ? audioEl.play() : audioEl.pause();
  else pauseBrowserToggle();
}

let browserPaused = false;
function pauseBrowserToggle() {
  if (browserPaused) resumeBrowser();
  else pauseBrowser();
  browserPaused = !browserPaused;
}

function stopAudio() {
  stopBrowser();
  if (audioEl) {
    audioEl.pause();
    audioEl = null;
  }
  toggleAudioButtons(false);
}

function toggleAudioButtons(playing) {
  $('playBtn').classList.toggle('hidden', playing);
  $('pauseBtn').classList.toggle('hidden', !playing);
  $('stopBtn').classList.toggle('hidden', !playing);
}

function rate() {
  return parseFloat($('rateSelect').value) || 1;
}
