// BENSON Mission Governance — WhatsApp Tool Layer.
// The ONLY place allowed to call Linking for WhatsApp. Nothing outside this file may open a
// wa.me / whatsapp:// deep link — src/core/mission/missionExecutor.ts is the only caller.
// Contact resolution reuses the existing contact path (src/core/contacts) rather than
// reimplementing matching — per instruction, extend what exists, don't parallel-build it.

import * as Contacts from 'expo-contacts';
import { endWhatsAppCall, muteWhatsAppCall, executeCommand, setWhatsAppAutomationActive } from 'benson-accessibility';
import type { CommandStep } from 'benson-accessibility';
import { resolveContact as resolveAgainstList, loadDeviceContacts } from '../../contacts';
import type { ContactResolveResult, TrustedContact } from '../../contacts';
import { isPackageInstalled, launchPackage, openUriWithPackage } from '../../action-engine/androidActionExecutor';
import { enterPipMode } from 'benson-app-registry';
import { hasOverlayPermission, showBubble } from 'benson-overlay';
import { waitForBackground } from '../appStateSignal';
import type { ToolCallResult } from '../missionTypes';
import { ensureAccessibilityReady, ACCESSIBILITY_DISCONNECTED_ERROR } from '../../safety';

const LOG_TAG = '[WhatsAppTool]';
function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

const WHATSAPP_PACKAGE = 'com.whatsapp';

// Linking.canOpenURL('whatsapp://send') was used here originally and reported a false negative
// on a real device with WhatsApp actually installed — Android's package-visibility rules treat
// package-IDENTITY checks (PackageManager.getPackageInfo, what <queries><package> grants) and
// implicit-INTENT-resolution checks (queryIntentActivities, what canOpenURL performs) as
// separate concerns; a <package> queries entry doesn't reliably satisfy the latter. The native
// isPackageInstalled() (benson-app-registry) already does the identity check correctly and is
// proven working for Waze detection elsewhere in this codebase — reused here instead.
export async function isInstalled(): Promise<boolean> {
  return isPackageInstalled(WHATSAPP_PACKAGE);
}

export async function hasContactsPermission(): Promise<boolean> {
  const perm = await Contacts.getPermissionsAsync();
  return perm.status === 'granted';
}

// deviceContacts is injected by the caller when a test/stub list should take priority (e.g. the
// TEST_CONTACTS stand-in already used elsewhere in this app before real contact memory exists) —
// falls back to real expo-contacts data otherwise. Never a second, parallel matching algorithm.
export async function resolveContact(name: string, extraContacts: TrustedContact[] = []): Promise<ContactResolveResult> {
  const deviceContacts = await loadDeviceContacts();
  const merged = [...extraContacts, ...deviceContacts];
  return resolveAgainstList({ rawName: name, preferredChannel: 'whatsapp', contacts: merged });
}

// Only a leading '+' followed by 7-15 digits is treated as a confirmed E.164 number — a bare
// digit string with no country code prefix can't be reliably normalized (a Romanian 07xx number
// and a German 07xx number look identical without it), so this returns null rather than guess.
export function toE164(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed.startsWith('+')) return null;
  const digits = trimmed.replace(/[^\d]/g, '');
  if (digits.length < 7 || digits.length > 15) return null;
  return `+${digits}`;
}

// "Benson stays a bubble" (product-owner-directed 2026-07-17, replacing the earlier real-PiP
// approach): BENSON shows as a small, draggable overlay bubble at the bottom of the screen right
// before WhatsApp takes the foreground — a plain WindowManager overlay, not Android's real
// Picture-in-Picture/multi-window. PiP was dropped because ColorOS's own flexible-window layer
// kept reinterpreting it unpredictably (confirmed live: sometimes a tiny illegible window,
// sometimes indistinguishable from Expo's own dev-tools overlay); a system overlay bubble has no
// such OS-level ambiguity — it's the exact same mechanism the wake-ring already uses successfully
// on top of any foreground app. The reverse (WhatsApp shrunk, BENSON full-screen) still isn't
// possible; Android has no API to force a different app into a small window. Falls back to real
// PiP only if the user hasn't granted the overlay permission yet, so the handoff still does
// *something* visible rather than nothing.
async function enterPipBeforeWhatsApp(): Promise<void> {
  try {
    if (await hasOverlayPermission()) {
      showBubble();
    } else {
      enterPipMode();
    }
  } catch (err) {
    devLog('enterPipBeforeWhatsApp: bubble/PiP threw', err);
  }
}

