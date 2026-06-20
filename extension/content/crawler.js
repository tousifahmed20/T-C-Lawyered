/**
 * Auto-crawl (injected on demand by the popup, never auto-run).
 *
 * Handles "one section at a time" policy UIs (e.g. Instagram's privacy centre)
 * where opening a section lazy-loads its text into a popup and closing it
 * destroys that text. Strategy: click each section open, capture its text the
 * instant it appears (before the next click closes it), de-duplicate at the
 * line level, then hand the merged document to the service worker as one policy.
 *
 * Defensive by design — this drives the user's live page, so:
 *   - it is capped (max toggles + hard time wall),
 *   - it captures-as-it-goes (a mid-run break still keeps everything gathered),
 *   - it re-entry-guards so double-clicking the button can't run two crawls.
 */
import { MSG, POLICY_TYPES, CRAWL, UI_MARKER_ATTR } from '../utils/CONSTANTS.js';
import { createLogger } from '../utils/logger.js';
import { extractPolicyText, wordCount, findOpenModal } from './extractor.js';

const log = createLogger('crawler');

/**
 * False once the extension has been reloaded/disabled while this page stayed
 * open — at which point `chrome.runtime` is torn down and any messaging call
 * would throw "Cannot read properties of undefined (reading 'sendMessage')".
 */
function extensionAlive() {
  return Boolean(chrome?.runtime?.id);
}

/** Elements that plausibly toggle a collapsible/section open. */
const TOGGLE_SELECTOR = [
  'summary',
  '[aria-expanded]',
  '[role="button"][aria-controls]',
  '[aria-controls]',
  '[role="tab"]',
  '[class*="accordion" i] button',
  '[class*="accordion" i] [role="button"]',
  '[class*="accordion" i] a',
  '[class*="expand" i] [role="button"]',
  '[data-testid*="accordion" i]',
].join(',');

const delay = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/* ----------------------- on-page progress overlay ---------------- */
/*
 * A branded banner injected into the page so the user sees that the extension
 * is actively working — the sections visibly opening is reassuring, this makes
 * it intentional rather than looking like the page is glitching. It also lives
 * on the page itself, so it survives even if the extension popup loses focus
 * and closes. Marked with UI_MARKER_ATTR so the extractor never reads it.
 */
let overlayEl = null;

function buildOverlay() {
  const host = document.createElement('div');
  host.setAttribute(UI_MARKER_ATTR, '');
  host.style.cssText = [
    'position:fixed', 'top:16px', 'left:50%', 'transform:translateX(-50%)',
    'z-index:2147483647', 'pointer-events:none',
    'background:#1a1d24', 'color:#e8eaed', 'border:1px solid #2c3140',
    'border-radius:12px', 'padding:12px 16px', 'min-width:300px', 'max-width:360px',
    'box-shadow:0 8px 30px rgba(0,0,0,.45)', 'opacity:0', 'transition:opacity .2s',
    'font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif',
  ].join(';');
  host.innerHTML =
    '<div style="display:flex;align-items:center;gap:8px;font-size:13px;font-weight:600;">' +
    '<span style="color:#6c8cff;font-size:16px;line-height:1;">§</span>' +
    '<span>T&amp;C Lawyered is reading this policy…</span></div>' +
    '<div data-msg style="font-size:12px;color:#9aa0aa;margin:6px 0 8px;">Starting…</div>' +
    '<div style="height:6px;background:#232733;border-radius:6px;overflow:hidden;">' +
    '<div data-bar style="height:100%;width:0;background:#6c8cff;transition:width .25s;"></div></div>';
  (document.body || document.documentElement).appendChild(host);
  requestAnimationFrame(() => {
    host.style.opacity = '1';
  });
  return host;
}

function showOverlay(msg, pct) {
  if (!overlayEl) overlayEl = buildOverlay();
  const msgEl = overlayEl.querySelector('[data-msg]');
  const barEl = overlayEl.querySelector('[data-bar]');
  if (msgEl) msgEl.textContent = msg;
  if (barEl && typeof pct === 'number') barEl.style.width = `${Math.max(0, Math.min(100, pct))}%`;
}

function removeOverlay(afterMs = 0) {
  if (!overlayEl) return;
  const el = overlayEl;
  overlayEl = null;
  setTimeout(() => {
    el.style.opacity = '0';
    setTimeout(() => el.remove(), 250);
  }, afterMs);
}

/* ------------------------- line-level merge ---------------------- */

const seenLines = new Set();
const collected = [];

/** Add only previously-unseen lines, so repeated chrome/boilerplate collapses. */
function ingest(text) {
  let added = 0;
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    const norm = line.toLowerCase();
    if (norm.length < 3) continue;
    if (seenLines.has(norm)) continue;
    seenLines.add(norm);
    collected.push(line);
    added += 1;
  }
  return added;
}

