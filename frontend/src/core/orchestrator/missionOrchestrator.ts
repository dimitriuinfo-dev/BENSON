// BENSON Mission Orchestrator — the new upper-brain entry point.
// rawTranscript -> normalize -> Goal Extractor -> Problem Solver -> Mission Planner -> execute
// tasks one at a time through the EXISTING App Governance Engine (governAction, unchanged) ->
// update Context/Event Bus -> user-facing result.
//
// Mission Orchestrator never talks to Linking/native modules directly and never re-implements
// execution — every task still runs through governAction -> androidActionExecutor, exactly as
// BENSON 20 already does. This file only adds decomposition, sequencing, and confirmation-waiting
// on top of that unchanged chain.

import { normalizeTranscript, cleanDiscourse, createActionRequest, governAction, enrichContactAction } from '../action-engine';
import type { ActionIntent, ActionRequest, ActionSource } from '../action-engine';
import type { TrustedContact } from '../contacts';
import { searchContacts } from '../contacts';
import { extractGoals } from './goalExtractor';
import { solveProblem } from './problemSolver';
import { planMission } from './missionPlanner';
import { emitEvent } from './eventBus';
import { getContext, updateContext, setActiveMission, resetActiveMission } from './contextBus';
import type { MissionPlan, MissionTask } from './orchestratorTypes';
import {
  buildActionRequest as buildGovernedRequest,
  execute as executeGoverned,
} from '../mission';
import type { MissionAction, ToolName } from '../mission';
import { logAudioDiag } from 'benson-foreground-service';
// ROUND_EXECUTION_PIPELINE_DIAG_1 — independent, uniform foreground verification for BOTH the
// generic (AppLauncherExecutor) and governed (Waze/WhatsApp) OPEN_APP paths. Read-only import of
// an already-exported, non-protected function — does not touch missionExecutor.ts/whatsappTool.ts
// (protected files) at all; this just checks the REAL foreground app independently of whatever
// each path's own internal outcome claims, per the round's own rule: "only real target package
// foreground counts."
import { getForegroundPackage } from '../action-engine/androidActionExecutor';
// ROUND_YOUTUBE_GOVERNANCE_1/2 — reusable YouTube search+select+verify mechanics live in their own
// executor file (pure mechanics, no conversation state); this file only owns the goal detection,
// the pending-selection turn, and presenting candidates via the SAME CONFIRMING/disambiguation
// signal path index.tsx already renders (see MissionRunResult.disambiguation below) — zero changes
// to app/index.tsx, the bubble lifecycle, or session UX needed.
import { searchYouTube, selectYouTubeCandidate, type YtCandidate } from '../../executors/youtubeExecutor';
// ROUND_MEDIA_GOVERNANCE_1 / ROUND_ENTERTAINMENT_GOVERNANCE_1 — generic playback transport control
// (MediaSession-first) and provider-parameterized search, layered on top of the YouTube-specific
// pieces above without touching them. See mediaGovernor.ts / mediaSearchExecutor.ts file headers.
import {
  mediaPause, mediaResume, mediaNext, mediaPrevious, stopMedia, returnToBensonFromMedia, verifyPlaying,
} from '../../executors/mediaGovernor';
import {
  searchMedia, selectMediaCandidate, findProviderByMention, type MediaProvider, type MediaCandidate,
} from '../../executors/mediaSearchExecutor';

const LOG_TAG = '[MissionOrchestrator]';
function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

export interface RunMissionOptions {
  source?: ActionSource;
  contacts?: TrustedContact[];
  // E1-5 (2026-09-07, product-owner-directed) — fired the moment the intent is resolved to an
  // executable task, BEFORE the Android side effect runs. The caller speaks a short ACK ("Deschid.",
  // "Pornesc traseul.") in parallel with execution instead of a long confirmation after it. NOT
  // fired for a task still awaiting the user's "da" (there, the confirmation question IS the reply).
  onAck?: (shortText: string) => void;
}

// E1-5 revert: E1_ACK_IMMEDIATE = false → onAck is never invoked, old "speak the long result after
// the launch" behavior is restored end to end.
const E1_ACK_IMMEDIATE = true;

// Short spoken acknowledgement per task type — deliberately terse (<~1s TTS) so it lands well
// under the 500ms target and does not delay the launch it runs alongside.
function e1AckText(task: MissionTask): string | null {
  switch (task.type) {
    case 'NAVIGATE': return 'Pornesc traseul.';
    case 'OPEN_APP': return 'Deschid.';
    case 'PLAY_MEDIA': return 'Pornesc.';
    case 'PREPARE_MESSAGE': return task.input.mode === 'voice_call' ? 'O sun.' : 'Trimit mesajul.';
    case 'PREPARE_CALL': return 'Sun acum.';
    default: return null;
  }
}

export interface MissionRunResult {
  handled: boolean; // false => Mission Orchestrator found nothing; caller should fall through to Claude
  message: string;
  plan?: MissionPlan;
  pendingTask?: { plan: MissionPlan; taskIndex: number }; // set when a task needs confirmation
  // Set when the reply is a "which one?" proposal awaiting the user's pick. The orchestrator holds
  // the candidate list internally (pendingDisambiguation) and routes the NEXT utterance to it;
  // the caller uses this only to render the reply as a CONFIRMING state (no auto-clear).
  disambiguation?: { candidates: DisambiguationCandidate[] };
}

type DisambiguationCandidate = { name: string; packageName?: string };

const CONTACT_TASK_TYPES = ['PREPARE_CALL', 'PREPARE_MESSAGE', 'CHECK_FAMILY_LOCATION'];

// Item 1 — read-only contacts lookup, spoken directly (never the whole address book: capped at
// MAX_SPOKEN_CONTACTS names either way, per the "never read the whole book" requirement).
const MAX_SPOKEN_CONTACTS = 8;

function buildContactsListMessage(contacts: TrustedContact[]): string {
  if (contacts.length === 0) {
    return 'Nu am acces la contactele tale — dă-mi permisiunea din setări ca să le pot căuta.';
  }
  const names = contacts.slice(0, MAX_SPOKEN_CONTACTS).map((c) => c.displayName);
  const suffix = contacts.length > MAX_SPOKEN_CONTACTS ? ', și altele' : '';
  return `Ai ${contacts.length} contacte. Câteva dintre ele: ${names.join(', ')}${suffix}.`;
}

function buildContactsSearchMessage(rawName: string, contacts: TrustedContact[]): string {
  if (!rawName.trim()) return 'Pe cine cauți?';
  if (contacts.length === 0) {
    return 'Nu am acces la contactele tale — dă-mi permisiunea din setări ca să le pot căuta.';
  }
  const candidates = searchContacts(rawName, contacts, 5);
  if (candidates.length === 0) return `Nu găsesc niciun contact "${rawName}".`;
  if (candidates.length === 1) return `Da, ${candidates[0].displayName} este în contactele tale.`;
  const names = candidates.map((c) => c.displayName).join(', ');
  return `Am găsit mai mulți: ${names}. Pe care îl vrei?`;
}

