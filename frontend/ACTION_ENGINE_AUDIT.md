# BENSON Action Engine — Audit

Audit only. No source files were changed. Voice loop, native wake/foreground service, and UI were not touched.

---

## A. Current command flow

**1. Where does user input enter the system?**
`app/index.tsx:1051`, `handleIncomingText(msg)`. Fed by three sources: STT result, typed text, and the native wake-word command tail (e.g. "Benson, deschide Waze" said in one breath).

**2. Where is intent detected?**
There is no dedicated intent-detection step. `lib/agents/orchestrator.ts`'s `routeCommand()` is a flat, ordered regex cascade — the first pattern that matches *is* the routing decision. No intermediate `Intent` value, no classifier, no confidence score (except inside the Notepad sub-flow, which does call an LLM to classify calendar/reminder/message/todo/feedback).

**3. Where is Claude called?**
Two places, both effectively "chat AND action," not "chat only":
- `orchestrator.ts`'s final fallback (line ~172): if nothing in the regex cascade matched, `askClaudeWithTools()` runs with the full `AGENT_TOOLS` list (`lib/agents/tools.ts`) — Claude can call `openApp`, `callContact`, `sendWhatsApp`, `readScreen`, `fillForm`, `startCarMode`, `getLocation`, `setAlarm`.
- `lib/agents/noteRouterAgent.ts`'s `runNoteRouterAgent()` — a separate, smaller Claude call used only to classify text already matched by `NOTEPAD_PATTERN` into a `ParsedNote`.

**4. Where are non-chat commands detected, if at all?**
The regex cascade in `routeCommand()` IS the non-chat detection layer: `CALL_PATTERN`, `PLAY_PATTERN`, `launchApp()`'s internal patterns (`NAV_PATTERN`, `SEARCH_IN_APP_PATTERN`, `OPEN_PATTERN`, hotel/restaurant/parking), `WEATHER_PATTERN`, `GALLERY_PATTERN`, `NOTEPAD_PATTERN`, `SEARCH_PATTERN`. Anything none of these match falls to Claude, which — per finding A3 — is *also* where non-chat commands get a second chance via tool-use, not a pure chat fallback.

**5. Where is app launching handled?**
`lib/agents/appLauncherAgent.ts`'s `launchApp()`, called from two places: directly by `routeCommand()` (fast path) and via the `openApp` tool in `lib/agents/tools.ts` (Claude path) — both call the exact same function.

---

## B. Existing action capability

| Action | File / function | Status |
|---|---|---|
| 1. Open Waze | `appLauncherAgent.ts:151` (`NAV_PATTERN` branch, `wazeAllowed`) for navigation-with-destination; `appRegistry.ts`'s `waze` entry for plain "open Waze" | Plain open works. Navigate-with-destination is gated on an allowlist that is empty by default — see D1. |
| 2. Open Google Maps | `appLauncherAgent.ts:153-154` (`mapsAllowed`) | Same gate, same default-empty problem — see D2. |
| 3. Open WhatsApp generic | `appLauncherAgent.ts`'s `OPEN_PATTERN` branch → `findAppByName` → `openAppEntry(entry)`, no query | Works. |
| 4. Open WhatsApp to contact | `lib/notepad/actions.ts`'s `sendMessageToPerson()`, reached via `NOTEPAD_PATTERN` (confirmation required) or the `sendWhatsApp` tool (no confirmation) | Two disconnected paths; neither is reached by plain "open/deschide WhatsApp for/to X" phrasing — see D3/D4. |
| 5. Call contact | `lib/agents/contactsAgent.ts`'s `callContact()`, reached via `CALL_PATTERN` or the `callContact` tool | Works, but `CALL_PATTERN` misses Romanian diacritic forms — see D5. |
| 6. Share location | **Does not exist.** No file implements sending/sharing the user's own location with a contact. | Missing entirely. |
| 7. Navigate to saved place | **Does not exist.** No alias/saved-place table anywhere in the repo; `NAV_PATTERN` only accepts a literal destination string after `"la"`/`"to"`. | Missing entirely. |
| 8. Open generic app by registry | `appLauncherAgent.ts`'s `OPEN_PATTERN` branch, `lib/appRegistry.ts` (35+ curated apps) + dynamic per-device allowlist (`lib/appPermissions.ts`) fallback | Works. |

