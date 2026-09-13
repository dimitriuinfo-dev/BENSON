// BENSON Action Engine — App Launcher Executor.
// Deterministic, allowlist-based app opening. For every named app: check installed -> launch via
// PackageManager -> log every step. No scheme/web-fallback guessing — if the package isn't on
// the device, say so clearly instead of silently falling back to a browser tab.
//
// CLOSE_APP is also handled here (realistic MVP scope only): there is no reliable way to force-
// close a third-party app without Accessibility/Device Owner/root, so this always means "bring
// BENSON back to the front" — and the reply must say exactly that, never "I closed X".

import { logAudioDiag } from 'benson-foreground-service';
import { executeCommand, isServiceEnabled as isAccessibilityConnected } from 'benson-accessibility';
import {
  loadAppIndex,
  resolveAppQuery,
  matchApps,
  listAppNames,
  normalizeName,
  type AppMatch,
  type IndexedApp,
} from '../../lib/appIndex';
import {
  isPackageInstalled,
  launchPackage,
  dial,
  sendTo,
  bringBensonToForeground,
  getForegroundPackage,
  waitForPackageForeground,
} from '../core/action-engine/androidActionExecutor';
import {
  findAppRegistryEntry,
  isPhoneRequest,
  isSmsRequest,
  isRadioRequest,
  isMusicRequest,
  RADIO_NAME_HINTS,
  MUSIC_NAME_HINTS,
} from '../core/action-engine/appRegistry';
import type { ActionIntent, ActionRequest, ActionResult, Executor } from '../core/action-engine';
import {
  successResult,
  notFoundResult,
  failedResult,
  unsupportedResult,
  needsDisambiguationResult,
} from '../core/action-engine';

const LOG_TAG = '[AppLauncherExecutor]';

function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

const HANDLED_INTENTS: ActionIntent[] = [
  'OPEN_APP',
  'CLOSE_APP',
  'RETURN_TO_BENSON',
  'OPEN_WAZE',
  'OPEN_GOOGLE_MAPS',
  'OPEN_WHATSAPP',
  'MEDIA_PLAY',
];

async function findInstalledAppsByNameHint(hints: string[]): Promise<IndexedApp[]> {
  const installed = await loadAppIndex();
  const nHints = hints.map((h) => normalizeName(h)).filter(Boolean);
  return installed.filter((a) => {
    const n = normalizeName(a.appName);
    return nHints.some((h) => n.includes(h));
  });
}

// ── App-index resolution shared by OPEN_APP / CLOSE_APP / category (radio, music) ──────────────
// The device's real launcher list (loadAppIndex / benson-app-registry) is the primary source;
// the curated APP_REGISTRY is only an alias layer on top (findAppRegistryEntry below). No path
// ever ends in a bare "nu găsesc" — a miss becomes a proposal or the honest zero-match line.

function toCandidates(apps: IndexedApp[]): { name: string; packageName: string }[] {
  return apps.map((a) => ({ name: a.appName, packageName: a.packageName }));
}

// Turn a fuzzy AppMatch into the right ActionResult for an "open" request.
function openResultFromMatch(
  requestId: string,
  query: string,
  m: AppMatch,
  launch: (name: string, pkg: string) => Promise<ActionResult>,
): Promise<ActionResult> | ActionResult {
  if (m.kind === 'exact') return launch(m.app.appName, m.app.packageName);
  if (m.kind === 'single') {
    // ROUND_EXECUTION_PIPELINE_DIAG_1 — a real, on-device-confirmed stop point: a 'single' (not
    // 'exact') match never reaches LAUNCH_REQUEST at all this turn — it stops here and waits for
    // a yes/no reply. See missionOrchestrator.ts's matchDisambiguationPick() for whether that
    // reply is actually recognized (traced separately — this file only knows it asked).
    logAudioDiag('EXEC_TRACE_FAILURE', `stage=APP_RESOLUTION package=${JSON.stringify(m.app.packageName)} reason=SINGLE_MATCH_NEEDS_CONFIRMATION query=${JSON.stringify(query)}`);
    return needsDisambiguationResult(requestId, `Am găsit ${m.app.appName}. O deschid?`, {
      candidates: toCandidates([m.app]),
    });
  }
  if (m.kind === 'multiple') {
    return needsDisambiguationResult(
      requestId,
      `Am găsit mai multe: ${listAppNames(m.apps)}. Pe care s-o deschid?`,
      { candidates: toCandidates(m.apps) },
    );
  }
  return notFoundResult(requestId, `Nu am nicio aplicație instalată care să semene cu ${query}.`);
}