// Rebuilds the same ActionRequest shape governAction already accepts — Mission Planner only
// needs to know about destination/contact/message/mediaType, never about Linking/packages.
function taskToActionRequest(task: MissionTask, rawText: string): ActionRequest {
  let intent: ActionIntent;
  const parameters: Record<string, unknown> = { ...task.input };

  switch (task.type) {
    case 'NAVIGATE':
      intent = 'NAVIGATE_TO_PLACE';
      parameters.destinationLabel = task.input.destination;
      break;
    case 'OPEN_APP': {
      const appName = typeof task.input.appName === 'string' ? task.input.appName.toLowerCase() : '';
      if (appName.includes('waze')) intent = 'OPEN_WAZE';
      else if (appName.includes('maps')) intent = 'OPEN_GOOGLE_MAPS';
      else if (appName.includes('whatsapp')) intent = 'OPEN_WHATSAPP';
      else intent = 'OPEN_APP';
      break;
    }
    case 'RETURN_TO_BENSON':
      intent = task.input.appName ? 'CLOSE_APP' : 'RETURN_TO_BENSON';
      break;
    case 'PREPARE_CALL':
      intent = 'CALL_CONTACT';
      break;
    case 'PREPARE_MESSAGE':
      intent = 'OPEN_WHATSAPP_CONTACT';
      parameters.channel = 'whatsapp';
      break;
    case 'PLAY_MEDIA':
      intent = 'MEDIA_PLAY';
      break;
    case 'READ_CALENDAR':
      intent = 'CALENDAR_ACTION';
      break;
    case 'CHECK_FAMILY_LOCATION':
      intent = 'FAMILY_LOCATION';
      break;
    case 'SHOW_HELP':
      intent = 'HELP';
      break;
    default:
      intent = 'UNKNOWN';
  }

  return createActionRequest({
    source: 'voice',
    rawText,
    intent,
    parameters,
    riskLevel: 'LOW',
    requiresConfirmation: task.requiresConfirmation,
  });
}

// Waze + WhatsApp Mission Governance (Phase 1, src/core/mission) — the single execution path for
// these two tools. A governed task never goes through taskToActionRequest/governAction below; it
// hands off entirely to MissionExecutor.execute(), which owns validation, confirmation, the
// Android side effect, and its own AsyncStorage-persisted mission state. Google Maps and
// WhatsApp voice-call mode stay on the existing governAction path — out of Phase 1 scope.
interface GovernedCall {
  tool: ToolName;
  action: MissionAction;
  params: Record<string, unknown>;
}

function toGovernedCall(task: MissionTask): GovernedCall | null {
  if (task.type === 'NAVIGATE') {
    if (task.input.preferredApp === 'google_maps') return null;
    const destination = typeof task.input.destination === 'string' ? task.input.destination : '';
    return { tool: 'waze', action: 'openNavigation', params: { destination } };
  }

  if (task.type === 'OPEN_APP') {
    const appName = typeof task.input.appName === 'string' ? task.input.appName.toLowerCase() : '';
    if (task.appCapability === 'navigation' && appName.includes('waze')) {
      return { tool: 'waze', action: 'openApp', params: {} };
    }
    if (appName.includes('whatsapp')) {
      return { tool: 'whatsapp', action: 'openApp', params: {} };
    }
    return null;
  }

  if (task.type === 'PREPARE_MESSAGE') {
    const contactName = typeof task.input.contactName === 'string' ? task.input.contactName : '';
    const message = typeof task.input.message === 'string' ? task.input.message.trim() : '';
    const sourceIntent = typeof task.input.intent === 'string' ? task.input.intent : '';
    const rawText = typeof task.input.rawText === 'string' ? task.input.rawText : '';

    // ROUND_WHATSAPP_RELIABILITY_1 — CONFIRMED LIVE (real device): resuming this task after "da"
    // called toGovernedCall() again, which always returned plain prepareMessage — hitting
    // missionExecutor.ts's guarded dead-fallback ("Scrierea de mesaje... dezactivată momentan"),
    // since real Phase B (confirmSendMessageDirect) is only reached when params.waWriteTyped is
    // set. Phase A's own mission id (stamped onto task.input by runGovernedTask below once it
    // sees WaitingConfirmation) marks this as a resume — route to Phase B instead of retyping.
    if (typeof task.input.waWriteMissionId === 'string' && task.input.waWriteMissionId) {
      return {
        tool: 'whatsapp', action: 'prepareMessage',
        params: {
          contactName, message, waWriteTyped: true,
          waWriteMissionId: task.input.waWriteMissionId,
          waWriteMessage: typeof task.input.waWriteMessage === 'string' ? task.input.waWriteMessage : message,
          waWriteContact: typeof task.input.waWriteContact === 'string' ? task.input.waWriteContact : contactName,
        },
      };
    }

    if (task.input.mode === 'voice_call') {
      // "sună X pe WhatsApp" — governed as its own action (accessibility-verified call-button
      // tap, product-owner-authorized). Unchanged.
      return { tool: 'whatsapp', action: 'placeCall', params: { contactName } };
    }

    // ROUND_WA_GOVERNANCE_ROUTING — a message intent stays a message intent. It ALWAYS becomes
    // prepareMessage (empty body carried through → missionExecutor emits MESSAGE_BODY_MISSING).
    // openContact is produced ONLY for an explicit OPEN_WHATSAPP_CONTACT parse — never as a
    // fallback from messaging.
    logAudioDiag('WA_MSG_INTENT_RAW', `text=${JSON.stringify(rawText || contactName)}`);
    if (sourceIntent === 'OPEN_WHATSAPP_CONTACT') {
      logAudioDiag('WA_MSG_ROUTE_SELECTED', 'route=OPEN_CHAT');
      return { tool: 'whatsapp', action: 'openContact', params: { contactName } };
    }
    logAudioDiag('WA_MSG_INTENT_PARSED', `contact=${JSON.stringify(contactName)} message=${JSON.stringify(message)}`);
    logAudioDiag('WA_MSG_ROUTE_SELECTED', `route=${message ? 'DIRECT_WRITE' : 'MESSAGE_BODY_MISSING'}`);
    logAudioDiag('WA_PAYLOAD_ACTION', `contact=${JSON.stringify(contactName)} message=${JSON.stringify(message)}`);
    return { tool: 'whatsapp', action: 'prepareMessage', params: { contactName, message } };
  }

  return null;
}

// Governed OPEN_APP -> expected target package, for the independent post-hoc foreground check
// below. Deliberately narrow (only the two callers of toGovernedCall's OPEN_APP branch) — not a
// general mapping, just enough to verify what THIS round is diagnosing.
const GOVERNED_OPEN_APP_PACKAGE: Partial<Record<ToolName, string>> = {
  waze: 'com.waze',
  whatsapp: 'com.whatsapp',
};

