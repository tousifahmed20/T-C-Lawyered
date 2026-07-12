-- 0001_init — the hive's single table (PHASE2.md §3, CLAUDE.md Infrastructure).
-- No user_id, no ip, no created_by. content_hash UNIQUE enforces first-write-wins.

CREATE EXTENSION IF NOT EXISTS "pgcrypto"; -- for gen_random_uuid()

CREATE TABLE IF NOT EXISTS policies (
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  domain        TEXT NOT NULL,
  policy_type   TEXT NOT NULL,                         -- 'privacy_policy' | 'terms_of_service'
  content_hash  TEXT UNIQUE NOT NULL,                  -- SHA-256 hex; the dedupe key
  parent_hash   TEXT REFERENCES policies(content_hash), -- version chain; null for a root version
  summary       JSONB NOT NULL,
  submitted_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_policies_domain_type ON policies (domain, policy_type);
CREATE INDEX IF NOT EXISTS idx_policies_hash        ON policies (content_hash);
