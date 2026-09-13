# ROUND_WA_PROVIDER_DIAG_1_REPORT

Read-only diagnosis: why `"sună pe Baby pe WhatsApp"` opened **WhatsApp Business** instead of
normal WhatsApp, and did not start the Baby call.

`source modified: NO` · `build: NO` · `patch: NO`

---

## VERDICT

**`IMPLICIT_INTENT_RESOLVED_TO_WHATSAPP_BUSINESS`**

`src/executors/whatsappExecutor.ts` opens the chat with an **implicit** `ACTION_VIEW` on
`https://wa.me/<number>` (via `Linking.openURL`) — **no `setPackage`, no `ComponentName`**. Both
`com.whatsapp` and `com.whatsapp.w4b` are installed, both register the `whatsapp://send` deep-link
activity, and both have `wa.me` as a **verified** app-link domain, with **no user default set**.
Android is therefore free to route the intent to either package; on this device it went to
`com.whatsapp.w4b` (Business — the newer of the two, v2.26.35.73 vs v2.26.34.81). The
"Business, silently" behaviour is already documented in this codebase (`whatsappTool.ts:106-108`,
`benson-app-registry` `openUriWithPackage` comment) and was fixed for the **mission** tool but
never for this **action-engine executor**.

- **NOT** `CONTACT_RESOLUTION_FAILED` — resolution succeeded (see §5); a number was produced and
  put into `wa.me/<number>`.
- **NOT** `WRONG_PACKAGE_HARDCODED` — nothing hardcodes `com.whatsapp.w4b`.
- **NOT** the native WA-FIX-4 direct route — that path is package-explicit and immune (§4).
- `DEFAULT_APP_ASSOCIATION` is a *contributing* device condition (both apps verify `wa.me`, no
  default chosen), but the *code* fault is the implicit intent — with `setPackage("com.whatsapp")`
  the association would be irrelevant.

### Architectural fix: **C) Both**

- **A)** `src/executors/whatsappExecutor.ts` must target `com.whatsapp` explicitly
  (`openUriWithPackage(url, WHATSAPP_PACKAGE)` / `launchPackage(WHATSAPP_PACKAGE)`), exactly the fix
  already applied to `src/core/mission/tools/whatsappTool.ts:106-134`. This alone stops the
  arbitrary Business routing.
- **B)** A provider resolver — `WHATSAPP_NORMAL = com.whatsapp`, `WHATSAPP_BUSINESS = com.whatsapp.w4b`:
  "WhatsApp" → normal; "WhatsApp Business" → Business; if the user says just "WhatsApp" and both are
  installed, use a saved preferred provider or ask **once** — never let Android choose. Needed
  because (i) other WhatsApp entry points (`openApp`, app-registry) also need provider awareness and
  (ii) hardcoding `com.whatsapp` everywhere breaks a user who has *only* Business.

---

## 1. Installed packages (device `9c1464eb`)

| package | role | versionName | launcher activity | `whatsapp://send?phone=` handler | `wa.me` app-link |
|---|---|---|---|---|---|
| `com.whatsapp` | normal WhatsApp | **2.26.34.81** | `com.whatsapp.Main` | `com.whatsapp/.TextAndDirectChatDeepLink` | `wa.me: verified` |
| `com.whatsapp.w4b` | WhatsApp **Business** | **2.26.35.73** (newer) | `com.whatsapp.Main` (same class name, diff package) | `com.whatsapp.w4b/.TextAndDirectChatDeepLink` | `wa.me: verified` |

Implicit-intent resolution (device, `cmd package resolve-activity`):

```
VIEW whatsapp://send?phone=<n>   → android/com.android.internal.app.ResolverActivity   isDefault=false
VIEW https://wa.me/<n>           → android/com.android.internal.app.ResolverActivity   isDefault=false
query-activities  whatsapp://send:
    com.whatsapp/.TextAndDirectChatDeepLink
    com.whatsapp.w4b/.TextAndDirectChatDeepLink
pm get-app-links: both packages → wa.me: verified  (only api.whatsapp.com Disabled; wa.me enabled for BOTH)
pm dumpsys package preferred-activities / domain-preferred-apps: no user default for wa.me / whatsapp://
```

⇒ An implicit `ACTION_VIEW` on either URI is genuinely ambiguous. With no default set, the platform
picks — OxygenOS routed to Business (typically the most-recently-updated verifying app). No chooser
was shown to the user because the platform resolved it silently.

## 2. BENSON route for this command (the path that failed)

