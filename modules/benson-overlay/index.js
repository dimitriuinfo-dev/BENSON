import { requireNativeModule, EventEmitter } from 'expo-modules-core';

const NativeModule = requireNativeModule('BensonOverlay');
const emitter = new EventEmitter(NativeModule);

export function hasOverlayPermission() {
  return NativeModule.hasOverlayPermission();
}

// Opens the system "draw over other apps" settings screen — Android won't grant this via a
// normal runtime permission dialog, the user must flip it on manually.
export function requestOverlayPermission() {
  return NativeModule.requestOverlayPermission();
}

// Shows the floating bubble. Requires hasOverlayPermission() to be true first.
export function showBubble() {
  return NativeModule.showBubble();
}

export function hideBubble() {
  return NativeModule.hideBubble();
}

// Fires when the user taps the bubble (not a drag) — activate the mic in response.
export function addBubbleTappedListener(listener) {
  return emitter.addListener('onBubbleTapped', listener);
}

// Fires when the user taps the camera icon on the status card (2026-09-18) — open the
// camera/gallery flow the same way the main-screen buttons do.
export function addBubbleCameraTappedListener(listener) {
  return emitter.addListener('onBubbleCameraTapped', listener);
}

// Wake-word reveal — two counter-rotating rings, centered, semi-transparent, drawn natively
// (no second React Native surface). Call while the foreground service is already running.
export function showWakeRing() {
  return NativeModule.showWakeRing();
}

export function hideWakeRing() {
  return NativeModule.hideWakeRing();
}

// E2-2 — the written band next to the bubble while BENSON operates over another app.
// state: short word shown above the transcript ("ascult" / "am înțeles" / "execut" / "gata").
// transcript: the user's last utterance, verbatim. visible=false removes the band.
// ROUND_BUBBLE_STATE_DESYNC_FIX_1 — terminal: this is a DONE/ERROR result (arms native's short
// ~2.5s auto-dismiss instead of the long stale-safety-net); turnId: Date.now() at the call site,
// lets native detect and drop an out-of-order/stale delivery instead of an old update clobbering
// a newer one. Native now owns show/hide/dismiss timing entirely — this call only REQUESTS a
// state, it is not a promise anything stays visible until a matching visible=false arrives.
// ROUND_ASSISTANT_SESSION_UX_FIX_1 — dismissDelayMs: the session-aware readable dwell for a
// terminal (DONE/ERROR) result, computed by the caller (0/omitted = native's own default).
export function updateBubbleStatus(state, transcript, visible, terminal, turnId, dismissDelayMs) {
  return NativeModule.updateBubbleStatus(
    String(state || ''), String(transcript || ''), !!visible, !!terminal, turnId ?? Date.now(),
    dismissDelayMs ?? 0,
  );
}

// E3-2 — the bubble's inner motion: "listening" | "executing" | "static".
export function setBubbleMotion(motion) {
  return NativeModule.setBubbleMotion(String(motion || 'static'));
}

// URGENT_REPAIR_AND_ADVANCE_1 — real-RMS mic level bars on the active status card. level: 0-1;
// active=false hides the bars (no fake idle animation). Silent no-op if the card isn't up.
export function setMicLevel(level, active) {
  try { return NativeModule.setMicLevel(Number(level) || 0, !!active); } catch { return undefined; }
}

// E3-3 — play the short procedurally-synthesised "mic open" SF tone (respects system silent mode).
export function playWakeSound() {
  return NativeModule.playWakeSound();
}
