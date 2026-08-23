// BENSON Action Engine — the request shape every executor receives, and the one place that
// constructs it. No runtime app launching, no Linking, no contacts, no storage — pure data.

import type { ActionIntent, ActionRiskLevel, ActionSource } from './actionTypes';

export interface ActionRequest {
  id: string;
  source: ActionSource;
  rawText: string;
  intent: ActionIntent;
  parameters: Record<string, unknown>;
  riskLevel: ActionRiskLevel;
  requiresConfirmation: boolean;
  createdAt: number;
}

export interface CreateActionRequestInput {
  source: ActionSource;
  rawText: string;
  intent: ActionIntent;
  parameters?: Record<string, unknown>;
  riskLevel?: ActionRiskLevel;
  requiresConfirmation?: boolean;
}

let requestCounter = 0;

// Monotonic-enough id for in-process correlation (ActionRequest <-> ActionResult) — not a
// persisted identifier, so a simple counter + timestamp is sufficient.
function generateRequestId(): string {
  requestCounter += 1;
  return `action_${Date.now()}_${requestCounter}`;
}

export function createActionRequest(input: CreateActionRequestInput): ActionRequest {
  return {
    id: generateRequestId(),
    source: input.source,
    rawText: input.rawText,
    intent: input.intent,
    parameters: input.parameters ?? {},
    riskLevel: input.riskLevel ?? 'LOW',
    requiresConfirmation: input.requiresConfirmation ?? false,
    createdAt: Date.now(),
  };
}