```
voice/STT "sună pe Baby pe WhatsApp"
 └─ src/core/action-engine/commandParser.ts  classify()
      WHATSAPP_CALL_PATTERNS match → return { intent:'OPEN_WHATSAPP_CONTACT',
                                              parameters:{ contactName:'Baby', mode:'voice_call' } }   (≈ :296)
      (package: NONE at this stage)
    ── OR, equivalently ──
    src/core/orchestrator/missionOrchestrator.ts  runMission()
      BARE_CHANNEL_REPAIR_PATTERN / pendingClarification kind='whatsapp_contact'
        → createActionRequest({ intent:'OPEN_WHATSAPP_CONTACT', parameters:{ contactName, channel:'whatsapp' } })  (:509-524, :530-551)
 └─ src/core/action-engine/contactActionBridge.ts  enrichContactAction(request, contacts)   (:27)
      preferredChannelFor → 'whatsapp'  (:21,:37)
      resolveContact(...) resolved → request.parameters.phoneNumber = contact.phoneNumbers[0]   (:81)
      (package: still NONE — this only fills in the number)
 └─ src/core/action-engine/appGovernanceEngine.ts  governAction(request, { confirmed:true })   (:50)
 └─ src/core/action-engine/appGovernanceEngine.ts  → dispatchAction(request)   (:51)
 └─ src/core/action-engine/actionDispatcher.ts  DEFAULT_EXECUTORS → WhatsAppExecutor.canHandle('OPEN_WHATSAPP_CONTACT')=true
      → executor.execute(request)   (:34, :84)
 └─ src/executors/whatsappExecutor.ts  execute()
      rawPhone = request.parameters.phoneNumber   (:53)   — non-empty (resolution succeeded)
      digits = sanitizeForWaMe(rawPhone)          (:24, :68)
      url = buildWaMeUrl(digits)  → `https://wa.me/${digits}`   (:27-30, :76)
      request.parameters.mode === 'voice_call'  →  await openDeepLink(url)   (:82-84)   ◄── IMPLICIT
 └─ src/core/action-engine/androidActionExecutor.ts
      openDeepLink(url)  → openUrl('openDeepLink', url)   (:80-81)
      openUrl → await Linking.openURL(url)   (:68-70)      ◄── IMPLICIT ACTION_VIEW, NO setPackage / ComponentName
 └─ Android → ResolverActivity → **com.whatsapp.w4b** (Business). Chat not for Baby's normal-WA
    account; no call button pressed; "Baby call did not start".
```

| stage | file:fn | package explicit? |
|---|---|---|
| parse | `commandParser.ts classify` | n/a (no launch) |
| clarify route | `missionOrchestrator.ts` :509/:530 | n/a |
| enrich contact | `contactActionBridge.ts enrichContactAction` :27 | n/a (fills `phoneNumber` only) |
| govern | `appGovernanceEngine.ts governAction` :50 → `actionDispatcher.dispatchAction` | n/a |
| execute | `whatsappExecutor.ts execute` :82-84 (`openDeepLink`) | **NO** |
| launch | `androidActionExecutor.ts openUrl` :70 (`Linking.openURL`) | **NO — implicit** |

The **other** WhatsApp-call route — `solveProblem` → `problemType=COMMUNICATION_PROBLEM` →
`planMission` → `PREPARE_MESSAGE {mode:'voice_call'}` → `whatsappTool.placeCall` → WA-FIX-4 native
`runWhatsAppOpenConversationCall` — is package-**explicit** (§4) and is what worked in prior device
tests (`WA_DIRECT_*`). This incident took route B instead (the `OPEN_WHATSAPP_CONTACT` /
executor path), most likely because `solveProblem` did not classify the utterance as
`COMMUNICATION_PROBLEM` this time (STT variance / a bare-channel-repair phrasing) and it was routed
through `enrichContactAction` + `governAction` instead.

## 3. Implicit-intent bug — YES

`src/executors/whatsappExecutor.ts`:
- `:34` `await openDeepLink(url)` (`buildWaMeUrl`) — `MESSAGE_CONTACT` / non-`voice_call` `OPEN_WHATSAPP_CONTACT`
- `:59` `return openUrl(request.id, 'WhatsApp', 'whatsapp://')` — `OPEN_WHATSAPP`
- `:84` `const outcome = await openDeepLink(url)` — `OPEN_WHATSAPP_CONTACT` `mode:'voice_call'`  ◄── the one that fired

All three go through `androidActionExecutor.openUrl` → `Linking.openURL` → **no `setPackage`**.
Android may legally route `whatsapp://send` / `https://wa.me/` to `com.whatsapp.w4b`:
- intent filter for `com.whatsapp` : `com.whatsapp/.TextAndDirectChatDeepLink` (scheme `whatsapp`/`whatsapp-consumer`/`whatsapp-sheet`, authority `send`) + `wa.me` verified
- intent filter for `com.whatsapp.w4b` : `com.whatsapp.w4b/.TextAndDirectChatDeepLink` (same) + `wa.me` verified
- current default handler for `wa.me` / `whatsapp://` : **none** (`isDefault=false`, no preferred/domain-preferred entry)
- chooser/default association : none — platform resolves silently, and did so to Business

