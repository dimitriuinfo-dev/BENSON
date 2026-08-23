// BENSON Action Engine — the result shape every executor returns, and the helper constructors
// for each outcome. Pure data + pure functions: no runtime app launching, no Linking, no
// contacts, no storage.

import type { ActionStatus } from './actionTypes';

export interface ActionResult {
  requestId: string;
  status: ActionStatus;
  message: string;
  executed: boolean;
  appOpened?: string;
  errorCode?: string;
  errorDetails?: string;
  data?: Record<string, unknown>;
}

export function successResult(
  requestId: string,
  message: string,
  options?: { appOpened?: string; data?: Record<string, unknown> },
): ActionResult {
  return {
    requestId,
    status: 'success',
    message,
    executed: true,
    appOpened: options?.appOpened,
    data: options?.data,
  };
}

export function failedResult(
  requestId: string,
  message: string,
  options?: { errorCode?: string; errorDetails?: string },
): ActionResult {
  return {
    requestId,
    status: 'failed',
    message,
    executed: false,
    errorCode: options?.errorCode,
    errorDetails: options?.errorDetails,
  };
}

export function notFoundResult(requestId: string, message: string): ActionResult {
  return { requestId, status: 'not_found', message, executed: false };
}

export function unsupportedResult(requestId: string, message: string): ActionResult {
  return { requestId, status: 'unsupported', message, executed: false };
}

export function needsConfirmationResult(
  requestId: string,
  message: string,
  data?: Record<string, unknown>,
): ActionResult {
  return { requestId, status: 'needs_confirmation', message, executed: false, data };
}

export function needsDisambiguationResult(
  requestId: string,
  message: string,
  data?: Record<string, unknown>,
): ActionResult {
  return { requestId, status: 'needs_disambiguation', message, executed: false, data };
}
