# ROUND_WA_NATIVE_CALL_PROBE_1_REPORT

Feasibility probe: can a native `ContactsContract` WhatsApp voice-call target bypass the
chat / header / call-button UI automation on this device?

**Short answer: YES — the MIME row exists and the typed intent resolves to a dedicated WhatsApp
landing activity. The one thing still unproven (needs a real call) is whether that activity places
the call immediately or shows an intermediate screen.**

Existing WhatsApp call route: **untouched** (probe lives in a different module,
`benson-app-registry`). No Accessibility, no chat-header verify, no `wa.me`, `com.whatsapp` only.

---

## IMPLEMENTED

### New — `modules/benson-app-registry/android/.../WhatsAppNativeCallProbe.kt` (~165 lines)
`object WhatsAppNativeCallProbe.run(context, contactName, doLaunch): ProbeResult`

1. `READ_CONTACTS` check → `WA_NATIVE_CALL_PROBE_FAIL reason=no_read_contacts_permission` if absent.
2. Query `ContactsContract.Data.CONTENT_URI`, projection `_ID, DISPLAY_NAME, MIMETYPE`, selection
   `MIMETYPE = 'vnd.android.cursor.item/vnd.com.whatsapp.voip.call'`
   (`+ AND DISPLAY_NAME LIKE '%<contactName>%'` when a name is given), ordered by `DISPLAY_NAME`.
   First row → `Data._ID` + display name.
3. Independent `ContactsContract.Contacts` name lookup for the `WA_NATIVE_CALL_CONTACT_FOUND` /
   `…_FAIL reason=contact_not_found` line.
4. `dataId == null` → `WA_NATIVE_CALL_MIME_NOT_FOUND` (returns `mimeFound=false`).
   Otherwise `WA_NATIVE_CALL_MIME_FOUND dataId=<id>`.
5. Build `Intent(ACTION_VIEW).setDataAndType(Uri("content://com.android.contacts/data/<id>"),
   MIME).setPackage("com.whatsapp").addFlags(FLAG_ACTIVITY_NEW_TASK)`.
   `packageManager.queryIntentActivities(intent, 0)` → `WA_NATIVE_CALL_INTENT_RESOLVED
   value=<bool> activity=<pkg/activity>`.
6. **`startActivity` only when `doLaunch == true`** (a launch places a REAL call) →
   `WA_NATIVE_CALL_INTENT_LAUNCHED`, or `…_FAIL reason=start_activity_exception:*`.
7. `dataId` is returned to JS for the report and **never written to prefs / cached** — every call
   re-queries it live; the MIME row is never assumed to exist.

All logs under tag `BENSON_AUDIO`, exactly the set the round specified.

### `modules/benson-app-registry/android/.../BensonAppRegistryModule.kt` (+~26 lines)
`AsyncFunction("probeWhatsAppNativeCall") { contactName: String, doLaunch: Boolean, promise -> … }`
→ resolves `{ contactFound, displayName, mimeFound, dataId, intentResolved, intentLaunched,
resolverActivity, fail }`.

### `modules/benson-app-registry/index.js` (+7) · `index.d.ts` (+18)
`probeWhatsAppNativeCall(contactName, doLaunch = false)` + `WhatsAppNativeCallProbeResult` type.

### Not wired into any production path
No change to `whatsappTool.ts`, `runWhatsAppOpenConversationCall`, `runWhatsAppCallNative`,
`app/index.tsx`. The probe is callable only explicitly. Production route not replaced.

---

## BUILD

- `npx tsc --noEmit` → **0 errors**.
- `:app:assembleRelease` → **BUILD SUCCESSFUL in 58s**. APK signed `CN=BENSON, OU=Dev, O=TOKKO`
  (O=TOKKO ✓). `adb install -r` → **Success** on `9c1464eb`, data preserved
  (`lastUpdateTime 2026-09-10 11:21:38`, `firstInstallTime` unchanged), accessibility bound.
- `WhatsAppNativeCallProbe.class` (+ `$ProbeResult`) emitted in the module.

---

## DEVICE RESULT

The BENSON probe function is installed but was **not invoked on-device** this round (no
JS/debug-panel harness can be driven from here, and `doLaunch=true` places a real call). Its own
log chain (`WA_NATIVE_CALL_PROBE_START` …) is therefore **NOT_RUN**.

**However, every fact the probe would report was confirmed directly with read-only `adb` (no call
placed, no activity launched):**

