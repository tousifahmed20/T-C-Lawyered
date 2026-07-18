/**
 * Write rate limiter (PHASE2.md §5.3). In-memory rolling 24h counter per IP.
 *
 * v1 tradeoff: a single Railway instance means one process, so an in-memory map
 * is sufficient. It resets on redeploy/restart — acceptable for v1; a DB-backed
 * counter is a Phase 3 upgrade. We store only a coarse timestamp list per IP —
 * no bodies, no persistence, purged as it ages out.
 */
const WINDOW_MS = 24 * 60 * 60 * 1000;

/** ip -> number[] of request timestamps within the window. */
const hits = new Map();

/**
 * @param {string} ip
 * @param {number} limit  max writes per IP per rolling 24h
 * @returns {boolean} true if the request is allowed, false if rate-limited
 */
export function allowWrite(ip, limit) {
  const now = Date.now();
  const cutoff = now - WINDOW_MS;
  const recent = (hits.get(ip) || []).filter((ts) => ts > cutoff);
  if (recent.length >= limit) {
    hits.set(ip, recent);
    return false;
  }
  recent.push(now);
  hits.set(ip, recent);
  return true;
}

/** Drop empty/expired buckets so the map can't grow unbounded. Called on a timer. */
export function pruneRateLimit() {
  const cutoff = Date.now() - WINDOW_MS;
  for (const [ip, list] of hits) {
    const recent = list.filter((ts) => ts > cutoff);
    if (recent.length === 0) hits.delete(ip);
    else hits.set(ip, recent);
  }
}

/** Test-only: clear all counters. */
export function _resetRateLimit() {
  hits.clear();
}