async function runGovernedTask(
  governed: GovernedCall,
  task: MissionTask,
  plan: MissionPlan,
  contacts: TrustedContact[],
  confirmed: boolean,
): Promise<{ message: string; waiting: boolean }> {
  task.status = 'RUNNING';
  emitEvent('TaskStarted', { type: task.type }, plan.id, task.id);

  logAudioDiag('EXEC_TRACE_EXECUTOR', `executor=governed:${governed.tool} action=${governed.action}`);
  const expectedPackage = task.type === 'OPEN_APP' ? GOVERNED_OPEN_APP_PACKAGE[governed.tool] : undefined;
  if (expectedPackage) logAudioDiag('EXEC_TRACE_TARGET', `name=${governed.tool} package=${JSON.stringify(expectedPackage)}`);
  logAudioDiag('EXEC_TRACE_LAUNCH_REQUEST', `tool=${governed.tool} action=${governed.action} mechanism=missionExecutor(protected)`);

  const request = buildGovernedRequest(governed.tool, governed.action, governed.params);
  const outcome = await executeGoverned(request, { confirmed }, contacts);
  logAudioDiag('EXEC_TRACE_LAUNCH_RESULT', `tool=${governed.tool} action=${governed.action} missionState=${outcome.mission.state} message=${JSON.stringify(outcome.message)}`);

  // Independent, real check — regardless of what missionExecutor/whatsappTool's own outcome
  // claims (their internal verification, e.g. whatsappTool.openApp()'s waitForBackground(), is a
  // DIFFERENT signal than "the target package is actually foreground" and does not require
  // Accessibility to be bound). Only meaningful for an actual OPEN_APP launch attempt, not
  // placeCall/prepareMessage/etc.
  if (expectedPackage && (outcome.mission.state === 'Completed' || outcome.mission.state === 'WaitingUser')) {
    await new Promise((r) => setTimeout(r, 1200));
    const observed = getForegroundPackage();
    logAudioDiag('EXEC_TRACE_FOREGROUND_VERIFY', `expected=${JSON.stringify(expectedPackage)} observed=${JSON.stringify(observed)} confirmedByEvent=false method=post_hoc_check`);
    if (observed !== expectedPackage) {
      logAudioDiag('EXEC_TRACE_FAILURE', `stage=FOREGROUND_VERIFICATION package=${JSON.stringify(expectedPackage)} reason=${observed === null ? 'ACCESSIBILITY_UNAVAILABLE_OR_NO_SIGNAL' : 'FOREGROUND_MISMATCH'} observed=${JSON.stringify(observed)}`);
    }
  }

  if (outcome.mission.state === 'WaitingConfirmation') {
    // ROUND_WHATSAPP_RELIABILITY_1 — Phase A's own mission id, stamped onto the task so a later
    // resume (see toGovernedCall's PREPARE_MESSAGE branch) can route to Phase B instead of retyping.
    if (governed.tool === 'whatsapp' && governed.action === 'prepareMessage' && !governed.params.waWriteTyped) {
      task.input.waWriteMissionId = request.id;
      task.input.waWriteMessage = governed.params.message;
      task.input.waWriteContact = governed.params.contactName;
    }
    task.status = 'WAITING';
    plan.status = 'WAITING_FOR_CONFIRMATION';
    emitEvent('ConfirmationRequested', { task: task.type }, plan.id, task.id);
    logAudioDiag('EXEC_TRACE_FAILURE', `stage=COMMAND_DISPATCH tool=${governed.tool} reason=WAITING_CONFIRMATION`);
    return { message: outcome.message, waiting: true };
  }

  if (outcome.mission.state === 'Failed') {
    // ROUND_WA_REPLY_CONTEXT_1 — MESSAGE_BODY_MISSING ("Ce să-i scriu?") is NOT a terminal
    // failure like every other reason on this branch (contact not found, launch failed, etc.) —
    // missionExecutor.ts's own "Hard invariant" comment already documents it as a question the
    // mission should survive to receive an answer to. It never did: this branch previously
    // hard-failed it identically to every other reason, so the dictated reply had no mission left
    // to attach to and was routed as a brand-new utterance instead (confirmed live: fell through
    // to the LLM brain, which read it as small talk). Reuses the EXACT same stamp+WAITING pattern
    // as WaitingConfirmation above — no new store, no new mission architecture — plus one marker
    // (waAwaitingMessageBody) so app/index.tsx's existing pendingMissionTaskRef gate knows to hand
    // the next utterance to resumePendingTask() as the message body, not through classifyConfirmation.
    if (governed.tool === 'whatsapp' && governed.action === 'prepareMessage' && outcome.mission.reason === 'MESSAGE_BODY_MISSING') {
      task.input.waAwaitingMessageBody = true;
      task.input.waWriteContact = governed.params.contactName;
      task.status = 'WAITING';
      plan.status = 'WAITING_FOR_CONFIRMATION';
      emitEvent('ConfirmationRequested', { task: task.type }, plan.id, task.id);
      logAudioDiag('WA_REPLY_CONTEXT_AFTER', `missionId=${plan.id} contact=${JSON.stringify(governed.params.contactName)} message_body=null state=WAITING_MESSAGE_BODY`);
      return { message: outcome.message, waiting: true };
    }
    task.status = 'FAILED';
    task.errorMessage = outcome.mission.reason;
    task.resultMessage = outcome.message;
    emitEvent('TaskFailed', { reason: outcome.mission.reason }, plan.id, task.id);
    logAudioDiag('EXEC_TRACE_FAILURE', `stage=LAUNCH_ACTION tool=${governed.tool} reason=${JSON.stringify(outcome.mission.reason ?? 'unknown')}`);
    return { message: outcome.message, waiting: false };
  }

  // WaitingUser (the expected outcome on a real launch) or Completed — the governance mission
  // itself continues to be owned by src/core/mission from here; this task/plan just records that
  // the hand-off happened.
  task.status = 'DONE';
  task.resultMessage = outcome.message;
  emitEvent('TaskCompleted', { result: outcome.mission.state }, plan.id, task.id);
  return { message: outcome.message, waiting: false };
}

async function executeTask(
  task: MissionTask,
  plan: MissionPlan,
  rawText: string,
  contacts: TrustedContact[],
  confirmed: boolean,
): Promise<{ message: string; waiting: boolean; disambiguation?: DisambiguationCandidate[] }> {
  if (task.type === 'STUB_NOT_IMPLEMENTED') {
    task.status = 'SKIPPED';
    task.resultMessage = 'Nu pot face asta încă.';
    emitEvent('TaskCompleted', { status: 'SKIPPED' }, plan.id, task.id);
    return { message: task.resultMessage, waiting: false };
  }

  if (task.type === 'LIST_CONTACTS' || task.type === 'SEARCH_CONTACTS') {
    task.status = 'RUNNING';
    emitEvent('TaskStarted', { type: task.type }, plan.id, task.id);
    const contactName = typeof task.input.contactName === 'string' ? task.input.contactName : '';
    const message =
      task.type === 'LIST_CONTACTS'
        ? buildContactsListMessage(contacts)
        : buildContactsSearchMessage(contactName, contacts);
    task.status = 'DONE';
    task.resultMessage = message;
    emitEvent('TaskCompleted', { result: 'success' }, plan.id, task.id);
    return { message, waiting: false };
  }

  const governed = toGovernedCall(task);
  if (governed) {
    return runGovernedTask(governed, task, plan, contacts, confirmed);
  }
  // ROUND_EXECUTION_PIPELINE_DIAG_1 — the fork point: OPEN_APP for Waze/WhatsApp takes the
  // governed path above (never reaches here); every other app name (Calculator included) falls
  // through to the generic governAction/AppLauncherExecutor path below. AppLauncherExecutor.execute()
  // logs its own EXEC_TRACE_EXECUTOR once request.intent is known; this logs the routing DECISION
  // itself, one level up, so a failure that never even reaches the executor is still traceable.
  if (task.type === 'OPEN_APP') {
    logAudioDiag('EXEC_TRACE_EXECUTOR', `executor=generic:governAction route=AppLauncherExecutor appName=${JSON.stringify(task.input.appName ?? '')}`);
  }

  if (task.requiresConfirmation && !confirmed) {
    task.status = 'WAITING';
    plan.status = 'WAITING_FOR_CONFIRMATION';
    emitEvent('ConfirmationRequested', { task: task.type }, plan.id, task.id);
    const contactName = typeof task.input.contactName === 'string' ? task.input.contactName : '';
    const message = typeof task.input.message === 'string' ? task.input.message : '';
    const question =
      task.type === 'PREPARE_MESSAGE' && task.input.mode === 'voice_call'
        ? `Deschid conversația WhatsApp cu ${contactName} pentru apel — confirmi?`
        : task.type === 'PREPARE_MESSAGE'
          ? `Trimit lui ${contactName}: "${message}"?`
          : task.type === 'PREPARE_CALL'
            ? `Îl/o sun pe ${contactName}?`
            : 'Confirmi?';
    return { message: question, waiting: true };
  }

  task.status = 'RUNNING';
  emitEvent('TaskStarted', { type: task.type }, plan.id, task.id);

  let request = taskToActionRequest(task, rawText);

  if (CONTACT_TASK_TYPES.includes(task.type) && contacts.length >= 0) {
    const bridgeResult = enrichContactAction(request, contacts);
    if (task.type !== 'CHECK_FAMILY_LOCATION') {
      // CHECK_FAMILY_LOCATION has no real executor yet (StubExecutor handles it regardless of
      // contact resolution) — for PREPARE_CALL/PREPARE_MESSAGE, an unresolved contact is a real
      // failure, not something to silently continue past.
      if (bridgeResult.status !== 'resolved') {
        task.status = 'FAILED';
        task.errorMessage = bridgeResult.message;
        emitEvent('TaskFailed', { reason: bridgeResult.message }, plan.id, task.id);
        return { message: bridgeResult.message, waiting: false };
      }
      request = bridgeResult.request;
    }
  }

  const result = await governAction(request, { confirmed: true });

  // 'needs_disambiguation' — the executor found several candidates and is asking the user to pick.
  // This is NOT a failure: the task pauses, the candidate list is handed up so runPlanFrom can
  // arm pendingDisambiguation, and the user's next utterance is routed to it (not re-parsed).
  if (result.status === 'needs_disambiguation') {
    const raw = (result.data as { candidates?: unknown } | undefined)?.candidates;
    const candidates: DisambiguationCandidate[] = Array.isArray(raw)
      ? raw.map((c) => ({
          name: String((c as { name?: unknown })?.name ?? ''),
          packageName: (c as { packageName?: unknown })?.packageName ? String((c as { packageName?: unknown }).packageName) : undefined,
        })).filter((c) => c.name)
      : [];
    task.status = 'WAITING';
    task.resultMessage = result.message;
    emitEvent('TaskCompleted', { result: result.status }, plan.id, task.id);
    logAudioDiag('EXEC_TRACE_FAILURE', `stage=COMMAND_DISPATCH reason=NEEDS_DISAMBIGUATION candidates=${candidates.length}`);
    return { message: result.message, waiting: false, disambiguation: candidates };
  }

  // 'unsupported' means the executor honestly declined (not built yet, e.g. StubExecutor) — same
  // "not implemented" outcome as the STUB_NOT_IMPLEMENTED task-type branch above, not a genuine
  // failure. Only mark FAILED for an actual attempted-and-didn't-work result.
  task.status = result.status === 'success' ? 'DONE' : result.status === 'unsupported' ? 'SKIPPED' : 'FAILED';
  task.resultMessage = result.message;
  if (task.status === 'FAILED') task.errorMessage = result.errorDetails;
  emitEvent(task.status === 'DONE' ? 'TaskCompleted' : task.status === 'FAILED' ? 'TaskFailed' : 'TaskCompleted', { result: result.status }, plan.id, task.id);
  return { message: result.message, waiting: false };
}