---

## C. Missing architecture

| Component | Exists? | Where |
|---|---|---|
| 1. Benson Orchestrator | Partially | `lib/agents/orchestrator.ts` — exists, but as a flat regex-first-match cascade, not an intent→plan structure |
| 2. Action Engine | No | No `ActionPlan`/intent-vocabulary abstraction anywhere; the orchestrator's `if` cascade *is* the action engine, undifferentiated from routing |
| 3. Contact Resolver | No | Only inline logic in `contactsAgent.ts`'s `findContact()` — first substring match, no alias table, no disambiguation |
| 4. Navigation Executor | Partially | Logic lives inline inside `appLauncherAgent.ts`'s `launchApp()`, not a separate module; no saved-places support |
| 5. WhatsApp Executor | Partially | `sendMessageToPerson()` in `lib/notepad/actions.ts` does the actual work, but two different callers (notepad path, tool path) reach it inconsistently |
| 6. Phone Call Executor | Partially | `callContact()` exists and works end-to-end, just not reached consistently (D5) |
| 7. App Launcher Executor | Yes | `appLauncherAgent.ts` — the most complete piece of the whole system |
| 8. Confirmation Gate | No | Only ad hoc: `orchestrator.ts`'s `pendingNote` for notepad-detected calendar/message notes. Does not apply to the same actions reached via Claude tool-use. |
| 9. Execution Result handling | No | Every executor does `Linking.openURL(...).catch(() => {})` and returns an optimistic reply string regardless of what actually happened |
| 10. Error reporting to user | Partially | Individual functions do return specific failure strings for known failure modes (no permission, contact not found, app not approved) — but nothing surfaces a deep-link failure, since none of the `Linking` calls are checked for success |

---

## D. Root cause hypotheses