// Radio/music are CATEGORIES: even a single candidate is proposed, never auto-opened (a concrete
// app name is handled before this by the exact-match branch in OPEN_APP).
function categoryResult(
  requestId: string,
  categoryLabelZero: string,
  apps: IndexedApp[],
): ActionResult {
  if (apps.length === 0) return notFoundResult(requestId, categoryLabelZero);
  if (apps.length === 1) {
    return needsDisambiguationResult(requestId, `Am găsit ${apps[0].appName}. O deschid?`, {
      candidates: toCandidates(apps),
    });
  }
  return needsDisambiguationResult(
    requestId,
    `Am găsit mai multe: ${listAppNames(apps.slice(0, 3))}. Pe care s-o deschid?`,
    { candidates: toCandidates(apps.slice(0, 3)) },
  );
}

type RadioResolution =
  | { kind: 'exact'; name: string; packageName: string } // a concrete named station -> open direct
  | { kind: 'found'; name: string; packageName: string } // one radio-category candidate -> propose
  | { kind: 'none' }
  | { kind: 'ambiguous'; candidates: { name: string; packageName: string }[] };

// Radio needs richer resolution than a first-match lookup: real radio apps are almost always
// named after a station/brand (TuneIn, Antena, Kiss FM, ...) rather than containing the literal
// word "radio", and a specific station named in the utterance ("Radio România Actualități")
// should launch THAT app, not an arbitrary radio candidate. Matching runs through the shared
// diacritic-insensitive fuzzy matcher over the device's real launcher list.
async function resolveRadioTarget(stationName?: string): Promise<RadioResolution> {
  const installed = await loadAppIndex();

  // Only treat this as a SPECIFIC station name if it's more than just the bare word "radio" —
  // otherwise "radio"/"radioul" would match whichever radio app happens to come first, silently
  // skipping disambiguation when there are several.
  const isBareRadioWord = /^radio(?:ul)?$/i.test((stationName ?? '').trim());
  if (stationName && stationName.trim() && !isBareRadioWord) {
    const m = matchApps(stationName, installed);
    if (m.kind === 'exact') return { kind: 'exact', name: m.app.appName, packageName: m.app.packageName };
    if (m.kind === 'single') return { kind: 'found', name: m.app.appName, packageName: m.app.packageName };
    // Named station isn't a clear hit — fall through to the general radio-candidate search below.
  }

  const nHints = RADIO_NAME_HINTS.map((h) => normalizeName(h)).filter(Boolean);
  const candidates = installed
    .filter((a) => { const n = normalizeName(a.appName); return nHints.some((h) => n.includes(h)); })
    .map((a) => ({ name: a.appName, packageName: a.packageName }));

  if (candidates.length === 0) return { kind: 'none' };
  if (candidates.length === 1) return { kind: 'found', name: candidates[0].name, packageName: candidates[0].packageName };
  return { kind: 'ambiguous', candidates };
}

async function resolveAndLaunchRadio(requestId: string, stationName?: string): Promise<ActionResult> {
  const resolution = await resolveRadioTarget(stationName);
  devLog('radio resolution ->', resolution);
  logAudioDiag(
    'APP_MATCH',
    `intent=radio query=${JSON.stringify(stationName ?? '')} candidates=${resolution.kind === 'ambiguous' ? resolution.candidates.length : resolution.kind === 'none' ? 0 : 1} chosen=${JSON.stringify(resolution.kind === 'exact' || resolution.kind === 'found' ? resolution.name : resolution.kind === 'ambiguous' ? resolution.candidates.map((c) => c.name).join('|') : '')} asked=${resolution.kind === 'found' || resolution.kind === 'ambiguous'}`,
  );

  if (resolution.kind === 'none') {
    return notFoundResult(requestId, 'Nu am nicio aplicație radio instalată.');
  }
  if (resolution.kind === 'ambiguous') {
    const names = resolution.candidates.slice(0, 3).map((c) => c.name).join(', ');
    return needsDisambiguationResult(
      requestId,
      `Am găsit mai multe aplicații radio: ${names}. Pe care s-o deschid?`,
      { candidates: resolution.candidates.slice(0, 3) },
    );
  }
  if (resolution.kind === 'found') {
    // Category match — propose, never auto-open (directive 4).
    return needsDisambiguationResult(requestId, `Am găsit ${resolution.name}. O deschid?`, {
      candidates: [{ name: resolution.name, packageName: resolution.packageName }],
    });
  }
  // Concrete named station -> open directly.
  return launchAllowlisted(requestId, resolution.name, resolution.packageName);
}