async function runPlanFrom(
  plan: MissionPlan,
  startIndex: number,
  rawText: string,
  contacts: TrustedContact[],
  firstTaskConfirmed: boolean,
  onAck?: (shortText: string) => void,
): Promise<MissionRunResult> {
  plan.status = 'RUNNING';
  const messages: string[] = [];

  for (let i = startIndex; i < plan.tasks.length; i += 1) {
    const task = plan.tasks[i];
    const confirmed = i === startIndex ? firstTaskConfirmed : false;
    // E1-5 — ACK the instant the intent is known, BEFORE executeTask() runs the Android side
    // effect, but ONLY for a task that will actually execute now (not one still gated behind an
    // unanswered "Confirmi?"). executeTask() awaits the launch; onAck() here does not.
    if (E1_ACK_IMMEDIATE && onAck && (confirmed || !task.requiresConfirmation)) {
      const ack = e1AckText(task);
      if (ack) onAck(ack);
    }
    const outcome = await executeTask(task, plan, rawText, contacts, confirmed);
    messages.push(outcome.message);
    plan.updatedAt = Date.now();

    if (outcome.disambiguation && outcome.disambiguation.length > 0) {
      pendingDisambiguation = { candidates: outcome.disambiguation };
      pendingDisambiguationSetAt = Date.now();
      resetActiveMission();
      return { handled: true, message: outcome.message, plan, disambiguation: { candidates: outcome.disambiguation } };
    }

    if (outcome.waiting) {
      return { handled: true, message: outcome.message, plan, pendingTask: { plan, taskIndex: i } };
    }

    // ROUND_MEDIA_GOVERNANCE_1 — a successfully-opened radio/music app (PLAY_MEDIA) or a plain
    // OPEN_APP that turned out to be a media app becomes the active session for "pauză"/
    // "oprește"/etc. continuity. No exact packageName is known from this generic path (only a
    // display name/station) — mediaGovernor's own no-filter fallback (prefer whichever session is
    // actually PLAYING) covers that; this is strictly additive, no existing radio/music behavior
    // is changed.
    if (task.status === 'DONE' && (task.type === 'PLAY_MEDIA' || task.type === 'OPEN_APP')) {
      const label =
        (typeof task.input.stationName === 'string' && task.input.stationName) ||
        (typeof task.input.appName === 'string' && task.input.appName) ||
        '';
      if (label) setActiveMediaSession({ label });
    }
  }

  // SKIPPED (stub, not implemented) counts as "not truly done" alongside FAILED for mission
  // status — a mission with an unimplemented task never claims a clean COMPLETED.
  const unresolvedCount = plan.tasks.filter((t) => t.status === 'FAILED' || t.status === 'SKIPPED').length;
  plan.status = unresolvedCount === 0 ? 'COMPLETED' : unresolvedCount < plan.tasks.length ? 'PARTIAL' : 'FAILED';
  emitEvent(plan.status === 'FAILED' ? 'MissionFailed' : 'MissionCompleted', { status: plan.status }, plan.id);
  resetActiveMission();

  if (plan.tasks.some((t) => t.type === 'NAVIGATE')) {
    const navTask = plan.tasks.find((t) => t.type === 'NAVIGATE');
    const destination = navTask && typeof navTask.input.destination === 'string' ? navTask.input.destination : undefined;
    if (destination) updateContext({ lastDestination: destination });
  }
  const contactTask = plan.tasks.find((t) => CONTACT_TASK_TYPES.includes(t.type));
  if (contactTask && typeof contactTask.input.contactName === 'string') {
    updateContext({ lastContact: contactTask.input.contactName });
  }

  // Silence skip-only messages (nothing failed) from the spoken reply if it's the sole task.
  const finalMessage = messages.filter(Boolean).join(' ') || 'Gata.';
  return { handled: true, message: finalMessage, plan };
}

// Last mission plan built, whether or not it's still active — the Debug Panel shows this so
// "what did BENSON just do" is inspectable without needing an active/pending mission.
let lastMissionPlan: MissionPlan | undefined;

// Second, independent safety net against a self-triggering loop confirmed live 2026-07-16:
// BENSON's own spoken confirmation ("Am solicitat deschiderea Waze.") was picked back up by the
// mic, misheard as a fresh command ("...oazei"), and re-planned the identical mission — the
// app/index.tsx echo-content guard is the primary defense, but it depends on a time window
// matching real-world mic-cycle timing, which varied 12-40+ seconds live and let a few repeats
// through. This is orthogonal: regardless of what the STT layer thinks it heard, the SAME goal
// signature (type + entities) is never re-executed within the cooldown, full stop.
let lastExecutedGoalSignature: string | null = null;
let lastExecutedGoalAt = 0;
const MISSION_REPEAT_COOLDOWN_MS = 20000;

export function getLastMissionPlan(): MissionPlan | undefined {
  return lastMissionPlan;
}

// A bare channel mention with no contact/message of its own ("Pe WhatsApp, pardon.") — a repair
// to a previous, channel-ambiguous communication request. Checked against the DISCOURSE-CLEANED
// text (not just normalized) so "pardon" is already gone by the time this pattern is tried.
const BARE_CHANNEL_REPAIR_PATTERN = /^\s*pe\s+whatsapp\s*$/i;

