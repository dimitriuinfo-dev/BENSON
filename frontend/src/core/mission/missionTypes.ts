// BENSON Mission Governance — Waze + WhatsApp (Phase 1). Shared types only, no runtime logic.
// Deliberately a narrower, tool-based schema than the existing action-engine's intent-based
// ActionRequest (src/core/action-engine/actionRequest.ts) — that one stays exactly as-is and
// keeps governing every other intent (calls, media, calendar, ...). This one exists only for the
// two tools this phase governs end-to-end.

export type ToolName = 'waze' | 'whatsapp';

export type WazeAction = 'openApp' | 'openNavigation' | 'openSearch';
export type WhatsAppAction = 'openApp' | 'openContact' | 'prepareMessage' | 'placeCall' | 'endCall' | 'muteCall';
export type MissionAction = WazeAction | WhatsAppAction;

export type ValidationStatus = 'pending' | 'valid' | 'invalid';

// Exact shape requested: id, tool, action, params, requiresConfirmation, validationStatus, createdAt.
export interface ActionRequest {
  id: string;
  tool: ToolName;
  action: MissionAction;
  params: Record<string, unknown>;
  requiresConfirmation: boolean;
  validationStatus: ValidationStatus;
  createdAt: number;
}

export type MissionState =
  | 'Pending'
  | 'Validating'
  | 'WaitingConfirmation'
  | 'Running'
  | 'WaitingUser'
  | 'Completed'
  | 'Failed'
  | 'Cancelled'
  // MISSION-FIX-1 — terminal: a still-unconfirmed mission was outranked by a concrete new user
  // command. Never executable, never re-confirmable. Treated exactly like Cancelled downstream.
  | 'Superseded';

// launch_requested: Android accepted the intent/deep link without throwing — the only claim
// BENSON is ever allowed to make on its own. app_switch_observed: additionally corroborated by an
// AppState transition away from 'active' (supporting evidence, not proof). launch_failed: the
// intent threw, could not be resolved, or every fallback failed. opened_manual_action_required:
// the deep link succeeded but a subsequent accessibility-driven step (e.g. finding/tapping
// WhatsApp's own call button) could not be verified — BENSON stops short of tapping anything it
// isn't sure about and says so, rather than guessing.
export type LaunchOutcome =
  | 'launch_requested'
  | 'app_switch_observed'
  | 'launch_failed'
  | 'opened_manual_action_required';

export interface ToolCallResult {
  outcome: LaunchOutcome;
  via?: string;
  error?: string;
}

export interface Mission {
  id: string;
  request: ActionRequest;
  state: MissionState;
  reason?: string; // structured, honest — shown to the user, never raw JSON
  userMessage: string;
  createdAt: number;
  updatedAt: number;
}
