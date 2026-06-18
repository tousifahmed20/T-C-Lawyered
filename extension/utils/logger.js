/**
 * Logger wrapper. Verbose in dev, silent (except errors) in prod.
 * Replaces raw console.* everywhere — no stray console.log ships to users.
 *
 * `process.env.NODE_ENV` is statically replaced by esbuild at build time, so
 * the dead-code branches are stripped from production bundles entirely.
 */
const IS_DEV = process.env.NODE_ENV !== 'production';

/** @param {string} scope - module name shown in the log prefix. */
export function createLogger(scope) {
  const prefix = `[T&C:${scope}]`;
  return {
    debug: (...args) => {
      if (IS_DEV) console.debug(prefix, ...args);
    },
    info: (...args) => {
      if (IS_DEV) console.info(prefix, ...args);
    },
    warn: (...args) => {
      if (IS_DEV) console.warn(prefix, ...args);
    },
    /** Errors always surface — they matter in prod too. */
    error: (...args) => {
      console.error(prefix, ...args);
    },
  };
}
