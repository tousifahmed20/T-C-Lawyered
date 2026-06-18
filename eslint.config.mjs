/** Flat ESLint config for the T&C Lawyered monorepo. */
const browserAndWorkerGlobals = {
  chrome: 'readonly',
  crypto: 'readonly',
  fetch: 'readonly',
  indexedDB: 'readonly',
  window: 'readonly',
  document: 'readonly',
  navigator: 'readonly',
  speechSynthesis: 'readonly',
  SpeechSynthesisUtterance: 'readonly',
  TextEncoder: 'readonly',
  TextDecoder: 'readonly',
  MutationObserver: 'readonly',
  KeyboardEvent: 'readonly',
  getComputedStyle: 'readonly',
  requestAnimationFrame: 'readonly',
  structuredClone: 'readonly',
  // timers (available in workers, windows, and Node)
  setTimeout: 'readonly',
  clearTimeout: 'readonly',
  setInterval: 'readonly',
  clearInterval: 'readonly',
  // web platform types used in DOM/worker code
  AbortController: 'readonly',
  URL: 'readonly',
  URLSearchParams: 'readonly',
  Blob: 'readonly',
  Audio: 'readonly',
  Element: 'readonly',
  Node: 'readonly',
  NodeFilter: 'readonly',
  btoa: 'readonly',
  atob: 'readonly',
  confirm: 'readonly',
  console: 'readonly',
  process: 'readonly',
};

const nodeGlobals = {
  Buffer: 'readonly',
  process: 'readonly',
  console: 'readonly',
  __dirname: 'readonly',
  require: 'readonly',
  module: 'readonly',
};

export default [
  {
    ignores: ['**/dist/**', '**/node_modules/**'],
  },
  {
    files: ['**/*.{js,mjs}'],
    languageOptions: {
      ecmaVersion: 2022,
      sourceType: 'module',
      globals: browserAndWorkerGlobals,
    },
    rules: {
      'no-unused-vars': ['warn', { argsIgnorePattern: '^_' }],
      'no-undef': 'error',
      // `== null` / `!= null` is the idiomatic null+undefined check; allow it.
      eqeqeq: ['error', 'smart'],
      'prefer-const': 'error',
      'no-var': 'error',
    },
  },
  {
    // Build + tooling scripts run in Node, not the browser.
    files: ['**/esbuild.config.mjs', '**/scripts/**/*.mjs'],
    languageOptions: {
      globals: nodeGlobals,
    },
  },
];
