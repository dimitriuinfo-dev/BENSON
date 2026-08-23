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

// Wake-word reveal — two counter-rotating rings, centered, semi-transparent, drawn natively
// (no second React Native surface). Call while the foreground service is already running.
export function showWakeRing() {
  return NativeModule.showWakeRing();
}

export function hideWakeRing() {
  return NativeModule.hideWakeRing();
}
