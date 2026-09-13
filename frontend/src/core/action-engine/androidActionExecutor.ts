// BENSON Action Engine — Android Action Executor (Module 7).
// The single call surface every executor uses to actually touch the device: install-check,
// launch, deep link, web fallback, dial, SMS, bring-BENSON-to-front. Wraps the existing native
// modules (benson-app-registry, benson-foreground-service) and Linking — no new native code,
// this is the "stop scattering app launching through React components" boundary the directive
// asks for, implemented as a JS facade rather than a native rewrite (lower risk, same effect
// from every executor's point of view).

import { Linking } from 'react-native';
import { launchApp as nativeLaunchApp, isPackageInstalled as nativeIsPackageInstalled, openUriWithPackage as nativeOpenUriWithPackage } from 'benson-app-registry';
import { bringToForeground as nativeBringToForeground, logAudioDiag } from 'benson-foreground-service';
import {
  getForegroundPackage as nativeGetForegroundPackage,
  addForegroundChangeListener,
} from 'benson-accessibility';

// ROUND_UNEXPECTED_WHATSAPP_FOREGROUND_DIAG_1 — every BENSON-originated app-launch call funnels
// through this one file (launchPackage / openUriWithPackage / openUrl are the ONLY places any
// executor or governed tool actually asks Android to bring another app to the foreground).
// Logging here, once, catches every caller — including the protected whatsappTool.ts, without
// needing to touch it — and gives a real, greppable "did BENSON ask for this" trail for next
// time. `source` is optional/best-effort (old call sites this round didn't update just log
// "unknown", honestly, rather than a guessed value).
function logForegroundRequest(targetPackage: string, source: string, reason: string, success: boolean): void {
  try {
    logAudioDiag('APP_FOREGROUND_REQUEST', `source=${source} targetPackage=${targetPackage} reason=${reason} success=${success}`);
    if (targetPackage === 'com.whatsapp' || targetPackage.includes('whatsapp')) {
      logAudioDiag('WHATSAPP_FOREGROUND_REQUEST', `source=${source} missionId=none reason=${reason} success=${success}`);
    }
  } catch {}
}

export type AndroidActionOutcome = {
  attempted: boolean;
  success: boolean;
  error?: string;
};

const LOG_TAG = '[AndroidActionExecutor]';

function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

export function isPackageInstalled(packageName: string): boolean {
  try {
    const result = nativeIsPackageInstalled(packageName);
    devLog('isPackageInstalled', packageName, '->', result);
    return result;
  } catch (err) {
    devLog('isPackageInstalled threw', packageName, err);
    return false;
  }
}

export function launchPackage(packageName: string, source: string = 'unknown'): AndroidActionOutcome {
  try {
    const success = nativeLaunchApp(packageName);
    devLog('launchPackage', packageName, '-> success=', success);
    logForegroundRequest(packageName, source, 'launch_package', success);
    return { attempted: true, success };
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    devLog('launchPackage threw', packageName, error);
    logForegroundRequest(packageName, source, 'launch_package_threw', false);
    return { attempted: true, success: false, error };
  }
}

// Opens a URI with an explicit target package (Intent.setPackage) instead of a plain implicit
// ACTION_VIEW (Linking.openURL) — the latter lets Android silently pick whichever installed app
// is the current default handler for that link, which on a phone with both WhatsApp and WhatsApp
// Business installed turned out to be Business. Use this whenever the target app matters.
export function openUriWithPackage(uri: string, packageName: string, source: string = 'unknown'): AndroidActionOutcome {
  try {
    const success = nativeOpenUriWithPackage(uri, packageName)
    devLog('openUriWithPackage', packageName, '-> success=', success);
    logForegroundRequest(packageName, source, 'open_uri_with_package', success);
    return { attempted: true, success };
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    devLog('openUriWithPackage threw', packageName, error);
    logForegroundRequest(packageName, source, 'open_uri_with_package_threw', false);
    return { attempted: true, success: false, error };
  }
}