**1 & 2. Waze / Google Maps do not open (navigate-with-destination).**
Confirmed in code, not a hypothesis: `appLauncherAgent.ts:148-150`
```ts
const allowedApps = await getAllowedApps();
const wazeAllowed  = allowedApps.some(a => a.packageName === 'com.waze');
const mapsAllowed  = allowedApps.some(a => a.packageName === 'com.google.android.apps.maps');
```
`getAllowedApps()` (`lib/appPermissions.ts`) reads a **separate, BENSON-4-only dynamic permissions list** that starts **empty** and is only populated if the user completed the newer App Permissions onboarding screen and explicitly toggled each app on. This is a *different* list from the one that gates plain "open Waze" (`isApprovedAnywhere()`, which OR's this dynamic list with the older curated-registry list that defaults to *all apps approved*). Result: unless the user specifically went through onboarding and toggled Waze/Maps on in that exact screen, both `wazeAllowed` and `mapsAllowed` are `false`, and every "navigate to X" silently falls to the generic `https://maps.google.com/?q=X` **web URL** — which opens a browser tab, not the Waze/Maps app, and carries no `navigate=yes` semantics. Plain "open Waze" (no destination) uses the OR'd check and works, which is why it can look like "Waze works" while "navigate to X" doesn't — same app, different gate.

**3 & 4. WhatsApp opens only generically / cannot open to a named contact.**
Confirmed in code: `launchApp()` checks `SEARCH_IN_APP_PATTERN` before `OPEN_PATTERN`, but `SEARCH_IN_APP_PATTERN`'s trigger words are `play/pune/redă/joacă/book/rezervă/găsește/caută` — none of which is `"open"`/`"deschide"`/`"trimite"`. So a command like *"deschide WhatsApp lui Hannah"* skips that branch entirely and instead matches `OPEN_PATTERN` (`/\b(?:open|deschide|...)\b\s+(.+)/i`), which captures **the whole remainder** — `"WhatsApp lui Hannah"` — as one phrase, then `findAppByName()`'s substring check (`"whatsapp lui hannah".includes("whatsapp")`) matches the WhatsApp registry entry, and `openAppEntry(entry)` is called **with no query at all**. The contact name is silently swallowed into the app-name match and discarded. The only paths that *do* resolve a contact for WhatsApp — `NOTEPAD_PATTERN` (needs a trigger word like "spune-i lui"/"notează", which "deschide WhatsApp lui Hannah" doesn't contain) or the `sendWhatsApp` Claude tool (only reached if the entire regex cascade misses, which it doesn't here since `OPEN_PATTERN` already matched) — never get a chance to run.

**5. Named contact actions fail (calls).**
Two contributing, independent causes: (a) `CALL_PATTERN = /\b(call|suna|ruf|appelle)\b/i` has no diacritic form — `sună` (with ă) never matches `suna` — so "sună-o pe Hannah" misses the fast path and always costs an LLM round-trip via tool-use (works, but slower and less reliable than intended); (b) `findContact()`'s resolution itself is a first-substring-match with no disambiguation — if the spoken name is a partial/fuzzy match to multiple device contacts, whichever one the device's contact list returns first wins silently, with no signal to the user that it might be the wrong person.

**6. Benson stops reacting after external app handoff.**
No `setTimeout`/`AppState`-triggered auto-return code was found anywhere in the current repo (re-confirmed this audit — same result as the previous one). No `externalAppHandoff` state variable exists anywhere in the codebase; that concept was never implemented here (it appears in the unrelated `BENSON 2.0` handoff package's `wakeMode.ts`, not in this repo). Given the native wake-word loop (added this session) is designed to run independent of Activity/screen state, this specific complaint may already be resolved by that change — or it may be a live-device-only issue invisible from static code, most plausibly: (a) audio-focus contention between Waze's own turn-by-turn TTS and the native hotword loop's periodic `SpeechRecognizer` sessions both wanting the microphone/audio stack, or (b) an OEM (ColorOS) background-process management action unrelated to app logic. This item needs a live logcat capture during an actual reproduction, not further static analysis — it can't be confirmed or ruled out from source alone.

**7. System behaves like voice/chat, not an app-governing butler.**
Structural, not a bug: per A3/C2/C8, Claude is not confined to "Intent == CHAT" the way the master architecture specifies — it is a second, parallel executor-invoker for the exact same actions the regex cascade handles, without going through the same confirmation gate. The product feels like "voice talking to an LLM that sometimes does things" rather than "an action engine that occasionally talks" because there is no single pipeline stage where every command — regardless of how it was recognized — passes through the same resolver → executor → confirmation → result flow.

---

## E. Minimal architecture proposal

```
src/core/orchestrator/     — intent detection (wraps existing regex cascade + Claude classification)
src/core/action-engine/    — ActionPlan contracts, dispatch to executors, result handling
src/core/safety/           — Confirmation Gate, hard-blocked action patterns (payments etc.)
src/core/memory/           — contacts/aliases/saved-places/preferences (extends what AsyncStorage already holds)
src/executors/             — one file per action family: appLauncher, navigation, whatsapp, phoneCall,
                              contactResolver (shared by navigation/whatsapp/phoneCall), location, sos
src/services/              — thin wrappers for things executors call but don't own (Linking, expo-contacts,
                              expo-location) — mostly already exist under lib/, would be referenced not rebuilt
```

This does not require a `src/` migration of the existing `lib/agents/*` files — they can be referenced from the new structure rather than moved, per the standing "extend, do not replace" rule.

---

## F. Minimal implementation order

1. **Action Engine contracts** — `ActionPlan`/`ExecutionResult`/`Intent` types; no behavior change yet, just the shared vocabulary everything else plugs into.
2. **App Launcher Executor** — wrap the already-solid `appLauncherAgent.ts` behind the new contract; lowest risk, proves the contract works before touching anything fragile.
3. **Navigation Executor** — split out of `appLauncherAgent.ts`'s `NAV_PATTERN` branch; fix the allowlist-source bug (D1/D2) as part of this, since the executor boundary is the natural place to fix which permission list gates it.
4. **Contact Resolver** — alias table + fuzzy match + disambiguation; both Phone Call and WhatsApp executors depend on this next.
5. **Phone Call Executor** — wire through the resolver; fix `CALL_PATTERN` diacritics.
6. **WhatsApp Executor** — unify the notepad-detected and tool-called paths into one executor reached the same way regardless of phrasing; fix the `OPEN_PATTERN`-swallows-contact-name bug (D3/D4).
7. **Confirmation Gate** — now that three sensitive executors exist, add the single choke point they all call through, and route Claude's tool-use calls through it too (closing the "Claude bypasses confirmation" gap from A3).
8. **Voice-to-Action bridge** — point `handleIncomingText`'s call into `routeCommand` at the new orchestrator/action-engine instead of the raw regex cascade, without changing anything upstream of it (STT, wake word) or downstream of it (TTS, UI).

---

## G. Risk list

- **Android package visibility** — `Linking.canOpenURL` requires the target package to be declared in `<queries>` (already present for the apps in `appRegistry.ts`; any new executor targeting an app not yet in that manifest block will silently report "not installed" even when it is).
- **Wrong/incomplete deep links** — `waze://?q=X&navigate=yes` and `google.navigation:q=X` are correct schemes, but neither is currently verified to have actually launched navigation (D1/D2) — any executor rewrite needs a result-verification step, not just a corrected allowlist.
- **Release vs. debug differences** — confirmed working this session: debug builds depend on a live Metro/`adb reverse` tunnel and can silently "die" in ways release builds don't; any executor testing should happen on the release build already established as the stable baseline (BENSON 17).
- **Contact permission** — `findContact()` calls `Contacts.requestPermissionsAsync()` on every invocation; if denied, callers get a `'no-permission'` sentinel, but nothing currently prompts the user proactively or explains why calls/messages silently fail beyond that string.
- **Foreground service handoff** — the native wake-word loop and any executor that also wants microphone access (none currently do, but a future voice-confirmation flow might) must go through the existing `pauseHotword()`/`resumeHotword()` pair — a new executor must not open its own competing `SpeechRecognizer` session.
- **`Linking` errors swallowed** — nearly every call site in the current code is `Linking.openURL(...).catch(() => {})` — errors are deliberately discarded, which is also why nothing today can distinguish "deep link fired but the target app ignored it" from "deep link genuinely failed." Any executor rewrite needs to stop swallowing this.
- **Uncaught promise rejection** — `openAppEntry()` and friends are `async` functions called without a surrounding try/catch in a few paths (e.g. the tool-execution switch in `tools.ts`); an unexpected throw (not just a rejected `Linking` call) could still surface as an unhandled rejection.
- **`externalAppHandoff` state** — does not exist in this codebase today (confirmed, see D6); if a future executor introduces a "we just handed off to an external app, suppress X until they return" concept, it needs to be built fresh, not assumed to already exist from the unrelated handoff-package files.

---

## H. Out of scope for this task (not mentioned, not touched)

Animated logo, greeting briefing, streaming chat, TTS queue, UI redesign, new wake mode, Gemini fallback — none of these appear anywhere above and none were touched.

---

**File created**: `ACTION_ENGINE_AUDIT.md`
**Zero source files changed** — confirmed via `git status`, identical to before this task except this one new file.
**Next recommended implementation task**: `ACTION_ENGINE_TASK_2 — Contracts + Result Types`, per the order in section F above.
