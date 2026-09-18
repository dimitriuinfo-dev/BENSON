import { requireNativeModule, EventEmitter } from 'expo-modules-core';

const NativeModule = requireNativeModule('BensonAppRegistry');
const emitter = new EventEmitter(NativeModule);

// Returns every app with a launcher icon on the device (icon as a base64 PNG data URI),
// sorted by name. Used only by the onboarding/App Permissions screen.
export function getInstalledApps() {
  return NativeModule.getInstalledApps();
}

// Reliable arbitrary-app launch by package name (PackageManager.getLaunchIntentForPackage) —
// works for any installed app, unlike Linking.openURL('android-app://<pkg>') which depends on
// the target app declaring a matching intent filter. Returns false if the package isn't found.
export function launchApp(packageName) {
  return NativeModule.launchApp(packageName);
}

// Explicit installed-check, separate from actually launching — lets a caller log/report
// "not installed" distinctly from "installed but the launch itself failed."
export function isPackageInstalled(packageName) {
  return NativeModule.isPackageInstalled(packageName);
}

// Item 2 — direct native phone call (Intent.ACTION_CALL). Requires CALL_PHONE already granted;
// returns false without attempting anything if it isn't (checked again natively as a safety net
// even though the JS caller is expected to check/request first). No chooser, no dialer screen —
// an actually placed call.
export function placeDirectCall(phoneNumber) {
  return NativeModule.placeDirectCall(phoneNumber);
}

export function hasCallPhonePermission() {
  return NativeModule.hasCallPhonePermission();
}

// Opens a URI (e.g. a wa.me deep link) with an explicit target package (Intent.setPackage),
// bypassing whatever app the OS would otherwise pick as the default handler — needed because a
// plain implicit ACTION_VIEW silently opened WhatsApp Business instead of regular WhatsApp on a
// phone with both installed. Returns false if the package can't handle the URI or isn't installed.
export function openUriWithPackage(uri, packageName) {
  return NativeModule.openUriWithPackage(uri, packageName);
}

// ROUND_EMERGENCY_CORE_1 — classify a spoken utterance against the closed emergency set.
// Returns 'NONE' | 'EXPLICIT_112' | 'GENERIC_HELP'. Synchronous, no side effects.
export function classifyEmergencyIntent(text) {
  try { return NativeModule.classifyEmergencyIntent(String(text ?? '')); } catch { return 'NONE'; }
}

// One-shot emergency context: { timestamp, batteryPct, network, locationAvailable }. Logged
// natively; raw coordinates never cross the bridge. Null if the native context is unavailable.
export function getEmergencyContext() {
  try { return NativeModule.getEmergencyContext() ?? null; } catch { return null; }
}

// Routes 112 to the native Android telecom stack — a real placed call when CALL_PHONE is granted,
// otherwise the system dialer pre-filled with 112. Never WhatsApp, never a chooser, never typing.
// Resolves { success, mode: 'DIRECT_CALL'|'SYSTEM_DIALER'|'FAILED', reason }.
export function routeEmergencyCall() {
  return NativeModule.routeEmergencyCall();
}

// ROUND_WA_NATIVE_CALL_PROBE_1 — feasibility probe ONLY. Reads ContactsContract for the WhatsApp
// voip.call MIME row of `contactName`, checks whether the typed contacts intent resolves against
// com.whatsapp, and fires it ONLY when doLaunch === true (which places a REAL call). Does not
// touch the existing call route. Resolves the probe result object.
export function probeWhatsAppNativeCall(contactName, doLaunch = false) {
  return NativeModule.probeWhatsAppNativeCall(String(contactName ?? ''), !!doLaunch);
}

// Real Android Picture-in-Picture — shrinks BENSON's own Activity into a small, user-movable
// window right before another app (WhatsApp) takes over the rest of the screen. Only shrinks
// BENSON itself; there is no API to force a different app into PiP from outside it. No-op
// (returns false) below Android 8.0 (API 26), where PiP isn't available.
export function enterPipMode() {
  return NativeModule.enterPipMode();
}

// Fires whenever this same Activity (the only one BENSON has) enters or leaves real PiP — the
// tiny window renders the SAME React tree scaled down, so JS is what has to swap to a
// logo-only layout; there's no separate native "PiP screen" to point at instead.
// listener primește { isInPip: boolean }.
export function addPipModeListener(listener) {
  return emitter.addListener('onPipModeChanged', listener);
}