### 1. The MIME row EXISTS on this device — `WA_NATIVE_CALL_MIME_FOUND`
```
adb shell content query --uri content://com.android.contacts/data \
  --projection _id:mimetype:data1 \
  --where "mimetype='vnd.android.cursor.item/vnd.com.whatsapp.voip.call'"
```
→ **461 rows**, one per WhatsApp contact. Each row:
`_id=<n>, mimetype=vnd.android.cursor.item/vnd.com.whatsapp.voip.call, data1=<jid>@s.whatsapp.net`
(example `_id=8681`, `data1=…3352@s.whatsapp.net` — number tail only; full JIDs deliberately not
reproduced here). The sibling `…/vnd.com.whatsapp.video.call` MIME also has 461 rows.

So `ContactsContract.Data` on this device DOES carry a per-contact WhatsApp voice-call target.

### 2. The typed intent RESOLVES — `WA_NATIVE_CALL_INTENT_RESOLVED value=true`
```
adb shell cmd package query-activities -a android.intent.action.VIEW \
  -d content://com.android.contacts/data/8681 \
  -t vnd.android.cursor.item/vnd.com.whatsapp.voip.call
```
→ single resolver, `isDefault=true`, `priority=0`:
```
com.whatsapp / com.whatsapp.accountsync.CallContactLandingActivity
```
Only `com.whatsapp` — **not** `com.whatsapp.w4b` (Business, also installed). No `ResolverActivity`,
no chooser. With `setPackage("com.whatsapp")` added (as the probe does) the match is unambiguous.

### 3. Environment
- `com.whatsapp` `versionName=2.26.35.75` `versionCode=263507522` (note: newer than the
  `2.26.34.81` recorded in earlier rounds — WhatsApp auto-updated).
- `com.whatsapp.w4b` also installed.
- `com.benson.butler` `READ_CONTACTS granted=true`.
- App `<queries>` includes `ACTION_MAIN/CATEGORY_LAUNCHER`, so `com.whatsapp` is visible to
  `PackageManager` and the probe's `queryIntentActivities` result is trustworthy.

### What is still UNPROVEN (needs one careful real launch)
`com.whatsapp.accountsync.CallContactLandingActivity` — the name implies a landing/dispatch
activity that starts the outgoing voice call. Whether it:
- (a) **places the call immediately** (the desired bypass — no chat, no header, no call button), or
- (b) shows a confirm / lands on the contact card first,

cannot be determined without launching it, which **is a real WhatsApp voice call**. That is the
single remaining question for the `doLaunch=true` device test.

### To finish the probe on-device (you)
`adb logcat -c` → `adb logcat -v time BENSON_AUDIO:I ReactNativeJS:I *:S`, then from a JS context
(debug panel / a temporary dev button):
```js
await probeWhatsAppNativeCall('Baby', false);   // capability only — expect intentResolved:true, no call
await probeWhatsAppNativeCall('Baby', true);    // REAL CALL — have the callee expecting it, hang up at once
```
PASS for the bypass hypothesis = after `doLaunch=true`: `WA_NATIVE_CALL_INTENT_LAUNCHED` then the
WhatsApp **call screen** for the contact appears with **no** chat view and **no** call-button tap
in between.

---

## Recommendation (not acted on — round said stop after diagnosis)

The bypass is viable at the two layers that were in doubt (row present, intent resolves to a
dedicated activity). If the `doLaunch=true` test confirms it dials directly, this route would
remove the entire fragile middle of `runWhatsAppOpenConversationCall` (foreground settle → `entry`
wait → `conversation_contact_name` poll → call-button cascade) and its known non-determinism
(`ROUND_WA_HEADER_*`). Identity verification would move to "the `Data._ID` we resolved belongs to
the contact we resolved" (a local DB fact) instead of screen-scraping a header. Keep the current
route as the fallback until the direct route passes 5/5.

## Confirm
- existing WhatsApp call route modified: **NO** (probe is in `benson-app-registry`, separate module)
- Accessibility click / chat-header verify / wa.me used by the probe: **NO**
- target package: `com.whatsapp` only
- `Data._ID` persisted/cached: **NO** (re-queried every call)
- real call placed during this round: **NO** (only `content query` + `cmd package query-activities`,
  both read-only)
- tsc: PASS · release build: PASS (`O=TOKKO`) · installed: YES (data preserved)
- git / prebuild / setx: NO
- probe's own device invocation: **NOT_RUN** — underlying feasibility facts confirmed via adb; the
  direct-dial behaviour of `CallContactLandingActivity` is the one open item, hand-off above
