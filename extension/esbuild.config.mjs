/**
 * esbuild build for the T&C Lawyered extension.
 *
 * MV3 constraints handled here:
 *  - service worker is bundled as an ESM `type: module` worker
 *  - content script is bundled as a classic IIFE (content scripts cannot be modules)
 *  - static assets (manifest, HTML, CSS, icons, locales) are copied verbatim to /dist
 *
 * Run: `node esbuild.config.mjs` (build) or `--watch` (rebuild on change).
 */
import * as esbuild from 'esbuild';
import { cp, mkdir, rm } from 'node:fs/promises';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SRC = __dirname;
const OUT = resolve(__dirname, 'dist');
const WATCH = process.argv.includes('--watch');
// Dev mode keeps debug logs + sourcemaps and skips minify. Implied by --watch.
const DEV = WATCH || process.argv.includes('--dev');

/** Entry points that are ESM-capable (service worker, popup, settings). */
const moduleEntries = {
  'background/service-worker': 'background/service-worker.js',
  'popup/popup': 'popup/popup.js',
  'settings/settings': 'settings/settings.js',
};

/** Content scripts must be classic scripts (no ESM) — bundled as IIFE. */
const contentEntries = {
  'content/detector': 'content/detector.js',
  // Injected on demand by the popup (Auto-read all sections), not auto-run.
  'content/crawler': 'content/crawler.js',
};

/** Static files copied verbatim into /dist. */
const staticAssets = [
  'manifest.json',
  'popup/popup.html',
  'popup/popup.css',
  'settings/settings.html',
  'settings/settings.css',
  'icons',
  '_locales',
];

const sharedOptions = {
  bundle: true,
  sourcemap: DEV ? 'inline' : false,
  minify: !DEV,
  target: ['chrome120'],
  logLevel: 'info',
  define: {
    // Strip dev-only logging in production builds.
    'process.env.NODE_ENV': DEV ? '"development"' : '"production"',
  },
};

async function copyStatic() {
  await mkdir(OUT, { recursive: true });
  for (const asset of staticAssets) {
    await cp(resolve(SRC, asset), resolve(OUT, asset), { recursive: true }).catch((err) => {
      if (err.code !== 'ENOENT') throw err;
      console.warn(`[esbuild] skipped missing asset: ${asset}`);
    });
  }
}

async function buildAll() {
  await rm(OUT, { recursive: true, force: true });
  await copyStatic();

  const moduleCtx = await esbuild.context({
    ...sharedOptions,
    entryPoints: Object.fromEntries(
      Object.entries(moduleEntries).map(([out, src]) => [out, resolve(SRC, src)]),
    ),
    outdir: OUT,
    format: 'esm',
    splitting: false,
  });

  const contentCtx = await esbuild.context({
    ...sharedOptions,
    entryPoints: Object.fromEntries(
      Object.entries(contentEntries).map(([out, src]) => [out, resolve(SRC, src)]),
    ),
    outdir: OUT,
    format: 'iife',
  });

  if (WATCH) {
    await moduleCtx.watch();
    await contentCtx.watch();
    console.log('[esbuild] watching for changes…');
  } else {
    await moduleCtx.rebuild();
    await contentCtx.rebuild();
    await moduleCtx.dispose();
    await contentCtx.dispose();
    console.log('[esbuild] build complete → /dist');
  }
}

buildAll().catch((err) => {
  console.error(err);
  process.exit(1);
});
