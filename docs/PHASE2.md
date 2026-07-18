# Phase 2 — The Hive Backend

> Implementation spec for the shared, content-addressed summary cache.
> Status: **planned** (no backend code written yet). DB choice: **deferred**.
> Owner: Maintainer. Prereq to read: `CLAUDE.md` (Infrastructure + TDD sections).

---

## 0. Why we're ready

Phase 1 (the extension) already ships a complete hive **client** that calls a
backend which does not exist yet. Phase 2 is simply building the server the
extension is already trying to talk to. Nothing in Phase 1 needs to change for
the happy path.

Client surface that defines our contract (do not drift from this):

| Client fn (`extension/background/hive.js`) | Endpoint |
|---|---|
| `lookupPolicy(hash, domain)` | `GET /policy?hash=&domain=` |
| `fetchHistory(domain, type)` | `GET /policy/history?domain=&type=` |
| `uploadPolicy(payload)` | `POST /policy` |
| keep-warm / uptime | `GET /health` |

Base URL is `HIVE_BASE_URL` in `extension/utils/CONSTANTS.js`
(currently `https://api.tclawyered.dev`). Cutover = point this at the deployed URL.

---

## 1. Goals & non-goals

**Goals**
- Be a read-heavy, content-addressed cache of policy summaries.
- First-write-wins: a hash is written once, never overwritten.
- Zero user data: no accounts, no PII, no IP logging beyond ephemeral rate-limit counters.
- Cheap to run (Railway free tier, no spin-down).

**Non-goals (v1)**
- No auth for reads (public cache).
- No moderation/flagging UI (Phase 3+).
- No per-user history (the client owns local history; the hive only knows hashes).

---

## 2. API contract (authoritative)

All responses are JSON. Errors use a consistent envelope: `{ error: string }`.

### GET /policy
Look up a summary by content hash. `hash` is the source of truth; `domain` is
advisory (used only to detect/log a hash↔domain mismatch, never to widen the match).

```
Query:  hash (string, required, 64-hex), domain (string, required)
200:    { found: true,  summary: <SummaryJSON>, submittedAt: ISO8601 }
200:    { found: false }            ← miss is a 200, not a 404 (client treats !found as miss)
400:    { error: "invalid hash" }
```
> Note: the client treats any non-200 or `found:false` as a miss and proceeds to
> local summarization. Returning `found:false` with 200 is the clean path.

### GET /policy/history
Version chain for a domain + policy type.

```
Query:  domain (string, required), type (string, required)  ← param is "type", not "policyType"
200:    { versions: [ { hash, parentHash, submittedAt }, ... ] }  ← newest first
```

### POST /policy
Store a new summary. **First-write-wins.**

```
Body:   {
          domain: string,
          policyType: "privacy_policy" | "terms_of_service",   ← body uses "policyType"
          hash: string (64-hex),
          parentHash: string | null,
          summary: <SummaryJSON>,
          submittedAt: ISO8601 (advisory; server stamps its own)
        }
201:    { stored: true }
409:    { stored: false, reason: "hash_exists" }   ← existing hash, no overwrite
400:    { error: "..." }                            ← validation / authenticity gate failure
429:    { error: "rate_limited" }
```

### GET /health
```
200:    { status: "ok", uptime: <seconds> }
```

### SummaryJSON shape (stored as JSONB — schema-flexible)
The server stores the summary blob as-is; it does **not** need a migration when the
client adds fields. As of Phase 1 the blob contains: `tldr`, `keyRisks[]`,
`dataCollected[{item,detail}]`, `thirdPartySharing[]`, `userRights[]`,
`protectionTips[]`, `examples{}`, `whatChanged`, `changeList[]`, `changesSeverity`,
`genuineCheck{genuine,confidence,reason}`. The server validates only the fields it
gates on (see §5), not the whole shape.

---

## 3. Data model

Single table, per CLAUDE.md. Drizzle schema + a raw SQL migration.

