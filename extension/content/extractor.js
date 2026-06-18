/**
 * Text extraction + cleaning (F-01). Pulls meaningful policy text from the page
 * while dropping genuine noise. Runs in the content script (has DOM, never makes
 * network calls).
 *
 * Two refinements over naive "visible text only":
 *  - Modal-aware: if a policy popup/dialog is open, we read *from it* instead of
 *    the page behind it (otherwise a click-triggered policy popup gets missed).
 *  - Collapsed-region aware: text inside <details>, tabs, and accordions is part
 *    of the policy even when collapsed, so we include it — but we still skip
 *    truly hidden chrome (nav, footers, cookie banners, off-screen SR-only text).
 */
import { UI_MARKER_ATTR } from '../utils/CONSTANTS.js';

/** Tags whose text is never part of a policy document. */
const SKIP_TAGS = new Set([
  'SCRIPT', 'STYLE', 'NOSCRIPT', 'BUTTON', 'INPUT', 'SELECT', 'TEXTAREA',
  'SVG', 'IMG', 'IFRAME',
]);

/** Structural page chrome — text here is navigation/branding, not policy. */
const CHROME_SELECTOR = 'nav, header, footer, aside';

/** Cookie/consent/marketing chrome we must not misread as the policy. */
const NOISE_SELECTOR = [
  '[class*="cookie" i]', '[class*="consent" i]', '[class*="gdpr" i]',
  '[class*="banner" i]', '[class*="newsletter" i]', '[class*="subscribe" i]',
  '[class*="advert" i]', '[class*="promo" i]',
  '[id*="cookie" i]', '[id*="consent" i]',
].join(',');

/** Collapsible policy regions whose text we keep even while hidden. */
const COLLAPSIBLE_SELECTOR = [
  'details', '[role="tabpanel"]',
  '[class*="accordion" i]', '[class*="collapse" i]', '[class*="disclosure" i]',
  '[class*="expander" i]', '[class*="expand" i]',
].join(',');

/** Things that look like an open modal/dialog holding the policy. */
const MODAL_SELECTOR = [
  '[role="dialog"]', '[aria-modal="true"]', 'dialog[open]',
  '.modal', '.overlay', '[class*="modal" i]',
].join(',');

/** Min words for a popup to count as a real text container (not a tooltip). */
const MIN_MODAL_TEXT_WORDS = 80;

/**
 * Is an element actually rendered on screen right now?
 * @param {Element} el
 * @returns {boolean}
 */
function isRenderedVisible(el) {
  if (!(el instanceof Element)) return false;
  const style = window.getComputedStyle(el);
  if (style.display === 'none' || style.visibility === 'hidden' || style.visibility === 'collapse') {
    return false;
  }
  if (parseFloat(style.opacity) === 0) return false;
  const rect = el.getBoundingClientRect();
  if (rect.width === 0 && rect.height === 0) return false;
  // Off-screen screen-reader / hidden-menu text (e.g. left: -9999px).
  if (rect.right < 0 || rect.bottom < 0) return false;
  return true;
}

/** Is this element inside a collapsible policy region (accordion/tab/details)? */
function inCollapsibleRegion(el) {
  return Boolean(el.closest(COLLAPSIBLE_SELECTOR));
}

/**
 * Find the largest visible policy popup/dialog on the page, if any.
 * @returns {Element|null}
 */
export function findOpenModal() {
  let best = null;
  let bestWords = 0;
  for (const el of document.querySelectorAll(MODAL_SELECTOR)) {
    if (!isRenderedVisible(el)) continue;
    const words = wordCount(el.textContent || '');
    if (words >= MIN_MODAL_TEXT_WORDS && words > bestWords) {
      best = el;
      bestWords = words;
    }
  }
  return best;
}

/** Choose the container to extract from: explicit root → open modal → main → body. */
function pickRoot(explicitRoot) {
  if (explicitRoot instanceof Element) return explicitRoot;
  const modal = findOpenModal();
  if (modal) return modal;
  return (
    document.querySelector('main, article, [role="main"], #content, .policy, .legal') ||
    document.body
  );
}

/**
 * Should a text node's content be included?
 * @param {Text} node
 * @returns {boolean}
 */
function shouldRead(node) {
  const parent = node.parentElement;
  if (!parent) return false;
  if (SKIP_TAGS.has(parent.tagName)) return false;
  if (!node.textContent || !node.textContent.trim()) return false;
  if (parent.closest(`[${UI_MARKER_ATTR}]`)) return false; // our own overlay
  if (parent.closest(CHROME_SELECTOR)) return false;
  if (parent.closest(NOISE_SELECTOR)) return false;
  // Keep visible text, and collapsed accordion/tab/details text (it's policy).
  if (isRenderedVisible(parent)) return true;
  if (inCollapsibleRegion(parent)) return true;
  return false;
}

/**
 * Extract cleaned policy text. Pass an explicit root (e.g. a detected modal) to
 * guarantee extraction reads the same element detection matched.
 * @param {Element} [explicitRoot]
 * @returns {string}
 */
export function extractPolicyText(explicitRoot) {
  const root = pickRoot(explicitRoot);
  if (!root) return '';

  const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT, {
    acceptNode: (node) => (shouldRead(node) ? NodeFilter.FILTER_ACCEPT : NodeFilter.FILTER_REJECT),
  });

  const parts = [];
  let current = walker.nextNode();
  while (current) {
    parts.push(current.textContent.trim());
    current = walker.nextNode();
  }
  return cleanText(parts.join('\n'));
}

/** Collapse whitespace, drop empty lines. */
export function cleanText(text) {
  return text
    .replace(/\r/g, '')
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
    .join('\n')
    .trim();
}

/** Word count of text — used by detection heuristics. */
export function wordCount(text) {
  if (!text) return 0;
  return text.split(/\s+/).filter(Boolean).length;
}
