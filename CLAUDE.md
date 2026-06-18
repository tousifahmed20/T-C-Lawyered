# T&C Lawyered — Claude Code Project Brief

> This file is the single source of truth for Claude Code when working on this project.
> Read it fully before writing any code, creating any file, or making any architectural decision.

---

## Project Identity

**Product name:** T&C Lawyered
**Type:** Chrome Browser Extension + Hive Backend
**Mission:** Automatically detect, summarize, and diff Terms & Conditions and Privacy Policy documents in plain English — locally, privately, and for free after setup.
**Tagline:** *You clicked agree. We actually read it.*

---

## Core Philosophy

- User data never touches the server. Ever.
- The user owns their LLM API key. We never see it.
- The backend is a read-heavy content-addressed cache, not a user database.
- Every feature must work offline-first. The hive is an enhancement, not a dependency.
- No tracking. No analytics. No telemetry. Nothing phoned home except hashed T&C content + its summary.

---

## Feature List

### F-01 — Automatic T&C Detection
- Content script monitors DOM via MutationObserver
- Detects T&C and Privacy Policy pages using URL pattern matching + keyword heuristics
- Triggers on: dedicated policy pages, sign-up modals, inline consent dialogs, update banners
- Extracts visible text only (no hidden elements, no cookie banners misidentified as T&C)
- Fires a detection event to the background service worker

### F-02 — App / Site Recognition
- Identity key: `{ domain: window.location.hostname, type: "privacy_policy" | "terms_of_service" }`
- Domain is normalized (strips subdomains like `accounts.`, `legal.`, keeps root domain)
- Package name fallback for PWAs where available
- Stored in IndexedDB keyed by normalized domain + type

### F-03 — Content Hashing
- SHA-256 hash of extracted raw text (via Web Crypto API)
- Hash computed before any API call
- Same hash = identical document = no re-processing needed
- Hash is the primary lookup key for both local DB and hive backend

### F-04 — Hive Lookup (Remote Cache)
- Background SW queries hive backend: `GET /policy?hash={sha256}&domain={domain}`
- If hit: download summary JSON, store locally, render immediately. Zero API cost.
- If miss: proceed to local LLM summarization pipeline
- Lookup is non-blocking — extension renders a loading state, not a hard wait

### F-05 — Authenticity Validation
- Before any summarization or upload, LLM validates the document
- Prompt sends: current URL + extracted text
- LLM returns: `{ genuine: boolean, confidence: 0-100, reason: string }`
- Threshold: `genuine === true && confidence >= 85` to proceed
- Documents failing validation: summarized locally only, never uploaded to hive
- This prevents poisoning the hive with fake or misidentified content

### F-06 — Chunked Summarization Pipeline
- Documents under 8,000 tokens: single API call
- Documents over 8,000 tokens: split into chunks of 4,000 tokens with 200-token overlap
- Each chunk summarized independently → section summaries collected
- Final meta-summary pass combines section summaries into one structured output
- Output schema (JSON):
  ```json
  {
    "tldr": "string — 2-3 sentence plain English summary",
    "keyRisks": ["string"],
    "dataCollected": ["string"],
    "thirdPartySharing": ["string"],
    "userRights": ["string"],
    "whatChanged": "string | null — only populated on diffs",
    "changesSeverity": "none | low | medium | high | null",
    "genuineCheck": { "genuine": true, "confidence": 95, "reason": "string" }
  }
  ```

### F-07 — Version Diffing
- On hive miss: check if any prior version exists for this domain + type
- If prior version exists: fetch its raw text hash chain, compute diff
- Diff passed to LLM with prompt: "Explain what changed between these two versions in plain English. Flag anything that affects user rights, data collection, or third-party sharing."
- `whatChanged` and `changesSeverity` fields populated in summary JSON
- Version lineage stored as a linked hash chain: `{ hash, parentHash, timestamp }`

### F-08 — Hive Upload
- Only triggered after: authenticity validated + summarization complete
- Payload: `{ domain, type, hash, parentHash, summary, submittedAt }`
- No user ID. No IP. No API key. Purely content-addressed.
- First-write-wins: backend rejects uploads for existing hashes (no overwrite)
- Upload is fire-and-forget — does not block UI rendering

