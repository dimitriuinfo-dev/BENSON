// BENSON Action Engine — Android Action Executor (Module 7).
// The single call surface every executor uses to actually touch the device: install-check,
// launch, deep link, web fallback, dial, SMS, bring-BENSON-to-front. Wraps the existing native
// modules (benson-app-registry, benson-foreground-service) and Linking — no new native code,
// this is the "stop scattering app launching through React components" boundary the directive
// asks for, implemented as a JS facade rather than a native rewrite (lower risk, same effect
// from every executor's point of view).

import { Linking } from 'react-native';
import { launchApp as nativeLaunchApp, isPackageInstalled as nativeIsPackageInstalled, openUriWithPackage as nativeOpenUriWithPackage } from 'benson-app-registry';
import { bringToForeground as nativeBringToForeground } from 'benson-foreground-service';
import {
  getForegroundPackage as nativeGetForegroundPackage,
  addForegroundChangeListener,
} from 'benson-accessibility';

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

export function launchPackage(packageName: string): AndroidActionOutcome {
  try {
    const success = nativeLaunchApp(packageName);
    devLog('launchPackage', packageName, '-> success=', success);
    return { attempted: true, success };
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    devLog('launchPackage threw', packageName, error);
    return { attempted: true, success: false, error };
  }
}

// Opens a URI with an explicit target package (Intent.setPackage) instead of a plain implicit
// ACTION_VIEW (Linking.openURL) — the latter lets Android silently pick whichever installed app
// is the current default handler for that link, which on a phone with both WhatsApp and WhatsApp
// Business installed turned out to be Business. Use this whenever the target app matters.
export function openUriWithPackage(uri: string, packageName: string): AndroidActionOutcome {
  try {
    const success = nativeOpenUriWithPackage(uri, packageName);
    devLog('openUriWithPackage', packageName, '-> success=', success);
    return { attempted: true, success };
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    devLog('openUriWithPackage threw', packageName, error);
    return { attempted: true, success: false, error };
  }
}

async function openUrl(kind: string, url: string): Promise<AndroidActionOutcome> {
  try {
    await Linking.openURL(url);
    devLog(kind, url, '-> success');
    return { attempted: true, success: true };
  } catch (err) {
    const error = err instanceof Error ? err.message : String(err);
    devLog(kind, url, '-> failed', error);
    return { attempted: true, success: false, error };
  }
}

export function openDeepLink(url: string): Promise<AndroidActionOutcome> {
  return openUrl('openDeepLink', url);
}

export function openFallbackUrl(url: string): Promise<AndroidActionOutcome> {
  return openUrl('openFallbackUrl', url);
}

export function dial(number: string): Promise<AndroidActionOutcome> {
  return openUrl('dial', `tel:${number}`);
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
