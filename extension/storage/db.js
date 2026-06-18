/**
 * IndexedDB abstraction (F-12) via the `idb` wrapper. Four stores:
 *   sites      keyPath [domain, policyType]      — latest pointer per policy
 *   snapshots  keyPath hash                       — raw text + summary per version
 *   diffs      keyPath [hash, parentHash]         — computed plain-English diffs
 *   settings   keyPath key                        — single-record settings store
 *
 * Every public function is wrapped in try/catch and degrades to a safe default
 * so a storage failure never breaks the pipeline (offline-first).
 */
import { openDB } from 'idb';
import { SNAPSHOT_TTL_MS } from '../utils/CONSTANTS.js';
import { createLogger } from '../utils/logger.js';

const log = createLogger('db');
const DB_NAME = 'tc-lawyered';
const DB_VERSION = 1;

let dbPromise = null;

function getDB() {
  if (!dbPromise) {
    dbPromise = openDB(DB_NAME, DB_VERSION, {
      upgrade(db) {
        if (!db.objectStoreNames.contains('sites')) {
          db.createObjectStore('sites', { keyPath: ['domain', 'policyType'] });
        }
        if (!db.objectStoreNames.contains('snapshots')) {
          const snaps = db.createObjectStore('snapshots', { keyPath: 'hash' });
          snaps.createIndex('byDomainType', ['domain', 'policyType']);
          snaps.createIndex('byTimestamp', 'ts');
        }
        if (!db.objectStoreNames.contains('diffs')) {
          db.createObjectStore('diffs', { keyPath: ['hash', 'parentHash'] });
        }
        if (!db.objectStoreNames.contains('settings')) {
          db.createObjectStore('settings', { keyPath: 'key' });
        }
      },
    });
  }
  return dbPromise;
}

/* ----------------------------- sites ----------------------------- */

/** Upsert the latest-version pointer for a domain+type. */
export async function putSite(site) {
  try {
    const db = await getDB();
    await db.put('sites', { ...site, lastSeen: Date.now() });
  } catch (error) {
    log.error('putSite failed:', error);
  }
}

export async function getSite(domain, policyType) {
  try {
    const db = await getDB();
    return (await db.get('sites', [domain, policyType])) || null;
  } catch (error) {
    log.error('getSite failed:', error);
    return null;
  }
}

/* --------------------------- snapshots --------------------------- */

/** Store a full version snapshot (raw text + summary). */
export async function putSnapshot(snapshot) {
  try {
    const db = await getDB();
    await db.put('snapshots', { ...snapshot, ts: snapshot.ts ?? Date.now() });
  } catch (error) {
    log.error('putSnapshot failed:', error);
  }
}

export async function getSnapshot(hash) {
  try {
    const db = await getDB();
    return (await db.get('snapshots', hash)) || null;
  } catch (error) {
    log.error('getSnapshot failed:', error);
    return null;
  }
}

/** All snapshots for a domain+type, newest first (History tab, F-09). */
export async function getSnapshotsForDomain(domain, policyType) {
  try {
    const db = await getDB();
    const all = await db.getAllFromIndex('snapshots', 'byDomainType', [domain, policyType]);
    return all.sort((a, b) => b.ts - a.ts);
  } catch (error) {
    log.error('getSnapshotsForDomain failed:', error);
    return [];
  }
}

/* ----------------------------- diffs ----------------------------- */

export async function putDiff(diff) {
  try {
    const db = await getDB();
    await db.put('diffs', { ...diff, ts: diff.ts ?? Date.now() });
  } catch (error) {
    log.error('putDiff failed:', error);
  }
}

export async function getDiff(hash, parentHash) {
  try {
    const db = await getDB();
    return (await db.get('diffs', [hash, parentHash])) || null;
  } catch (error) {
    log.error('getDiff failed:', error);
    return null;
  }
}

/* ---------------------------- settings --------------------------- */

export async function getSetting(key, fallback = null) {
  try {
    const db = await getDB();
    const record = await db.get('settings', key);
    return record ? record.value : fallback;
  } catch (error) {
    log.error('getSetting failed:', error);
    return fallback;
  }
}

export async function setSetting(key, value) {
  try {
    const db = await getDB();
    await db.put('settings', { key, value });
  } catch (error) {
    log.error('setSetting failed:', error);
  }
}

/* -------------------------- maintenance -------------------------- */

/** Auto-prune snapshots older than the TTL unless pinned (F-12). */
export async function pruneOldSnapshots() {
  try {
    const db = await getDB();
    const cutoff = Date.now() - SNAPSHOT_TTL_MS;
    const tx = db.transaction('snapshots', 'readwrite');
    let cursor = await tx.store.index('byTimestamp').openCursor();
    let pruned = 0;
    while (cursor) {
      if (cursor.value.ts < cutoff && !cursor.value.pinned) {
        await cursor.delete();
        pruned += 1;
      }
      cursor = await cursor.continue();
    }
    await tx.done;
    if (pruned) log.debug(`pruned ${pruned} old snapshots`);
    return pruned;
  } catch (error) {
    log.error('prune failed:', error);
    return 0;
  }
}

/** Export the entire local database as a plain object (F-10). */
export async function exportAll() {
  try {
    const db = await getDB();
    const [sites, snapshots, diffs, settings] = await Promise.all([
      db.getAll('sites'),
      db.getAll('snapshots'),
      db.getAll('diffs'),
      db.getAll('settings'),
    ]);
    // Strip encrypted secrets from exports — user data is portable, keys are not.
    const safeSettings = settings.filter((s) => s.key !== 'providers');
    return { exportedAt: new Date().toISOString(), sites, snapshots, diffs, settings: safeSettings };
  } catch (error) {
    log.error('exportAll failed:', error);
    return null;
  }
}

/** Clear all local data (F-12 destructive action — UI confirms first). */
export async function clearAll() {
  try {
    const db = await getDB();
    await Promise.all(
      ['sites', 'snapshots', 'diffs', 'settings'].map((store) => db.clear(store)),
    );
  } catch (error) {
    log.error('clearAll failed:', error);
  }
}

/** Rough storage usage estimate for the settings indicator (F-12, NFR). */
export async function estimateUsage() {
  try {
    if (navigator.storage?.estimate) {
      const { usage, quota } = await navigator.storage.estimate();
      return { usage, quota };
    }
  } catch (error) {
    log.error('estimateUsage failed:', error);
  }
  return { usage: 0, quota: 0 };
}