/* ----------------------------- helpers --------------------------- */

function isClickable(el) {
  if (!el || !el.isConnected || el.disabled) return false;
  const style = window.getComputedStyle(el);
  if (style.display === 'none' || style.visibility === 'hidden') return false;
  const rect = el.getBoundingClientRect();
  return rect.width > 0 || rect.height > 0;
}

/** Wait until the DOM stops changing (lazy content loaded) or a hard cap hits. */
function waitForSettle() {
  return new Promise((resolve) => {
    let quietTimer = null;
    const finish = () => {
      clearTimeout(quietTimer);
      clearTimeout(hardCap);
      obs.disconnect();
      resolve();
    };
    const obs = new MutationObserver(() => {
      clearTimeout(quietTimer);
      quietTimer = setTimeout(finish, CRAWL.SETTLE_QUIET_MS);
    });
    obs.observe(document.documentElement, { childList: true, subtree: true, characterData: true });
    quietTimer = setTimeout(finish, CRAWL.SETTLE_QUIET_MS);
    const hardCap = setTimeout(finish, CRAWL.SETTLE_MAX_MS);
  });
}

/** Try to close an open popup so the next section can be opened. */
function closeOpenModal() {
  const modal = findOpenModal();
  if (!modal) return;
  const closeBtn = [...modal.querySelectorAll('button, [role="button"], a')].find((b) => {
    const label = (b.getAttribute('aria-label') || b.textContent || '').trim().toLowerCase();
    return label === 'x' || /\b(close|dismiss|back)\b/.test(label) || /[×✕✖]/.test(label);
  });
  if (closeBtn) {
    try {
      closeBtn.click();
      return;
    } catch {
      /* fall through to Escape */
    }
  }
  document.dispatchEvent(
    new KeyboardEvent('keydown', { key: 'Escape', keyCode: 27, bubbles: true }),
  );
}

function inferPolicyType() {
  const s = `${window.location.href} ${document.title}`.toLowerCase();
  if (/terms|tos|eula|conditions|user agreement/.test(s)) return POLICY_TYPES.TERMS;
  return POLICY_TYPES.PRIVACY;
}

function reportProgress(extra) {
  if (!extensionAlive()) return;
  try {
    chrome.runtime
      .sendMessage({ type: MSG.CRAWL_PROGRESS, lines: collected.length, ...extra })
      .catch(() => {});
  } catch {
    /* context invalidated mid-crawl — nothing left to report to */
  }
}

/** Short human label for a toggle, for the diagnostic report. */
function labelOf(el) {
  const t = (el.getAttribute('aria-label') || el.textContent || '').replace(/\s+/g, ' ').trim();
  return t.slice(0, 70) || '(no label)';
}

/**
 * Stable-ish identity for a toggle. React re-renders swap DOM nodes, so element
 * identity alone isn't enough — we also key on label + aria-controls to avoid
 * re-clicking the same section forever.
 */
function keyOf(el) {
  return `${labelOf(el).toLowerCase()}|${el.getAttribute('aria-controls') || ''}`;
}

function isCandidate(el, seenEls, seenKeys) {
  return (
    isClickable(el) &&
    !el.closest(`[${UI_MARKER_ATTR}]`) &&
    !seenEls.has(el) &&
    !seenKeys.has(keyOf(el))
  );
}

/** Next unprocessed toggle — prefer ones inside an open modal (exhaust nested). */
function nextToggle(seenEls, seenKeys) {
  const all = [...document.querySelectorAll(TOGGLE_SELECTOR)].filter((el) =>
    isCandidate(el, seenEls, seenKeys),
  );
  if (all.length === 0) return null;
  const modal = findOpenModal();
  if (modal) {
    const inside = all.find((el) => modal.contains(el));
    if (inside) return inside;
  }
  return all[0];
}

/** Does an open modal still contain nested sections we haven't opened? */
function hasUnprocessedInside(modal, seenEls, seenKeys) {
  return [...modal.querySelectorAll(TOGGLE_SELECTOR)].some((el) =>
    isCandidate(el, seenEls, seenKeys),
  );
}

/**
 * Click without letting an <a href> navigate away — a real navigation would
 * unload the page and kill this injected script. We suppress default navigation
 * for this synthetic click while still letting the app's own click handlers run
 * (so SPA in-place expanders still fire).
 */
function clickNoNav(el) {
  const stop = (e) => e.preventDefault();
  document.addEventListener('click', stop, true);
  try {
    el.scrollIntoView({ block: 'center' });
    el.click();
  } finally {
    setTimeout(() => document.removeEventListener('click', stop, true), 0);
  }
}

/* ------------------------------ crawl ---------------------------- */

