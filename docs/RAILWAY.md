# Deploying the Hive to Railway

Step-by-step to get the `backend/` hive live on Railway's free tier and cut the
extension over to it. You only need to do this once; redeploys are automatic on
push after step 1 is done.

Prereqs: a GitHub account with this repo, and ~10 minutes. No credit card is
required for the trial/free usage tier.

---

## 1. Create the Railway project from this repo

1. Go to <https://railway.app> and sign in with **GitHub**.
2. **New Project → Deploy from GitHub repo →** pick `tousifahmed20/T-C-Lawyered`.
3. When it asks for the service root/directory, set it to **`backend`** (this repo
   is a monorepo; the hive lives in `backend/`). Railway reads `backend/railway.json`
   for build + start commands.

Railway will start a first build. It will fail until the database exists — that's
expected; add it next.

## 2. Add managed Postgres

1. In the project canvas: **New → Database → Add PostgreSQL**.
2. Railway provisions it and exposes a `DATABASE_URL`. You do **not** copy it by
   hand — reference it as a shared variable (next step) so it stays in sync.

## 3. Set environment variables on the backend service

Open the **backend service → Variables** and add:

| Variable                      | Value                                            |
| ----------------------------- | ------------------------------------------------ |
| `DATABASE_URL`                | `${{Postgres.DATABASE_URL}}` (reference, not literal) |
| `PORT`                        | `3000`                                            |
| `RATE_LIMIT_WRITES`           | `100`                                             |
| `AUTHENTICITY_MIN_CONFIDENCE` | `85`                                              |
| `ALLOWED_ORIGINS`             | `*`  (public read cache; writes are still rate-limited) |
| `KEEPWARM_CRON`               | `*/10 * * * *`                                    |

> `${{Postgres.DATABASE_URL}}` is Railway's reference syntax — type it exactly;
> it wires the DB credentials in automatically. If your Postgres service has a
> different name, use that name before the dot.

## 4. Deploy + run the migration

The start command in `railway.json` is `npm run migrate && npm start`, so the
`policies` table is created automatically on first boot. Trigger a redeploy
(**Deployments → Redeploy**, or just push to `main`). Watch the logs for:

```
apply 0001_init.sql
migrations up to date
hive listening on http://0.0.0.0:3000
```

## 5. Get the public URL and smoke-test it

1. **backend service → Settings → Networking → Generate Domain.** You'll get a URL
   like `https://t-c-lawyered-production.up.railway.app`.
2. Test it from your machine:

   ```bash
   curl https://YOUR-URL.up.railway.app/health
   # -> {"status":"ok","uptime":<n>}

   curl 'https://YOUR-URL.up.railway.app/policy?hash=0000000000000000000000000000000000000000000000000000000000000000&domain=example.com'
   # -> {"found":false}
   ```

If both respond, the hive is live. The keep-warm cron self-pings `/health` every
10 minutes so the free-tier instance never sleeps.

## 6. Cut the extension over

Point the client at your new URL and rebuild:

1. Edit `extension/utils/CONSTANTS.js`:

   ```js
   export const HIVE_BASE_URL = 'https://YOUR-URL.up.railway.app';
   ```

2. `cd extension && npm run build`, then reload the unpacked extension at
   `chrome://extensions`.
3. The hive stays behind the existing **Use the hive** toggle in Settings, so it
   can be switched off any time. Send me the URL and I'll make this edit + rebuild
   for you.

## 7. Prove it works (the headline test)

1. Scan a policy on one Chrome profile → it summarizes and uploads to the hive.
2. Scan the **same** policy on a second profile (or after clearing local data) →
   it should be a **hive hit: instant, zero AI cost.** That's the whole point.

---

### Cost note

Railway's free/trial usage covers a small always-on service + Postgres. A single
low-traffic hive fits comfortably. If usage ever grows past the free allotment,
Railway shows it in the project's **Usage** tab before anything is charged — you
stay in control. The extension itself never costs us anything: every summary is
generated on the **user's own** free AI key (see the OpenRouter tour), not ours.