export function maskPhone(raw: string): string {
  const digits = raw.replace(/[^\d]/g, '');
  if (digits.length <= 4) return '••••';
  return `••••${digits.slice(-4)}`;
}

// Logs must never carry a raw phone number (standing privacy constraint) — this redacts any
// 7+ digit run (the phone number embedded in a wa.me/<digits> URL) before a url is ever logged,
// keeping the rest of the URL/query readable for debugging.
function redactUrlForLog(url: string): string {
  return url.replace(/\d{7,}/g, (digits) => `••••${digits.slice(-4)}`);
}

export function buildWaMeUrl(e164Phone: string, message?: string): string {
  const digits = e164Phone.replace(/[^\d]/g, ''); // wa.me wants bare digits, no leading '+'
  const base = `https://wa.me/${digits}`;
  return message ? `${base}?text=${encodeURIComponent(message)}` : base;
}

// Explicit-package intent (Intent.setPackage), not a plain implicit ACTION_VIEW — confirmed live
// 2026-07-17: Linking.openURL('https://wa.me/...') let Android silently pick whichever app is the
// current default handler for that link, which on a phone with both WhatsApp and WhatsApp
// Business installed turned out to be Business, sending the message from the wrong account.
// Targeting WHATSAPP_PACKAGE explicitly guarantees regular WhatsApp regardless of the OS default.
async function tryOpen(url: string): Promise<boolean> {
  const outcome = openUriWithPackage(url, WHATSAPP_PACKAGE);
  if (!outcome.success) {
    devLog('open failed', redactUrlForLog(url), outcome.error);
  }
  return outcome.success;
}

async function attemptAndConfirm(url: string): Promise<ToolCallResult> {
  const accepted = await tryOpen(url);
  if (!accepted) return { outcome: 'launch_failed', error: 'Could not open WhatsApp.' };
  const wentBackground = await waitForBackground(3000);
  devLog('accepted', redactUrlForLog(url), 'app_switch_observed=', wentBackground);
  return { outcome: wentBackground ? 'app_switch_observed' : 'launch_requested' };
}

// Bare "open WhatsApp" (no chat/contact) — deliberately NOT a Linking deep link. whatsapp://
// with no path was tried first and reported a silent no-op on a real device (Linking.openURL
// resolved without throwing, but no activity actually handled it — WhatsApp apparently doesn't
// register a bare-scheme handler). Native package launch is the proven mechanism already used
// for this exact case elsewhere in the codebase (appLauncherExecutor.ts's OPEN_WHATSAPP branch).
export async function openApp(): Promise<ToolCallResult> {
  enterPipBeforeWhatsApp();
  const outcome = launchPackage(WHATSAPP_PACKAGE);
  if (!outcome.success) {
    return { outcome: 'launch_failed', error: outcome.error ?? 'Could not launch WhatsApp.' };
  }
  const wentBackground = await waitForBackground(3000);
  devLog('launchPackage', WHATSAPP_PACKAGE, 'app_switch_observed=', wentBackground);
  return { outcome: wentBackground ? 'app_switch_observed' : 'launch_requested' };
}

// Opens the conversation with the message pre-filled — WhatsApp itself is what "sends". Used
// directly only for the no-message "openContact" case; sendMessage (below) is what actually gets
// the message sent when there is one.
export async function openConversation(e164Phone: string, message?: string): Promise<ToolCallResult> {
  return attemptAndConfirm(buildWaMeUrl(e164Phone, message));
}

