/**
 * Recent "how to protect your data" videos (protection feature).
 *
 * Two modes:
 *  - If the user has supplied a YouTube Data API key, we fetch a real, current
 *    list of SHORT videos, filtered to those published after the policy's last
 *    change (or the last ~2 years), so the listing tracks how recent the policy
 *    is — exactly when fresh advice matters.
 *  - With no key we return nothing and the popup falls back to a YouTube search
 *    deep-link (also built here), so the feature is always at least useful.
 *
 * The only thing sent out is a generic search query (company + "privacy
 * settings") to googleapis.com — never the user's identity. Results cached daily.
 */
import { createLogger } from '../utils/logger.js';

const log = createLogger('videos');
const YT_SEARCH_API = 'https://www.googleapis.com/youtube/v3/search';
const CACHE_KEY = 'protection_videos';
const CACHE_TTL_MS = 24 * 60 * 60 * 1000;

/** A YouTube search deep-link (no key needed) — recent, protection-focused. */
export function buildYoutubeSearchUrl(company) {
  const year = new Date().getFullYear();
  const q = `${company} privacy settings protect your data ${year}`;
  return `https://www.youtube.com/results?search_query=${encodeURIComponent(q)}`;
}

/**
 * Fetch (and cache) short protection videos for a company.
 * @param {object} args - { domain, company, publishedAfter (ISO), apiKey }
 * @returns {Promise<Array<{id,title,channel,published,thumb,url}>>}
 */
export async function getProtectionVideos({ domain, company, publishedAfter, apiKey }) {
  if (!apiKey || !domain) return [];

  const { [CACHE_KEY]: all } = await chrome.storage.local.get(CACHE_KEY);
  const entry = all?.[domain];
  if (entry && Date.now() - entry.fetchedAt < CACHE_TTL_MS) return entry.videos;

  try {
    const params = new URLSearchParams({
      key: apiKey,
      part: 'snippet',
      type: 'video',
      videoDuration: 'short', // < 4 minutes
      videoEmbeddable: 'true',
      order: 'relevance',
      safeSearch: 'moderate',
      maxResults: '5',
      q: `${company} privacy settings protect your data`,
    });
    if (publishedAfter) params.set('publishedAfter', publishedAfter);

    const res = await fetch(`${YT_SEARCH_API}?${params.toString()}`);
    if (!res.ok) {
      log.warn('YouTube API returned', res.status);
      return entry?.videos || [];
    }
    const data = await res.json();
    const videos = (data.items || [])
      .filter((it) => it.id?.videoId)
      .map((it) => ({
        id: it.id.videoId,
        title: it.snippet?.title || 'Untitled',
        channel: it.snippet?.channelTitle || '',
        published: it.snippet?.publishedAt || '',
        thumb: it.snippet?.thumbnails?.medium?.url || it.snippet?.thumbnails?.default?.url || '',
        url: `https://www.youtube.com/watch?v=${it.id.videoId}`,
      }));
    await chrome.storage.local.set({
      [CACHE_KEY]: { ...(all || {}), [domain]: { fetchedAt: Date.now(), videos } },
    });
    return videos;
  } catch (error) {
    log.warn('YouTube fetch failed:', error.message);
    return entry?.videos || [];
  }
}
