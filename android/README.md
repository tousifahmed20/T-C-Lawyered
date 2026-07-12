# T&C Lawyered — Android App

Native Android client (Kotlin + Jetpack Compose) for the same privacy-first
mission as the Chrome extension: detect, summarize, and diff Terms & Conditions
and Privacy Policies in plain English, on your own AI key.

It talks to the **same hive backend** as the extension — nothing there changes.
Only `{ hash, summary }` ever leaves the device, and only after the user's own
LLM produces the summary.

> Status: **Slice 1 — foundation.** Project skeleton, shared core logic, hive
> client, and the two Android-native capture surfaces are scaffolded. The full
> summarize pipeline, LLM providers, storage, and rich UI are the next slices
> (see [Roadmap](#roadmap)).

## How input gets in (two surfaces, by design)

Mobile OS sandboxing means no app can silently watch another app. So we use two
complementary surfaces:

1. **Share sheet** — `Share → T&C Lawyered` from any app or browser hands us a
   link or selected text. Best for full, multi-screen policy pages.
   (`share/ShareReceiverActivity.kt`)
2. **Floating bubble + screen capture + on-device OCR** — a hovering bubble
   (`overlay/BubbleService.kt`) that, on tap, captures the current screen
   (`capture/ScreenCaptureService.kt`, MediaProjection) and reads it with ML Kit
   OCR (`capture/OcrExtractor.kt`). Best for short in-app consent popups.

Both keep everything on-device. See `../docs/` and the in-repo discussion for the
rationale (OCR is weak on long/multi-screen docs; share/URL is preferred there).

## Architecture

```
android/app/src/main/java/dev/tclawyered/app/
├── core/          Shared logic ported 1:1 from the extension (must stay in sync)
│   ├── Constants.kt   Hive URL, thresholds, RECHECK_TTL — mirrors CONSTANTS.js
│   ├── Hasher.kt      SHA-256 + whitespace normalization (identical to hasher.js)
│   ├── Domain.kt      Root-domain normalization (identical to domain.js)
│   ├── Chunker.kt     Token estimate + overlapping split (chunker.js)
│   └── Prompts.kt     All LLM prompts (prompts.js)
├── model/         Summary.kt, PolicyType.kt — the SummaryJSON schema, wire-compatible
├── data/          HiveClient.kt (hive.js contract) + SettingsRepository (DataStore)
├── llm/           Provider abstraction + adapters (openrouter/anthropic/openai/gemini)
├── crypto/        KeyVault.kt — Android Keystore AES-GCM key encryption
├── capture/       OcrExtractor.kt, ScreenCaptureService.kt — on-device screen→text
├── overlay/       BubbleService.kt — the draggable floating reader bubble
├── share/         ShareReceiverActivity.kt — the share-sheet entry point
└── ui/            MainActivity.kt (home + tour) + SettingsActivity.kt (provider setup)
```

**Why these stay in lock-step with the extension:** the hash is the shared cache
key across all clients, so `Hasher` and `Domain` must be byte-for-byte identical
to the JS, and `Summary`/`HiveClient` must match the backend contract in
`docs/PHASE2.md`. Any drift silently breaks cross-client hive hits.

## Build

Requires Android Studio (Ladybug or newer) and JDK 17.

```bash
# From Android Studio: File → Open → select the android/ folder, let Gradle sync.
# Or CLI (with a local Android SDK + gradle wrapper generated):
cd android
./gradlew assembleDebug
```

> Note: the Gradle wrapper JAR/scripts aren't committed yet — on first open,
> Android Studio generates them, or run `gradle wrapper` once with a local Gradle.
> `local.properties` (SDK path) is machine-specific and git-ignored.

Requires a real device or emulator for the overlay + screen-capture permissions.

## Permissions the headline features need

- `SYSTEM_ALERT_WINDOW` (draw over other apps) — the bubble. Requested at runtime.
- `MediaProjection` screen-capture consent — granted per session via the system dialog.
- `FOREGROUND_SERVICE*` — keep the bubble/capture alive with a visible notification.

## Roadmap

- [x] Slice 1 — project skeleton, shared core (hash/domain/chunk/prompts), hive
      client, capture + overlay + share scaffolds, onboarding tour.
- [x] Slice 2 — LLM provider layer (OpenRouter, Anthropic, OpenAI, Gemini),
      Android Keystore key encryption, DataStore settings repo, settings screen.
- [ ] Slice 3 — `SummarizePipeline` (hash → hive lookup → summarize → diff →
      upload), local storage (Room/SQLite) with the 2-month re-check, history.
- [ ] Slice 4 — wire the bubble tap to a bound capture session + `TextStitcher`
      scroll-capture; summary rendering UI (TL;DR, risks, data, sharing, rights).
- [ ] Slice 5 — TTS (Android native), data-safety (breaches/track record), polish.

## Not doing on Android

- Accessibility-service auto-detect: technically possible (auto-detects consent
  dialogs, reads other apps' text) but carries real Play Store policy risk. Kept
  out of v1; revisit only with clear policy/legal justification.
