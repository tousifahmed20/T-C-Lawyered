/**
 * Background service worker — the orchestrator (MV3).
 *
 * Owns the full pipeline for a detected policy:
 *   detect → hash → hive lookup → (miss) validate → summarize → diff → store →
 *   upload (gated) → notify popup + set badge.
 *
 * Design rules honoured here:
 *  - Offline-first: hive failures degrade to local-only, never block.
 *  - The SW has no DOM. All text comes from the content script via messages.
 *  - Per-tab in-flight results are cached so the popup can pull them on open.
 */
import { MSG, POLICY_TYPES, SEVERITY, SEVERITY_COLORS, RECHECK_TTL_MS } from '../utils/CONSTANTS.js';
import { createLogger } from '../utils/logger.js';
import { normalizeDomain } from '../utils/domain.js';
import { getActiveLLMConfig, getLLMConfig, getPrefs, getYoutubeKey } from '../utils/config.js';
import { computeHash } from './hasher.js';
import { lookupPolicy, uploadPolicy } from './hive.js';
import { validateAuthenticity, passesUploadGate } from './validator.js';
import { summarizeDocument } from './chunker.js';
import { diffVersions } from './differ.js';
import { getBreachesForDomain } from './breaches.js';
import { getCachedReportedActions, generateReportedActions } from './reputation.js';
import { getProtectionVideos, buildYoutubeSearchUrl } from './videos.js';
import {
  putSite,
  getSite,
  putSnapshot,
  getSnapshot,
  getSnapshotsForDomain,
  putDiff,
  pruneOldSnapshots,
} from '../storage/db.js';

const log = createLogger('sw');

/**
 * Per-tab pipeline state, so the popup can render whatever is current without
 * re-triggering work. Keyed by tabId.
 * @type {Map<number, { status: string, summary?: object, error?: string, meta?: object }>}
 */
const tabState = new Map();

/* --------------------------- lifecycle --------------------------- */

chrome.runtime.onInstalled.addListener(() => {
  log.info('installed');
  chrome.alarms.create('prune', { periodInMinutes: 60 * 24 });
});

chrome.alarms.onAlarm.addListener((alarm) => {
  if (alarm.name === 'prune') pruneOldSnapshots();
});

chrome.tabs.onRemoved.addListener((tabId) => tabState.delete(tabId));

/* --------------------------- messaging --------------------------- */

chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  handleMessage(message, sender)
    .then(sendResponse)
    .catch((error) => {
      log.error('message handler error:', error);
      sendResponse({ ok: false, error: error.message });
    });
  return true; // async response
});

async function handleMessage(message, sender) {
  switch (message?.type) {
    case MSG.POLICY_DETECTED:
      return onPolicyDetected(message.payload, sender);
    case MSG.GET_SUMMARY:
      return getSummaryForTab(message.tabId ?? sender.tab?.id);
    case MSG.GET_HISTORY:
      return { ok: true, versions: await getHistory(message.domain, message.policyType) };
    case MSG.REPROCESS:
      return onPolicyDetected({ ...message.payload, force: true }, sender);
    case MSG.TEST_PROVIDER:
      return testProvider(message.provider);
    case MSG.CHECK_REPUTATION:
      return checkReputation(message.domain, message.tabId ?? sender.tab?.id);
    default:
      return { ok: false, error: `Unknown message type: ${message?.type}` };
  }
}

/* ----------------------------- pipeline -------------------------- */

/**
 * Entry point when the content script reports a detected policy.
 * @param {object} payload - { url, hostname, policyType, text }
 * @param {chrome.runtime.MessageSender} sender
 */
