// BENSON Mission Governance — Mission Executor.
// THE single entry point for every Waze/WhatsApp Android side effect. Nothing else in the app
// may call src/core/mission/tools/*.ts directly — always through execute() below, so validation
// and confirmation can never be skipped regardless of which caller (deterministic Mission
// Orchestrator, Claude tool-use) originated the request.
//
// Mandated order (governance clarification 7): ActionRequest -> schema validation -> parameter
// validation -> install/permission preflight -> mission persistence -> confirmation (if required)
// -> mission persistence -> Android side effect -> structured launch result -> mission state
// update -> user-facing response. No Android side effect may occur before every gate above it.

import type { TrustedContact } from '../contacts';
import { ACCESSIBILITY_DISCONNECTED_ERROR, ACCESSIBILITY_DOWN_SPOKEN_MESSAGE_RO } from '../safety';
import { validateActionRequest } from './missionValidator';
import { getActiveMission, persistMission, transitionMission, clearIfTerminal, setLastWazeDestination } from './missionStore';
import * as wazeTool from './tools/wazeTool';
import * as whatsappTool from './tools/whatsappTool';
import type { ActionRequest, LaunchOutcome, Mission, MissionAction, ToolName } from './missionTypes';
import { logAudioDiag } from 'benson-foreground-service';

const LOG_TAG = '[MissionExecutor]';
function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

let counter = 0;
function nextId(prefix: string): string {
  counter += 1;
  return `${prefix}_${Date.now()}_${counter}`;
}

export function buildActionRequest(tool: ToolName, action: MissionAction, params: Record<string, unknown>): ActionRequest {
  return {
    id: nextId('req'),
    tool,
    action,
    params,
    // Placeholder — requiredConfirmationFor() below is the single source of truth and overrides
    // whatever a caller (including an LLM-originated request) passes here. Never trusted as-is.
    requiresConfirmation: false,
    validationStatus: 'pending',
    createdAt: Date.now(),
  };
}

// Hardcoded policy (governance clarification 4) — the LLM cannot disable this gate by omitting or
// zeroing requiresConfirmation on the request it produces; this function is the only thing that
// decides, every time, regardless of caller input.
function requiredConfirmationFor(tool: ToolName, action: MissionAction): boolean {
  if (tool === 'whatsapp') {
    // placeCall re-gated (2026-07-17, reversing the same-day no-confirmation change): confirmed
    // live that WhatsApp's own in-app search can surface the WRONG contact as a valid whole-word
    // match (searching "Mama" called a completely different saved contact whose name merely ended
    // in the word "mama") — the exact-match-first fix in BensonAccessibilityService.kt reduces
    // this, but doesn't eliminate it for names that are themselves ambiguous across contacts.
    // buildConfirmationPrompt already states which contact was resolved ("Îl sun pe X pe
    // WhatsApp. Confirmi?") — that's the user's chance to catch a wrong match before it dials,
    // which "no confirmation for calls" traded away. A sent message stays gated for the same
    // reason it always was: it can't be recalled the way an unanswered call can be hung up.
    return action === 'openContact' || action === 'prepareMessage' || action === 'placeCall';
  }
  return false; // waze: navigation is never gated behind confirmation in this phase
}

export interface ExecuteOutcome {
  mission: Mission;
  message: string;
}

