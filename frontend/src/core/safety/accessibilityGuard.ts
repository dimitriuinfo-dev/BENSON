// BENSON Safety — Accessibility Service liveness guard.
// The single pre-flight check every accessibility-dependent action (WhatsApp call flow today,
// anything else built on BensonAccessibilityService later) must call immediately before touching
// the service. Never trust a state cached from an earlier check — OxygenOS can kill the service
// process between one call and the next, so this always re-queries the native side fresh.

import { getConnectionState, openAccessibilitySettings, type AccessibilityConnectionState } from 'benson-accessibility';

const LOG_TAG = '[AccessibilityGuard]';
function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

// Sentinel carried in ActionResult/ToolCallResult error fields so a caller building the
// spoken failure message can recognize this specific case and speak the exact required sentence
// instead of wrapping it in a generic "could not open X (error)" template.
export const ACCESSIBILITY_DISCONNECTED_ERROR = 'ACCESSIBILITY_DISCONNECTED';

export const ACCESSIBILITY_DOWN_SPOKEN_MESSAGE_RO =
  'Serviciul de accesibilitate s-a oprit — reactivează-l în setări.';

export interface AccessibilityGuardResult {
  ready: boolean;
  state: AccessibilityConnectionState;
}

// Avoids repeatedly launching the Settings activity if several accessibility-dependent calls
// fail in quick succession (e.g. a mission retried right after a spoken "nu") — the spoken
// message and the failure result are still returned every time, only the actual Settings
// launch is throttled.
const SETTINGS_LAUNCH_THROTTLE_MS = 10_000;
let lastSettingsLaunchAt = 0;

export async function ensureAccessibilityReady(): Promise<AccessibilityGuardResult> {
  let state: AccessibilityConnectionState = 'disabled';
  try {
    state = await getConnectionState();
  } catch (err) {
    devLog('getConnectionState threw', err);
  }
  devLog('state=', state);

  if (state === 'enabled_connected') {
    return { ready: true, state };
  }

  const now = Date.now();
  if (now - lastSettingsLaunchAt > SETTINGS_LAUNCH_THROTTLE_MS) {
    lastSettingsLaunchAt = now;
    try {
      await openAccessibilitySettings();
    } catch (err) {
      devLog('openAccessibilitySettings threw', err);
    }
  }

  return { ready: false, state };
}