// Music mirror of resolveAndLaunchRadio: a concrete app name ("Spotify") opens directly, the bare
// category word ("muzică") is proposed from the installed music apps, never auto-opened.
async function resolveAndLaunchMusic(requestId: string, rawName?: string): Promise<ActionResult> {
  const name = (rawName ?? '').trim();
  const isBareMusicWord = /^(muzica|music)$/.test(normalizeName(name));
  if (name && !isBareMusicWord) {
    const m = await resolveAppQuery(name, 'music');
    if (m.kind !== 'none') {
      return openResultFromMatch(requestId, name, m, (n, p) => launchAllowlisted(requestId, n, p));
    }
    // no concrete hit -> fall through to the music-category candidates
  }
  const musicApps = await findInstalledAppsByNameHint(MUSIC_NAME_HINTS);
  devLog('music category lookup ->', musicApps.map((a) => a.appName));
  logAudioDiag(
    'APP_MATCH',
    `intent=music query=${JSON.stringify(name)} candidates=${musicApps.length} chosen=${JSON.stringify(musicApps.slice(0, 3).map((a) => a.appName).join('|'))} asked=${musicApps.length > 0}`,
  );
  return categoryResult(requestId, 'Nu am nicio aplicație muzicală instalată.', musicApps);
}

const BENSON_PACKAGE = 'com.benson.butler';

// E1-1 (2026-09-07, product-owner-directed): BENSON nu se mai ridică singur în prim-plan pe calea
// de lansare. Nu se mai apelează bringBensonToForeground() înainte de a deschide aplicația țintă,
// și nu se mai pierd cele 3 secunde pe waitForPackageForeground(BENSON, 3000) (care oricum
// raporta reached=false constant, fără Accessibility legat de fereastra BENSON). RETURN_TO_BENSON
// / CLOSE_APP (bringBensonBack, mai jos) NU sunt afectate — acolo utilizatorul a cerut explicit
// revenirea. Revert: E1_LAUNCH_NO_SELF_FOREGROUND = false.
const E1_LAUNCH_NO_SELF_FOREGROUND = true;