export async function execute(request: ActionRequest, options: { confirmed?: boolean } = {}, contacts: TrustedContact[] = []): Promise<ExecuteOutcome> {
  const requiresConfirmation = requiredConfirmationFor(request.tool, request.action);
  const normalizedRequest: ActionRequest = { ...request, requiresConfirmation };

  let mission: Mission = {
    id: nextId('mission'),
    request: normalizedRequest,
    state: 'Pending',
    userMessage: '',
    createdAt: Date.now(),
    updatedAt: Date.now(),
  };
  await persistMission(mission);
  mission = (await transitionMission('Validating'))!;

  const validation = await validateActionRequest(normalizedRequest, contacts);
  if (!validation.valid) {
    mission = (await transitionMission('Failed', {
      reason: validation.reason,
      userMessage: validation.reason ?? 'That could not be completed.',
      request: { ...normalizedRequest, validationStatus: 'invalid' },
    }))!;
    await clearIfTerminal();
    devLog('validation failed', validation.reason);
    return { mission, message: mission.userMessage };
  }

  const enrichedRequest: ActionRequest = {
    ...normalizedRequest,
    params: { ...normalizedRequest.params, ...(validation.enrichedParams ?? {}) },
    validationStatus: 'valid',
  };

  // Mission persistence (1st of 2 mandated points) — validated, before confirmation is asked.
  mission = (await transitionMission('Pending', { request: enrichedRequest }))!;

  // Reverted 2026-07-17, second time: the two-phase "open the chat, THEN ask Confirmi?" flow has
  // now broken live twice in one day (first from an unbounded confirmation gap, then again even
  // with a bounded timeout + foreground-check, from a ~80s real-world gap landing on a stale
  // search). Back to asking "Confirmi?" first, with no Android side effect yet, exactly per the
  // mandated order above — the version actually proven reliable across many back-to-back live
  // calls. Not worth re-attempting the show-before-confirm idea without a fundamentally different
  // mechanism than a live confirmation round-trip.
  if (requiresConfirmation && !options.confirmed) {
    // Bad-payload guard (product-owner-directed) — confirmed live: the gate asked the user to
    // confirm a prepareMessage with an empty message ('mesajul: ""') and a placeCall search
    // string that was a raw, torn STT fragment (', Pehana, Pehatsap.'). Checked here, the single
    // choke point for every Waze/WhatsApp confirmation, so it covers placeCall/prepareMessage/
    // openContact uniformly without touching each action's own resolution logic in
    // missionValidator.ts/whatsappTool.ts. Never opens the gate on a bad payload — asks for the
    // command to be reformulated instead.
    const badPayloadReason = findBadConfirmationPayload(enrichedRequest);
    if (badPayloadReason) {
      const userMessage = 'Nu am înțeles clar comanda — poți s-o spui din nou?';
      mission = (await transitionMission('Failed', {
        reason: badPayloadReason,
        userMessage,
        request: { ...enrichedRequest, validationStatus: 'invalid' },
      }))!;
      await clearIfTerminal();
      devLog('rejected bad payload before confirmation gate', enrichedRequest.tool, enrichedRequest.action, badPayloadReason);
      return { mission, message: userMessage };
    }

    // ROUND_WA_GOVERNANCE_WRITE_1B — for a WhatsApp message WITH a body, type the requested text
    // into the VERIFIED compose field now (Doctrine-6 approved: typing is not externally
    // consequential), then gate SEND behind this confirmation. Phase A does resolve → open →
    // verify identity → find input → type → verify-typed, and STOPS. `enrichedRequest.id` is the
    // stable idempotency key (preserved verbatim into the persisted request and reused by
    // confirmActiveMission → Phase B). If the direct write is unavailable (flag off / no contacts
    // permission / not locally resolvable) Phase A returns 'skipped' and the existing
    // confirm-then-sendMessageByName flow is kept unchanged.
    const phaseA = await maybeRunWhatsAppWritePhaseA(enrichedRequest);
    if (phaseA.kind === 'failed') {
      mission = (await transitionMission('Failed', {
        reason: phaseA.reason,
        userMessage: phaseA.userMessage,
        request: { ...enrichedRequest, validationStatus: 'valid' },
      }))!;
      await clearIfTerminal();
      devLog('wa-write phase A failed', phaseA.reason);
      return { mission, message: phaseA.userMessage };
    }

    let gateRequest: ActionRequest = enrichedRequest;
    let confirmMessage: string;
    if (phaseA.kind === 'typed') {
      // Carry the send parameters through the confirmation round-trip on the persisted request.
      gateRequest = {
        ...enrichedRequest,
        params: {
          ...enrichedRequest.params,
          waWriteTyped: true,
          waWriteMissionId: enrichedRequest.id,
          waWriteMessage: phaseA.message,
          waWriteContact: phaseA.contact,
        },
      };
      confirmMessage = `Am scris în conversația cu ${phaseA.contact} pe WhatsApp: "${phaseA.message}". Îl trimit?`;
    } else {
      confirmMessage = buildConfirmationPrompt(enrichedRequest);
    }
    mission = (await transitionMission('WaitingConfirmation', { userMessage: confirmMessage, request: gateRequest }))!;
    devLog('needs confirmation', enrichedRequest.tool, enrichedRequest.action, phaseA.kind);
    return { mission, message: confirmMessage };
  }

  // Mission persistence (2nd of 2 mandated points) — immediately before the Android side effect.
  mission = (await transitionMission('Running'))!;

  // ROUND_WA_GOVERNANCE_WRITE_1B — PHASE B. YES on a prepareMessage whose text was already typed
  // & verified at mission-create: this is the SEND. confirmSendMessageDirect is idempotent in
  // native (SEND_ATTEMPTED persisted before the tap → a re-entry re-VERIFIES, never re-presses),
  // and refuses to send unless identity + typed text were verified earlier.
  let result: { outcome: LaunchOutcome; via?: string; error?: string };
  if (enrichedRequest.params.waWriteTyped === true) {
    const missionId = String(enrichedRequest.params.waWriteMissionId ?? enrichedRequest.id);
    const message = String(enrichedRequest.params.waWriteMessage ?? enrichedRequest.params.message ?? '');
    const contact = String(enrichedRequest.params.waWriteContact ?? enrichedRequest.params.contactName ?? 'contact');
    result = await whatsappTool.confirmSendMessageDirect(missionId, message, contact);
    devLog('wa-write phase B', result);
  } else {
    result = await runTool(enrichedRequest);
  }
  devLog('tool result', enrichedRequest.tool, enrichedRequest.action, result);

  if (result.outcome === 'launch_failed') {
    mission = (await transitionMission('Failed', { reason: result.error, userMessage: buildFailureMessage(enrichedRequest, result.error) }))!;
    await clearIfTerminal();
    return { mission, message: mission.userMessage };
  }

  if (enrichedRequest.tool === 'waze' && enrichedRequest.action !== 'openApp') {
    const destination = typeof enrichedRequest.params.destination === 'string' ? enrichedRequest.params.destination : undefined;
    if (destination) await setLastWazeDestination(destination);
  }

  const waitingMessage = buildWaitingUserMessage(enrichedRequest, result);
  mission = (await transitionMission('WaitingUser', { userMessage: waitingMessage }))!;
  return { mission, message: waitingMessage };
}

