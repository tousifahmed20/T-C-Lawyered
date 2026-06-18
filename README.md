<div align="center">

# T&C Lawyered

### You clicked agree. We actually read it.

A privacy-first Chrome extension that automatically detects, summarizes, and diffs
**Terms & Conditions** and **Privacy Policies** in plain English — using **your own**
LLM API key. Your data never touches our servers.

</div>

---

## The problem

Nobody reads Terms & Conditions. They're long, deliberately dense, and they change
without telling you. You agree to things you'd never accept if you understood them.
T&C Lawyered reads the fine print for you and tells you, in seconds, what you're
actually signing up for — locally and privately.

## What it does

- **🔍 Auto-detects** policy pages, sign-up consent modals, and pop-up dialogs — no clicks needed.
- **📝 Summarizes** any policy into clear sections: **TL;DR, Key Risks, Data Collected,
  Third-Party Sharing, Your Rights** — each with a plain-English definition and a
  *content-specific* example.
- **🗂 Explains every data type collected** in full sentences (what it is, how it's gathered, why).
- **🆚 Diffs versions** — on a revisit it tells you exactly **what changed** since last time,
  bulleted and colour-coded by severity.
- **🛡 Data Safety** — surfaces known breaches for the site (matched **locally** against the
  [Have I Been Pwned](https://haveibeenpwned.com) database) plus AI-reported fines/controversies,
  each with a verification link.
- **🔐 Protect Your Data** — site-specific steps to lock down your privacy, plus recent short
  YouTube videos (optional) anchored to when the policy last changed.
- **📑 Auto-read all sections** — for "one section at a time" policy centres (e.g. Instagram's),
  it opens each section, captures the lazy-loaded text, and assembles the whole policy.
- **🔊 Read aloud** — free browser voice, or premium OpenAI TTS.
- **🕘 History** — every policy version you've seen per site, kept locally.

Everything works **offline-first**. The optional shared cache (the "hive", Phase 2) is an
enhancement, never a dependency.

## Privacy first — the whole point

- **No accounts. No tracking. No analytics. No telemetry.**
- Your **LLM API key is encrypted at rest** (AES-GCM) and is only ever sent to the provider
  *you* choose (Anthropic, OpenAI, Google, or OpenRouter).
- Breach lookups match **on your device** — the site you're visiting is never sent anywhere.
- The only thing that will *ever* leave your browser (in Phase 2) is a hashed policy + its
  summary — and only after an authenticity check. Nothing that identifies you.

## Install (developer build)

```bash
git clone https://github.com/tousifahmed20/T-C-Lawyered.git
cd T-C-Lawyered
npm install
cd extension
npm run icons      # generate icons (one-time)
npm run build      # bundles to extension/dist
```

Then in Chrome:

1. Open `chrome://extensions`
2. Enable **Developer mode** (top-right)
3. Click **Load unpacked** → select the **`extension/dist`** folder

> Load `extension/dist` (the built output), **not** the source folder.

## First-run setup

1. Click the extension icon → ⚙ **Settings**.
2. Add an API key for one provider — **Anthropic, OpenAI, Gemini, or OpenRouter**
   ([OpenRouter has free models](https://openrouter.ai/models)) — then **Save** and **Use this**.
3. *(Optional)* add a **YouTube Data API key** to list recent protection videos.
4. Visit any Terms or Privacy page — the summary appears automatically.

## Supported providers

| Provider | Example models | Notes |
|---|---|---|
| Anthropic | `claude-haiku-4-5`, `claude-sonnet-4-6` | |
| OpenAI | `gpt-4o-mini`, `gpt-4o` | also powers optional premium TTS |
| Google Gemini | `gemini-1.5-flash`, `gemini-1.5-pro` | |
| OpenRouter | many, incl. free `:free` models | one key, many models |

## How it works

```
content/detector ─▶ service-worker ─▶ hash ─▶ (hive lookup, Phase 2)
                                         on miss: validate ─▶ summarize ─▶ diff
                                         ─▶ render in popup + (gated) hive upload
```

- **content/** — detects policies and extracts clean, visible text (no network).
- **background/** — orchestrator: hashing, provider-agnostic LLM layer, chunked
  summarization, authenticity validation, version diffing, breach + reputation lookups.
- **storage/** — IndexedDB (sites / snapshots / diffs / settings) via `idb`.
- **popup/** + **settings/** — the UI.

See [`CLAUDE.md`](CLAUDE.md) for the full spec and [`docs/PHASE2.md`](docs/PHASE2.md) for the
hive backend plan.

## Repository layout

```
extension/   Chrome extension (Manifest V3) — Phase 1, complete
backend/     Hive API (Fastify + Postgres) — Phase 2, planned
test/        Local test fixtures (e.g. a lazy-load policy page)
docs/        PHASE2.md — hive backend spec
CLAUDE.md    Full project brief / spec
```

## Build from source

```bash
npm install                 # from repo root (npm workspaces)
npm run build --workspace extension      # production build
npm run dev   --workspace extension      # watch / rebuild on change
```

## Roadmap

- **Phase 1 — Extension** ✅ Complete (this repo).
- **Phase 2 — The Hive** 🔜 A content-addressed, first-write-wins shared cache so the same
  policy is summarized once and reused by everyone, at zero API cost. Spec in
  [`docs/PHASE2.md`](docs/PHASE2.md).
- **Phase 2.5 — Warm-up** Seed the hive with the top ~1000 sites.

## Tech

Manifest V3 · vanilla JS (ESM) + JSDoc · esbuild · `idb` · Web Crypto (AES-GCM / SHA-256) ·
Web Speech API. No framework, minimal dependencies.

## License

[MIT](LICENSE) — open source, do what you like, no warranty.

---

<div align="center"><sub>Built privacy-first. Your data is yours.</sub></div>