// Deterministic install -> launch -> verify protocol, no LLM/reasoning involved. Never guesses a
// web fallback — if it isn't installed, the caller gets a clear "not found" result. Logs every
// step in the exact shape requested for the Waze-launch diagnostic, printed via console.log so
// it's visible directly in logcat without depending on the Debug Panel.
//
// Both waits below (2026-07-09) are event-gated on the AccessibilityService's real
// TYPE_WINDOW_STATE_CHANGED signal (waitForPackageForeground), not a fixed delay tuned by
// guessing. A fixed sleep can't distinguish "transition already finished" from "transition still
// in flight" — it either wastes time or fires too early depending on device load, which is
// exactly the "lottery draw" behavior this replaces. The bounded timeout (3s) still applies so a
// disabled/disconnected Accessibility Service can't hang the flow; a timeout here is reported as
// unconfirmed, the same as before, never silently upgraded to success.
async function launchAllowlisted(requestId: string, name: string, packageName: string): Promise<ActionResult> {
  // ROUND_EXECUTION_PIPELINE_DIAG_1 — this whole function already computed every one of these
  // facts (see the devLog calls throughout, unchanged below); they just never reached the real
  // device log (devLog is console.log — invisible via `adb logcat BENSON_AUDIO:I` in a release
  // build). logAudioDiag mirrors the SAME data onto the tag that's actually captured on-device.
  logAudioDiag('EXEC_TRACE_TARGET', `name=${JSON.stringify(name)} package=${JSON.stringify(packageName)}`);
  const installed = isPackageInstalled(packageName);
  devLog('detected package=', packageName, 'installed=', installed);

  if (!installed) {
    devLog('result', { detectedPackage: packageName, launchAttempted: false, foregroundAfter: null, success: false, error: 'PACKAGE_NOT_INSTALLED' });
    logAudioDiag('EXEC_TRACE_FAILURE', `stage=APP_RESOLUTION package=${JSON.stringify(packageName)} reason=PACKAGE_NOT_INSTALLED`);
    return notFoundResult(requestId, `${name} nu este instalată pe telefon.`);
  }

  // E1-1: the old pre-launch "bring BENSON's own MainActivity to front, then wait up to 3s for it
  // to be confirmed foreground" step is gated OFF. It was meant to satisfy Android's "caller has a
  // visible activity" BAL exemption, but on this device the wait reached=false essentially every
  // time (no Accessibility signal tied to BENSON's own window) — 3s of dead time per action for no
  // measured benefit — and it directly contradicts "BENSON nu se ridică singur". The target-app
  // foreground check AFTER launch (below) is unchanged and still governs the honest success reply.
  if (!E1_LAUNCH_NO_SELF_FOREGROUND) {
    bringBensonToForeground();
    const bensonForeground = await waitForPackageForeground(BENSON_PACKAGE, 3000);
    devLog('benson-foreground confirmed before launch=', bensonForeground.reached, 'lastSeen=', bensonForeground.lastSeen);
  } else {
    devLog('E1-1: skipping self-foreground + 3s pre-launch wait');
  }

  logAudioDiag('EXEC_TRACE_LAUNCH_REQUEST', `package=${JSON.stringify(packageName)} mechanism=nativeLaunchApp(PackageManager)`);
  const outcome = launchPackage(packageName, 'AppLauncherExecutor:launchAllowlisted');
  devLog('launch intent created + startActivity accepted=', outcome.success, 'error=', outcome.error);
  logAudioDiag('EXEC_TRACE_LAUNCH_RESULT', `package=${JSON.stringify(packageName)} startActivityAccepted=${outcome.success} error=${JSON.stringify(outcome.error ?? '')}`);

  if (!outcome.success) {
    devLog('result', { detectedPackage: packageName, launchAttempted: true, foregroundAfter: null, success: false, error: outcome.error ?? 'LAUNCH_FAILED' });
    logAudioDiag('EXEC_TRACE_FAILURE', `stage=LAUNCH_ACTION package=${JSON.stringify(packageName)} reason=${JSON.stringify(outcome.error ?? 'LAUNCH_FAILED')}`);
    return failedResult(requestId, `Nu am putut deschide ${name}.`, {
      errorCode: 'LAUNCH_FAILED',
      errorDetails: outcome.error,
    });
  }

  const targetForeground = await waitForPackageForeground(packageName, 3000);
  const foregroundAfter = targetForeground.reached ? packageName : (targetForeground.lastSeen ?? getForegroundPackage());
  devLog('foreground app after launch=', foregroundAfter, 'confirmedByEvent=', targetForeground.reached);
  logAudioDiag('EXEC_TRACE_FOREGROUND_VERIFY', `expected=${JSON.stringify(packageName)} observed=${JSON.stringify(foregroundAfter)} confirmedByEvent=${targetForeground.reached}`);

  if (foregroundAfter === null) {
    // Reverted (2026-07-09): briefly reported this as successResult (see git history) reasoning
    // that BAL_ALLOW_VISIBLE_WINDOW + no Accessibility meant the launch was real, just unverified.
    // Live testing immediately disproved that: BENSON claimed "Am deschis WhatsApp" and it never
    // actually appeared on screen — startActivity() being *accepted* does not mean the app
    // actually reached the foreground; ColorOS can still silently swallow it afterward (e.g. its
    // own per-app background-launch permission), and Accessibility being off means we truly
    // cannot tell the two cases apart. Claiming success here was a false positive, worse than the
    // honest-but-gloomy 'unsupported' it replaced — reverted to that. The Mission Orchestrator
    // marking this SKIPPED (and the mission FAILED) is a separate, lower-stakes cosmetic issue;
    // never sacrifice truthfulness in what BENSON tells the user for a nicer-looking status label.
    devLog('result', { detectedPackage: packageName, launchAttempted: true, foregroundAfter: null, success: 'unverified', error: 'ACCESSIBILITY_NOT_ENABLED' });
    logAudioDiag('EXEC_TRACE_FAILURE', `stage=FOREGROUND_VERIFICATION package=${JSON.stringify(packageName)} reason=ACCESSIBILITY_NOT_ENABLED`);
    return unsupportedResult(
      requestId,
      `Am încercat să deschid ${name}, dar nu pot verifica dacă s-a deschis (Accessibility Service dezactivat în Settings).`,
    );
  }

  const success = foregroundAfter === packageName;
  devLog('result', { detectedPackage: packageName, launchAttempted: true, foregroundAfter, success, error: success ? undefined : 'FOREGROUND_MISMATCH' });

  if (success) {
    return successResult(requestId, `Deschid ${name}.`, { appOpened: name });
  }
  logAudioDiag('EXEC_TRACE_FAILURE', `stage=FOREGROUND_VERIFICATION package=${JSON.stringify(packageName)} reason=FOREGROUND_MISMATCH observed=${JSON.stringify(foregroundAfter)}`);
  return failedResult(requestId, `Am încercat să deschid ${name}, dar aplicația din prim-plan este încă ${foregroundAfter}.`, {
    errorCode: 'FOREGROUND_MISMATCH',
    errorDetails: `expected ${packageName}, got ${foregroundAfter}`,
  });
}