### F-09 — Summary UI (Extension Popup)
- Triggered automatically when T&C detected on active tab
- Renders structured summary in a clean side panel or popup
- Sections: TL;DR, Key Risks, Data Collected, Third-Party Sharing, Your Rights
- Change diff section shown only when `whatChanged` is not null
- Severity badge: color-coded (green / yellow / orange / red) based on `changesSeverity`
- "Read full policy" link to original URL
- History tab: shows all previously seen T&Cs for this domain with timestamps

### F-10 — Text-to-Speech (Audio Mode)
- Default: Web Speech API (zero cost, no API key, works offline)
- Reads the TL;DR + Key Risks sections aloud by default
- Full summary audio available on demand
- Controls: Play / Pause / Speed (0.75x, 1x, 1.25x, 1.5x) / Voice selector
- Premium voice: OpenAI TTS API (`tts-1` model) if user has OpenAI key configured
- Toggle between browser voice and premium voice in settings
- Auto-stops when tab is closed or navigated away

### F-11 — LLM Provider Settings
- Supported providers in v1: Anthropic, OpenAI, Google Gemini
- Per-provider settings: API key, model selection
- API key stored in `chrome.storage.local` encrypted via AES-GCM (Web Crypto API)
- Key is browser-derived — never sent anywhere except the chosen provider's API endpoint
- Model options:
  - Anthropic: `claude-haiku-4-5`, `claude-sonnet-4-6`
  - OpenAI: `gpt-4o-mini`, `gpt-4o`
  - Gemini: `gemini-1.5-flash`, `gemini-1.5-pro`
- Provider health check on settings save (makes a minimal test call)

### F-12 — Local Storage Management
- Engine: IndexedDB (via `idb` wrapper library)
- Tables: `sites`, `snapshots`, `diffs`, `settings`
- Storage usage indicator in settings (MB used / estimated quota)
- Export all data as JSON (user owns their data)
- Clear all data option with confirmation dialog
- Auto-prune: snapshots older than 12 months purged unless pinned

### F-13 — Update Notifications
- Badge on extension icon when a T&C change is detected on revisit
- Non-intrusive — no OS notifications by default
- Notification dot clears on popup open
- Change severity determines badge color (matches in-popup severity badge)

---

## Infrastructure Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                        USER'S BROWSER                           │
│                                                                 │
│  ┌─────────────────┐     ┌──────────────────────────────────┐  │
│  │  Content Script │────▶│     Background Service Worker    │  │
│  │                 │     │                                  │  │
│  │ - DOM monitoring│     │ - Orchestrates all logic         │  │
│  │ - Text extract  │     │ - Hash computation               │  │
│  │ - URL capture   │     │ - Hive lookup                    │  │
│  └─────────────────┘     │ - LLM API calls                  │  │
│                          │ - Chunking pipeline              │  │
│  ┌─────────────────┐     │ - Hive upload (fire & forget)    │  │
│  │   Popup / UI    │◀────│ - IndexedDB read/write           │  │
│  │                 │     └──────────────────────────────────┘  │
│  │ - Summary cards │                   │                       │
│  │ - Audio player  │                   │                       │
│  │ - History tab   │     ┌─────────────▼────────────────────┐  │
│  │ - Settings      │     │           IndexedDB              │  │
│  └─────────────────┘     │                                  │  │
│                          │  sites      { domain, type,      │  │
│                          │               hash, lastSeen }   │  │
│                          │  snapshots  { hash, rawText,     │  │
│                          │               summary, ts }      │  │
│                          │  diffs      { hash, parentHash,  │  │
│                          │               diffSummary, ts }  │  │
│                          │  settings   { provider, key,     │  │
│                          │               model, prefs }     │  │
│                          └──────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
          │                                        │
          │ GET /policy?hash=&domain=              │ POST /policy
          │ (lookup before API call)               │ (upload after summarize)
          ▼                                        ▼
