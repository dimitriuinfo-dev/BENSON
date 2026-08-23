// BENSON Action Engine — App Launcher Executor.
// Deterministic, allowlist-based app opening. For every named app: check installed -> launch via
// PackageManager -> log every step. No scheme/web-fallback guessing — if the package isn't on
// the device, say so clearly instead of silently falling back to a browser tab.
//
// CLOSE_APP is also handled here (realistic MVP scope only): there is no reliable way to force-
// close a third-party app without Accessibility/Device Owner/root, so this always means "bring
// BENSON back to the front" — and the reply must say exactly that, never "I closed X".

import { getInstalledApps } from 'benson-app-registry';
import { executeCommand, isServiceEnabled as isAccessibilityConnected } from 'benson-accessibility';
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

async function findInstalledAppByNameHint(hints: string[]): Promise<{ name: string; packageName: string } | null> {
  try {
    const installed = await getInstalledApps();
    const match = installed.find((a) => hints.some((h) => a.appName.toLowerCase().includes(h)));
    return match ? { name: match.appName, packageName: match.packageName } : null;
  } catch {
    return null;
  }
}

type RadioResolution =
  | { kind: 'found'; name: string; packageName: string }
  | { kind: 'none' }
  | { kind: 'ambiguous'; candidates: { name: string; packageName: string }[] };

// Radio needs richer resolution than a first-match lookup: real radio apps are almost always
// named after a station/brand (TuneIn, Antena, Kiss FM, ...) rather than containing the literal
// word "radio", and a specific station named in the utterance ("Radio România Actualități")
// should launch THAT app, not an arbitrary radio candidate.
async function resolveRadioTarget(stationName?: string): Promise<RadioResolution> {
  let installed: { appName: string; packageName: string }[];
  try {
    installed = await getInstalledApps();
  } catch {
    installed = [];
  }

  // Only treat this as a SPECIFIC station name if it's more than just the bare word "radio" —
  // otherwise "radio"/"radioul" would substring-match whichever radio app happens to come first
  // in the installed list, silently skipping disambiguation when there are several.
  const isBareRadioWord = /^radio(?:ul)?$/i.test((stationName ?? '').trim());
  if (stationName && stationName.trim() && !isBareRadioWord) {
    const wanted = stationName.trim().toLowerCase();
    const exact = installed.find((a) => a.appName.toLowerCase().includes(wanted));
    if (exact) return { kind: 'found', name: exact.appName, packageName: exact.packageName };
    // Named station isn't installed under that name — fall through to the general candidate
    // search below rather than failing outright; there may still be one obvious radio app.
  }

  const candidates = installed
    .filter((a) => RADIO_NAME_HINTS.some((h) => a.appName.toLowerCase().includes(h)))
    .map((a) => ({ name: a.appName, packageName: a.packageName }));

  if (candidates.length === 0) return { kind: 'none' };
  if (candidates.length === 1) return { kind: 'found', name: candidates[0].name, packageName: candidates[0].packageName };
  return { kind: 'ambiguous', candidates };
}

async function resolveAndLaunchRadio(requestId: string, stationName?: string): Promise<ActionResult> {
  const resolution = await resolveRadioTarget(stationName);
  devLog('radio resolution ->', resolution);

  if (resolution.kind === 'none') {
    return notFoundResult(requestId, 'Nu am găsit nicio aplicație radio instalată.');
  }
  if (resolution.kind === 'ambiguous') {
    const names = resolution.candidates.map((c) => c.name).join(', ');
    return needsDisambiguationResult(
      requestId,
      `Am găsit mai multe aplicații radio: ${names}. Pe care să o deschid?`,
      { candidates: resolution.candidates },
    );
  }
  return launchAllowlisted(requestId, resolution.name, resolution.packageName);
}