async function runTool(request: ActionRequest): Promise<{ outcome: LaunchOutcome; via?: string; error?: string }> {
  if (request.tool === 'waze') {
    if (request.action === 'openApp') return wazeTool.openApp();
    const destination = String(request.params.destination ?? '');
    return request.action === 'openSearch' ? wazeTool.openSearch(destination) : wazeTool.openNavigation(destination);
  }
  if (request.action === 'openApp') return whatsappTool.openApp();
  if (request.action === 'placeCall') {
    // No phone number: WhatsApp's own search is used by name, same as a human would. uiLang
    // (WhatsApp's own display language, not BENSON's app language — the two can differ, as
    // confirmed on this device) isn't populated by any caller yet; whatsappTool.placeCall
    // defaults to 'de' when absent, identical to the previously-hardcoded behavior.
    const uiLang = typeof request.params.uiLang === 'string' ? request.params.uiLang : undefined;
    return whatsappTool.placeCall(String(request.params.contactName ?? ''), uiLang);
  }
  if (request.action === 'endCall') return whatsappTool.endCall();
  if (request.action === 'muteCall') return whatsappTool.muteCall();

  // ROUND_WA_GOVERNANCE_ROUTING — `prepareMessage` never reaches runTool: Phase A (mission
  // create) + Phase B (confirmSendMessageDirect on YES) own the whole message lifecycle. If it
  // gets here, the direct-write path was disabled (WA_WRITE_DIRECT=false) — fail loud, never
  // silently open a chat instead.
  if (request.action === 'prepareMessage') {
    return { outcome: 'launch_failed', error: 'Scrierea de mesaje pe WhatsApp e dezactivată momentan.' };
  }
  // `openContact` — an explicit open-the-chat intent only (never a messaging fallback).
  const uiLang = typeof request.params.uiLang === 'string' ? request.params.uiLang : undefined;
  return whatsappTool.openContactByName(String(request.params.contactName ?? ''), uiLang);
}

