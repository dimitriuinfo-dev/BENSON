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

const LOG_TAG = '[MissionOrchestrator]';
function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

export interface RunMissionOptions {
  source?: ActionSource;
  contacts?: TrustedContact[];
}

export interface MissionRunResult {
  handled: boolean; // false => Mission Orchestrator found nothing; caller should fall through to Claude
  message: string;
  plan?: MissionPlan;
  pendingTask?: { plan: MissionPlan; taskIndex: number }; // set when a task needs confirmation
}

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
    if (task.input.mode === 'voice_call') {
      // "sună X pe WhatsApp" — governed as its own action (accessibility-verified call-button
      // tap, product-owner-authorized), not folded into prepareMessage/openContact.
      return { tool: 'whatsapp', action: 'placeCall', params: { contactName } };
    }
    const message = typeof task.input.message === 'string' ? task.input.message.trim() : '';
    return message
      ? { tool: 'whatsapp', action: 'prepareMessage', params: { contactName, message } }
      : { tool: 'whatsapp', action: 'openContact', params: { contactName } };
  }

  return null;
}

async function runGovernedTask(
  governed: GovernedCall,
  task: MissionTask,
  plan: MissionPlan,
  contacts: TrustedContact[],
  confirmed: boolean,
): Promise<{ message: string; waiting: boolean }> {
  task.status = 'RUNNING';
  emitEvent('TaskStarted', { type: task.type }, plan.id, task.id);

  const request = buildGovernedRequest(governed.tool, governed.action, governed.params);
  const outcome = await executeGoverned(request, { confirmed }, contacts);

  if (outcome.mission.state === 'WaitingConfirmation') {
    task.status = 'WAITING';
    plan.status = 'WAITING_FOR_CONFIRMATION';
    emitEvent('ConfirmationRequested', { task: task.type }, plan.id, task.id);
    return { message: outcome.message, waiting: true };
  }

  if (outcome.mission.state === 'Failed') {
    task.status = 'FAILED';
    task.errorMessage = outcome.mission.reason;
    task.resultMessage = outcome.message;
    emitEvent('TaskFailed', { reason: outcome.mission.reason }, plan.id, task.id);
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
): Promise<{ message: string; waiting: boolean }> {
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
): Promise<MissionRunResult> {
  plan.status = 'RUNNING';
  const messages: string[] = [];

  for (let i = startIndex; i < plan.tasks.length; i += 1) {
    const task = plan.tasks[i];
    const confirmed = i === startIndex ? firstTaskConfirmed : false;
    const outcome = await executeTask(task, plan, rawText, contacts, confirmed);
    messages.push(outcome.message);
    plan.updatedAt = Date.now();

    if (outcome.waiting) {
      return { handled: true, message: outcome.message, plan, pendingTask: { plan, taskIndex: i } };
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

// WhatsApp in-call controls (product-owner requested 2026-07-14) — narrow, self-contained
// control commands over an ALREADY-active call, not a new communication request, so these are
// checked and dispatched directly here rather than threaded through the full goal/task pipeline
// (Goal Extractor/Mission Planner) built for starting new missions.
const END_CALL_PATTERN = /\b(închide|inchide|termină|termina|opre[șs]te)\s+apelul\b|\bhang\s*up\b|\bend\s+(?:the\s+)?call\b/i;
const MUTE_CALL_PATTERN = /\b(pune|fă|fa|activeaz[ăa])\s+(?:pe\s+)?mute\b|\bmute\b|\bdezactiveaz[ăa]\s+microfonul\b/i;

export async function runMission(rawText: string, options: RunMissionOptions = {}): Promise<MissionRunResult> {
  const normalizedText = normalizeTranscript(rawText);
  const cleanedForRepairCheck = cleanDiscourse(normalizedText);
  devLog('rawText=', rawText, 'normalizedText=', normalizedText);

  if (BARE_CHANNEL_REPAIR_PATTERN.test(cleanedForRepairCheck)) {
    const lastContact = getContext().lastContact;
    if (!lastContact) {
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

  const goals = extractGoals(rawText, normalizedText);
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

  const goalSignature = problem.inferredGoals.map((g) => `${g.type}:${JSON.stringify(g.entities)}`).join('|');
  const now = Date.now();
  if (goalSignature === lastExecutedGoalSignature && now - lastExecutedGoalAt < MISSION_REPEAT_COOLDOWN_MS) {
    devLog('mission repeat cooldown: identical goal signature within', MISSION_REPEAT_COOLDOWN_MS, 'ms — likely self-echo, skipping re-execution');
    return { handled: true, message: '' };
  }
  lastExecutedGoalSignature = goalSignature;
  lastExecutedGoalAt = now;

  const plan = planMission(problem.inferredGoals);
  lastMissionPlan = plan;
  emitEvent('MissionPlanned', { taskCount: plan.tasks.length }, plan.id);
  setActiveMission(plan.id);
  devLog('mission planned', plan.id, 'tasks=', plan.tasks.map((t) => t.type));

  return runPlanFrom(plan, 0, rawText, options.contacts ?? [], false);
}

// Called when the user replies to a pending confirmation question — continues the SAME mission
// from the task that was waiting, using the SAME rawText the mission was originally built from
// (the contact/message/destination values already resolved don't need re-parsing).
export async function resumePendingTask(pending: { plan: MissionPlan; taskIndex: number }, contacts: TrustedContact[] = []): Promise<MissionRunResult> {
  const { plan, taskIndex } = pending;
  const rawText = plan.goals[0]?.rawText ?? '';
  return runPlanFrom(plan, taskIndex, rawText, contacts, true);
}
