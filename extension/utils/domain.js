/**
 * Domain normalization (F-02). Strips known non-identifying subdomains so that
 * accounts.google.com, legal.google.com and www.google.com all collapse to the
 * same identity key. Deliberately conservative — keeps the last two labels for
 * common TLDs and three for known multi-part TLDs (co.uk, com.au, …).
 */
import { STRIPPED_SUBDOMAINS } from './CONSTANTS.js';

const MULTI_PART_TLDS = new Set([
  'co.uk', 'org.uk', 'gov.uk', 'ac.uk', 'co.jp', 'com.au', 'co.nz', 'co.in', 'com.br',
]);

/**
 * Normalize a hostname to its root identity domain.
 * @param {string} hostname - e.g. window.location.hostname
 * @returns {string}
 */
export function normalizeDomain(hostname) {
  if (!hostname) return '';
  let host = hostname.toLowerCase().trim();
  if (host.startsWith('.')) host = host.slice(1);

  const labels = host.split('.').filter(Boolean);
  if (labels.length <= 2) return labels.join('.');

  // Drop explicitly stripped leading subdomains first.
  while (labels.length > 2 && STRIPPED_SUBDOMAINS.includes(labels[0])) {
    labels.shift();
  }

  const lastTwo = labels.slice(-2).join('.');
  if (MULTI_PART_TLDS.has(lastTwo) && labels.length >= 3) {
    return labels.slice(-3).join('.');
  }
  return labels.slice(-2).join('.');
}
