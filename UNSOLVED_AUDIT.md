# BENSON — Generic app-automation audit (for second opinion / Codex)

Scope, per product owner: not a list of individual bugs — the underlying, generic question of
**how BENSON should operate other apps on the phone so it works reliably, for any app, without
needing a bespoke fix every time.** Concrete WhatsApp/ColorOS incidents are included only as
evidence of the generic problem, not as the thing to fix one-off.

---

## The core problem

Android has no single API for "operate any app the way a human would." Three approaches exist,
and BENSON currently mixes all three ad hoc, app by app:

1. **Official deep links / intents** (`Intent.ACTION_CALL`, `wa.me/...`, `geo:`/Waze URIs). Fast,
   reliable, OS-supported — but only covers what each app's developers chose to expose. Most apps
   expose almost nothing this way (open-to-a-screen at best; no "send this exact message" or
   "tap this specific button" primitive for arbitrary apps).
2. **Accessibility-service UI automation** — read the screen tree, find a node by label/viewId,
   perform a click/text-set action. This is the *only* way to do things no deep link exists for
   (typing a search query into WhatsApp, tapping its call button, tapping "send"). It is how
   BENSON's one fully-automated app (WhatsApp) works today
   (`modules/benson-accessibility/android/.../BensonAccessibilityService.kt`).
3. **No automation** — just launch the app and hand off to the user (what BENSON does for every
   app that isn't WhatsApp today).

Approach 2 is the only one capable of real hands-free operation, and it is fundamentally
**non-generic**: every function in `BensonAccessibilityService.kt` (`placeWhatsAppCall`,
`pressWhatsAppSend`, `endWhatsAppCall`, `muteWhatsAppCall`) is hand-written against WhatsApp's
*current* screen layout — exact button labels (`"sprachanruf"`, `"senden"`, in German, on this
device's language setting), exact view IDs (`com.whatsapp:id/search_input`,
`contact_row_container`), exact navigation sequence (open → tap search icon → type → wait for
result → tap → wait for chat → find call button). None of that logic transfers to any other app.
To make BENSON operate, say, Google Maps, Gmail, or a banking app the same hands-free way, someone
has to reverse-engineer that app's current screen tree by hand and write a new, equally brittle,
app-specific Kotlin function — and repeat it again whenever that app's UI changes.

**This is the generic question worth a second opinion on: is there a way to make step 2 (UI
automation) generalize across apps, instead of being re-derived by hand per app, per language, per
OS skin, per app version?**

---

## Why the current approach doesn't scale

- **Per-app, hand-written flows.** Adding WhatsApp calling took an entire multi-hour live-debug
  session (this one) to get right, iterating against real on-device accessibility dumps. There is
  no shortcut version of that process today — the same investment would be needed for every
  additional app.
- **Language-dependent.** Button labels are matched as literal strings (`"sprachanruf"` = German
  for "voice call"). If the user's phone language changes, or WhatsApp changes its translation,
  matching silently breaks. Nothing here is locale-aware by design — it happens to work because
  this one phone is set to German UI language.
- **Version-fragile.** Any WhatsApp update can rename a view ID or restructure the screen tree with
  zero warning; BENSON only finds out live, via a user's failed voice command, the same way every
  bug this session was found (manual `adb logcat` capture during a real repro). There's no
  automated way to detect "WhatsApp changed, BENSON's automation for it is now stale" before a real
  user hits it.
- **OEM-dependent on top of that.** This device runs ColorOS (OPPO/Realme/OnePlus), which throttles
  BENSON's JS thread the instant another app takes the foreground (confirmed live — this is *why*
  the WhatsApp flow had to be rewritten as a native Kotlin state machine instead of JS-driven
  polling), layers its own "flexible window" system on top of standard Android Picture-in-Picture,
  and aggressively manages background processes in ways that required a bespoke
  cooldown/resurrection guard (`BensonAccessibilityService.maybeResurrect()`,
  120s cooldown, tuned against one observed failure, not a documented OS contract). None of this
  has been tested against stock Android or a different OEM skin (Samsung/Xiaomi/etc.) — it's
  entirely possible the ColorOS-specific workarounds are wrong, unnecessary, or insufficient
  elsewhere.
- **No regression safety net.** There is no automated test of any accessibility flow — every
  verification this session was: change code, rebuild (sometimes a native rebuild), have the user
  speak a live command, capture logcat, read the trace. A change to one app's automation can't be
  checked against a suite; it can only be caught by a human hitting it live.

---

## What a generic solution might need (open question, not decided)

Directions that haven't been evaluated, worth a second opinion on:

- **Declarative per-app "profiles"** instead of hand-written Kotlin per app — a config describing
  "search field selector," "result-row selector," "send-button selector" per app, so adding a new
  app is data entry instead of new native code. Doesn't solve fragility to UI changes, but at least
  makes the *authoring* step generic.
- **Fuzzy/structural node matching** instead of literal label strings — e.g. "the single editable
  text field near the top of the screen" instead of matching a specific viewId, so language changes
  don't break matching. Some of this already exists (`containsWholeWord`, avatar-node exclusion in
  `BensonAccessibilityService.kt`) but it's still fundamentally per-app logic, not a general
  heuristic reusable for a new app.
- **Prefer official integration points wherever they exist** (Android App Actions / Shortcuts API,
  any app-specific SDK/API) over UI automation, and only fall back to accessibility automation for
  the remainder — hasn't been surveyed app-by-app for which of BENSON's target apps actually expose
  anything usable this way.
- **Automated UI-change detection** — some kind of scheduled/CI check that walks each automated
  app's current screen tree and flags when expected selectors go missing, instead of finding out
  from a live user failure.
- **A real device/OEM test matrix** — before claiming an automation flow "works," define what set
  of Android versions/OEM skins/languages it's actually been verified against, since none of that
  has been tracked so far.

None of the above has been designed or committed to — this section is deliberately a list of
options, not a plan.
