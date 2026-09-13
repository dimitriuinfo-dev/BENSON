import {
  addScreenUpdateListener,
  getScreenSnapshot as nativeGetScreenSnapshot,
  type BensonScreenSnapshot,
} from 'benson-accessibility';

// Caches the most recent on-screen snapshot pushed by the native Accessibility Service, so the
// readScreen/fillForm agent tools (lib/agents/tools.ts) can answer synchronously instead of
// waiting on the next accessibility event. Snapshots only arrive for whatever app is currently
// foregrounded — usually not BENSON itself, since the service is meant to read *other* apps
// while BENSON listens hands-free in the background.
let lastSnapshot: BensonScreenSnapshot | null = null;
let started = false;

export function startScreenBridge(): void {
  if (started) return;
  started = true;
  addScreenUpdateListener((event) => {
    try {
      lastSnapshot = JSON.parse(event.json);
    } catch {}
  });
}

export function getLastScreenSnapshot(): BensonScreenSnapshot | null {
  return lastSnapshot;
}

// On-demand FRESH snapshot — reads the live accessibility tree NOW (native retries 3×@150ms if
// it comes back empty). Use this, not getLastScreenSnapshot, whenever the answer must reflect
// the CURRENT screen: the pushed cache above only updates on TYPE_WINDOW_STATE_CHANGED, so it is
// stale for in-place content changes like WhatsApp's search-result list. Returns null only if the
// native call throws or the service isn't running.
export async function getScreenSnapshot(): Promise<BensonScreenSnapshot | null> {
  try {
    const json = await nativeGetScreenSnapshot();
    const snap = JSON.parse(json) as BensonScreenSnapshot;
    return Array.isArray(snap?.nodes) ? snap : null;
  } catch {
    return null;
  }
}
