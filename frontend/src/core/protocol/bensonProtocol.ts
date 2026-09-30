// PHASE_A_PROTOCOL_AND_TIMEOUT (2026-09-19) — provider-neutral, device-neutral Brain/Hand
// contract. Types + pure mapping functions ONLY. Nothing in the runtime app imports this file
// yet — no behavior change. See ROUND report for the full migration plan (PHASE A/B/C/D).
//
// Deliberately depends on NOTHING but the two existing pure type files (import type, erased at
// build/test time — no runtime coupling, no Android import, no OpenAI import, no executor
// import). ActionRequest/ActionResult already carry almost everything this contract needs
// (requiresConfirmation, status, errorCode, appOpened) — these mappings are additive, not a
// rewrite of either shape.
import type { ActionRequest } from '../action-engine/actionRequest';
import type { ActionResult } from '../action-engine/actionResult';
import type { ActionIntent, ActionStatus } from '../action-engine/actionTypes';

export type BensonDeviceType = 'ANDROID_PHONE' | 'ANDROID_TV' | 'WINDOWS' | 'WATCH' | 'SMART_HOME';

export type BensonAction =
  | 'OPEN_APP'
  | 'CLICK_VISIBLE'
  | 'SEARCH'
  | 'TYPE_TEXT'
  | 'PLAY'
  | 'PAUSE'
  | 'STOP'
  | 'NEXT'
  | 'PREVIOUS'
  | 'CALL'
  | 'MESSAGE'
  | 'SET_DEVICE_STATE';

export interface BensonIntent {
  protocolVersion: 1;
  intentId: string;
  missionId: string;
  targetDevice: { deviceId: string; deviceType: BensonDeviceType };
  action: BensonAction;
  app?: string;
  target?: string;
  query?: string;
  text?: string;
  requiresConfirmation: boolean;
  expectedOutcome?: { type: string; value?: string };
}

export type HandStatus = 'SUCCESS' | 'FAILED' | 'PARTIAL' | 'REQUIRES_USER' | 'UNSUPPORTED';

export interface HandResult {
  protocolVersion: 1;
  intentId: string;
  missionId: string;
  deviceId: string;
  status: HandStatus;
  errorCode?: string;
  failedStep?: string;
  observation?: { foregroundApp?: string; visibleState?: string; matchedTarget?: string };
  verification?: { verified: boolean; method?: string; evidence?: string };
  executionTimeMs: number;
}

// ── ActionRequest -> BensonIntent ───────────────────────────────────────────────────────────
// Only the existing ActionIntent values with an unambiguous, already-Android-hand-neutral
// equivalent are mapped. The rest (NAVIGATE_TO_PLACE, SOS, CALENDAR_ACTION, CHAT, ...) have no
// honest 1:1 match in this initial 12-action vocabulary — mapActionRequestToBensonIntent()
// returns null for those rather than guessing, exactly per this phase's "no runtime decision"
// scope. Extend this table (never accept a raw string) when a real BensonAction is defined for
// them.
const ACTION_INTENT_TO_BENSON_ACTION: Partial<Record<ActionIntent, BensonAction>> = {
  OPEN_APP: 'OPEN_APP',
  CLOSE_APP: 'STOP',
  OPEN_WAZE: 'OPEN_APP',
  OPEN_GOOGLE_MAPS: 'OPEN_APP',
  CALL_CONTACT: 'CALL',
  OPEN_WHATSAPP: 'OPEN_APP',
  OPEN_WHATSAPP_CONTACT: 'OPEN_APP',
  MESSAGE_CONTACT: 'MESSAGE',
  SEND_SMS: 'MESSAGE',
  CONTACTS_SEARCH: 'SEARCH',
  MEDIA_PLAY: 'PLAY',
};

export interface BensonIntentContext {
  missionId: string;
  deviceId: string;
  deviceType: BensonDeviceType;
}

function stringParam(parameters: Record<string, unknown>, key: string): string | undefined {
  const v = parameters[key];
  return typeof v === 'string' && v.trim() ? v : undefined;
}

// Pure: no Date.now(), no id generation, no I/O. request.id is already a stable per-request id
// (see actionRequest.ts's generateRequestId) and is reused verbatim as intentId — missionId and
// the target device are not knowable from an ActionRequest alone (neither concept exists on that
// type yet), so the caller supplies them explicitly rather than this function inventing defaults.
export function mapActionRequestToBensonIntent(
  request: ActionRequest,
  context: BensonIntentContext,
): BensonIntent | null {
  const action = ACTION_INTENT_TO_BENSON_ACTION[request.intent];
  if (!action) return null;
  return {
    protocolVersion: 1,
    intentId: request.id,
    missionId: context.missionId,
    targetDevice: { deviceId: context.deviceId, deviceType: context.deviceType },
    action,
    app: stringParam(request.parameters, 'appName') ?? stringParam(request.parameters, 'app'),
    target: stringParam(request.parameters, 'target'),
    query: stringParam(request.parameters, 'query'),
    text: stringParam(request.parameters, 'text') ?? stringParam(request.parameters, 'message'),
    requiresConfirmation: request.requiresConfirmation,
  };
}

// ── ActionResult -> HandResult ──────────────────────────────────────────────────────────────
// Total mapping (every ActionStatus has a defined HandStatus) — unlike the intent side, a result
// must never be silently dropped: something already happened and the caller needs an answer.
const ACTION_STATUS_TO_HAND_STATUS: Record<ActionStatus, HandStatus> = {
  success: 'SUCCESS',
  needs_confirmation: 'REQUIRES_USER',
  needs_permission: 'REQUIRES_USER',
  needs_disambiguation: 'REQUIRES_USER',
  not_found: 'FAILED',
  unsupported: 'UNSUPPORTED',
  failed: 'FAILED',
  cancelled: 'FAILED',
};

export interface HandResultContext {
  intentId: string;
  missionId: string;
  deviceId: string;
  executionTimeMs: number;
}

// Pure: executionTimeMs is measured by the caller (this function does no timing itself) so the
// mapping stays deterministic and testable.
export function mapActionResultToHandResult(result: ActionResult, context: HandResultContext): HandResult {
  return {
    protocolVersion: 1,
    intentId: context.intentId,
    missionId: context.missionId,
    deviceId: context.deviceId,
    status: ACTION_STATUS_TO_HAND_STATUS[result.status],
    errorCode: result.errorCode,
    observation: result.appOpened ? { foregroundApp: result.appOpened } : undefined,
    executionTimeMs: context.executionTimeMs,
  };
}
