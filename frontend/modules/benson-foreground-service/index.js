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

// WAKE HEALTH — re-issue the ongoing notification with an honest body ("BENSON is listening." only
// when the wake recognizer is actually active + receiving audio; otherwise "BENSON wake inactive"
// / "BENSON microphone blocked" / …). No-op if the service isn't running. Does NOT restart the
// service or the hotword loop.
export function updateNotification(title, body) {
  try { return NativeModule.updateNotification(title, body); } catch { return undefined; }
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

// ROUND_WAKE_NATIVE_TO_JS_ACK_1 — atomic read+clear of a durable pending wake command (native
// Handler-timed, survives JS suspension). Returns null if nothing is pending; "" is a valid bare
// "Benson" result. Call on every live wake event AND on a reliable heartbeat (onWakePoke) so a
// wake that fires while JS is suspended is still picked up the moment JS runs again.
export function takePendingWakeCommand() {
  try { return NativeModule.takePendingWakeCommand() ?? null; } catch { return null; }
}

// ROUND_WAKE_STATE_BUG_1 — native heartbeat (every ~3 s from the foreground service, on a native
// Handler that runs regardless of RN host state). Delivered as an EVENT, so the JS callback
// executes even while the app is backgrounded — unlike setTimeout/setInterval, which RN suspends.
// The handler re-arms the JS wake loop if it should be running but isn't (the setTimeout-based
// re-arm inside startLocalWakeLoop is frozen while backgrounded).
export function addWakePokeListener(listener) {
  return emitter.addListener('onWakePoke', () => listener());
}

// ROUND_NATIVE_WAKE_MICROWAKEWORD_1 — native (TFLite/AudioRecord) wake engine, runs in the
// foreground service outside React Native. owner: 'COMMAND_STT'|'TTS'|'CALL' suspends it (releases
// the mic); 'WAKE'|'NONE' re-arms it. Idempotent.
export function nativeWakeSetOwner(owner) {
  try { return NativeModule.nativeWakeSetOwner(owner); } catch { return undefined; }
}

// ROUND_STT_SESSION_WATCHDOG_NATIVE_1 — native Handler-backed timer (survives JS suspension while
// backgrounded, unlike the setTimeout it replaces). armSttSessionWatchdog re-arms/replaces any
// previously armed session for this process; cancelSttSessionWatchdog is a no-op if sessionId is
// not the one currently armed (stale-safe). onSttWatchdogTimeout fires only for the still-current
// session id at expiry — a superseded session's own timer is silently ignored natively
// (STT_WATCHDOG_STALE_IGNORED), never reaching JS.
export function armSttSessionWatchdog(sessionId, timeoutMs) {
  try { return NativeModule.armSttSessionWatchdog(sessionId, timeoutMs); } catch { return undefined; }
}
export function cancelSttSessionWatchdog(sessionId) {
  try { return NativeModule.cancelSttSessionWatchdog(sessionId); } catch { return undefined; }
}
export function addSttWatchdogTimeoutListener(listener) {
  return emitter.addListener('onSttWatchdogTimeout', (ev) => listener(ev?.sessionId ?? ''));
}

// URGENT_CONFIRMATION_NATIVE_1 — native one-shot YES/NO/UNKNOWN reply capture (AudioRecord+VAD+
// cloud STT inside the foreground service), survives BENSON backgrounded + JS timers suspended.
// verdict from native is diagnostic only; callers should re-classify the transcript themselves.
export function startConfirmationListening(confirmationId, timeoutMs) {
  try { return NativeModule.startConfirmationListening(String(confirmationId), Number(timeoutMs)); } catch { return undefined; }
}
export function cancelConfirmationListening(confirmationId) {
  try { return NativeModule.cancelConfirmationListening(String(confirmationId)); } catch { return undefined; }
}
export function addConfirmationResultListener(listener) {
  return emitter.addListener('onConfirmationResult', (ev) =>
    listener(ev?.confirmationId ?? '', ev?.verdict ?? 'UNKNOWN', ev?.transcript ?? ''));
}
// { model: bool, cloud: bool, running: bool } — model=false ⇒ benson.tflite is not bundled;
// cloud=false ⇒ no STT credentials pushed yet (setNativeWakeCredentials). JS treats
// "model || cloud" as "a native engine can own passive wake" (ROUND_WAKE_NATIVE_GENERIC_1).
// Never pretends a native engine is running when neither is true.
export function isNativeWakeAvailable() {
  try { return NativeModule.isNativeWakeAvailable() || { model: false, cloud: false, running: false }; }
  catch { return { model: false, cloud: false, running: false }; }
}

// ROUND_WAKE_NATIVE_GENERIC_1 — ONE authoritative wake-name config, native-persisted (survives JS
// suspension / service recreation) so the native cloud wake loop reads the same name Settings
// writes. Default "Benson". Same push idiom as setSttLanguage.
export function setWakeName(name) {
  try { return NativeModule.setWakeName(name); } catch { return undefined; }
}
export function getWakeName() {
  try { return NativeModule.getWakeName(); } catch { return 'Benson'; }
}

// Pushes the active STT provider's credentials down so the native cloud wake loop can transcribe
// an utterance without JS being alive. JS (settingsStore.ts + expo-secure-store) remains the sole
// place the real secret is authored — this is a runtime push, not a second source of truth.
export function setNativeWakeCredentials(apiKey, baseUrl, model) {
  try { return NativeModule.setNativeWakeCredentials(apiKey, baseUrl, model); } catch { return undefined; }
}

export function isNativeCloudWakeConfigured() {
  try { return NativeModule.isNativeCloudWakeConfigured(); } catch { return false; }
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