function bringBensonBack(requestId: string, successMessage: string): ActionResult {
  const outcome = bringBensonToForeground();
  devLog('bringBensonToForeground ->', outcome);
  if (outcome.success) return successResult(requestId, successMessage, { appOpened: 'BENSON' });
  return failedResult(requestId, 'Nu am putut reveni în Benson.', {
    errorCode: 'BRING_TO_FOREGROUND_FAILED',
    errorDetails: outcome.error,
  });
}

// ── CLOSE_APP (real close, not just "return to Benson") ──────────────────────────────────────
// Android gives a normal app NO force-stop API and the Recents-swipe trick needs
// canPerformGestures (which OxygenOS anti-spyware auto-disables the whole service for). The one
// reliable, gesture-free path is: open the target's system "App info" screen, then tap the OEM's
// "Force stop"/"Forțează oprirea" button + its confirmation via Accessibility. Labels differ by
// OEM/locale, so we try several (both Romanian t-comma/t-cedilla spellings + English).
const FORCE_STOP_LABELS = [
  'forțează oprirea', 'forţează oprirea', 'oprire forțată', 'oprire forţată',
  'închide forțat', 'oprește forțat', 'force stop',
];
const CONFIRM_LABELS = [
  'forțează oprirea', 'forţează oprirea', 'force stop', 'ok', 'da',
];

// CLOSE_APP resolution — curated alias first (exact), then the shared fuzzy matcher over the real
// launcher list. Returns an AppMatch so closeApp() can propose ("O închid?" / "pe care?") instead
// of ever saying it can't find the app.
async function resolveCloseTarget(name: string): Promise<AppMatch> {
  const entry = findAppRegistryEntry(name);
  if (entry?.packageName) {
    const installed = await loadAppIndex();
    const hit = installed.find((a) => a.packageName === entry.packageName);
    return { kind: 'exact', app: hit ?? { packageName: entry.packageName, appName: entry.name } };
  }
  return resolveAppQuery(name, 'close');
}

// Tries each candidate label in turn (own executeCommand each) — returns true on the first tap
// that lands. Best-effort: a label simply not being on screen is not an error here.
async function tryClickAny(labels: string[], timeoutMs: number): Promise<boolean> {
  for (const label of labels) {
    try {
      const command = { steps: [{ action: 'click', match: { textContains: label, clickable: true, clickableAncestor: true }, timeoutMs }] };
      const r = await executeCommand(command as any);
      if ((r as { success?: boolean })?.success) return true;
    } catch {
      // try the next label
    }
  }
  return false;
}

async function closeApp(requestId: string, targetName: string): Promise<ActionResult> {
  const label = targetName || 'aplicația';
  let connected = false;
  try { connected = await isAccessibilityConnected(); } catch {}
  if (!connected) {
    return bringBensonBack(requestId, `Ca să închid ${label}, activează întâi Serviciul de Accesibilitate. Am revenit la tine.`);
  }
  const match = await resolveCloseTarget(targetName);
  if (match.kind === 'none') {
    return bringBensonBack(requestId, `Nu am nicio aplicație instalată care să semene cu ${label}. Am revenit la tine.`);
  }
  if (match.kind === 'single') {
    return needsDisambiguationResult(requestId, `Am găsit ${match.app.appName}. Pe asta s-o închid?`, {
      candidates: toCandidates([match.app]),
    });
  }
  if (match.kind === 'multiple') {
    return needsDisambiguationResult(
      requestId,
      `Am găsit mai multe: ${listAppNames(match.apps)}. Pe care s-o închid?`,
      { candidates: toCandidates(match.apps) },
    );
  }
  const resolved = { name: match.app.appName, packageName: match.app.packageName };
  devLog('CLOSE_APP via force-stop, pkg=', resolved.packageName);
  // 1) open the app's system App-info screen
  try {
    const openCmd = { steps: [{ action: 'open_app_settings', package: resolved.packageName }, { action: 'wait', ms: 1100 }] };
    await executeCommand(openCmd as any);
  } catch {}
  // 2) tap "Force stop"
  const tapped = await tryClickAny(FORCE_STOP_LABELS, 5000);
  if (!tapped) {
    return bringBensonBack(
      requestId,
      `Am deschis setările pentru ${resolved.name}, dar nu am găsit butonul de oprire forțată. Apasă tu „Forțează oprirea". Am revenit la tine.`,
    );
  }
  // 3) confirm the dialog (best-effort — some phones stop immediately, no dialog)
  await new Promise((res) => setTimeout(res, 500));
  await tryClickAny(CONFIRM_LABELS, 2500);
  // 4) come back to BENSON with an honest success message
  return bringBensonBack(requestId, `Am închis ${resolved.name}.`);
}

