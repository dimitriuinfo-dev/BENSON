// PERSISTENT_WAKE_CONSUMER_1 (2026-09-20, device-proven) — module-scope indirection so the
// persistent listener registered once in index.js (survives app/index.tsx's screen component
// being unmounted) can still reach the live, fully-featured wake handler when the screen exists,
// and fall back to a direct mission dispatch when it doesn't. Root cause this fixes: OxygenOS can
// kill BENSON's Activity (and with it, the screen's own addWakeWordDetectedListener subscription)
// while the foreground service/JS engine survive — the native side's hasListenerRegistered check
// only tests a Kotlin closure, not whether a real JS subscriber still exists, so those wake events
// silently died until the 5s WakeHandoffWatchdog forced a lesser Headless recovery. Two module-
// scope functions instead of an event emitter: exactly one live handler can ever be registered at
// a time (the mounted screen), so there is no ambiguity about who owns it.
let liveHandler: ((commandTail: string) => void) | null = null;

export function setLiveWakeHandler(fn: ((commandTail: string) => void) | null): void {
  liveHandler = fn;
}

export function getLiveWakeHandler(): ((commandTail: string) => void) | null {
  return liveHandler;
}