// A real payload never starts with punctuation/quotes and is never a bare literal quote
// character — a torn STT fragment often does exactly that (', Pehana, Pehatsap.'). Deliberately
// NOT rejecting on internal commas alone — a real message can legitimately contain one
// ("spune-i că vin, dar mai târziu") — only the leading-punctuation and stray-quote signals are
// specific enough to fragmentation artifacts to act on without risking a false rejection.
const GARBLED_PAYLOAD_LEADING_PATTERN = /^[.,!?;:"'\s]/;
function isBadPayload(value: string | undefined): boolean {
  if (!value) return true;
  const trimmed = value.trim();
  if (trimmed.length < 2) return true;
  if (GARBLED_PAYLOAD_LEADING_PATTERN.test(trimmed)) return true;
  if (trimmed.includes('"')) return true;
  return false;
}

// Which param(s) are the user-facing payload for a given action — only those are checked, so
// e.g. openApp/endCall/muteCall (no name/message of their own) are never affected.
function findBadConfirmationPayload(request: ActionRequest): string | null {
  if (request.tool !== 'whatsapp') return null;
  if (request.action === 'placeCall' || request.action === 'openContact' || request.action === 'prepareMessage') {
    const contactName = typeof request.params.contactName === 'string' ? request.params.contactName : undefined;
    if (isBadPayload(contactName)) return `Search/contact payload is empty or looks like a torn STT fragment: "${contactName ?? ''}".`;
  }
  if (request.action === 'prepareMessage') {
    const message = typeof request.params.message === 'string' ? request.params.message : undefined;
    // ROUND_WA_GOVERNANCE_ROUTING — an EMPTY body is handled downstream as MESSAGE_BODY_MISSING
    // (a specific "ce să-i scriu?" prompt), NOT a bad payload. Only a non-empty but torn-STT body
    // is rejected here.
    if (message !== undefined && message.trim() !== '' && isBadPayload(message)) {
      return `Message payload looks like a torn STT fragment: "${message}".`;
    }
  }
  return null;
}

// ROUND_WA_GOVERNANCE_ROUTING — PHASE A gate.
//   'skipped' → not a `prepareMessage` (openContact / placeCall) → normal confirmation flow.
//   'typed'   → message staged & verified in the compose field; ask for the SEND confirmation.
//   'failed'  → HARD failure (MESSAGE_BODY_MISSING / CONTACT_UNRESOLVED / CONTACTS_PERMISSION /
//               LAUNCH_FAILED / PHASE_A_STOPPED). The message was NEVER staged, NOTHING was sent,
//               and there is NO fallback to the old open/search flow — BENSON says exactly what
//               failed.
type PhaseAOutcome =
  | { kind: 'skipped' }
  | { kind: 'typed'; message: string; contact: string }
  | { kind: 'failed'; reason: string; userMessage: string };

async function maybeRunWhatsAppWritePhaseA(request: ActionRequest): Promise<PhaseAOutcome> {
  if (request.tool !== 'whatsapp' || request.action !== 'prepareMessage') return { kind: 'skipped' };

  const contact = String(request.params.contactName ?? '').trim();
  const message = typeof request.params.message === 'string' ? request.params.message.trim() : '';
  logAudioDiag('WA_PAYLOAD_MISSION', `contact=${JSON.stringify(contact)} message=${JSON.stringify(message)}`);
  logAudioDiag('WA_MSG_PHASE_A_ENTER', `mission=${request.id} contact=${JSON.stringify(contact)} msgLen=${message.length}`);

  // Hard invariant — an empty body is a MESSAGE intent missing its body, NEVER an open-chat.
  if (!message) {
    logAudioDiag('WA_MSG_ROUTE_FAIL', 'reason=MESSAGE_BODY_MISSING');
    logAudioDiag('WA_MSG_OLD_FALLBACK_BLOCKED', 'reason=message_body_missing');
    return { kind: 'failed', reason: 'MESSAGE_BODY_MISSING', userMessage: `Ce să-i scriu lui ${contact || 'acel contact'}?` };
  }

  if (!whatsappTool.WA_WRITE_DIRECT) {
    logAudioDiag('WA_MSG_ROUTE_FAIL', 'reason=WA_WRITE_DISABLED');
    return { kind: 'failed', reason: 'WA_WRITE_DISABLED', userMessage: 'Scrierea de mesaje pe WhatsApp e dezactivată momentan.' };
  }

  const attempt = await whatsappTool.prepareMessageDirect(contact, message, request.id);

  // Local contact resolution failed → CONTACT_UNRESOLVED / CONTACTS_PERMISSION. NO fallback to
  // the old open/search flow. ContactResolver's own ambiguity question (if any) is surfaced as-is.
  if (!attempt.handled) {
    const reason = attempt.reason === 'contacts_permission' ? 'CONTACTS_PERMISSION' : 'CONTACT_UNRESOLVED';
    logAudioDiag('WA_MSG_ROUTE_FAIL', `reason=${reason} detail=${attempt.reason}`);
    logAudioDiag('WA_MSG_OLD_FALLBACK_BLOCKED', `reason=${attempt.reason}`);
    const userMessage = reason === 'CONTACTS_PERMISSION'
      ? 'Am nevoie de acces la contacte ca să trimit mesaje pe WhatsApp.'
      : `Nu am găsit contactul „${contact}" în agendă. N-am trimis nimic.`;
    return { kind: 'failed', reason, userMessage };
  }

  const displayName = attempt.displayName ?? (contact || 'contact');
  const r = attempt.result;
  if (r.outcome === 'app_switch_observed' && r.via === 'wa_write_typed_verified') {
    logAudioDiag('WA_MSG_ROUTE_SELECTED', `route=DIRECT_WRITE contact=${JSON.stringify(displayName)}`);
    return { kind: 'typed', message, contact: displayName };
  }
  if (r.outcome === 'launch_failed') {
    const userMessage = r.error === ACCESSIBILITY_DISCONNECTED_ERROR
      ? ACCESSIBILITY_DOWN_SPOKEN_MESSAGE_RO
      : (r.error ?? 'Nu am putut deschide WhatsApp.');
    logAudioDiag('WA_MSG_ROUTE_FAIL', 'reason=LAUNCH_FAILED');
    return { kind: 'failed', reason: r.error ?? 'launch_failed', userMessage };
  }
  // opened_manual_action_required — Phase A stopped after opening (verify / find-input / type /
  // verify-typed), or the resolver's "which one?" question. Nothing was staged.
  logAudioDiag('WA_MSG_ROUTE_FAIL', 'reason=PHASE_A_STOPPED');
  return { kind: 'failed', reason: r.error ?? 'wa_write_phase_a_stopped', userMessage: r.error ?? 'Nu am reușit să pregătesc mesajul în WhatsApp.' };
}

function buildConfirmationPrompt(request: ActionRequest): string {
  // ROUND_WA_GOVERNANCE_ROUTING — `prepareMessage` no longer reaches here: Phase A stages the
  // message and builds its own "Am scris … Îl trimit?" prompt, or fails hard. The legacy
  // "Deschid WhatsApp, caut …, trimit mesajul: … Confirmi?" prompt is removed.
  if (request.tool === 'whatsapp' && request.action === 'openContact') {
    const searchString = String(request.params.contactName ?? 'this contact');
    return `Deschid WhatsApp și deschid conversația cu "${searchString}". Confirmi?`;
  }
  if (request.tool === 'whatsapp' && request.action === 'placeCall') {
    // States the governance plan (target app + search string), never a bare/raw name — the
    // search string is exactly what gets typed into WhatsApp's own search, nothing resolved
    // against any contact list.
    const searchString = String(request.params.contactName ?? '');
    return `Deschid WhatsApp, caut "${searchString}", aleg primul rezultat și apăs apelul vocal. Confirmi?`;
  }
  return 'Confirmi?';
}

// Only the two allowed phrasings (governance clarification 2) — never "started navigating" or
// "message sent", since BENSON cannot verify either from here.
function buildFailureMessage(request: ActionRequest, error?: string): string {
  // Accessibility-down is spoken as its own exact sentence (Item 0 requirement), never wrapped in
  // the generic "could not open X (error)" template — the required wording is a standalone
  // instruction (go re-enable it in Settings), not a diagnostic detail about a WhatsApp failure.
  if (error === ACCESSIBILITY_DISCONNECTED_ERROR) return ACCESSIBILITY_DOWN_SPOKEN_MESSAGE_RO;
  // The user hears exactly two things — what failed and in which app. Any technical detail
  // (selectors, native status strings) goes to logcat only, under EXEC_ERROR.
  if (error) logAudioDiag('EXEC_ERROR', `phase=launch tool=${request.tool} detail=${String(error).replace(/\s+/g, ' ').slice(0, 300)}`);
  if (request.tool === 'waze') return 'Nu am putut deschide Waze.';
  return 'Nu am putut deschide WhatsApp.';
}

// E1-5 (2026-09-07, product-owner-directed) — punctul de confirmare finală. ACK-ul scurt
// ("Pornesc traseul.") e deja rostit de app/index.tsx în paralel cu lansarea (prin onAck din
// Mission Orchestrator), așa că mesajul lung de aici devine terse: nu se mai rostesc două
// propoziții pentru aceeași acțiune. Ramurile de EȘEC și cele "opened_manual_action_required"
// rămân verbatim mai jos (onestitate — utilizatorul trebuie să audă exact ce n-a mers).
// Revert: E1_SHORT_FINAL_CONFIRM = false → propozițiile lungi revin.
const E1_SHORT_FINAL_CONFIRM = true;

function buildWaitingUserMessage(
  request: ActionRequest,
  result: { outcome: LaunchOutcome; via?: string; error?: string },
): string {
  if (request.tool === 'waze') {
    if (E1_SHORT_FINAL_CONFIRM) return 'Gata.';
    return request.action === 'openApp'
      ? 'Am solicitat deschiderea Waze.'
      : 'Am solicitat deschiderea traseului în Waze.';
  }
  if (request.action === 'placeCall') {
    const contact = String(request.params.contactName ?? 'contact');
    if (result.via === 'chat_opened') {
      // Honest fallback (Item 3): the call-button couldn't be verified safely, so nothing was
      // tapped — the chat is left open exactly as found, for the user to tap manually.
      return `Am deschis conversația cu ${contact} în WhatsApp, dar nu am găsit sigur butonul de apel — apasă-l tu.`;
    }
    if (result.outcome === 'opened_manual_action_required') {
      // whatsappTool already returns a clean, user-safe Romanian reason ("Nu am găsit contactul
      // «X» în WhatsApp.") and has logged EXEC_ERROR with the technical detail. Surface that
      // reason as-is; only fall back to a generic line if it somehow didn't set one.
      return result.error ?? `Nu am reușit să sun pe ${contact} pe WhatsApp.`;
    }
    return `L-am sunat pe ${contact} pe WhatsApp.`;
  }
  if (request.action === 'endCall') {
    if (result.outcome === 'opened_manual_action_required') {
      return 'Nu am găsit un buton de închidere a apelului — poate nu era niciun apel activ. Verifică manual.';
    }
    return 'Am închis apelul WhatsApp.';
  }
  if (request.action === 'muteCall') {
    if (result.outcome === 'opened_manual_action_required') {
      return 'Nu am găsit un buton de mute — poate nu era niciun apel activ. Verifică manual.';
    }
    return 'Am apăsat butonul de mute pentru apelul WhatsApp.';
  }
  if (request.action === 'prepareMessage') {
    const contact = String(request.params.contactName ?? 'contact');
    if (result.outcome === 'opened_manual_action_required') {
      // whatsappTool sets a clean, user-safe `error` for a real failure (contact not found, or a
      // "which one?" question). Surface it; only fall back to the generic "tap send yourself" line
      // when the flow got far enough that the chat is actually open and only send didn't verify.
      return result.error ?? `Am pregătit mesajul pentru ${contact}, dar nu am reușit să apăs trimite — apasă-l tu.`;
    }
    return `I-am trimis mesajul lui ${contact} pe WhatsApp.`;
  }
  if (request.action === 'openContact') {
    if (result.outcome === 'opened_manual_action_required') {
      return result.error ?? 'Nu am reușit să deschid conversația în WhatsApp.';
    }
    return 'Am deschis conversația WhatsApp.';
  }
  return 'Am deschis WhatsApp.';
}

export async function confirmActiveMission(contacts: TrustedContact[] = []): Promise<ExecuteOutcome | null> {
  const mission = getActiveMission();
  if (!mission || mission.state !== 'WaitingConfirmation') return null;
  return execute(mission.request, { confirmed: true }, contacts);
}

export async function cancelActiveMission(): Promise<Mission | null> {
  const mission = getActiveMission();
  if (!mission) return null;
  const cancelled = await transitionMission('Cancelled', { userMessage: 'Am anulat.' });
  await clearIfTerminal();
  return cancelled;
}

// MISSION-FIX-1 — a still-unconfirmed mission was outranked by a concrete new user command
// (ROUND_MISSION_DIAG_REPORT.md). Terminal + cleared, exactly like cancel, but recorded as
// 'Superseded' with a reason so the trace is unambiguous. A superseded mission can never be
// confirmed later (confirmActiveMission requires state === 'WaitingConfirmation').
export async function supersedeActiveMission(reason: string): Promise<Mission | null> {
  const mission = getActiveMission();
  if (!mission) return null;
  const superseded = await transitionMission('Superseded', {
    reason: `superseded: ${reason}`,
    userMessage: 'Am lăsat comanda anterioară și trec la ce mi-ai cerut acum.',
  });
  await clearIfTerminal();
  return superseded;
}

// Waze: keep active / complete / cancel. WhatsApp: sent / not sent / cancel. BENSON never infers
// completion just because the user is back (governance clarification 6) — only an explicit word.
const COMPLETE_PATTERN = /\b(gata|am\s+ajuns|am\s+trimis|trimis|sent|done|complet|finalizat)\b/i;
const CANCEL_PATTERN = /\b(anuleaz[ăa]|cancel|renun[țt])\b/i;

export async function resolveActiveMissionFromUtterance(text: string): Promise<ExecuteOutcome | null> {
  const mission = getActiveMission();
  if (!mission || mission.state !== 'WaitingUser') return null;

  if (CANCEL_PATTERN.test(text)) {
    const cancelled = await transitionMission('Cancelled', { userMessage: 'Am anulat misiunea.' });
    await clearIfTerminal();
    return cancelled ? { mission: cancelled, message: cancelled.userMessage } : null;
  }
  if (COMPLETE_PATTERN.test(text)) {
    const completed = await transitionMission('Completed', { userMessage: 'Notat, mulțumesc.' });
    await clearIfTerminal();
    return completed ? { mission: completed, message: completed.userMessage } : null;
  }
  return null; // no resolving word — mission stays WaitingUser, ordinary conversation continues
}
