# T&C Lawyered — Hive Backend

The **hive**: a read-heavy, content-addressed cache of policy summaries. It is an
*enhancement*, never a dependency — the extension works fully offline without it.

- **Zero user data.** No accounts, no PII, no IP logging beyond an ephemeral
  rate-limit counter.
- **First-write-wins.** A content hash is written once and never overwritten.
- **Cheap to run.** Fastify + Postgres on Railway's free tier, kept warm by a cron.

Full spec: [`../docs/PHASE2.md`](../docs/PHASE2.md). This server implements the
contract the extension client (`extension/background/hive.js`) already calls.

## Stack

Node 20+ · Fastify 5 · Drizzle ORM · `pg` · `node-cron`.

## Quick start (local)

```bash
# 1. Start Postgres (Docker — the low-friction default)
docker compose up -d

# 2. Install + configure
npm install
cp .env.example .env        # defaults already match docker-compose.yml

# 3. Migrate + run
npm run migrate
npm run dev                 # Fastify on http://localhost:3000
```

Already have a Postgres? Skip step 1 and set `DATABASE_URL` in `.env`.

## API

| Method | Path              | Purpose                                             |
| ------ | ----------------- | --------------------------------------------------- |
| GET    | `/health`         | uptime check + keep-warm target                     |
| GET    | `/policy`         | lookup by `hash` (miss = `200 { found:false }`)     |
| POST   | `/policy`         | store a summary, first-write-wins                   |
| GET    | `/policy/history` | version chain for a `domain` + `type`, newest first |

See PHASE2.md §2 for the authoritative request/response shapes.

### Try it

```bash
curl 'http://localhost:3000/health'

curl 'http://localhost:3000/policy?hash=0000000000000000000000000000000000000000000000000000000000000000&domain=example.com'
# -> {"found":false}
```

## Configuration

All via env (see `.env.example`): `DATABASE_URL`, `PORT`, `RATE_LIMIT_WRITES`,
`AUTHENTICITY_MIN_CONFIDENCE`, `ALLOWED_ORIGINS`, `KEEPWARM_CRON`.

## Testing

```bash
npm test        # unit tests (rate limiter) — no DB required
```

Integration tests (first-write-wins, authenticity gate, history ordering) run
against a live Postgres; see PHASE2.md §9.

## Deploy (Railway)

1. New Railway project + managed Postgres (injects `DATABASE_URL`).
2. `railway.json` runs `npm run migrate && npm start` on deploy and health-checks
   `/health`.
3. Keep-warm cron self-pings `/health` every 10 min (no spin-down).
4. **Cutover:** point `HIVE_BASE_URL` in `extension/utils/CONSTANTS.js` at the
   Railway URL, rebuild the extension, reload. Staged behind the `hiveEnabled` pref.