┌─────────────────────────────────────────────────────────────────┐
│                        HIVE BACKEND                             │
│                      (Railway — free tier)                      │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                  Node.js + Fastify API                   │   │
│  │                                                          │   │
│  │  GET  /policy       — lookup by hash + domain            │   │
│  │  POST /policy       — store new summary (first-write)    │   │
│  │  GET  /policy/history — version chain for a domain       │   │
│  │  GET  /health       — uptime check                       │   │
│  └──────────────────────────────────────────────────────────┘   │
│                          │                                      │
│  ┌───────────────────────▼──────────────────────────────────┐   │
│  │                     PostgreSQL                           │   │
│  │                                                          │   │
│  │  TABLE policies                                          │   │
│  │    id            UUID PRIMARY KEY                        │   │
│  │    domain        TEXT NOT NULL                           │   │
│  │    policy_type   TEXT NOT NULL  -- privacy|terms         │   │
│  │    content_hash  TEXT UNIQUE NOT NULL  -- SHA-256        │   │
│  │    parent_hash   TEXT REFERENCES policies(content_hash)  │   │
│  │    summary       JSONB NOT NULL                          │   │
│  │    submitted_at  TIMESTAMPTZ DEFAULT now()               │   │
│  │                                                          │   │
│  │  INDEX ON (domain, policy_type)                          │   │
│  │  INDEX ON (content_hash)                                 │   │
│  └──────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
          │                          │                   │
          ▼                          ▼                   ▼
   api.anthropic.com        api.openai.com     generativelanguage
   (user's own key)         (user's own key)   .googleapis.com
                                               (user's own key)
```

### Backend Stack Decision
- **Runtime:** Node.js 20 LTS
- **Framework:** Fastify (faster than Express, built-in schema validation)
- **Database:** PostgreSQL 15 (via Railway managed Postgres)
- **ORM:** Drizzle ORM (lightweight, type-safe, no bloat)
- **Hosting:** Railway free tier (no spin-down, unlike Render)
- **Keep-warm:** Built-in cron ping every 10 minutes via `node-cron`

---

## Business Requirements Document (BRD)

### Problem Statement
Users routinely agree to Terms & Conditions and Privacy Policies without reading them. Documents are intentionally long, written in legal language, and change without clear notification. Users have no practical way to understand what they agreed to or what changed.

### Stakeholders
| Role | Description |
|---|---|
| End User | Person installing the extension who wants to understand T&C without reading it |
| Contributor | User whose API call seeds the hive for others |
| Maintainer | Project owner (you) who manages the hive backend and Git repo |

### Business Goals
| ID | Goal |
|---|---|
| BG-01 | Reduce time-to-understanding of any T&C from 45 minutes to under 2 minutes |
| BG-02 | Build a self-sustaining hive database that reduces per-user API cost to zero over time |
| BG-03 | Ship a privacy-first product with zero user data collection as a core differentiator |
| BG-04 | Open-source the project so the community can maintain and extend it |

### Functional Requirements
| ID | Requirement | Priority |
|---|---|---|
| FR-01 | Extension detects T&C pages automatically without user action | P0 |
| FR-02 | Extension identifies the site/app correctly | P0 |
| FR-03 | Summary rendered in under 10 seconds for hive hits | P0 |
| FR-04 | Summary rendered in under 60 seconds for new documents | P0 |
| FR-05 | User can configure their own LLM API key | P0 |
| FR-06 | Diff shown when a policy is updated | P0 |
| FR-07 | Document authenticity validated before hive upload | P0 |
| FR-08 | Audio playback of summary | P1 |
| FR-09 | History of all seen T&Cs per domain | P1 |
| FR-10 | Export local data as JSON | P1 |
| FR-11 | Storage usage indicator | P2 |
| FR-12 | Auto-prune old snapshots | P2 |

### Non-Functional Requirements
| ID | Requirement |
|---|---|
| NFR-01 | No user PII stored anywhere — local or server |
| NFR-02 | API key never leaves the browser except to the chosen LLM provider |
| NFR-03 | Extension must not degrade page load performance (content script < 5ms overhead) |
| NFR-04 | Hive backend must respond to lookup in < 500ms |
| NFR-05 | Extension works fully offline if hive is unreachable (graceful degradation) |
| NFR-06 | First-write-wins on backend — no summary overwrite possible |
| NFR-07 | Open-source MIT license |

### Out of Scope (v1)
- Mobile app (Android)
- Ollama / local model support
- iOS
- Browser support beyond Chrome/Chromium
- User accounts or login
- Community flagging of bad summaries
- Proactive push notifications when T&C changes without user visiting

---

## Technical Design Document (TDD)

### Extension File Structure
```
tc-lawyered/
├── manifest.json
├── background/
│   ├── service-worker.js       # Main orchestrator
│   ├── hive.js                 # Hive API client (lookup + upload)
│   ├── llm.js                  # LLM provider abstraction layer
│   ├── chunker.js              # Token chunking + summarization pipeline
│   ├── hasher.js               # SHA-256 via Web Crypto API
│   ├── validator.js            # Authenticity validation logic
│   └── differ.js               # Diff computation + plain-English diff prompt
├── content/
│   ├── detector.js             # MutationObserver + heuristic detection
│   └── extractor.js            # Text extraction + cleaning
├── popup/
│   ├── popup.html
│   ├── popup.js
│   └── popup.css
├── settings/
│   ├── settings.html
│   ├── settings.js
│   └── settings.css
├── storage/
│   └── db.js                   # IndexedDB abstraction via idb library
├── audio/
│   └── tts.js                  # TTS abstraction (Web Speech + OpenAI TTS)
├── utils/
│   └── crypto.js               # AES-GCM key encrypt/decrypt for API keys
├── icons/
│   ├── icon16.png
│   ├── icon48.png
│   └── icon128.png
└── _locales/
    └── en/messages.json
```

### Manifest v3 Key Permissions
```json
{
  "manifest_version": 3,
  "permissions": [
    "storage",
    "activeTab",
    "scripting",
    "alarms"
  ],
  "host_permissions": ["<all_urls>"],
  "background": { "service_worker": "background/service-worker.js" },
  "content_scripts": [{
    "matches": ["<all_urls>"],
    "js": ["content/detector.js", "content/extractor.js"],
    "run_at": "document_idle"
  }]
}
```

### LLM Abstraction Layer
All three providers conform to a single internal interface:

```javascript
// llm.js — provider-agnostic interface
async function callLLM({ systemPrompt, userPrompt, provider, model, apiKey })
// Returns: { text: string, tokensUsed: number }
```

Internally routes to:
- `providers/anthropic.js` — Messages API
- `providers/openai.js` — Chat Completions API
- `providers/gemini.js` — generateContent API

### Detection Heuristics (content/detector.js)
Priority order — first match wins:
1. URL pattern match: `/privacy`, `/terms`, `/legal`, `/tos`, `/eula`, `/policy`
2. Page `<title>` contains: "Privacy Policy", "Terms of Service", "Terms and Conditions", "Terms of Use", "User Agreement"
3. DOM: `<h1>` or `<h2>` text matches above keywords
4. DOM: Large text block (> 2000 words) adjacent to a checkbox + "I agree" / "I accept" button
5. DOM: Modal overlay containing > 1000 words of text + accept/decline buttons

### IndexedDB Schema
```javascript
// db.js
const DB_NAME = 'tc-lawyered';
const DB_VERSION = 1;

// Object stores:
// sites      — keyPath: [domain, policyType]
// snapshots  — keyPath: hash
// diffs      — keyPath: [hash, parentHash]
// settings   — keyPath: key (single record store)
```

### Hive API Contract
```
BASE URL: https://api.tclawyered.dev  (or Railway URL)

GET /policy
  Query: hash (string, required), domain (string, required)
  200: { found: true, summary: SummaryJSON, submittedAt: ISO8601 }
  404: { found: false }

GET /policy/history
  Query: domain (string, required), type (string, required)
  200: { versions: [{ hash, parentHash, submittedAt }] }

POST /policy
  Body: { domain, policyType, hash, parentHash|null, summary: SummaryJSON }
  201: { stored: true }
  409: { stored: false, reason: "hash_exists" }  ← first-write-wins

GET /health
  200: { status: "ok", uptime: seconds }
```

### Chunking Pipeline (chunker.js)
```
Input: rawText (string)

1. Estimate token count: Math.ceil(rawText.length / 4)
2. If tokens <= 8000: single summarization call → return summary
3. If tokens > 8000:
   a. Split into chunks of ~4000 tokens with 200-token overlap
   b. For each chunk: call LLM with section summarization prompt
   c. Collect section summaries (array of strings)
   d. Call LLM with meta-summarization prompt on section summaries
   e. Return structured summary JSON
```

### Authenticity Validation Prompt
```
System: You are a document authenticity checker for a browser extension.
        Respond ONLY in valid JSON. No preamble. No explanation outside JSON.

User:   URL: {currentURL}
        Document excerpt (first 2000 tokens): {textExcerpt}

        Does this appear to be a genuine privacy policy or terms of service
        document for the domain in the URL?

        Check for:
        - Brand/company name in text matches or relates to the domain
        - Coherent legal language and document structure
        - Not a cookie consent banner, advertisement, or unrelated content

        Respond: { "genuine": boolean, "confidence": 0-100, "reason": "string" }
```

### Security Considerations
- API keys: encrypted at rest using AES-GCM 256-bit via `window.crypto.subtle`
- Encryption key derived from browser fingerprint (not stored, re-derived on access)
- Hive backend: no auth required for reads (public cache), rate-limited writes (100/day per IP via simple in-memory counter)
- Content Security Policy in manifest restricts extension pages to own resources only
- No `eval()`, no remote code execution, no inline scripts

### Backend File Structure
```
tc-lawyered-api/
├── src/
│   ├── index.js            # Fastify app entry point
│   ├── routes/
│   │   └── policy.js       # GET + POST /policy, GET /policy/history
│   ├── db/
│   │   ├── client.js       # Drizzle + pg connection
│   │   └── schema.js       # Drizzle schema definition
│   └── middleware/
│       └── rateLimit.js    # Simple write rate limiter
├── migrations/
│   └── 0001_init.sql
├── .env.example
├── package.json
├── railway.json
└── README.md
```

### Environment Variables (Backend)
```
DATABASE_URL=postgresql://...   # Railway injects this automatically
PORT=3000
RATE_LIMIT_WRITES=100           # Max POST /policy per IP per day
ALLOWED_ORIGINS=chrome-extension://*
```

---

## Warm-Up Strategy

Before public launch, seed the hive with the top 1000 websites' T&C documents.

```
Budget:     ~$10–12 using Claude Haiku or GPT-4o mini
Script:     scripts/warmup.js
Input:      data/top-1000-urls.json  (domain + policy URL pairs)
Output:     Directly POSTs to hive backend

Tier 1:     Top 20 platforms (Google, Meta, Apple, Amazon, etc.)
Tier 2:     Next 80 popular consumer apps
Tier 3:     Remaining 900 regional + niche sites

Run order:  Tier 1 first (highest cache hit probability at launch)
```

---

## Git Repository Structure

```
tc-lawyered/               ← root repo
├── extension/             ← Chrome extension source
├── backend/               ← Hive API source
├── scripts/
│   └── warmup.js          ← DB seeding script
├── data/
│   └── top-1000-urls.json ← URL list for warm-up
├── docs/
│   ├── CLAUDE.md          ← this file
│   └── PERSONA.md         ← developer persona
├── .github/
│   └── workflows/
│       └── deploy.yml     ← Railway auto-deploy on push to main
├── README.md              ← public-facing setup guide
├── LICENSE                ← MIT
└── CONTRIBUTING.md
```

---

## Definition of Done (per feature)

A feature is considered complete when:
- [ ] Core logic implemented and manually tested
- [ ] Edge cases handled (network failure, empty text, invalid API key)
- [ ] Graceful degradation if hive is unreachable
- [ ] No `console.log` left in production code
- [ ] Relevant IndexedDB operations wrapped in try/catch
- [ ] Feature flag added if experimental (can be toggled in settings)

---

## Build & Run Instructions

### Extension (local dev)
```bash
cd extension
npm install
npm run build       # webpack build to /dist
# Load /dist as unpacked extension in chrome://extensions
```

### Backend (local dev)
```bash
cd backend
npm install
cp .env.example .env
# Fill in DATABASE_URL (local Postgres or Railway dev DB)
npm run migrate
npm run dev         # nodemon + Fastify on port 3000
```

### Warm-up script
```bash
cd scripts
npm install
export LLM_PROVIDER=anthropic
export LLM_API_KEY=your_key_here
export HIVE_URL=https://your-railway-url.railway.app
node warmup.js --tier=1   # Run tier 1 first
node warmup.js --tier=2
node warmup.js --tier=3
```

---

*Last updated: June 2026*
*Maintainer: see README.md*
