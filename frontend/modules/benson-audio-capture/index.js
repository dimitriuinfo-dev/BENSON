import { requireNativeModule, EventEmitter } from 'expo-modules-core';

const NativeModule = requireNativeModule('BensonAudioCapture');
const emitter = new EventEmitter(NativeModule);

// Starts native AudioRecord capture with built-in silence-based VAD (16kHz mono PCM16 -> WAV).
// Resolves once recording has actually started; the capture itself ends on its own (VAD silence,
// max duration, or no speech at all) — listen via addCaptureEndListener for the result.
export function startCapture() {
  return NativeModule.startCapture();
}

// Force-ends an in-progress capture (e.g. user cancelled) — still fires onCaptureEnd with
// whatever was captured so far (reason: 'stopped').
export function stopCapture() {
  return NativeModule.stopCapture();
}

// Fires exactly once per startCapture() call, when the capture naturally ends.
// filePath is '' (falsy) if nothing usable was captured (reason: 'no_speech' or 'error').
export function addCaptureEndListener(listener) {
  return emitter.addListener('onCaptureEnd', (ev) => listener(ev?.filePath || null, ev?.reason ?? 'unknown'));
}

// Fires ~10x/sec while a capture is running, with the real (normalized 0-1) mic level for that
// instant — for a listening indicator that reflects actual audio, not a decorative animation.
export function addVolumeChangeListener(listener) {
  return emitter.addListener('onVolumeChanged', (ev) => listener(typeof ev?.level === 'number' ? ev.level : 0));
}