async function onPolicyDetected(payload, sender) {
  const tabId = sender.tab?.id;
  const { url, hostname, policyType, text } = payload;
  if (!text || text.trim().length === 0) {
    return { ok: false, error: 'EMPTY_TEXT: No document text extracted.' };
  }

  const prefs = await getPrefs();
  if (!prefs.autoSummarize && !payload.force) {
    return { ok: true, status: 'idle' };
  }

  const domain = normalizeDomain(hostname);
  const hash = await computeHash(text);
  const meta = { domain, policyType, hash, url };
  setTab(tabId, { status: 'working', meta });

  try {
    // Staleness: a cached summary older than the re-check window is refreshed on
    // this visit even if the policy text is unchanged (F-06 currency).
    const existing = await getSnapshot(hash);
    const lastChecked = existing?.lastCheckedAt ?? existing?.ts ?? 0;
    const stale = Boolean(existing?.summary) && Date.now() - lastChecked > RECHECK_TTL_MS;
    const refresh = Boolean(payload.force) || stale;

    // 1) Already have it locally and still fresh? Render instantly.
    if (existing?.summary && !refresh) {
      log.debug('local cache hit');
      meta.scannedAt = lastChecked;
      await finalize(tabId, { domain, policyType, hash, summary: existing.summary, meta });
      return { ok: true, status: 'ready', cached: 'local' };
    }
    if (stale) log.debug('cached summary older than the re-check window — refreshing');

    // 2) Hive lookup (non-blocking, degrades to miss). Skipped on a refresh —
    //    for the same hash the hive only holds the same summary we're refreshing.
    if (prefs.hiveEnabled && !refresh) {
      const hit = await lookupPolicy(hash, domain);
      if (hit.found && hit.summary) {
        log.debug('hive hit');
        await putSnapshot({ hash, domain, policyType, rawText: text, summary: hit.summary });
        meta.scannedAt = Date.now();
        await finalize(tabId, { domain, policyType, hash, summary: hit.summary, meta });
        return { ok: true, status: 'ready', cached: 'hive' };
      }
    }

    // 3) Miss → need the user's LLM key from here on.
    const llmConfig = await getActiveLLMConfig();
    if (!llmConfig) {
      // Graceful degradation: if we only wanted to refresh a stale summary but
      // have no provider to do it, keep showing the cached one rather than error.
      if (existing?.summary) {
        log.debug('no provider to refresh stale summary — serving cached');
        meta.scannedAt = lastChecked;
        await finalize(tabId, { domain, policyType, hash, summary: existing.summary, meta });
        return { ok: true, status: 'ready', cached: 'local-stale' };
      }
      const err = 'NO_PROVIDER: Add an LLM API key in settings to summarize new documents.';
      setTab(tabId, { status: 'error', error: err, meta });
      notify(tabId, { type: MSG.SUMMARY_ERROR, error: err });
      return { ok: false, error: err };
    }

    // 4) Authenticity validation (gates hive upload, not local summarize).
    const genuineCheck = await validateAuthenticity({ url, text, llmConfig });

    // 5) Summarize.
    const { summary } = await summarizeDocument({ text, domain, policyType, llmConfig });

    // 6) Diff against the latest prior version, if any.
    let whatChanged = null;
    let changeList = [];
    let changesSeverity = SEVERITY.NONE;
    const prior = await getSite(domain, policyType);
    if (prior && prior.hash && prior.hash !== hash) {
      const priorSnap = await getSnapshot(prior.hash);
      if (priorSnap?.rawText) {
        const diff = await diffVersions({
          oldText: priorSnap.rawText,
          newText: text,
          domain,
          policyType,
          llmConfig,
        });
        whatChanged = diff.whatChanged;
        changeList = diff.changes;
        changesSeverity = diff.changesSeverity;
        await putDiff({
          hash,
          parentHash: prior.hash,
          diffSummary: whatChanged,
          changes: changeList,
          changesSeverity,
        });
      }
    }

    const fullSummary = {
      ...summary,
      whatChanged,
      changeList,
      changesSeverity: whatChanged ? changesSeverity : null,
      previousSeenAt: prior?.lastSeen || null,
      genuineCheck,
    };

    // 7) Persist locally (always) and update the latest pointer.
    await putSnapshot({
      hash,
      domain,
      policyType,
      rawText: text,
      summary: fullSummary,
      parentHash: prior?.hash || null,
    });
    await putSite({ domain, policyType, hash });

    // 8) We're already paying for an LLM call on this fresh path, so generate
    //    the reported track record now (cached per domain). Never done on hive/
    //    cache hits — that would break the zero-cost guarantee.
    if (!(await getCachedReportedActions(domain))) {
      await generateReportedActions({ domain, llmConfig }).catch(() => {});
    }

    // 9) Render now, then upload to hive in the background (gated).
    meta.scannedAt = Date.now();
    await finalize(tabId, { domain, policyType, hash, summary: fullSummary, meta });

    if (prefs.hiveEnabled && passesUploadGate(genuineCheck)) {
      // Fire-and-forget — never blocks, never throws upward.
      uploadPolicy({
        domain,
        policyType,
        hash,
        parentHash: prior?.hash || null,
        summary: fullSummary,
        submittedAt: new Date().toISOString(),
      }).catch(() => {});
    } else if (prefs.hiveEnabled) {
      log.debug('skipped hive upload — failed authenticity gate');
    }

    return { ok: true, status: 'ready', cached: 'none' };
  } catch (error) {
    log.error('pipeline failed:', error);
    setTab(tabId, { status: 'error', error: error.message, meta });
    notify(tabId, { type: MSG.SUMMARY_ERROR, error: error.message });
    return { ok: false, error: error.message };
  }
}

/**
 * Build the full "ready" tab state (summary + data safety + protection) and
 * cache it. Does NOT notify the popup — callers decide whether to.
 */
async function buildReadyState(tabId, { domain, policyType, hash, summary, meta }) {
  const company = domain.replace(/\.[a-z.]+$/i, '');
  const ytKey = await getYoutubeKey();
  // Anchor video recency to the last policy change, else the last ~2 years.
  const sinceMs = summary?.previousSeenAt || Date.now() - 2 * 365 * 24 * 60 * 60 * 1000;
  const publishedAfter = new Date(sinceMs).toISOString();

  // Breach check is free (no key) so it always runs; reported actions only show
  // if already cached (paid generation happens on the fresh path); videos need
  // the optional YouTube key.
  const [breaches, reportedActions, videos] = await Promise.all([
    getBreachesForDomain(domain),
    getCachedReportedActions(domain),
    getProtectionVideos({ domain, company, publishedAfter, apiKey: ytKey }),
  ]);

  const state = {
    status: 'ready',
    summary,
    dataSafety: {
      domain,
      breaches,
      reportedActions: reportedActions || [],
      reportedAvailable: reportedActions != null,
    },
    protection: {
      domain,
      videos,
      hasYoutubeKey: Boolean(ytKey),
      searchUrl: buildYoutubeSearchUrl(company),
      sinceYear: new Date(sinceMs).getFullYear(),
    },
    meta: { ...meta, domain, policyType, hash },
  };
  setTab(tabId, state);
  return state;
}