// Minimal clarification-answer memory (product-owner-confirmed live bug, fixed 2026-08-23): when
// BENSON asks "Pentru cine?" (no lastContact to repair the "pe WhatsApp" pattern above onto), the
// NEXT utterance IS the answer to that question ("Hannah"), not a fresh unrelated command. Before
// this fix nothing recorded that a question was pending, so the answer fell straight through to
// goal-extraction/Claude with no idea what it was answering, and the original request was silently
// dropped. Scoped narrowly to this ONE confirmed case (not a general "any clarifying question"
// mechanism, which doesn't exist elsewhere in the orchestrator either) and self-expires so it can
// never hijack an unrelated later command if the user changes topic instead of answering.
type PendingClarification = { kind: 'whatsapp_contact' };
let pendingClarification: PendingClarification | null = null;
let pendingClarificationSetAt = 0;
const PENDING_CLARIFICATION_TIMEOUT_MS = 60000;

// Round D (2026-08-31) — a "which one?" proposal (radio/music/app disambiguation) is a real
// pending state now: the candidate list is held here, and the NEXT utterance is matched against
// it and launched directly, instead of being re-parsed as a fresh command (which previously hit
// the repeat-cooldown and returned an empty message = silence). Self-expires so an unrelated
// later command can't be hijacked.
let pendingDisambiguation: { candidates: DisambiguationCandidate[] } | null = null;
let pendingDisambiguationSetAt = 0;
const PENDING_DISAMBIGUATION_TIMEOUT_MS = 60000;