async function autoCrawl() {
  const startedAt = Date.now();
  showOverlay('Scanning the page for sections…', 2);

  // Capture whatever is already on screen first.
  ingest(extractPolicyText());

  // Worklist crawl: re-scan after every click so nested sections revealed by a
  // parent (and one-at-a-time siblings) are all discovered, not just the
  // toggles that existed at the start.
  const seenEls = new Set();
  const seenKeys = new Set();
  const report = []; // per-section diagnostics — the verification record
  let opened = 0;

  while (opened < CRAWL.MAX_TOGGLES && Date.now() - startedAt < CRAWL.MAX_TOTAL_MS) {
    const toggle = nextToggle(seenEls, seenKeys);
    if (!toggle) break;
    seenEls.add(toggle);
    seenKeys.add(keyOf(toggle));
    opened += 1;

    const label = labelOf(toggle);
    const urlBefore = window.location.href;
    const before = collected.length;

    try {
      clickNoNav(toggle);
    } catch {
      report.push({ n: opened, label, added: 0, note: 'click failed' });
      continue;
    }

    await waitForSettle();

    // Prefer the freshly opened popup; fall back to whatever became visible.
    const modal = findOpenModal();
    ingest(modal ? extractPolicyText(modal) : extractPolicyText());
    const added = collected.length - before;
    const urlChanged = window.location.href !== urlBefore;
    report.push({ n: opened, label, added, modal: Boolean(modal), urlChanged });
    log.debug(
      `#${opened} "${label}" → +${added} lines` +
        `${modal ? ' [modal]' : ''}${urlChanged ? ' [URL CHANGED]' : ''}`,
    );

    showOverlay(`Reading: ${label} (${opened} opened, ${collected.length} lines)`, Math.min(92, 6 + opened * 5));
    reportProgress({ processed: opened, lines: collected.length, label });

    await delay(CRAWL.HOLD_OPEN_MS);

    // Close the modal only once nothing nested remains unopened inside it, so a
    // section's own sub-sections get read before we move to the next sibling.
    if (modal && !hasUnprocessedInside(modal, seenEls, seenKeys)) {
      closeOpenModal();
      await delay(150);
    }
  }

  const merged = collected.join('\n');

  // ---- Verification report: did each opened section actually yield text? ----
  const empties = report.filter((r) => r.added === 0);
  const navigated = report.filter((r) => r.urlChanged);
  log.debug('===== PER-SECTION CAPTURE REPORT =====');
  for (const r of report) {
    log.debug(
      `#${r.n}  +${r.added} lines  ${r.modal ? 'modal' : 'inline'}` +
        `${r.urlChanged ? '  [URL CHANGED]' : ''}${r.note ? `  [${r.note}]` : ''}  |  ${r.label}`,
    );
  }
  log.debug(
    `Opened ${report.length} sections — ${report.length - empties.length} yielded text, ` +
      `${empties.length} yielded NOTHING, ${navigated.length} changed the URL.`,
  );
  if (empties.length) {
    log.debug(
      'Sections that yielded NO new text (suspect — content may not have been read): ' +
        empties.map((r) => `#${r.n} "${r.label}"`).join('  |  '),
    );
  }
  log.debug(`Total: ${collected.length} unique lines, ${wordCount(merged)} words`);
  log.debug('===== CAPTURED TEXT BEGIN =====\n' + merged + '\n===== CAPTURED TEXT END =====');

  if (wordCount(merged) < CRAWL.MIN_FINAL_WORDS) {
    showOverlay('No expandable policy sections found here.', 100);
    removeOverlay(2200);
    reportProgress({ done: true, error: 'No expandable policy sections found on this page.' });
    return;
  }

  showOverlay(`Read ${report.length} sections — summarizing in the popup…`, 100);
  removeOverlay(2800);
  reportProgress({
    done: true,
    summarizing: true,
    sections: report.length,
    empties: empties.length,
  });
  if (!extensionAlive()) return;
  try {
    chrome.runtime
      .sendMessage({
        type: MSG.POLICY_DETECTED,
        payload: {
          url: window.location.href,
          hostname: window.location.hostname,
          policyType: inferPolicyType(),
          text: merged,
          force: true, // bypass cache; this is a freshly assembled document
        },
      })
      .catch(() => {});
  } catch {
    /* context invalidated — drop the freshly assembled result silently */
  }
}

// Re-entry guard: never run two crawls in the same page at once.
if (!window.__tcLawyeredCrawling) {
  window.__tcLawyeredCrawling = true;
  autoCrawl()
    .catch((error) => {
      log.error('crawl failed:', error);
      showOverlay('Auto-read stopped unexpectedly.', 100);
      reportProgress({ done: true, error: 'Auto-read stopped unexpectedly.' });
    })
    .finally(() => {
      removeOverlay(2000);
      window.__tcLawyeredCrawling = false;
    });
}