```
TABLE policies
  id            UUID PRIMARY KEY DEFAULT gen_random_uuid()
  domain        TEXT NOT NULL
  policy_type   TEXT NOT NULL                          -- 'privacy_policy' | 'terms_of_service'
  content_hash  TEXT UNIQUE NOT NULL                   -- SHA-256 hex; the dedupe key
  parent_hash   TEXT NULL REFERENCES policies(content_hash)
  summary       JSONB NOT NULL
  submitted_at  TIMESTAMPTZ NOT NULL DEFAULT now()

  INDEX idx_policies_domain_type ON (domain, policy_type)
  INDEX idx_policies_hash        ON (content_hash)      -- unique already indexes; keep explicit for clarity
```

Notes:
- `content_hash UNIQUE` is what enforces first-write-wins (insert → unique violation → 409).
- `parent_hash` FK builds the version chain for `/policy/history`. Allow null (root version).
- No `user_id`, no `ip`, no `created_by`. By design.

---

## 4. File structure (per CLAUDE.md TDD)

```
backend/
├── src/
│   ├── index.js            # Fastify app entry, plugin registration, listen
│   ├── routes/
│   │   └── policy.js        # GET/POST /policy, GET /policy/history, GET /health
│   ├── db/
│   │   ├── client.js        # Drizzle + pg pool from DATABASE_URL
│   │   └── schema.js        # Drizzle table definition (mirrors §3)
│   └── middleware/
│       └── rateLimit.js     # write rate limiter (see §5)
├── migrations/
│   └── 0001_init.sql        # raw SQL for the policies table + indexes
├── .env.example
├── package.json
├── railway.json
└── README.md
```

Stack: Node 20 LTS · Fastify · Drizzle ORM · `pg` · `node-cron` (keep-warm).
Fastify JSON schema validation on each route (built-in, no extra dep).

---

## 5. Security & abuse hardening

Uploads are unauthenticated, so the server cannot trust the client. Layered defenses:

