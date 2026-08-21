// BENSON Action Engine — Action Dispatcher.
// The single entry point that takes an already-built ActionRequest, runs it past the
// Confirmation Gate, picks the right executor, and returns the ActionResult. Not wired into
// voice/UI yet — this is the coordinating layer future callers (a voice bridge, a button) will
// use instead of talking to executors or the confirmation gate directly.

import type { ActionIntent } from './actionTypes';
import type { ActionRequest } from './actionRequest';
import type { ActionResult } from './actionResult';
import { failedResult, needsConfirmationResult, unsupportedResult } from './actionResult';
import type { Executor } from './executorTypes';
import { evaluateConfirmation } from '../safety';
import {
  AppLauncherExecutor,
  NavigationExecutor,
  PhoneCallExecutor,
  WhatsAppExecutor,
  StubExecutor,
  HelpExecutor,
} from '../../executors';

export interface DispatchOptions {
  // Set true once the user has explicitly confirmed a requires_confirmation action — the caller
  // (whatever shows the confirmation prompt) re-dispatches the same request with this set.
  confirmed?: boolean;
  // Override the executor list — mainly for testing; defaults to every known executor.
  executors?: Executor[];
}

const DEFAULT_EXECUTORS: Executor[] = [
  AppLauncherExecutor,
  NavigationExecutor,
  PhoneCallExecutor,
  WhatsAppExecutor,
  StubExecutor,
  HelpExecutor,
];

// OPEN_WAZE/OPEN_GOOGLE_MAPS are ambiguous on their own: "open Waze" (no destination) belongs to
// AppLauncherExecutor (Task 3's plain open); "navigate to X" (destination present) belongs to
// NavigationExecutor (Task 4). This is the one place that decision gets made.
const DESTINATION_PARAM_KEYS = ['address', 'latitude', 'longitude', 'destinationLabel'] as const;

function hasDestinationParams(parameters: Record<string, unknown>): boolean {
  return DESTINATION_PARAM_KEYS.some((key) => {
    const value = parameters[key];
    return value !== undefined && value !== null && value !== '';
  });
}

function selectExecutor(intent: ActionIntent, parameters: Record<string, unknown>, executors: Executor[]): Executor | undefined {
  if (intent === 'OPEN_WAZE' || intent === 'OPEN_GOOGLE_MAPS') {
    const preferredName = hasDestinationParams(parameters) ? 'NavigationExecutor' : 'AppLauncherExecutor';
    const preferred = executors.find((e) => e.name === preferredName && e.canHandle(intent));
    if (preferred) return preferred;
  }
  return executors.find((e) => e.canHandle(intent));
}

export async function dispatchAction(request: ActionRequest, options?: DispatchOptions): Promise<ActionResult> {
  const executors = options?.executors ?? DEFAULT_EXECUTORS;

  const policy = evaluateConfirmation(request);

  if (policy.decision === 'blocked') {
    return failedResult(request.id, policy.userFacingMessage || 'This action is blocked.', {
      errorCode: 'BLOCKED',
      errorDetails: policy.reason,
    });
  }

  if (policy.decision === 'requires_confirmation' && options?.confirmed !== true) {
    return needsConfirmationResult(request.id, policy.userFacingMessage || 'Confirmation required.', {
      riskLevel: policy.riskLevel,
      reason: policy.reason,
    });
  }

  const executor = selectExecutor(request.intent, request.parameters, executors);
  if (!executor) {
    return unsupportedResult(request.id, `No executor available for ${request.intent}.`);
  }

  return executor.execute(request);
}
