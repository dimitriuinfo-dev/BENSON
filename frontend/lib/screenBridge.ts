import { addScreenUpdateListener, type BensonScreenSnapshot } from 'benson-accessibility';

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