// Product-owner-authorized (2026-07-17): confirming once — by voice, before this ever runs — is
// enough; the user should never also have to reach into WhatsApp and tap send a second time.
// Opens the chat with the wa.me deep link (the only way to get text into WhatsApp's compose
// field at all — same as openConversation), then taps the native send button via the
// Accessibility Service. The final UI interaction now runs through the generic, JS-driven
// step-list executor (BensonCommandExecutor.kt) instead of the bundled declarative profile —
// being tried here first as the simplest real-device test of that engine, per instruction,
// before any other flow (placeWhatsAppCall) is touched. Still on the native service coroutine,
// so ColorOS cannot stall it. Falls back to "opened, manual send required" honestly if the
// button can't be found/tapped — never silently claims sent.
export async function sendMessage(e164Phone: string, message: string): Promise<ToolCallResult> {
  // Set BEFORE Linking.openURL (not just inside the native step-executor call below) — confirmed
  // live 2026-07-17: Guardian's resurrection check runs on its own event, independent of this
  // function's own timing, and was observed firing in the gap between WhatsApp opening and the
  // native send step even starting, yanking focus back to BENSON before the send button was ever
  // searched for. Always cleared in the finally block, however this function exits.
  setWhatsAppAutomationActive(true);
  try {
    enterPipBeforeWhatsApp();
    const opened = await openConversation(e164Phone, message);
    if (opened.outcome === 'launch_failed') return opened;

    const guard = await ensureAccessibilityReady();
    if (!guard.ready) {
      devLog('sendMessage: accessibility not ready, state=', guard.state, '- message left pre-filled, not sent');
      return { outcome: 'opened_manual_action_required', error: ACCESSIBILITY_DISCONNECTED_ERROR };
    }

    const result = await executeCommand({
      steps: [
        { action: 'assert_package', package: 'com.whatsapp', timeoutMs: 3000 },
        { action: 'click', match: { textContains: 'senden', clickable: true }, requirePackage: 'com.whatsapp' },
        { action: 'wait', ms: 1200 },
        { action: 'return_to_benson' },
      ],
    });
    devLog('sendMessage (command executor):', result);
    if (result.success) return { outcome: 'app_switch_observed', via: 'accessibility_command_executor' };
    return {
      outcome: 'opened_manual_action_required',
      error: result.detail ?? `Stopped at step ${result.stepIndex} (${result.action}): ${result.status}`,
    };
  } finally {
    setWhatsAppAutomationActive(false);
  }
}

// Doctrine (product-owner-directed 2026-07-31): BENSON does not read the phone's contact list
// for the WhatsApp call route at all, and does not resolve a "contact" — it governs WhatsApp's
// own UI, the same way a human would: open it, tap search, type a search string, tap the first
// result, tap the call button. Resolving that string against a real person is WhatsApp's job, not
// BENSON's. The caller (missionValidator.ts's placeCall branch) is responsible for turning the
// raw spoken text into a clean search string via buildCallSearchString before this ever runs.
//
// Every step below is a generic accessibility step through executeCommand (BensonCommandExecutor,
// unchanged native code) — the exact same primitive already used by sendMessage() above. No
// device contacts, no native placeWhatsAppCall call, no fallback if a step doesn't find its node:
// executeCommand already stops at the first problem and reports which step, honored as-is here.

// Per-language WhatsApp UI labels (product-owner-directed extraction — these were previously two
// bare hardcoded German strings). Only 'de' is confirmed live on this device (see
// BensonAccessibilityService.kt's SEARCH_KEYWORDS/CALL_KEYWORDS comments, which found German
// labels working live and kept EN/RO only as an untested fallback) — 'en'/'ro' reuse the exact
// same fallback words already chosen there for consistency; 'fr' has no prior art anywhere in
// this codebase and is an unverified best guess. Keyed by base language (first two letters of a
// BCP-47 tag), not the full tag, matching how the rest of this codebase already normalizes
// language codes (e.g. localWhisperEngine.ts's lang.split('-')[0]).
const WHATSAPP_UI_LABELS: Record<string, { search: string; voiceCall: string; send: string }> = {
  de: { search: 'suchen', voiceCall: 'sprachanruf', send: 'senden' }, // confirmed live on this device
  en: { search: 'search', voiceCall: 'voice call', send: 'send' }, // unverified — reused from CALL_KEYWORDS/SEARCH_KEYWORDS fallback list
  ro: { search: 'căutare', voiceCall: 'apel vocal', send: 'trimite' }, // unverified — reused from CALL_KEYWORDS/SEARCH_KEYWORDS fallback list
  fr: { search: 'rechercher', voiceCall: 'appel vocal', send: 'envoyer' }, // unverified — no prior art in this codebase for French at all
};
const DEFAULT_WHATSAPP_UI_LANG = 'de'; // preserves exact prior behavior when no language is supplied

