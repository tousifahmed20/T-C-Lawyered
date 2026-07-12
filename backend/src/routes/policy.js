/**
 * Hive routes (PHASE2.md §2). Authoritative contract — do not drift from the
 * extension client in extension/background/hive.js.
 *
 *   GET  /health           uptime check + keep-warm target
 *   GET  /policy           lookup by content hash (miss = 200 { found:false })
 *   POST /policy           store a new summary, first-write-wins
 *   GET  /policy/history   version chain for a domain + type, newest first
 */
import { and, desc, eq } from 'drizzle-orm';
import { db } from '../db/client.js';
import { policies } from '../db/schema.js';
import { allowWrite } from '../middleware/rateLimit.js';

const HASH_RE = '^[a-f0-9]{64}$';
const POLICY_TYPES = ['privacy_policy', 'terms_of_service'];

/** Server-side authenticity gate. The client is untrusted, so we re-check here. */
function passesAuthenticityGate(summary, minConfidence) {
  const check = summary?.genuineCheck;
  return (
    check != null &&
    check.genuine === true &&
    typeof check.confidence === 'number' &&
    check.confidence >= minConfidence
  );
}

export default async function policyRoutes(fastify) {
  const startedAt = Date.now();
  const minConfidence = Number(process.env.AUTHENTICITY_MIN_CONFIDENCE ?? 85);
  const writeLimit = Number(process.env.RATE_LIMIT_WRITES ?? 100);

  // ---- GET /health -------------------------------------------------------
  fastify.get('/health', async () => ({
    status: 'ok',
    uptime: Math.floor((Date.now() - startedAt) / 1000),
  }));

  // ---- GET /policy -------------------------------------------------------
  // hash is the source of truth; domain is advisory (mismatch is logged, never
  // widens or narrows the match).
  fastify.get(
    '/policy',
    {
      schema: {
        querystring: {
          type: 'object',
          required: ['hash', 'domain'],
          properties: {
            hash: { type: 'string', pattern: HASH_RE },
            domain: { type: 'string', minLength: 1, maxLength: 255 },
          },
        },
      },
    },
    async (request) => {
      const { hash, domain } = request.query;
      const [row] = await db
        .select()
        .from(policies)
        .where(eq(policies.contentHash, hash))
        .limit(1);

      if (!row) return { found: false };
      if (row.domain !== domain) {
        request.log.warn({ hash, expected: row.domain, got: domain }, 'hash/domain mismatch');
      }
      return {
        found: true,
        summary: row.summary,
        submittedAt: row.submittedAt.toISOString(),
      };
    },
  );

  // ---- GET /policy/history ----------------------------------------------
  fastify.get(
    '/policy/history',
    {
      schema: {
        querystring: {
          type: 'object',
          required: ['domain', 'type'],
          properties: {
            domain: { type: 'string', minLength: 1, maxLength: 255 },
            type: { type: 'string', enum: POLICY_TYPES },
          },
        },
      },
    },
    async (request) => {
      const { domain, type } = request.query;
      const rows = await db
        .select({
          hash: policies.contentHash,
          parentHash: policies.parentHash,
          submittedAt: policies.submittedAt,
        })
        .from(policies)
        .where(and(eq(policies.domain, domain), eq(policies.policyType, type)))
        .orderBy(desc(policies.submittedAt));

      return {
        versions: rows.map((r) => ({
          hash: r.hash,
          parentHash: r.parentHash,
          submittedAt: r.submittedAt.toISOString(),
        })),
      };
    },
  );

  // ---- POST /policy ------------------------------------------------------
  // First-write-wins: content_hash UNIQUE turns a duplicate into a 409.
  fastify.post(
    '/policy',
    {
      schema: {
        body: {
          type: 'object',
          required: ['domain', 'policyType', 'hash', 'summary'],
          properties: {
            domain: { type: 'string', minLength: 1, maxLength: 255 },
            policyType: { type: 'string', enum: POLICY_TYPES },
            hash: { type: 'string', pattern: HASH_RE },
            parentHash: { type: ['string', 'null'], pattern: HASH_RE },
            summary: { type: 'object' },
            submittedAt: { type: 'string' }, // advisory; server stamps its own
          },
        },
      },
    },
    async (request, reply) => {
      const ip = request.ip;
      if (!allowWrite(ip, writeLimit)) {
        return reply.code(429).send({ error: 'rate_limited' });
      }

      const { domain, policyType, hash, summary } = request.body;
      const parentHash = request.body.parentHash ?? null;

      if (!passesAuthenticityGate(summary, minConfidence)) {
        return reply.code(400).send({ error: 'authenticity_gate_failed' });
      }

      try {
        await db.insert(policies).values({
          domain,
          policyType,
          contentHash: hash,
          parentHash,
          summary,
        });
        return reply.code(201).send({ stored: true });
      } catch (err) {
        // 23505 = unique_violation → hash already exists (first-write-wins).
        if (err?.code === '23505') {
          return reply.code(409).send({ stored: false, reason: 'hash_exists' });
        }
        // 23503 = foreign_key_violation → parentHash points at an unknown hash.
        if (err?.code === '23503') {
          return reply.code(400).send({ error: 'unknown_parent_hash' });
        }
        request.log.error({ err }, 'insert failed');
        return reply.code(500).send({ error: 'internal_error' });
      }
    },
  );
}
