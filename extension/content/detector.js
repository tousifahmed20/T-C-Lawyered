/**
 * T&C / Privacy Policy detector (F-01) — content script entry point.
 *
 * Watches the DOM via MutationObserver and applies first-match-wins heuristics
 * to decide whether the current page — or a freshly opened modal/popup — is a
 * policy document. On a confident match it extracts the text and hands it to the
 * service worker. It never makes network calls — that's the SW's job.
 *
 * Performance (NFR-03): detection is debounced and the observer is disconnected
 * once a policy is confirmed, keeping per-mutation overhead minimal.
 */
import { MSG, POLICY_TYPES, MIN_POLICY_WORDS, MIN_MODAL_WORDS } from '../utils/CONSTANTS.js';
import { extractPolicyText, wordCount, findOpenModal } from './extractor.js';

const URL_PATTERNS = [
  { re: /privacy|gdpr|data-?policy/i, type: POLICY_TYPES.PRIVACY },
  { re: /terms|tos|eula|conditions|user-?agreement|legal/i, type: POLICY_TYPES.TERMS },
];

const TITLE_PATTERNS = [
  { re: /privacy policy|privacy notice|data policy/i, type: POLICY_TYPES.PRIVACY },
  {
    re: /terms of service|terms of use|terms and conditions|user agreement|terms & conditions/i,
    type: POLICY_TYPES.TERMS,
  },
];

/** Keywords that mark a chunk of text as policy-ish even without a heading. */
const POLICY_KEYWORDS = /privacy|personal data|personal information|we collect|cookies|terms of (service|use)|you agree|liability/i;

let detected = false;
let debounceTimer = null;

/**
 * Run the heuristic ladder. Returns { type, root } on first match, else null.
 * `root` is set only when a specific element (a modal) should be extracted.
 * @returns {{ type: string, root?: Element }|null}
 */
function detectPolicy() {
  // 1) URL path.
  const path = window.location.pathname + window.location.search;
  for (const { re, type } of URL_PATTERNS) {
    if (re.test(path)) return { type };
  }

  // 2) <title>.
  for (const { re, type } of TITLE_PATTERNS) {
    if (re.test(document.title)) return { type };
  }

  // 3) <h1>/<h2> headings.
  for (const h of document.querySelectorAll('h1, h2')) {
    for (const { re, type } of TITLE_PATTERNS) {
      if (re.test(h.textContent || '')) return { type };
    }
  }

  // 4) Large text block adjacent to a consent checkbox + accept button.
  if (hasConsentContext() && wordCount(extractPolicyText()) > MIN_POLICY_WORDS) {
    return { type: inferTypeFromText(document.body.textContent || '') };
  }

  // 5) An open policy popup/dialog. Fires for consent gates (accept button +
  //    long text) AND for informational popups (policy keywords, any close
  //    button) — the latter is the "click to read details" case.
  const modalMatch = detectModalPolicy();
  if (modalMatch) return modalMatch;

  return null;
}

/** Heuristic 4 support: a checkbox + an "I agree/accept" control on the page. */
function hasConsentContext() {
  if (!document.querySelector('input[type="checkbox"]')) return false;
  return Boolean(findAcceptControl());
}

/** Heuristic 5 support: classify an open modal as a policy popup. */
function detectModalPolicy() {
  const modal = findOpenModal();
  if (!modal) return null;
  const text = modal.textContent || '';
  const words = wordCount(text);
  if (words < 150) return null; // too short to be a real policy

  const looksLikePolicy = TITLE_PATTERNS.some((p) => p.re.test(text)) || POLICY_KEYWORDS.test(text);
  const isConsentGate = Boolean(findAcceptControl(modal)) && words >= MIN_MODAL_WORDS;

  if (looksLikePolicy || isConsentGate) {
    return { type: inferTypeFromText(text), root: modal };
  }
  return null;
}

function findAcceptControl(scope = document) {
  const controls = scope.querySelectorAll('button, a, [role="button"], input[type="submit"]');
  for (const c of controls) {
    const label = (c.textContent || c.value || '').trim();
    if (/\b(i agree|i accept|accept|agree|consent)\b/i.test(label)) return c;
  }
  return null;
}

/** Guess policy type from keyword density in a block of text. */
function inferTypeFromText(text) {
  const lower = text.toLowerCase();
  const privacyHits = (lower.match(/personal data|personal information|we collect|cookies/g) || [])
    .length;
  const termsHits = (lower.match(/you agree|liability|warranty|terminate|governing law/g) || [])
    .length;
  return privacyHits >= termsHits ? POLICY_TYPES.PRIVACY : POLICY_TYPES.TERMS;
}

/**
 * Extract and report a confirmed policy to the service worker.
 * @param {{ type: string, root?: Element }} match
 */
function reportDetection(match) {
  // Extract from the matched element (modal) when present so detection and
  // extraction never disagree about which text we're summarizing.
  const text = extractPolicyText(match.root);
  if (wordCount(text) < 150) return; // too short to be a real policy
  detected = true;
  observer.disconnect();

  chrome.runtime
    .sendMessage({
      type: MSG.POLICY_DETECTED,
      payload: {
        url: window.location.href,
        hostname: window.location.hostname,
        policyType: match.type,
        text,
      },
    })
    .catch(() => {
      // SW may be asleep/restarting; a later mutation will retry if needed.
      detected = false;
    });
}

function runDetection() {
  if (detected) return;
  const match = detectPolicy();
  if (match) reportDetection(match);
}

function scheduleDetection() {
  if (detected) return;
  clearTimeout(debounceTimer);
  debounceTimer = setTimeout(runDetection, 400);
}

const observer = new MutationObserver(scheduleDetection);

// Initial pass once the page settles, then watch for dynamically injected
// modals / consent dialogs / expanded accordions.
runDetection();
if (!detected) {
  observer.observe(document.documentElement, { childList: true, subtree: true });
}
