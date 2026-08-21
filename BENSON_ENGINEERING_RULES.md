# BENSON Engineering Rules

Supersedes `DECISIONS.md` (in `Pictures/BENSON/BENSON 2.0/`) as the binding reference for this repo. That document was written against a different, earlier snapshot of the project and now contradicts several things that are already built and shipped — see "Rewritten / removed" below. Do not treat `DECISIONS.md` as authoritative; if a future prompt or handoff package cites it, check here first.

## Stack & project

- Framework: Expo + React Native + TypeScript. Locked. No exceptions.
- Package: `com.benson.butler`. (Not `com.cogitanka.bensonandroid` — that name appears in some older planning docs but has never matched this repo.)
- Existing code (voice loop, chat service, app launcher, settings, memory, native modules) is the base. Extend, do not replace.
- No `src/` folder convention exists in this repo. New modules go under the existing top-level layout (`lib/`, `components/`, `modules/`) unless a specific integration prompt says otherwise for a specific file.

## Kept from DECISIONS.md — still valid

- Local-first memory: `AsyncStorage` for preferences/history, no new cloud writes without explicit instruction.
- No automatic payments, ever — payment/purchase actions are hard-blocked, never auto-confirmed.
- No automatic location sharing — location is prepared, the user sends/confirms it themselves.
- No hidden analytics — anything analytics-adjacent must be visible and explicit, not silently added.
- No cloud sync of memory unless explicitly ordered.
- Languages: Romanian, German, English (French as a bonus where it already appears in code). No separate translation layer beyond the persona prompt + per-sentence TTS locale detection already in place.
- UI: existing dark navy + gold design language, no new colors/fonts/UI kits — and, per direct instruction this session, **no AI-driven changes to `components/BensonMainScreen.tsx` or any HomeShell/dashboard/layout file at all** — that is the architect's exclusive scope until they explicitly release a file for controlled changes.

## Rewritten / removed — contradicted by what's already shipped

**Accessibility Service.** DECISIONS.md lists this as permanently out of scope ("never build, even if it seems helpful"). It is already built (`modules/benson-accessibility`, `readScreen`/`performClick`/`fillForm`) and has been manually enabled by the user in Android Settings. It must not be removed or treated as forbidden going forward. Payment-sensitive node blocking (`isPaymentSensitive` in `BensonAccessibilityService.kt`) stays as the hard safety limit on it.

**Wake word / always-on listening.** DECISIONS.md scopes wake word to "Tier 1 only... while the app is in the foreground" and explicitly forbids background/screen-off listening or any foreground service for it ("Tier 3, a separate future task... do NOT build"). This is already built past that point: `modules/benson-foreground-service`'s `BensonForegroundService.kt` runs a native, always-on "Benson" hotword loop independent of Activity/screen state, with screen-wake and command-tail extraction on detection. This is the standing implementation. Any future wake-word work (e.g. a JS-side filter layer) must sit on top of it, and must never remove, replace, or downgrade it back to foreground-only.

**Persona defaults.** Do not hardcode a forced "Master" address or "butler" tone as the default persona. Both remain available as one of several user-selectable options (character presets in `lib/agents/claudeAgent.ts`, `addressMode` in Settings), but the system prompt no longer force-addresses the user as "Master" — that was deliberately removed earlier this session. Any persona/config file that reintroduces a hardcoded Master/butler default contradicts a fix already in production and should be corrected, not merged as-is.

**Gemini fallback.** DECISIONS.md refers to "the existing Gemini integration, unchanged" — no Gemini integration exists anywhere in this repo. Don't assume one when integrating new chat-related code; if a fallback model is wanted, it needs to be built from scratch, not preserved.

**API key handling / streaming backend.** DECISIONS.md assumes a Supabase Edge Function (`chat-proxy`) holding a server-side `ANTHROPIC_API_KEY`, with the client never touching it. The actual current model is the opposite: the user pastes their own Anthropic key into Settings, it's stored in `AsyncStorage`, and `lib/agents/claudeAgent.ts` calls `api.anthropic.com` directly from the client. Streaming is **already implemented** this way too (`expo/fetch`'s real `ReadableStream` support, `onSentence` callback feeding `createSentenceSpeaker` in `app/index.tsx`) — there is no missing streaming capability and no backend proxy to build against unless a deliberate decision is made to change the API-key model itself, which is a product decision, not a plumbing task.

## Standing rule going forward

- Existing working native modules (`benson-accessibility`, `benson-app-registry`, `benson-car-bluetooth`, `benson-foreground-service`, `benson-overlay`) must not be removed or weakened by any future integration.
- Foreground service + native wake-word work already present must be preserved as the baseline; new wake-word-related code may only act as an additive filter on top of it, never a foreground-only replacement.
- Before integrating any file from an external handoff package, check whether the current repo already has an equivalent (it frequently does) — prefer extending what exists over introducing a parallel system with a different data shape or a different, less capable implementation.

## When done (per prompt)

- Deliver: changed-files list with one sentence per file.
- Do not run production builds unless explicitly asked.