/** Common success path: build state, set badge, notify popup. */
async function finalize(tabId, args) {
  await buildReadyState(tabId, args);
  applyBadge(tabId, args.summary);
  notify(tabId, { type: MSG.SUMMARY_READY });
}

/** History for a domain — a specific policy type, or both merged, newest first. */
async function getHistory(domain, policyType) {
  if (!domain) return [];
  let snaps;
  if (policyType) {
    snaps = await getSnapshotsForDomain(domain, policyType);
  } else {
    const [privacy, terms] = await Promise.all([
      getSnapshotsForDomain(domain, POLICY_TYPES.PRIVACY),
      getSnapshotsForDomain(domain, POLICY_TYPES.TERMS),
    ]);
    snaps = [...privacy, ...terms].sort((a, b) => b.ts - a.ts);
  }
  // Drop the full rawText — the popup never needs it and it bloats messages.
  return snaps.map(({ rawText: _rawText, ...rest }) => rest);
}

/** Manual "check reported violations" — used on hive/cache hits (F-04 zero-cost). */
async function checkReputation(domain, tabId) {
  const llmConfig = await getActiveLLMConfig();
  if (!llmConfig) {
    return { ok: false, error: 'NO_PROVIDER: Add an LLM API key in settings to check this.' };
  }
  const reportedActions = await generateReportedActions({ domain, llmConfig });
  const state = tabState.get(tabId);
  if (state?.dataSafety) {
    state.dataSafety.reportedActions = reportedActions;
    state.dataSafety.reportedAvailable = true;
  }
  return { ok: true, reportedActions };
}

/** Set the toolbar badge based on change severity (F-13). */
function applyBadge(tabId, summary) {
  if (typeof tabId !== 'number') return;
  const severity = summary?.changesSeverity;
  if (severity && severity !== SEVERITY.NONE) {
    chrome.action.setBadgeText({ tabId, text: '!' });
    chrome.action.setBadgeBackgroundColor({ tabId, color: SEVERITY_COLORS[severity] || '#888' });
  } else {
    chrome.action.setBadgeText({ tabId, text: '' });
  }
}

/** Push a lightweight event to the popup (if open). Errors ignored — popup may be closed. */
function notify(tabId, message) {
  chrome.runtime.sendMessage({ ...message, tabId }).catch(() => {});
}

/* ----------------------------- helpers --------------------------- */

function setTab(tabId, state) {
  if (typeof tabId === 'number') tabState.set(tabId, { ...tabState.get(tabId), ...state });
}

async function getSummaryForTab(tabId) {
  const state = tabState.get(tabId);
  if (state) return { ok: true, ...state };
  // In-memory state is gone (MV3 killed the worker, or this is a fresh open).
  // Rebuild from IndexedDB using the tab's current domain so results survive
  // tab switches and service-worker restarts.
  const rebuilt = await rebuildFromStorage(tabId);
  return rebuilt ? { ok: true, ...rebuilt } : { ok: true, status: 'none' };
}

/**
 * Reconstruct a tab's "ready" state from the most recent stored snapshot for
 * the tab's current domain. Returns null if nothing is stored for it.
 */
async function rebuildFromStorage(tabId) {
  if (typeof tabId !== 'number') return null;
  try {
    const tab = await chrome.tabs.get(tabId);
    if (!tab?.url || !/^https?:/.test(tab.url)) return null;
    const domain = normalizeDomain(new URL(tab.url).hostname);
    if (!domain) return null;

    const versions = await getHistory(domain); // newest first, both types
    const latest = versions.find((v) => v.summary);
    if (!latest) return null;

    log.debug('rebuilt tab state from storage for', domain);
    return buildReadyState(tabId, {
      domain,
      policyType: latest.policyType,
      hash: latest.hash,
      summary: latest.summary,
      meta: { url: tab.url },
    });
  } catch (error) {
    log.warn('rebuildFromStorage failed:', error.message);
    return null;
  }
}

/** Provider health check used by settings (F-11). Makes a minimal call. */
async function testProvider(provider) {
  const llmConfig = await getLLMConfig(provider);
  if (!llmConfig) return { ok: false, error: 'No API key saved for this provider.' };
  try {
    const { callLLM } = await import('./llm.js');
    await callLLM({
      systemPrompt: 'Reply with the single word: ok',
      userPrompt: 'ping',
      ...llmConfig,
    });
    return { ok: true };
  } catch (error) {
    return { ok: false, error: error.message };
  }
}

// Keep references used only for typing/imports from being tree-shaken in dev.
export { POLICY_TYPES };
