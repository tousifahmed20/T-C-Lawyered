/**
 * Fastify app entry (PHASE2.md §4). Registers CORS, the policy routes, a body
 * limit, and a keep-warm cron that self-pings /health so the free-tier instance
 * never spins down.
 */
import Fastify from 'fastify';
import cors from '@fastify/cors';
import cron from 'node-cron';
import policyRoutes from './routes/policy.js';
import { pruneRateLimit } from './middleware/rateLimit.js';

const PORT = Number(process.env.PORT ?? 3000);
const HOST = process.env.HOST ?? '0.0.0.0';
// Reads are a public cache; writes come from the extension origin. Comma list.
const ALLOWED_ORIGINS = process.env.ALLOWED_ORIGINS ?? 'chrome-extension://*';
const KEEPWARM_CRON = process.env.KEEPWARM_CRON ?? '*/10 * * * *';

export function buildApp() {
  const app = Fastify({
    logger: { level: process.env.LOG_LEVEL ?? 'info' },
    bodyLimit: 256 * 1024, // 256 KB cap on summary payloads (§5.5)
    trustProxy: true, // Railway sits behind a proxy; needed for a correct request.ip
  });

  const origins = ALLOWED_ORIGINS.split(',').map((o) => o.trim());
  app.register(cors, {
    origin: origins.includes('*') ? true : origins,
    methods: ['GET', 'POST'],
  });

  app.register(policyRoutes);
  return app;
}

// Only listen when run directly (tests import buildApp instead).
const isMain = process.argv[1] && import.meta.url === `file://${process.argv[1]}`;
if (isMain) {
  const app = buildApp();

  app
    .listen({ port: PORT, host: HOST })
    .then((address) => {
      app.log.info(`hive listening on ${address}`);

      // Keep-warm: ping our own /health so Railway's free tier stays awake.
      if (process.env.DISABLE_KEEPWARM !== 'true') {
        cron.schedule(KEEPWARM_CRON, async () => {
          try {
            await fetch(`http://127.0.0.1:${PORT}/health`);
          } catch {
            // best-effort; a failed self-ping is not fatal
          }
        });
      }

      // Periodically prune the rate-limit map so it can't grow unbounded.
      cron.schedule('0 * * * *', pruneRateLimit);
    })
    .catch((err) => {
      app.log.error(err);
      process.exit(1);
    });
}
