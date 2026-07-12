/**
 * Drizzle schema for the hive. Mirrors PHASE2.md §3 and CLAUDE.md exactly.
 *
 * One table. No user_id, no ip, no created_by — by design. The `content_hash`
 * UNIQUE constraint is what enforces first-write-wins: a second insert of the
 * same hash raises a unique violation, which the route turns into a 409.
 */
import { pgTable, uuid, text, jsonb, timestamp, index } from 'drizzle-orm/pg-core';

export const policies = pgTable(
  'policies',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    domain: text('domain').notNull(),
    policyType: text('policy_type').notNull(), // 'privacy_policy' | 'terms_of_service'
    contentHash: text('content_hash').notNull().unique(), // SHA-256 hex — the dedupe key
    parentHash: text('parent_hash'), // FK → policies.content_hash; null for a root version
    summary: jsonb('summary').notNull(),
    submittedAt: timestamp('submitted_at', { withTimezone: true }).notNull().defaultNow(),
  },
  (table) => ({
    domainTypeIdx: index('idx_policies_domain_type').on(table.domain, table.policyType),
    hashIdx: index('idx_policies_hash').on(table.contentHash),
  }),
);