**The fix for this exact pattern already exists in the codebase** and was applied to the mission
tool, not the executor:
- `src/core/mission/tools/whatsappTool.ts:106-112` — `openUriWithPackage(url, WHATSAPP_PACKAGE)`; comment: *"Explicit-package intent (Intent.setPackage), not a plain implicit ACTION_VIEW — confirmed live 2026-07-17: Linking.openURL('https://wa.me/...') let Android silently pick whichever app is the … turned out to be Business."*
- `src/core/mission/tools/whatsappTool.ts:127-134` — `launchPackage(WHATSAPP_PACKAGE)` for bare open.
- `modules/benson-app-registry/.../BensonAppRegistryModule.kt:144-152` — `openUriWithPackage` → `intent.setPackage(packageName)`. Same comment about Business.

## 4. Native direct-call implementation — NOT the cause

`modules/benson-accessibility/.../BensonAccessibilityService.kt`:

| helper | how it opens WhatsApp | explicit? |
|---|---|---|
| `runWhatsAppOpenConversationCall(phone, expectedName)` | `Intent(ACTION_VIEW, "whatsapp://send?phone=$phone").setPackage(WA_PKG).addFlags(NEW_TASK)` → `startActivity` (`WA_PKG = "com.whatsapp"`) | **YES** — cannot reach Business |
| `runWhatsAppCallNative(name)` → `launchWhatsApp()` | `packageManager.getLaunchIntentForPackage(WHATSAPP_PACKAGE)` (`= "com.whatsapp"`) | **YES** |
| conversation / package verification | `foregroundIsPackage(WA_PKG)` — only `com.whatsapp` passes | Business foreground ⇒ `PACKAGE` / `WHATSAPP_DEEPLINK_FAILED`, never proceeds |
| call-button lookup | `menuitem_call` / `voip_call` / desc `sprachanruf` … | resource-id prefix is `com.whatsapp:id/*` in **both** apps, but the package gate above rejects Business before this runs |

So `com.whatsapp.w4b` **cannot** pass the native path's package/window checks — if the deep link
ever landed on Business, the native executor would `fail("PACKAGE" / "WHATSAPP_DEEPLINK_FAILED")`,
not silently drive Business. The native route is safe; the executor route is not.

## 5. Contact isolation

| check | result |
|---|---|
| resolver found "Baby" | **YES** — `contactActionBridge.enrichContactAction` → `resolveContact({rawName:'Baby', preferredChannel:'whatsapp'})` → `status: 'resolved'`, `phoneNumber = contact.phoneNumbers[0]`. (If it had failed, `WhatsAppExecutor` returns `notFoundResult` and opens nothing.) Prior device runs: `WA_DIRECT_RESOLVE_RESULT status=resolved count=1`. |
| sanitized phone tail | `…8957` (last 4 only; matches the tail seen in earlier `WA_DIRECT_NUMBER_READY name="Baby" tail=8957`) |
| ambiguity | **NO** — `count=1` |
| multi-number | **NO** — single `phoneNumbers[0]` used |

⇒ `CONTACT_RESOLUTION_FAILED` is **ruled out**. This is `WRONG_WHATSAPP_PROVIDER`, downstream of a
correct resolution.

## 6. Device repro (safe — no chat opened, no call)

`cmd package resolve-activity` on bogus numbers (no real contact touched, no `startActivity`):
```
VIEW https://wa.me/10000000009        → android/com.android.internal.app.ResolverActivity  isDefault=false
VIEW whatsapp://send?phone=10000000009 → android/com.android.internal.app.ResolverActivity  isDefault=false
query-activities whatsapp://send → com.whatsapp/.TextAndDirectChatDeepLink + com.whatsapp.w4b/.TextAndDirectChatDeepLink
```
An implicit `ACTION_VIEW` is ambiguous between the two packages with no default → platform choice,
which on this device is Business. A real repro of the full failing flow would require opening Baby's
chat / a real number, which per §6 of the round I did not do. Header verification: n/a — this route
(`whatsappExecutor`) has no header check at all; it just `Linking.openURL`s and reports
`successResult` on `openDeepLink` success regardless of which app opened.

---

## Conceptual rule to encode (product-owner, for the fix round — not implemented here)

- `"WhatsApp"` ⇒ `com.whatsapp` (normal). `"WhatsApp Business"` ⇒ `com.whatsapp.w4b`.
- Both installed + user unspecified ⇒ use the saved preferred provider, else ask **once** and save.
- BENSON must **never** hand `wa.me` / `whatsapp://` to Android as an implicit `ACTION_VIEW` — every
  WhatsApp launch/deeplink goes through `setPackage(<resolved provider>)` (or an explicit
  `ComponentName`).

## Confirm

- source modified: **NO**
- build: **NO**
- patch: **NO**
- git / prebuild: **NO**
- real chat opened / call placed during diagnosis: **NO** (only `cmd package resolve-activity` on bogus numbers, `dumpsys`, `pm`)