function stripDiac(s: string): string {
  return (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
}

// Match the user's pick against the proposed candidates: an ordinal ("primul", "a doua", "3"),
// or a name substring either direction, or a >2-char token overlap. Null = no clear pick.
function matchDisambiguationPick(
  text: string,
  candidates: DisambiguationCandidate[],
): DisambiguationCandidate | null {
  const t = stripDiac(text);
  if (!t || candidates.length === 0) return null;
  const ord = /\b(prim(?:ul|a)|unu|1|a\s+doua|al\s+doilea|doi|2|a\s+treia|al\s+treilea|trei|3)\b/.exec(t);
  if (ord) {
    const g = ord[1];
    const idx = /prim|unu|1/.test(g) ? 0 : /dou|doi|2/.test(g) ? 1 : 2;
    if (candidates[idx]) return candidates[idx];
  }
  const tokens = t.split(/\s+/).filter((w) => w.length > 2);
  for (const c of candidates) {
    const n = stripDiac(c.name);
    if (!n) continue;
    if (n.includes(t) || t.includes(n)) return c;
    if (tokens.some((w) => n.includes(w))) return c;
  }
  return null;
}

// ── ROUND_YOUTUBE_GOVERNANCE_1/2 ────────────────────────────────────────────────────────────────
// GoalInterpreter for the YouTube domain: provider=YOUTUBE, goal=SEARCH, query=extracted text.
// Deliberately narrow and checked BEFORE extractGoals/planMission (same precedent as
// END_CALL_PATTERN/MUTE_CALL_PATTERN below) — "open YouTube" alone (no query) is NOT this goal and
// falls through unchanged to the existing generic OPEN_APP path (AppLauncherExecutor), per the
// round's explicit "do not treat 'open youtube' and 'search X on youtube' as the same mission."
function extractYouTubeQuery(text: string): string | null {
  const t = (text || '').trim();
  if (!/\byoutube\b/i.test(t)) return null;
  // "caută INNA pe YouTube" / "caută Take My Breath Away pe YouTube"
  let m = /\bcaut[ăa]\s+(.+?)\s+pe\s+youtube\b/i.exec(t);
  if (m && m[1].trim()) return m[1].trim();
  // "deschide YouTube și caută INNA" — query is whatever follows the search verb
  m = /youtube\b[\s\S]*?\bcaut[ăa]\s+(.+)$/i.exec(t);
  if (m && m[1].trim()) return m[1].trim();
  // ROUND_VOICE_INTENT_NORMALIZATION_1 — PLAY-style verbs ("pune INNA pe YouTube", "deschide X pe
  // YouTube", "vreau X pe YouTube"), not just the search verb "caută". Confirmed live gap: without
  // this, "pune INNA pe YouTube" fell through to the generic OPEN_APP pattern with the WHOLE
  // phrase "INNA pe YouTube" misread as a literal app name.
  m = /\b(?:pune|deschide|porne[șs]te|bag[ăa]|vreau)\s+(.+?)\s+pe\s+youtube\b/i.exec(t);
  if (m && m[1].trim()) return m[1].trim();
  // "caută INNA" with "youtube" mentioned anywhere else in the utterance
  m = /\bcaut[ăa]\s+(.+)$/i.exec(t);
  if (m) {
    const q = m[1].replace(/\bpe\s+youtube\b/i, '').replace(/\byoutube\b/i, '').trim();
    if (q) return q;
  }
  return null;
}

// Pending "which video?" turn (ROUND_YOUTUBE_GOVERNANCE_2) — deliberately separate from
// pendingDisambiguation (app-open resolution): resolving a pick here taps a real result inside
// YouTube and verifies playback, it never builds an OPEN_APP request. Self-expires like the other
// pending states above.
let pendingYouTubeSelection: { candidates: YtCandidate[]; query: string } | null = null;
let pendingYouTubeSelectionSetAt = 0;
const PENDING_YT_SELECTION_TIMEOUT_MS = 60000;

const YT_DECLINE_PATTERN = /\b(nu|altul|alta|altceva|niciunul|niciuna)\b/i;

async function resolveYouTubeSelection(
  rawText: string,
  pending: { candidates: YtCandidate[]; query: string },
): Promise<MissionRunResult> {
  const t = stripDiac(rawText);
  if (YT_DECLINE_PATTERN.test(t) && !/\bda\b/.test(t)) {
    // YT-GOV-CHOICE-4 — mission stays alive: re-offer the same candidate set rather than
    // restarting the whole search (no new candidates to add without a fresh search this round).
    logAudioDiag('YT_GOV_START', `stage=reselect query=${JSON.stringify(pending.query)}`);
    pendingYouTubeSelection = pending;
    pendingYouTubeSelectionSetAt = Date.now();
    const names = pending.candidates.map((c) => c.title).join(', ');
    return {
      handled: true,
      message: `Am: ${names}. Pe care s-o pornesc?`,
      disambiguation: { candidates: pending.candidates.map((c) => ({ name: c.title })) },
    };
  }
  const pick = matchDisambiguationPick(rawText, pending.candidates.map((c) => ({ name: c.title })));
  if (!pick) {
    pendingYouTubeSelection = pending;
    pendingYouTubeSelectionSetAt = Date.now();
    return {
      handled: true,
      message: 'N-am înțeles pe care. Spune titlul, sau „prima", „a doua".',
      disambiguation: { candidates: pending.candidates.map((c) => ({ name: c.title })) },
    };
  }
  const outcome = await selectYouTubeCandidate(pick.name);
  if (outcome.ok) setActiveMediaSession({ packageName: 'com.google.android.youtube', label: pick.name });
  return { handled: true, message: outcome.message };
}

// ── ROUND_MEDIA_GOVERNANCE_1 / ROUND_ENTERTAINMENT_GOVERNANCE_1 ─────────────────────────────────
// Context continuity: "pauză"/"continuă"/"oprește"/"următoarea"/"anterioară"/"revino la Benson"
// must act on WHATEVER is currently playing, regardless of which provider/mission started it —
// per the round's explicit "do not require BENSON, oprește videoclipul INNA din YouTube". This is
// deliberately or­thogonal to pendingYouTubeSelection/pendingDisambiguation (a "which one?"
// answer) — this is "control the thing already playing," checked whenever ANY media/app-open
// mission (YouTube, the generic provider search below, or a plain OPEN_APP for radio/music) last
// completed successfully. No expiry timeout: a movie or a radio stream can run for hours, and
// there is no other signal that would tell us it "expired" — it is simply replaced the next time
// a new media mission starts.
interface ActiveMediaSession {
  packageName?: string;
  label: string;
}
let activeMediaSession: ActiveMediaSession | null = null;

function setActiveMediaSession(session: ActiveMediaSession): void {
  activeMediaSession = session;
  logAudioDiag('MEDIA_SESSION_ACTIVE', `package=${JSON.stringify(session.packageName ?? '')} label=${JSON.stringify(session.label)}`);
}

const MEDIA_PAUSE_PATTERN = /^\s*pauz[ăa]\s*\.?\s*$/i;
const MEDIA_RESUME_PATTERN = /^\s*(continu[ăa]|reia)\s*\.?\s*$/i;
const MEDIA_NEXT_PATTERN = /^\s*urm[ăa]toarea\s*\.?\s*$/i;
const MEDIA_PREVIOUS_PATTERN = /^\s*anterioar[ăa]\s*\.?\s*$/i;
const MEDIA_RETURN_PATTERN = /\b(revino|inapoi|înapoi)\s+la\s+benson\b/i;
// A bare "oprește" (no object) is the generic media-stop case; "oprește apelul"/"oprește
// microfonul" etc. are handled by other, more specific patterns earlier in runMission() — this
// one only fires when activeMediaSession is actually set, so it never shadows those.
const MEDIA_STOP_PATTERN = /\bopre[șs]te\b/i;

// Checked ONLY while a media session is active. Returns null (not this utterance) rather than a
// result when nothing matches, so the caller falls through to normal dispatch.
async function tryHandleActiveMediaCommand(rawText: string): Promise<MissionRunResult | null> {
  if (!activeMediaSession) return null;
  const t = cleanDiscourse(normalizeTranscript(rawText));
  const pkg = activeMediaSession.packageName;

  if (MEDIA_RETURN_PATTERN.test(t)) {
    const outcome = await returnToBensonFromMedia(pkg);
    activeMediaSession = null;
    return { handled: true, message: outcome.message };
  }
  if (MEDIA_PAUSE_PATTERN.test(t)) {
    await mediaPause(pkg);
    const message = 'Am pus pauză.';
    return { handled: true, message };
  }
  if (MEDIA_RESUME_PATTERN.test(t)) {
    await mediaResume(pkg);
    const playing = await verifyPlaying(pkg, 2500);
    return { handled: true, message: playing ? 'Continui.' : 'Am încercat să continui, dar nu pot confirma redarea.' };
  }
  if (MEDIA_NEXT_PATTERN.test(t)) {
    await mediaNext(pkg);
    return { handled: true, message: 'Următoarea.' };
  }
  if (MEDIA_PREVIOUS_PATTERN.test(t)) {
    await mediaPrevious(pkg);
    return { handled: true, message: 'Anterioara.' };
  }
  // Defers to the more specific WhatsApp in-call patterns ("oprește apelul", mute) further down
  // in runMission() — a bare "oprește" here would otherwise shadow them if both a call and a
  // media session happened to be active at once.
  if (MEDIA_STOP_PATTERN.test(t) && !END_CALL_PATTERN.test(t) && !MUTE_CALL_PATTERN.test(t)) {
    const outcome = await stopMedia(pkg);
    if (outcome.ok) activeMediaSession = null;
    return { handled: true, message: outcome.message };
  }
  return null;
}

// Generic provider search trigger — "caută X pe Spotify/Netflix/Prime Video". "pe YouTube" is
// deliberately NOT matched here (extractYouTubeQuery above already owns that, untouched, proven).
function extractGenericMediaSearch(text: string): { provider: MediaProvider; query: string } | null {
  const t = (text || '').trim();
  const provider = findProviderByMention(t);
  if (!provider || provider.id === 'youtube') return null;
  let m = /\bcaut[ăa]\s+(.+?)\s+pe\s+\S+$/i.exec(t);
  if (m && m[1].trim()) return { provider, query: m[1].trim() };
  // ROUND_VOICE_INTENT_NORMALIZATION_1 — same PLAY-verb gap as extractYouTubeQuery above, for
  // every other provider ("pune INNA pe Spotify").
  m = /\b(?:pune|deschide|porne[șs]te|bag[ăa]|vreau)\s+(.+?)\s+pe\s+\S+$/i.exec(t);
  if (m && m[1].trim()) return { provider, query: m[1].trim() };
  m = /\bcaut[ăa]\s+(.+)$/i.exec(t);
  if (m) {
    const q = m[1].replace(/\bpe\s+\S+$/i, '').trim();
    if (q) return { provider, query: q };
  }
  return null;
}

const MEDIA_DECLINE_PATTERN = /\b(nu|altul|alta|altceva|niciunul|niciuna)\b/i;

let pendingMediaSelection: { provider: MediaProvider; candidates: MediaCandidate[]; query: string } | null = null;
let pendingMediaSelectionSetAt = 0;
const PENDING_MEDIA_SELECTION_TIMEOUT_MS = 60000;

async function resolveMediaSelection(
  rawText: string,
  pending: { provider: MediaProvider; candidates: MediaCandidate[]; query: string },
): Promise<MissionRunResult> {
  const t = stripDiac(rawText);
  if (MEDIA_DECLINE_PATTERN.test(t) && !/\bda\b/.test(t)) {
    pendingMediaSelection = pending;
    pendingMediaSelectionSetAt = Date.now();
    const names = pending.candidates.map((c) => c.title).join(', ');
    return {
      handled: true,
      message: `Am: ${names}. Pe care s-o pornesc?`,
      disambiguation: { candidates: pending.candidates.map((c) => ({ name: c.title })) },
    };
  }
  const pick = matchDisambiguationPick(rawText, pending.candidates.map((c) => ({ name: c.title })));
  if (!pick) {
    pendingMediaSelection = pending;
    pendingMediaSelectionSetAt = Date.now();
    return {
      handled: true,
      message: 'N-am înțeles pe care. Spune titlul, sau „prima", „a doua".',
      disambiguation: { candidates: pending.candidates.map((c) => ({ name: c.title })) },
    };
  }
  // ROUND_SPOTIFY_SELECT_2 — selectMediaCandidate() now verifies playback (and, where possible,
  // metadata consistency) internally before ever returning ok:true; its own message already
  // reflects the precise outcome (playing+matched / playing-but-unconfirmed-metadata / failed).
  // No second, redundant verifyPlaying() here — the orchestrator just relays what was already
  // authoritatively confirmed.
  const outcome = await selectMediaCandidate(pending.provider, pick.name, pending.query);
  if (outcome.ok) {
    setActiveMediaSession({ packageName: pending.provider.packageName, label: pick.name });
    return { handled: true, message: outcome.message };
  }
  return { handled: true, message: outcome.message };
}

// WhatsApp in-call controls (product-owner requested 2026-07-14) — narrow, self-contained
// control commands over an ALREADY-active call, not a new communication request, so these are
// checked and dispatched directly here rather than threaded through the full goal/task pipeline
// (Goal Extractor/Mission Planner) built for starting new missions.
const END_CALL_PATTERN = /\b(închide|inchide|termină|termina|opre[șs]te)\s+apelul\b|\bhang\s*up\b|\bend\s+(?:the\s+)?call\b/i;
const MUTE_CALL_PATTERN = /\b(pune|fă|fa|activeaz[ăa])\s+(?:pe\s+)?mute\b|\bmute\b|\bdezactiveaz[ăa]\s+microfonul\b/i;

export async function runMission(rawText: string, options: RunMissionOptions = {}): Promise<MissionRunResult> {
  logAudioDiag('EXEC_TRACE_INPUT', `text=${JSON.stringify(rawText)}`);
  const normalizedText = normalizeTranscript(rawText);
  const cleanedForRepairCheck = cleanDiscourse(normalizedText);
  devLog('rawText=', rawText, 'normalizedText=', normalizedText);

  // ROUND_YOUTUBE_GOVERNANCE_2 — a pending "which video?" proposal takes priority over everything
  // else, same precedence reasoning as pendingDisambiguation below (checked first since it is the
  // narrower, more recently-introduced pending state).
  if (pendingYouTubeSelection && Date.now() - pendingYouTubeSelectionSetAt < PENDING_YT_SELECTION_TIMEOUT_MS) {
    const py = pendingYouTubeSelection;
    pendingYouTubeSelection = null;
    return resolveYouTubeSelection(rawText, py);
  } else if (pendingYouTubeSelection) {
    pendingYouTubeSelection = null; // expired
  }

  // ROUND_MEDIA_GOVERNANCE_1 — generic-provider "which one?" proposal, same precedence tier as
  // the YouTube-specific one above (they never coexist — only one search can be pending).
  if (pendingMediaSelection && Date.now() - pendingMediaSelectionSetAt < PENDING_MEDIA_SELECTION_TIMEOUT_MS) {
    const pm = pendingMediaSelection;
    pendingMediaSelection = null;
    return resolveMediaSelection(rawText, pm);
  } else if (pendingMediaSelection) {
    pendingMediaSelection = null; // expired
  }

  // ROUND_MEDIA_GOVERNANCE_1 — "pauză"/"continuă"/"oprește"/"următoarea"/"anterioară"/"revino la
  // Benson" act on whatever is currently playing. Checked before the pending-selection answer
  // patterns would otherwise misinterpret them, but only actually fires when activeMediaSession is
  // set (see tryHandleActiveMediaCommand) — a null return here means "not this utterance," so
  // normal dispatch (including the pending-disambiguation checks below) still applies.
  const mediaCommandResult = await tryHandleActiveMediaCommand(rawText);
  if (mediaCommandResult) return mediaCommandResult;

  // Round D — a pending "which one?" proposal takes priority: route this utterance as the answer.
  if (pendingDisambiguation && Date.now() - pendingDisambiguationSetAt < PENDING_DISAMBIGUATION_TIMEOUT_MS) {
    const pd = pendingDisambiguation;
    pendingDisambiguation = null;
    if (!/\b(nu|nimic|las[ăa]|renun[țt]|stop|anuleaz[ăa])\b/i.test(normalizedText)) {
      // ROUND_GENERIC_CONFIRMATION_FIX_1 (device-log-proven) — a single-candidate proposal is
      // phrased as a yes/no question ("Am găsit Rechner. O deschid?"), not a "which one?" choice.
      // matchDisambiguationPick() only recognizes an ordinal or a name-token match, so a plain "da"
      // never picked the one candidate on offer — the only way it worked before was BENSON's own
      // TTS prompt (which contains the candidate's name) bleeding into the mic via self-echo and
      // accidentally satisfying the name match. A real affirmative reply, captured cleanly, must
      // resolve the same single candidate directly.
      const isPlainYes = pd.candidates.length === 1 && /\b(da|dap|yes|yeah|yep|sigur|ok|okay|bine)\b/i.test(normalizedText);
      const pick = isPlainYes ? pd.candidates[0] : matchDisambiguationPick(normalizedText, pd.candidates);
      if (pick) {
        devLog('disambiguation resolved ->', pick.name);
        const request = createActionRequest({
          source: options.source ?? 'voice',
          rawText,
          intent: 'OPEN_APP',
          parameters: { appName: pick.name },
          riskLevel: 'LOW',
          requiresConfirmation: false,
        });
        const result = await governAction(request, { confirmed: true });
        return { handled: true, message: result.message || `Deschid ${pick.name}.` };
      }
    }
    // No usable pick — fall through and handle this utterance as a fresh command.
  } else if (pendingDisambiguation) {
    pendingDisambiguation = null; // expired
  }

  if (pendingClarification && Date.now() - pendingClarificationSetAt < PENDING_CLARIFICATION_TIMEOUT_MS) {
    const clar = pendingClarification;
    pendingClarification = null; // consumed — resolved here or not, never ask twice off one answer
    if (clar.kind === 'whatsapp_contact' && normalizedText.trim()) {
      const request = createActionRequest({
        source: options.source ?? 'voice',
        rawText,
        intent: 'OPEN_WHATSAPP_CONTACT',
        parameters: { contactName: normalizedText.trim(), channel: 'whatsapp' },
        riskLevel: 'MEDIUM',
        requiresConfirmation: true,
      });
      const bridgeResult = enrichContactAction(request, options.contacts ?? []);
      if (bridgeResult.status !== 'resolved') {
        return { handled: true, message: bridgeResult.message };
      }
      const result = await governAction(bridgeResult.request, { confirmed: true });
      return { handled: true, message: result.message };
    }
    // Empty/unusable answer — fall through to normal dispatch below instead of dead-ending.
  } else if (pendingClarification) {
    pendingClarification = null; // expired — clear silently, don't resurrect a stale question
  }

  if (BARE_CHANNEL_REPAIR_PATTERN.test(cleanedForRepairCheck)) {
    const lastContact = getContext().lastContact;
    if (!lastContact) {
      pendingClarification = { kind: 'whatsapp_contact' };
      pendingClarificationSetAt = Date.now();
      return { handled: true, message: 'Pentru cine?' };
    }
    const request = createActionRequest({
      source: options.source ?? 'voice',
      rawText,
      intent: 'OPEN_WHATSAPP_CONTACT',
      parameters: { contactName: lastContact, channel: 'whatsapp' },
      riskLevel: 'MEDIUM',
      requiresConfirmation: true,
    });
    const bridgeResult = enrichContactAction(request, options.contacts ?? []);
    if (bridgeResult.status !== 'resolved') {
      return { handled: true, message: bridgeResult.message };
    }
    const result = await governAction(bridgeResult.request, { confirmed: true });
    return { handled: true, message: result.message };
  }

  if (END_CALL_PATTERN.test(cleanedForRepairCheck) || MUTE_CALL_PATTERN.test(cleanedForRepairCheck)) {
    const action = END_CALL_PATTERN.test(cleanedForRepairCheck) ? 'endCall' : 'muteCall';
    const request = buildGovernedRequest('whatsapp', action, {});
    const outcome = await executeGoverned(request, { confirmed: false });
    return { handled: true, message: outcome.message };
  }

  // ROUND_YOUTUBE_GOVERNANCE_1 — GoalInterpreter: provider=YouTube, goal=SEARCH, query=extracted
  // text. Checked before extractGoals/planMission so a query never gets collapsed into a plain
  // OPEN_APP("youtube") mission; a bare "deschide YouTube" (no query) returns null here and falls
  // through unchanged to the existing generic app-open pipeline below.
  const ytQuery = extractYouTubeQuery(cleanedForRepairCheck) || extractYouTubeQuery(normalizedText);
  if (ytQuery) {
    logAudioDiag('EXEC_TRACE_MISSION', `missionId=youtube_search taskCount=1`);
    const outcome = await searchYouTube(ytQuery);
    if (!outcome.ok || !outcome.candidates || outcome.candidates.length === 0) {
      return { handled: true, message: outcome.message };
    }
    pendingYouTubeSelection = { candidates: outcome.candidates, query: ytQuery };
    pendingYouTubeSelectionSetAt = Date.now();
    const names = outcome.candidates.map((c) => c.title).join(', ');
    return {
      handled: true,
      message: `Am găsit: ${names}. Ce vrei să asculți?`,
      disambiguation: { candidates: outcome.candidates.map((c) => ({ name: c.title })) },
    };
  }

  // ROUND_MEDIA_GOVERNANCE_1 — same GoalInterpreter idea, generalized to whichever OTHER provider
  // is named ("caută INNA pe Spotify"). "pe YouTube" never reaches here (filtered out inside
  // extractGenericMediaSearch itself so the block above always owns it, unchanged).
  const genericMedia = extractGenericMediaSearch(cleanedForRepairCheck) || extractGenericMediaSearch(normalizedText);
  if (genericMedia) {
    logAudioDiag('EXEC_TRACE_MISSION', `missionId=media_search provider=${genericMedia.provider.id} taskCount=1`);
    const outcome = await searchMedia(genericMedia.provider, genericMedia.query);
    if (!outcome.ok || !outcome.candidates || outcome.candidates.length === 0) {
      return { handled: true, message: outcome.message };
    }
    pendingMediaSelection = { provider: genericMedia.provider, candidates: outcome.candidates, query: genericMedia.query };
    pendingMediaSelectionSetAt = Date.now();
    const names = outcome.candidates.map((c) => c.title).join(', ');
    return {
      handled: true,
      message: `Am găsit: ${names}. Ce vrei să asculți?`,
      disambiguation: { candidates: outcome.candidates.map((c) => ({ name: c.title })) },
    };
  }

  const goals = extractGoals(rawText, normalizedText);
  // ROUND_WA_WRITE_MESSAGE_PAYLOAD — parser → goal payload trace (contact and body as separate
  // fields at every stage). goalExtractor copies parser params verbatim, so PARSED == GOAL here.
  for (const g of goals) {
    if (g.type === 'COMMUNICATION') {
      logAudioDiag('WA_PAYLOAD_PARSED', `contact=${JSON.stringify(g.entities.contact ?? '')} message=${JSON.stringify(g.entities.message ?? '')} intent=${g.sourceIntent}`);
      logAudioDiag('WA_PAYLOAD_GOAL', `contact=${JSON.stringify(g.entities.contact ?? '')} message=${JSON.stringify(g.entities.message ?? '')}`);
    }
  }
  const problem = solveProblem({ rawText, normalizedText, goals, context: getContext() });
  devLog('problemType=', problem.problemType, 'inferredGoals=', problem.inferredGoals.length, 'missingInfo=', problem.missingInfo);

  if (problem.suggestedNextStep) {
    emitEvent('GoalCreated', { problemType: problem.problemType, missingInfo: problem.missingInfo });
    return { handled: true, message: problem.suggestedNextStep };
  }

  if (problem.inferredGoals.length === 0) {
    // Genuinely unrecognized — fall through to the existing Claude/chat path, same invariant
    // as BENSON 20 (deterministic-first, Claude only for real ambiguity).
    return { handled: false, message: '' };
  }

  // Round D — the signature now includes the actual utterance text, not just the extracted goal
  // type+entities. "deschide un radio" and "vreau un radio românesc" both extract to the same
  // MEDIA goal but are DIFFERENT requests — the old signature collided them and the second was
  // dropped as a self-echo (returning an empty message = silence). Treapta 1's hard mic-close
  // during TTS is now the real defense against BENSON hearing its own reply, so this can be
  // stricter without reopening the echo loop.
  const goalPart = problem.inferredGoals.map((g) => `${g.type}:${JSON.stringify(g.entities)}`).join('|');
  const goalSignature = `${goalPart}|txt:${normalizedText.toLowerCase().replace(/\s+/g, ' ').trim().slice(0, 80)}`;
  const now = Date.now();
  if (goalSignature === lastExecutedGoalSignature && now - lastExecutedGoalAt < MISSION_REPEAT_COOLDOWN_MS) {
    devLog('mission repeat cooldown: identical goal signature within', MISSION_REPEAT_COOLDOWN_MS, 'ms — likely self-echo, skipping re-execution');
    // Never return an empty message (= silence). handled:false hands it to the brain/chat path,
    // which will at least say something rather than leave the user hanging.
    return { handled: false, message: '' };
  }
  lastExecutedGoalSignature = goalSignature;
  lastExecutedGoalAt = now;

  const plan = planMission(problem.inferredGoals);
  lastMissionPlan = plan;
  emitEvent('MissionPlanned', { taskCount: plan.tasks.length }, plan.id);
  setActiveMission(plan.id);
  devLog('mission planned', plan.id, 'tasks=', plan.tasks.map((t) => t.type));
  logAudioDiag('EXEC_TRACE_MISSION', `missionId=${plan.id} taskCount=${plan.tasks.length}`);
  logAudioDiag('EXEC_TRACE_PLAN', `tasks=${JSON.stringify(plan.tasks.map((t) => t.type))}`);

  // WA-CONTACT-TRACE — where the WhatsApp contact name comes from, step 1: the parser's output.
  // No resolution, no fallback happens here or later for placeCall — the parsed name is what
  // reaches the native executor (via buildCallSearchString clitic-strip only). See whatsappTool
  // placeCall's WA_CONTACT_INPUT line for the value handed to runWhatsAppCallNative.
  for (const t of plan.tasks) {
    if (t.type === 'PREPARE_MESSAGE' || t.type === 'PREPARE_CALL') {
      devLog('WA_CONTACT_INPUT', 'stage=parser transcript=', JSON.stringify(rawText),
        'parsed_contactName=', JSON.stringify(typeof t.input.contactName === 'string' ? t.input.contactName : ''),
        'mode=', JSON.stringify(t.input.mode ?? ''));
    }
  }

  return runPlanFrom(plan, 0, rawText, options.contacts ?? [], false, options.onAck);
}

// Called when the user replies to a pending confirmation question — continues the SAME mission
// from the task that was waiting, using the SAME rawText the mission was originally built from
// (the contact/message/destination values already resolved don't need re-parsing).
export async function resumePendingTask(
  pending: { plan: MissionPlan; taskIndex: number },
  contacts: TrustedContact[] = [],
  onAck?: (shortText: string) => void,
  // ROUND_WA_REPLY_CONTEXT_1 — set ONLY when the pending task is waAwaitingMessageBody (the "Ce
  // să-i scriu?" case, see runGovernedTask above): the dictated reply IS the data this task was
  // missing, not a yes/no answer to classify. toGovernedCall() already re-reads task.input.message
  // fresh on every call — writing it here before resuming is the whole fix, no new plumbing.
  injectedMessageBody?: string,
): Promise<MissionRunResult> {
  const { plan, taskIndex } = pending;
  const task = plan.tasks[taskIndex];
  const isMessageBodyInjection = injectedMessageBody !== undefined && !!task?.input.waAwaitingMessageBody;
  if (isMessageBodyInjection) {
    task.input.message = injectedMessageBody;
    task.input.waAwaitingMessageBody = false;
    logAudioDiag('WA_REPLY_CONTEXT_AFTER', `missionId=${plan.id} contact=${JSON.stringify(task.input.waWriteContact ?? '')} message_body=${JSON.stringify(injectedMessageBody)} state=RESUMING`);
  }
  const rawText = plan.goals[0]?.rawText ?? '';
  // E1-5 — the user just said "da"; the ACK ("O sun.") now fires before the WhatsApp/Waze side
  // effect, in parallel with it.
  // ROOT CAUSE (device-log-proven 2026-09-13): a message-body injection has NEVER been through
  // Phase A — toGovernedCall's PREPARE_MESSAGE branch has no waWriteMissionId yet, so passing
  // confirmed=true here skipped straight to missionExecutor's runTool(), whose prepareMessage
  // branch hard-refuses by design ("Scrierea de mesaje... dezactivată momentan" — that guard exists
  // exactly to catch a prepareMessage that reached runTool without going through Phase A first).
  // confirmed=false routes back through execute()'s normal gate, which runs Phase A for real
  // (open chat, verify header, type, verify-typed) now that the message text is known, then asks
  // "Îl trimit?" itself — the existing, proven Phase A/B flow, unchanged.
  return runPlanFrom(plan, taskIndex, rawText, contacts, !isMessageBodyInjection, onAck);
}