1. **Server-side authenticity gate** (spec gap I'm closing): reject POST unless
   `summary.genuineCheck.genuine === true && summary.genuineCheck.confidence >= 85`.
   This mirrors the client gate so a hand-rolled POST can't poison the hive.
   Constant lives server-side, not trusted from the body.
2. **Hash integrity** (recommended): the server cannot recompute the hash (it never
   sees raw text — by design), so it cannot verify `hash` matches the summary. It
   *can* enforce `hash` is 64-hex and `domain` is a plausible hostname. Accept this
   limit; flag for Phase 3 (e.g. require the raw-text hash preimage, or reputation).
3. **Write rate limit**: `RATE_LIMIT_WRITES` POSTs per IP per rolling 24h.
   In-memory counter for v1 (single instance on Railway free tier); note it resets on
   redeploy/restart — acceptable for v1, DB-backed counter is a Phase 3 upgrade.
4. **CORS**: allow `chrome-extension://*` (and `*` for reads if we want public reads).
   Reads can be open; writes restricted to extension origin + rate-limited.
5. **Payload limits**: Fastify `bodyLimit` (e.g. 256 KB) to cap summary size.
6. **Input validation**: Fastify schemas — `hash` regex `^[a-f0-9]{64}$`, `policyType`
   enum, `domain` length/charset, `summary` must be an object.
7. **No logging of bodies/IPs** beyond the ephemeral rate-limit map.

---

## 6. Configuration (env)

```
DATABASE_URL=postgresql://...     # Railway injects in prod; local TBD (§7)
PORT=3000
RATE_LIMIT_WRITES=100             # POST /policy per IP per 24h
AUTHENTICITY_MIN_CONFIDENCE=85    # server-side gate
ALLOWED_ORIGINS=chrome-extension://*
KEEPWARM_CRON=*/10 * * * *        # every 10 min self-ping (free-tier warm)
```

---

## 7. Local dev (DB choice deferred)

The backend code is DB-agnostic via `DATABASE_URL`; pick the source at run time:
- **Docker Postgres 15** — add `docker-compose.yml`, `docker compose up -d`, done.
- **Existing Postgres** — supply a `DATABASE_URL` (local install or Railway dev DB).
- Either way: `npm run migrate` (apply `0001_init.sql`) then `npm run dev` (Fastify + nodemon on :3000).

Decision recorded as **deferred** — does not block writing the backend; revisit at first run.

---

## 8. Deployment (Railway)

1. Create Railway project + managed Postgres (injects `DATABASE_URL`).
2. `railway.json` defines build/start; GitHub Actions (`.github/workflows/deploy.yml`)
   deploys on push to `main` (per CLAUDE.md repo structure).
3. Run the migration on first deploy.
4. Keep-warm cron pings `/health` every 10 min (no spin-down).
5. **Cutover**: set `HIVE_BASE_URL` in the extension to the Railway URL, rebuild,
   reload. Roll out behind the existing `hiveEnabled` pref so it can be toggled off.

---

## 9. Testing plan

- **Unit**: validation helpers, rate limiter, first-write-wins logic (Vitest).
- **Integration**: spin up against a test Postgres; assert:
  - POST new hash → 201; POST same hash → 409.
  - GET hit → `{found:true,...}`; GET miss → `{found:false}`.
  - History returns chain newest-first with correct parent links.
  - Authenticity gate rejects `confidence < 85`.
  - Rate limit returns 429 after N writes.
- **End-to-end vs the extension**: run backend on localhost, point `HIVE_BASE_URL`
  at it, scan a policy on one profile (uploads), scan the same policy on a second
  profile (should be a **hive hit → zero LLM cost**). This is the headline proof.

---

## 10. Launch strategy — free-first onboarding (replaces paid warm-up)

The original CLAUDE.md Warm-Up Strategy spent ~$10–12 of the maintainer's own money
to pre-seed the hive. We're **not** doing that. Instead the hive fills itself,
organically and for free, because every user runs on their **own** free AI key:

- The extension ships an in-app **free-setup tour** (settings page + popup empty
  state) that walks a new user through creating a free **OpenRouter** account and
  pasting the key. OpenRouter's `:free` models cost the user $0 and cost us nothing.
- Each genuine scan a user runs uploads its summary to the hive (authenticity-gated,
  first-write-wins). So the community seeds the cache as it's used — no maintainer
  spend, no scripted crawl.

`scripts/warmup.js` + `data/top-1000-urls.json` remain **optional** for a maintainer
who later wants to jump-start Tier 1 popular sites, but they are no longer required
for launch and are deprioritised.

---

## 11. Open decisions / risks

| # | Item | Recommendation |
|---|---|---|
| 1 | Local DB source | Deferred — Docker compose is the low-friction default when we run it. |
| 2 | Can't verify hash↔summary server-side (no raw text) | Accept for v1; reputation/preimage in Phase 3. |
| 3 | In-memory rate limit resets on redeploy | Acceptable v1; DB-backed in Phase 3. |
| 4 | Public reads vs extension-only | Recommend public reads (it's a cache), writes restricted. |
| 5 | Summary size growth (Phase 1 added fields) | `bodyLimit` 256 KB is comfortable; revisit if it grows. |

---

## 12. Milestone checklist

- [ ] Scaffold `backend/` (Fastify + Drizzle + pg)
- [ ] `0001_init.sql` + Drizzle schema
- [ ] `GET /health`
- [ ] `GET /policy` (lookup by hash, miss = 200 `found:false`)
- [ ] `POST /policy` (first-write-wins, authenticity gate, validation)
- [ ] `GET /policy/history`
- [ ] CORS + write rate limit + body limit
- [ ] Local run against a Postgres (DB TBD) + migration
- [ ] Integration tests + e2e vs extension (two-profile hive-hit proof)
- [ ] `railway.json` + deploy workflow + keep-warm cron
- [ ] Cutover `HIVE_BASE_URL`, staged behind `hiveEnabled`
- [ ] (2.5) Warm-up seeding
```