function whatsappUiLabels(uiLang: string): { search: string; voiceCall: string; send: string } {
  const base = uiLang.split('-')[0].toLowerCase();
  return WHATSAPP_UI_LABELS[base] ?? WHATSAPP_UI_LABELS[DEFAULT_WHATSAPP_UI_LANG];
}

// "voice call" label is distinct from "videoanruf"/"video call" in every language above, so no
// separate video-exclusion match is needed.
const WHATSAPP_SEARCH_INPUT_VIEW_ID = 'com.whatsapp:id/search_input';
// Message compose field, confirmed live elsewhere in this codebase (BensonAccessibilityService.kt
// already uses this exact viewId to detect "inside an individual chat"). Reused here for
// set_text — same accessibility action (ACTION_SET_TEXT) already proven on search_input above,
// applied to a different but structurally identical EditText. UNTESTED for this specific
// combination (typing via accessibility into THIS field, as opposed to WhatsApp's own wa.me
// URL-prefill mechanism the old sendMessage() below uses) — first thing to verify live.
const WHATSAPP_ENTRY_VIEW_ID = 'com.whatsapp:id/entry';

// Revert switch (product-owner-directed 2026-08-01, doctrine enforcement): true routes
// openContact/prepareMessage through the same no-contacts-read accessibility recipe pattern as
// placeCall (search by name string, tap first result, [type+send]). false restores the exact old
// path below (openConversation/sendMessage, wa.me deep link, phoneNumber from resolved device
// contacts) — untouched, still present, still exported. Flip this one constant to go back.
export const WHATSAPP_MESSAGE_VIA_ACCESSIBILITY = true;

// Strips leading Romanian clitic pronouns/prepositions that attach to a spoken name (sună-O PE
// Hannah, caută-L Ion) so the governance recipe below searches WhatsApp for a clean name, never a
// raw transcript fragment. commandParser.ts's own CALL_CONTACT_PATTERNS/WHATSAPP_CALL_PATTERNS
// already strip most of these inside their own capture groups — this is a safety net for whatever
// leaks through when the STT-produced word order doesn't match one of those patterns' optional
// groups exactly. Confirmed live (2026-07-31): STT fused the clitic "o" directly onto "pe" with no
// space ("sună-o pe Hannah" -> captured contactName "Upe Hana"), which no single-token strip could
// catch — "upe" is listed explicitly as its own fused alternative for that reason. Runs in a loop
// so more than one leaked token in a row ("-l pe Ion") is fully stripped, not just the first.
const LEADING_CLITIC_OR_PREPOSITION = /^[\s-]*(?:upe|o|u|l|le|îl|il|i|pe|la|lui)\b[\s-]*/i;

export function buildCallSearchString(rawName: string): string {
  let text = rawName.trim();
  let previous: string;
  do {
    previous = text;
    text = text.replace(LEADING_CLITIC_OR_PREPOSITION, '').trim();
  } while (text !== previous && text.length > 0);
  return text;
}

interface RecipeStep {
  label: string; // Romanian, human-facing — what to say if THIS step's node isn't found.
  step: CommandStep;
}

function buildCallRecipe(searchString: string, uiLang: string): RecipeStep[] {
  const labels = whatsappUiLabels(uiLang);
  return [
    { label: 'WhatsApp nu s-a putut lansa', step: { action: 'launch_app', package: WHATSAPP_PACKAGE } },
    { label: 'așteptare deschidere WhatsApp', step: { action: 'wait', ms: 900 } },
    { label: 'WhatsApp nu a ajuns în prim-plan', step: { action: 'assert_package', package: WHATSAPP_PACKAGE, timeoutMs: 4000 } },
    { label: 'așteptare ecran principal', step: { action: 'wait', ms: 400 } },
    {
      label: 'butonul de căutare',
      step: { action: 'click', match: { textContains: labels.search, clickable: true }, timeoutMs: 4000, requirePackage: WHATSAPP_PACKAGE },
    },
    { label: 'așteptare câmp de căutare', step: { action: 'wait', ms: 500 } },
    {
      label: 'câmpul de căutare',
      step: { action: 'set_text', match: { viewId: WHATSAPP_SEARCH_INPUT_VIEW_ID }, text: searchString, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE },
    },
    { label: 'așteptare rezultate căutare', step: { action: 'wait', ms: 900 } },
    {
      label: `primul rezultat pentru "${searchString}"`,
      step: {
        action: 'click',
        match: { textContains: searchString, wholeWord: true, excludeSearchUi: true, excludeAvatars: true, clickableAncestor: true },
        timeoutMs: 3000,
        requirePackage: WHATSAPP_PACKAGE,
      },
    },
    { label: 'așteptare deschidere conversație', step: { action: 'wait', ms: 900 } },
    { label: 'confirmare părăsire ecran de căutare', step: { action: 'assert_gone', match: { viewIdContains: 'search_input' }, timeoutMs: 3000 } },
    {
      label: 'butonul de apel vocal',
      step: { action: 'click', match: { textContains: labels.voiceCall, clickable: true, maxTopPercent: 15 }, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE },
    },
    { label: 'așteptare pornire apel', step: { action: 'wait', ms: 600 } },
    { label: 'revenire la BENSON', step: { action: 'return_to_benson' } },
  ];
}

