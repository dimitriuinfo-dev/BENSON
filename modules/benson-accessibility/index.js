import { requireNativeModule, EventEmitter } from 'expo-modules-core';

const NativeModule = requireNativeModule('BensonAccessibility');
const emitter = new EventEmitter(NativeModule);

// True dacă userul a activat Benson din Settings > Accessibility.
export function isServiceEnabled() {
  return NativeModule.isServiceEnabled();
}

// Authoritative pre-flight check for any accessibility-dependent action — distinguishes
// "disabled" (user never turned it on) / "enabled_disconnected" (user turned it on, but the
// process OxygenOS is running has since died) / "enabled_connected" (actually usable right now).
// Prefer this over isServiceEnabled for anything about to perform a click/read.
export function getConnectionState() {
  return NativeModule.getConnectionState();
}

// Deschide ecranul Android de Accessibility Settings — activarea rămâne
// mereu o acțiune manuală a userului, Benson nu se auto-activează.
export function openAccessibilitySettings() {
  return NativeModule.openAccessibilitySettings();
}

// Apasă un nod din ultimul snapshot (după id). Blocat automat pe noduri
// legate de plată — vezi PAYMENT_BLOCKLIST în serviciul nativ.
export function performClick(nodeId) {
  return NativeModule.performClick(nodeId);
}

// Scrie text într-un câmp editabil (id din snapshot).
export function performSetText(nodeId, value) {
  return NativeModule.performSetText(nodeId, value);
}

// Completează formularul curent pe bază de perechi { label: value }.
// Returnează câte câmpuri au fost completate.
export function fillForm(fields) {
  return NativeModule.fillForm(JSON.stringify(fields));
}

export function goBack() {
  return NativeModule.goBack();
}

export function goHome() {
  return NativeModule.goHome();
}

// Last package the accessibility service saw come to the foreground, or null if the service
// isn't running (not enabled in Settings, or not yet connected). Used to verify a launch actually
// worked instead of trusting startActivity() not throwing.
export function getForegroundPackage() {
  return NativeModule.getForegroundPackage();
}

// Fires la fiecare schimbare de conținut pe ecranul curent.
// listener primește { json: string } — parsezi cu JSON.parse(event.json).
export function addScreenUpdateListener(listener) {
  return emitter.addListener('onScreenUpdate', listener);
}

// Fires the instant Android reports a foreground window change (TYPE_WINDOW_STATE_CHANGED),
// independent of canRetrieveWindowContent. listener primește { packageName: string }. This is
// the real synchronization point for "did app X actually reach the screen" instead of a fixed
// delay — see waitForPackageForeground in androidActionExecutor.ts.
export function addForegroundChangeListener(listener) {
  return emitter.addListener('onForegroundChanged', listener);
}

// Native WhatsApp call flow (open -> search -> type name -> tap result -> verify -> [tap call
// button]), runs entirely inside the accessibility service's own coroutine scope — not a JS
// timer — so it survives BENSON being backgrounded the moment WhatsApp opens. Resolves with
// { success, step, error } — never throws for an expected "couldn't find X" outcome.
// autoPressCall: when false, stops right after the chat is confirmed open (step "chat_opened")
// and leaves the actual call-button tap for the user — chosen as the default caller behaviour
// (see whatsappTool.ts) after live testing (2026-07-14) showed that final tap was the least
// reliable step, at the mercy of ColorOS repeatedly killing/restarting the accessibility service.
export function placeWhatsAppCall(contactName, autoPressCall) {
  return NativeModule.placeWhatsAppCall(contactName, autoPressCall);
}

// Ends / mutes an in-progress WhatsApp call — same native, non-JS-timer execution model as
// placeWhatsAppCall. Assumes a call is currently active; reports honestly if no end/mute button
// is found rather than assuming one was ended/muted.
export function endWhatsAppCall() {
  return NativeModule.endWhatsAppCall();
}
export function muteWhatsAppCall() {
  return NativeModule.muteWhatsAppCall();
}

// Taps WhatsApp's own send button. Only meaningful right after opening a chat with the message
// already pre-filled via a wa.me deep link (whatsappTool.ts's openConversation) — there is no
// "type the message" step here, only "find and tap send".
export function pressWhatsAppSend() {
  return NativeModule.pressWhatsAppSend();
}

// Second half of the two-phase call flow (2026-07-17): call only after placeWhatsAppCall(name,
// false) has already opened the contact's chat and the user has confirmed by voice having SEEN
// it. Finds and taps the call button only — no search, no navigation.
export function pressWhatsAppCallButton() {
  return NativeModule.pressWhatsAppCallButton();
}

// Executes an APK-bundled, allow-listed UI profile. Parameters are used only as data for
// profile assertions; the profile DSL itself is interpreted in native Kotlin.
export function runAutomationProfile(profileId, params = {}) {
  return NativeModule.runAutomationProfile(profileId, JSON.stringify(params));
}

// JS-driven step-list executor (2026-07-17) — JS sends an explicit ordered list of steps
// (see BensonCommandExecutor.kt's doc comment for the format); native runs them and stops
// honestly at the first problem (not_found/ambiguous/blocked/tap_rejected/wrong_package),
// reporting exactly which step and why. Runs in parallel with the existing hardcoded flows
// (placeWhatsAppCall etc.) — nothing is replaced until this is confirmed to behave the same.
export function executeCommand(command) {
  return NativeModule.executeCommand(JSON.stringify(command));
}

// Tells the native Guardian watchdog whether a WhatsApp automation is currently in progress, so
// it skips stealing focus back to BENSON mid-flow. Always pair a `true` call with a `false` in a
// finally block — never leave this stuck on.
export function setWhatsAppAutomationActive(active) {
  return NativeModule.setWhatsAppAutomationActive(active);
}