const BENSON_PACKAGE = 'com.benson.butler';

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
  const installed = isPackageInstalled(packageName);
  devLog('detected package=', packageName, 'installed=', installed);

  if (!installed) {
    devLog('result', { detectedPackage: packageName, launchAttempted: false, foregroundAfter: null, success: false, error: 'PACKAGE_NOT_INSTALLED' });
    return notFoundResult(requestId, `Nu găsesc aplicația ${name} pe telefon.`);
  }

  // Bring BENSON's own MainActivity to front immediately before launching the target app —
  // confirmed live that launching a third-party app directly from a background/service context on
  // this device (OPPO/ColorOS) can start the target's process without ever bringing it to the
  // visible foreground ("opened in the background, like a launcher"). Android treats "caller
  // currently has a visible activity" as one of its own recognized exemptions from background-
  // activity-launch restrictions, so waiting for BENSON to be CONFIRMED foreground (not just
  // assumed after a delay) gives the very next startActivity call the best chance of actually
  // reaching the screen.
  bringBensonToForeground();
  const bensonForeground = await waitForPackageForeground(BENSON_PACKAGE, 3000);
  devLog('benson-foreground confirmed before launch=', bensonForeground.reached, 'lastSeen=', bensonForeground.lastSeen);

  const outcome = launchPackage(packageName);
  devLog('launch intent created + startActivity accepted=', outcome.success, 'error=', outcome.error);

  if (!outcome.success) {
    devLog('result', { detectedPackage: packageName, launchAttempted: true, foregroundAfter: null, success: false, error: outcome.error ?? 'LAUNCH_FAILED' });
    return failedResult(requestId, `Nu am putut deschide ${name}.`, {
      errorCode: 'LAUNCH_FAILED',
      errorDetails: outcome.error,
    });
  }

  const targetForeground = await waitForPackageForeground(packageName, 3000);
  const foregroundAfter = targetForeground.reached ? packageName : (targetForeground.lastSeen ?? getForegroundPackage());
  devLog('foreground app after launch=', foregroundAfter, 'confirmedByEvent=', targetForeground.reached);

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

async function resolveTargetPackage(name: string): Promise<{ name: string; packageName: string } | null> {
  const entry = findAppRegistryEntry(name);
  if (entry?.packageName) return { name: entry.name, packageName: entry.packageName };
  const q = name.toLowerCase().trim();
  if (!q) return null;
  try {
    const installed = await getInstalledApps();
    const hit =
      installed.find((a) => a.appName.toLowerCase() === q) ||
      installed.find((a) => a.appName.toLowerCase().includes(q)) ||
      installed.find((a) => q.includes(a.appName.toLowerCase()));
    return hit ? { name: hit.appName, packageName: hit.packageName } : null;
  } catch {
    return null;
  }
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
  const resolved = await resolveTargetPackage(targetName);
  if (!resolved) {
    return bringBensonBack(requestId, `Nu găsesc ${label} pe telefon ca să o închid. Am revenit la tine.`);
  }
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
      const found = await findInstalledAppByNameHint(MUSIC_NAME_HINTS);
      devLog('MEDIA_PLAY music lookup ->', found);
      if (!found) return notFoundResult(request.id, 'Nu am găsit nicio aplicație muzicală instalată.');
      return launchAllowlisted(request.id, found.name, found.packageName);
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
        const found = await findInstalledAppByNameHint(MUSIC_NAME_HINTS);
        devLog('music lookup ->', found);
        if (!found) return notFoundResult(request.id, 'Nu am găsit nicio aplicație muzicală instalată.');
        return launchAllowlisted(request.id, found.name, found.packageName);
      }

      const entry = findAppRegistryEntry(appName);
      if (!entry || !entry.packageName) return notFoundResult(request.id, `Nu găsesc aplicația ${appName} pe telefon.`);
      return launchAllowlisted(request.id, entry.name, entry.packageName);
    }

    return unsupportedResult(request.id, `AppLauncherExecutor does not handle ${request.intent}.`);
  },
};
