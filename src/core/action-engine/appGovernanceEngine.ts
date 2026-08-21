// BENSON Action Engine — App Governance Engine (Module 6, the priority module).
// The single decision core: every ActionRequest from the voice/text pipeline passes through
// governAction() before anything executes. It composes the existing Confirmation Gate (via
// dispatchAction, which already calls it) and the executor dispatch — nothing here duplicates
// that decision logic, this is a thin orchestration + full-record logging layer on top.
//
// Invariant this module enforces by construction, not by new code: simple/deterministic
// commands never reach Claude. governAction only ever resolves an ActionIntent to an executor
// result or a CHAT fallthrough — it never calls Claude itself. The CHAT intent is the only path
// back to app/index.tsx's existing Claude call, unchanged.

import type { ActionRequest } from './actionRequest';
import type { ActionResult } from './actionResult';
import { dispatchAction } from './actionDispatcher';
import type { DispatchOptions } from './actionDispatcher';

export interface GovernanceLogEntry {
  timestamp: number;
  rawTranscript: string;
  normalizedText: string;
  cleanedText: string;
  intent: string;
  confidence: number;
  targetApp?: string;
  packageName?: string;
  destination?: string;
  contact?: string;
  message?: string;
  action: string;
  permission?: string;
  result: string;
  error?: string;
}

const MAX_LOG_ENTRIES = 20;
const logRingBuffer: GovernanceLogEntry[] = [];

function appendLog(entry: GovernanceLogEntry): void {
  logRingBuffer.push(entry);
  if (logRingBuffer.length > MAX_LOG_ENTRIES) logRingBuffer.shift();
}

// Newest first — the debug panel wants "last command heard" at the top.
export function getLastActionLogs(): GovernanceLogEntry[] {
  return [...logRingBuffer].reverse();
}

export type GovernOptions = DispatchOptions;

export async function governAction(request: ActionRequest, options?: GovernOptions): Promise<ActionResult> {
  const result = await dispatchAction(request, options);

  appendLog({
    timestamp: Date.now(),
    rawTranscript: request.rawText,
    normalizedText:
      typeof request.parameters.normalizedText === 'string' ? request.parameters.normalizedText : request.rawText,
    cleanedText:
      typeof request.parameters.cleanedText === 'string' ? request.parameters.cleanedText : request.rawText,
    intent: request.intent,
    confidence: typeof request.parameters.confidence === 'number' ? request.parameters.confidence : 0,
    targetApp: typeof request.parameters.targetApp === 'string' ? request.parameters.targetApp : undefined,
    packageName: typeof request.parameters.packageName === 'string' ? request.parameters.packageName : undefined,
    destination: typeof request.parameters.destinationLabel === 'string' ? request.parameters.destinationLabel : undefined,
    contact: typeof request.parameters.contactName === 'string' ? request.parameters.contactName : undefined,
    message: typeof request.parameters.message === 'string' ? request.parameters.message : undefined,
    action: result.status,
    result: result.message,
    error: result.errorDetails,
  });

  return result;
}