export async function placeCall(searchString: string, uiLang: string = DEFAULT_WHATSAPP_UI_LANG): Promise<ToolCallResult> {
  const name = searchString.trim();
  if (!name) return { outcome: 'launch_failed', error: 'No search text was given.' };

  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('placeCall: accessibility not ready, state=', guard.state, '- aborting without touching WhatsApp');
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }
  enterPipBeforeWhatsApp();
  setWhatsAppAutomationActive(true);
  try {
    const recipe = buildCallRecipe(name, uiLang);
    const result = await executeCommand({ steps: recipe.map((r) => r.step) });
    devLog('placeCall (governance recipe):', result);
    if (result.success) return { outcome: 'app_switch_observed', via: 'accessibility_command_executor' };

    // Honest, specific failure — names exactly which step of the recipe stopped. Never a raw
    // JSON dump, never a guess at an alternative action, never a fallback to the old native
    // placeWhatsAppCall flow.
    const failedLabel = recipe[result.stepIndex]?.label ?? `pasul ${result.stepIndex}`;
    return {
      outcome: 'opened_manual_action_required',
      error: `Nu am găsit: ${failedLabel}${result.detail ? ` (${result.detail})` : ''}`,
    };
  } finally {
    setWhatsAppAutomationActive(false);
  }
}

// Shared prefix for openContact/prepareMessage recipes (product-owner-directed 2026-08-01,
// doctrine enforcement — "zero data integration", same reasoning as buildCallRecipe above): open
// WhatsApp, search by name string, tap the first result — identical to buildCallRecipe up through
// "chat confirmed open", just without the call-button steps. Kept as its own function so
// openContact/prepareMessage don't duplicate it.
function buildOpenChatSteps(searchString: string, uiLang: string): RecipeStep[] {
  const labels = whatsappUiLabels(uiLang);
  return [
    { label: 'WhatsApp nu s-a putut lansa', step: { action: 'launch_app', package: WHATSAPP_PACKAGE } },
    { label: 'așteptare deschidere WhatsApp', step: { action: 'wait', ms: 900 } },
    { label: 'WhatsApp nu a ajuns în prim-plan', step: { action: 'assert_package', package: WHATSAPP_PACKAGE, timeoutMs: 4000 } },
    { label: 'așteptare ecran principal', step: { action: 'wait', ms: 400 } },
    {
      label: 'butonul de căutare',
      step: { action: 'click', match: { textContains: labels.search, clickable: true }, timeoutMs: 4000, requirePackage: WHATSAPP_PACKAGE },
    },
    { label: 'așteptare câmp de căutare', step: { action: 'wait', ms: 500 } },
    {
      label: 'câmpul de căutare',
      step: { action: 'set_text', match: { viewId: WHATSAPP_SEARCH_INPUT_VIEW_ID }, text: searchString, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE },
    },
    { label: 'așteptare rezultate căutare', step: { action: 'wait', ms: 900 } },
    {
      label: `primul rezultat pentru "${searchString}"`,
      step: {
        action: 'click',
        match: { textContains: searchString, wholeWord: true, excludeSearchUi: true, excludeAvatars: true, clickableAncestor: true },
        timeoutMs: 3000,
        requirePackage: WHATSAPP_PACKAGE,
      },
    },
    { label: 'așteptare deschidere conversație', step: { action: 'wait', ms: 900 } },
    { label: 'confirmare părăsire ecran de căutare', step: { action: 'assert_gone', match: { viewIdContains: 'search_input' }, timeoutMs: 3000 } },
  ];
}