async function openUrl(kind: string, url: string, source: string = 'unknown'): Promise<AndroidActionOutcome> {
  try {
    await Linking.openURL(url);
    devLog(kind, url, '-> success');
    // A bare implicit ACTION_VIEW has no explicit target package — best-effort match on the URL
    // itself (whatsapp://, wa.me/...) so this class of launch is still traceable.
    logForegroundRequest(url, source, kind, true);
    return { attempted: true, success: true };
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    devLog(kind, url, '-> failed', error);
    logForegroundRequest(url, source, kind, false);
    return { attempted: true, success: false, error };
  }
}

export function openDeepLink(url: string, source: string = 'unknown'): Promise<AndroidActionOutcome> {
  return openUrl('openDeepLink', url, source);
}

export function openFallbackUrl(url: string, source: string = 'unknown'): Promise<AndroidActionOutcome> {
  return openUrl('openFallbackUrl', url, source);
}

export function dial(number: string, source: string = 'unknown'): Promise<AndroidActionOutcome> {
  return openUrl('dial', `tel:${number}`, source);
}

export function sendTo(number: string, body?: string): Promise<AndroidActionOutcome> {
  const url = body ? `sms:${number}?body=${encodeURIComponent(body)}` : `sms:${number}`;
  return openUrl('sendTo', url);
}

export function bringBensonToForeground(): AndroidActionOutcome {
  try {
    nativeBringToForeground();
    devLog('bringBensonToForeground -> success');
    return { attempted: true, success: true };
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    devLog('bringBensonToForeground threw', error);
    return { attempted: true, success: false, error };
  }
}

// Real HOME-press requires Accessibility performGlobalAction (Phase B) — bringing BENSON to
// front already satisfies the directive's own "realistic MVP" note for CLOSE_APP, so this is
// the same call for now, kept as its own named function so callers express intent clearly and
// the Phase B upgrade only touches this one place.
export function pressHomeFallback(): AndroidActionOutcome {
  return bringBensonToForeground();
}

// Null means "can't verify" (Accessibility Service not enabled/connected) — callers must treat
// that as a distinct outcome from both success and failure, never silently upgrade it to either.
export function getForegroundPackage(): string | null {
  try {
    const result = nativeGetForegroundPackage();
    devLog('getForegroundPackage ->', result);
    return result ?? null;
  } catch (err) {
    devLog('getForegroundPackage threw', err);
    return null;
  }
}

export type ForegroundWaitOutcome = {
  reached: boolean;
  lastSeen: string | null;
};

// Deterministic replacement for a fixed sleep(): resolves the instant the AccessibilityService
// reports `targetPackage` as foreground (TYPE_WINDOW_STATE_CHANGED), instead of gambling on a
// delay being long enough. Checks the already-known last-seen package first in case the target
// became foreground before this call subscribed (event fired earlier, not missed — just not
// waited for). Always resolves by timeoutMs so callers never hang; `reached: false` on timeout
// means "never confirmed", not "failed" — Accessibility being disabled looks the same as a slow
// transition from here, callers already treat null/unconfirmed foreground as its own outcome.
export function waitForPackageForeground(targetPackage: string, timeoutMs: number): Promise<ForegroundWaitOutcome> {
  return new Promise((resolve) => {
    const already = getForegroundPackage();
    if (already === targetPackage) {
      devLog('waitForPackageForeground', targetPackage, '-> already foreground');
      resolve({ reached: true, lastSeen: already });
      return;
    }

    let settled = false;
    let lastSeen: string | null = already;
    let subscription: { remove: () => void } | null = null;

    const finish = (reached: boolean) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      subscription?.remove();
      devLog('waitForPackageForeground', targetPackage, '-> reached=', reached, 'lastSeen=', lastSeen);
      resolve({ reached, lastSeen });
    };

    const timer = setTimeout(() => finish(false), timeoutMs);

    try {
      subscription = addForegroundChangeListener((event) => {
        lastSeen = event.packageName;
        if (event.packageName === targetPackage) finish(true);
      });
    } catch (err) {
      devLog('waitForPackageForeground addForegroundChangeListener threw', err);
      finish(false);
    }
  });
}
