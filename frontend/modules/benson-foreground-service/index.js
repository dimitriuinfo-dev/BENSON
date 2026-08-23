import { requireNativeModule, EventEmitter } from 'expo-modules-core';

const NativeModule = requireNativeModule('BensonForegroundService');
const emitter = new EventEmitter(NativeModule);

// Starts the Android foreground service with a persistent "listening" notification.
export function startListeningService(title, body) {
  return NativeModule.startService(title, body);
}

// Stops the foreground service and removes the notification.
export function stopListeningService() {
  return NativeModule.stopService();
}

// Fires when the user taps the "STOP" action on the notification.
export function addStopRequestedListener(listener) {
  return emitter.addListener('onStopRequested', listener);
}

// Fires when the user taps the "LISTEN" action on the notification (manual activation
// fallback while a real hotword/wake-word engine isn't wired in).
export function addListenRequestedListener(listener) {
  return emitter.addListener('onListenRequested', listener);
}

// Fires when the native "Benson" hotword loop (runs inside the foreground service, independent
// of the Activity/screen state) hears the word — listener receives the commandTail string
// (whatever followed the name in the same utterance, e.g. "deschide Waze"; empty if the name
// was said alone). Process a non-empty tail immediately; otherwise start real command capture.
export function addWakeWordDetectedListener(listener) {
  return emitter.addListener('onWakeWordDetected', (ev) => listener(ev?.commandTail ?? ''));
}

// Pause the native hotword loop right before JS starts its own STT session (manual conversation
// mode, or capturing the command right after wake word fired) — avoids both fighting the mic.
// Returns a Promise that resolves only once the native loop has actually stopped — callers should
// await it before starting their own recognition session, otherwise the two can briefly race.
export function pauseHotword() {
  return NativeModule.pauseHotword();
}

// Resume passive "Benson" listening once JS is back to idle.
export function resumeHotword() {
  return NativeModule.resumeHotword();
}

// Brings BENSON's own screen back to front — used by the "come back" voice command/tool.
export function bringToForeground() {
  return NativeModule.bringToForeground();
}

export function isIgnoringBatteryOptimizations() {
  return NativeModule.isIgnoringBatteryOptimizations();
}

// Shows the system "exempt from battery optimization" dialog once.
export function requestIgnoreBatteryOptimizations() {
  return NativeModule.requestIgnoreBatteryOptimizations();
}

// Suppresses the system STT start/stop tone (the "xylophone" beep) — call with true right
// before starting a JS-side recognition session, false right after it ends.
export function setSystemSoundsMuted(muted) {
  return NativeModule.setSystemSoundsMuted(muted);
}

// Guardian — true exactly once per resurrection (cleared on read by the native side). Call once
// on app mount; if true, speak an honest "I'm back" line so a silent OxygenOS kill + automatic
// restart is never invisible to the user.
export function consumeRecoveryFlag() {
  return NativeModule.consumeRecoveryFlag();
}

// Diagnostic only — "<timestampMs>:<reason>" pairs (most recent last) for measuring how often
// the Guardian has had to resurrect BENSON on this device.
export function getRecoveryEventsLog() {
  return NativeModule.getRecoveryEventsLog();
}

// BENSON_AUDIO diagnostics toggle — single native-backed flag (see AudioDiag.kt), read/written
// by both native audio-chain logging and JS (this wrapper). Debug Panel-controlled.
export function isAudioDiagnosticsEnabled() {
  return NativeModule.isAudioDiagnosticsEnabled();
}

export function setAudioDiagnosticsEnabled(enabled) {
  return NativeModule.setAudioDiagnosticsEnabled(enabled);
}

// Routes a JS-side audio-chain stage through the native BENSON_AUDIO logcat tag (same as the
// native hotword-loop stages) so the whole chain shows up in one place, not split across
// console.log (unreliable to capture, confirmed this session) and native Log.i separately.
export function logAudioDiag(stage, fields = '') {
  try { NativeModule.logAudioDiag(stage, fields); } catch {}
}

// Persists the user's selected STT language so the native passive hotword loop (no direct
// AsyncStorage access) can use it instead of silently falling back to the device's system
// locale — root cause of zero passive-loop transcriptions confirmed 2026-07-16.
export function setSttLanguage(lang) {
  try { NativeModule.setSttLanguage(lang); } catch {}
}

// Wake-word kill switch — a real OFF (native never opens the mic for passive listening at all
// when disabled), not a mute. Settings-controlled, default ON, persisted natively.
export function setWakeWordEnabled(enabled) {
  try { NativeModule.setWakeWordEnabled(enabled); } catch {}
}

export function isWakeWordEnabled() {
  return NativeModule.isWakeWordEnabled();
}

// Picovoice Porcupine AccessKey — see SESSION_REPORT.md for how to obtain one. No Settings UI
// wired to this yet; exists so the value can be set without another native round-trip later.
export function setPorcupineAccessKey(key) {
  try { NativeModule.setPorcupineAccessKey(key); } catch {}
}

export function isPorcupineConfigured() {
  return NativeModule.isPorcupineConfigured();
}

// Split status for the Settings UI indicator — tells the user WHICH of the two prerequisites
// (AccessKey pasted, .ppn model file present as a bundled asset) is missing, rather than a single
// combined bool.
export function getPorcupineStatus() {
  try { return NativeModule.getPorcupineStatus(); } catch { return { hasKey: false, hasModel: false }; }
}

// Which wake-word engine is ACTUALLY running right now (reads live native state, not the
// WAKE_ENGINE build constant) — Porcupine can silently fall back to SpeechRecognizer at start
// time if the model/key aren't in place, so only this reflects the truth. "none" if the hotword
// loop isn't running (kill switch off, or mid command-capture).
export function getActiveWakeEngine() {
  try { return NativeModule.getActiveWakeEngine(); } catch { return 'none'; }
}

// Toggles on-device (offline) recognition for the native passive "Benson" hotword loop — read
// fresh by the native loop on its next burst. See setSttLanguage's note: cloud recognition on
// this device has been confirmed independently unreliable (hangs/errors), not just mis-languaged.
export function setPreferOnDeviceStt(enabled) {
  try { NativeModule.setPreferOnDeviceStt(enabled); } catch {}
}

// Whether this device's OS version supports SpeechRecognizer.createOnDeviceSpeechRecognizer()
// (API 33 / Android 13+). The Settings toggle should be hidden/disabled if this is false.
export function isOnDeviceSttSupported() {
  try { return NativeModule.isOnDeviceSttSupported(); } catch { return false; }
}