async function runRecipe(recipe: RecipeStep[]): Promise<ToolCallResult> {
  const result = await executeCommand({ steps: recipe.map((r) => r.step) });
  devLog('recipe result:', result);
  if (result.success) return { outcome: 'app_switch_observed', via: 'accessibility_command_executor' };
  const failedLabel = recipe[result.stepIndex]?.label ?? `pasul ${result.stepIndex}`;
  return {
    outcome: 'opened_manual_action_required',
    error: `Nu am găsit: ${failedLabel}${result.detail ? ` (${result.detail})` : ''}`,
  };
}

// Search-by-name equivalent of openConversation() above — no phone number, no device contacts.
// Opens the chat and stops there (no message to send).
export async function openContactByName(searchString: string, uiLang: string = DEFAULT_WHATSAPP_UI_LANG): Promise<ToolCallResult> {
  const name = searchString.trim();
  if (!name) return { outcome: 'launch_failed', error: 'No search text was given.' };
  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('openContactByName: accessibility not ready, state=', guard.state);
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }
  enterPipBeforeWhatsApp();
  setWhatsAppAutomationActive(true);
  try {
    const recipe = [...buildOpenChatSteps(name, uiLang), { label: 'revenire la BENSON', step: { action: 'return_to_benson' as const } }];
    return await runRecipe(recipe);
  } finally {
    setWhatsAppAutomationActive(false);
  }
}

// Search-by-name equivalent of sendMessage() above — no phone number, no device contacts. Opens
// the chat, types the message into the compose field (WHATSAPP_ENTRY_VIEW_ID — see its own doc
// comment re: untested), taps send.
export async function sendMessageByName(searchString: string, message: string, uiLang: string = DEFAULT_WHATSAPP_UI_LANG): Promise<ToolCallResult> {
  const name = searchString.trim();
  if (!name) return { outcome: 'launch_failed', error: 'No search text was given.' };
  const labels = whatsappUiLabels(uiLang);
  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('sendMessageByName: accessibility not ready, state=', guard.state);
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }
  enterPipBeforeWhatsApp();
  setWhatsAppAutomationActive(true);
  try {
    const recipe: RecipeStep[] = [
      ...buildOpenChatSteps(name, uiLang),
      {
        label: 'câmpul de scriere a mesajului',
        step: { action: 'set_text', match: { viewId: WHATSAPP_ENTRY_VIEW_ID }, text: message, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE },
      },
      { label: 'așteptare scriere mesaj', step: { action: 'wait', ms: 400 } },
      {
        label: 'butonul de trimitere',
        step: { action: 'click', match: { textContains: labels.send, clickable: true }, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE },
      },
      { label: 'așteptare trimitere mesaj', step: { action: 'wait', ms: 1200 } },
      { label: 'revenire la BENSON', step: { action: 'return_to_benson' } },
    ];
    return await runRecipe(recipe);
  } finally {
    setWhatsAppAutomationActive(false);
  }
}

// endCall/muteCall: control an ALREADY-active WhatsApp call (placed via placeCall or manually).
// Same native, non-JS-timer execution as placeCall, and the same honest-failure contract — never
// claims the call was ended/muted unless the native side actually found and tapped the button.
export async function endCall(): Promise<ToolCallResult> {
  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('endCall: accessibility not ready, state=', guard.state, '- aborting without touching WhatsApp');
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }
  enterPipBeforeWhatsApp();
  const result = await endWhatsAppCall();
  devLog('endCall (native):', result);
  if (result.success) return { outcome: 'app_switch_observed', via: 'accessibility_native_state_machine' };
  return { outcome: 'opened_manual_action_required', error: result.error ?? `Stopped at step: ${result.step}` };
}

export async function muteCall(): Promise<ToolCallResult> {
  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('muteCall: accessibility not ready, state=', guard.state, '- aborting without touching WhatsApp');
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }
  enterPipBeforeWhatsApp();
  const result = await muteWhatsAppCall();
  devLog('muteCall (native):', result);
  if (result.success) return { outcome: 'app_switch_observed', via: 'accessibility_native_state_machine' };
  return { outcome: 'opened_manual_action_required', error: result.error ?? `Stopped at step: ${result.step}` };
}