export const AppLauncherExecutor: Executor = {
  name: 'AppLauncherExecutor',

  canHandle(intent: ActionIntent): boolean {
    return HANDLED_INTENTS.includes(intent);
  },

  async execute(request: ActionRequest): Promise<ActionResult> {
    devLog('execute', request.intent, request.parameters);
    logAudioDiag('EXEC_TRACE_EXECUTOR', `executor=AppLauncherExecutor intent=${request.intent} params=${JSON.stringify(request.parameters)}`);

    if (request.intent === 'RETURN_TO_BENSON') {
      return bringBensonBack(request.id, 'Am revenit în Benson.');
    }

    if (request.intent === 'CLOSE_APP') {
      const target = typeof request.parameters.appName === 'string' ? request.parameters.appName : '';
      devLog('CLOSE_APP target=', target, 'method=FORCE_STOP');
      return closeApp(request.id, target);
    }

    if (request.intent === 'OPEN_WAZE') return launchAllowlisted(request.id, 'Waze', 'com.waze');
    if (request.intent === 'OPEN_GOOGLE_MAPS') {
      return launchAllowlisted(request.id, 'Google Maps', 'com.google.android.apps.maps');
    }
    if (request.intent === 'OPEN_WHATSAPP') return launchAllowlisted(request.id, 'WhatsApp', 'com.whatsapp');

    if (request.intent === 'MEDIA_PLAY') {
      const mediaType = request.parameters.mediaType === 'radio' ? 'radio' : 'music';
      if (mediaType === 'radio') {
        const stationName = typeof request.parameters.stationName === 'string' ? request.parameters.stationName : undefined;
        return resolveAndLaunchRadio(request.id, stationName);
      }
      return resolveAndLaunchMusic(request.id);
    }

    if (request.intent === 'OPEN_APP') {
      const appName = typeof request.parameters.appName === 'string' ? request.parameters.appName : '';
      devLog('OPEN_APP appName param', appName);
      if (!appName.trim()) return notFoundResult(request.id, 'Nu ai spus ce aplicație să deschid.');

      if (isPhoneRequest(appName)) {
        const outcome = await dial('');
        if (outcome.success) return successResult(request.id, 'Deschid telefonul.', { appOpened: 'Phone' });
        return failedResult(request.id, 'Nu am putut deschide telefonul.', {
          errorCode: 'LINKING_OPEN_URL_ERROR',
          errorDetails: outcome.error,
        });
      }

      if (isSmsRequest(appName)) {
        const outcome = await sendTo('');
        if (outcome.success) return successResult(request.id, 'Deschid mesajele.', { appOpened: 'SMS' });
        return failedResult(request.id, 'Nu am putut deschide mesajele.', {
          errorCode: 'LINKING_OPEN_URL_ERROR',
          errorDetails: outcome.error,
        });
      }

      if (isRadioRequest(appName)) {
        return resolveAndLaunchRadio(request.id, appName);
      }

      if (isMusicRequest(appName)) {
        return resolveAndLaunchMusic(request.id, appName);
      }

      // Curated registry is now only an ALIAS layer over the device's real launcher list:
      // "harta" -> Google Maps, "oaza" -> Waze, etc. A hit here (with the package actually
      // installed) opens directly; a miss falls through to the fuzzy index match, never to a
      // "nu găsesc" dead end.
      const alias = findAppRegistryEntry(appName);
      if (alias?.packageName && isPackageInstalled(alias.packageName)) {
        return launchAllowlisted(request.id, alias.name, alias.packageName);
      }
      const m = await resolveAppQuery(appName, 'open');
      return openResultFromMatch(request.id, appName, m, (n, p) => launchAllowlisted(request.id, n, p));
    }

    return unsupportedResult(request.id, `AppLauncherExecutor does not handle ${request.intent}.`);
  },
};
