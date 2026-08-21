// BENSON Action Engine — Dry-Run Harness.
// Development/test-only function that runs a raw command through the full pipeline built across
// Tasks 9-11 (parse -> contact enrichment -> dispatch) without any voice/UI/Claude wiring. Useful
// for exercising the whole engine from a script or a future test suite before it's wired in.

import type { ActionRequest } from './actionRequest';
import type { ActionSource } from './actionTypes';
import type { ActionResult } from './actionResult';
import { unsupportedResult, notFoundResult, needsDisambiguationResult } from './actionResult';
import { parseCommandToActionRequest } from './commandParser';
import { enrichContactAction } from './contactActionBridge';
import { dispatchAction } from './actionDispatcher';
import type { ContactResolveStatus, TrustedContact } from '../contacts';

export interface ActionEngineDryRunOptions {
  source?: ActionSource;
  contacts?: TrustedContact[];
  confirmed?: boolean;
}

export interface ActionEngineDryRunResult {
  request: ActionRequest;
  enrichedRequest: ActionRequest;
  contactResolutionStatus?: ContactResolveStatus;
  result: ActionResult;
}

const CONTACT_INTENTS = ['CALL_CONTACT', 'OPEN_WHATSAPP_CONTACT', 'MESSAGE_CONTACT', 'FAMILY_LOCATION'];

export async function runActionEngineDryRun(
  rawText: string,
  options: ActionEngineDryRunOptions = {},
): Promise<ActionEngineDryRunResult> {
  const request = parseCommandToActionRequest(rawText, options.source ?? 'text');
  let enrichedRequest = request;
  let contactResolutionStatus: ContactResolveStatus | undefined;

  if (CONTACT_INTENTS.includes(request.intent)) {
    const bridgeResult = enrichContactAction(request, options.contacts ?? []);
    enrichedRequest = bridgeResult.request;
    contactResolutionStatus = bridgeResult.status;

    // Multiple candidates -> needs_disambiguation; anything else that isn't a clean resolution
    // (no match, or a match with no usable phone number) -> not_found. Neither case reaches an
    // executor — there's nothing safe to dispatch yet.
    if (bridgeResult.status === 'ambiguous') {
      return {
        request,
        enrichedRequest,
        contactResolutionStatus,
        result: needsDisambiguationResult(request.id, bridgeResult.message, { candidates: bridgeResult.candidates }),
      };
    }

    if (bridgeResult.status === 'not_found' || bridgeResult.status === 'missing_phone') {
      return {
        request,
        enrichedRequest,
        contactResolutionStatus,
        result: notFoundResult(request.id, bridgeResult.message),
      };
    }
  }

  if (request.intent === 'CHAT') {
    return {
      request,
      enrichedRequest,
      contactResolutionStatus,
      result: unsupportedResult(request.id, 'CHAT fallback not executed in dry run.'),
    };
  }

  const result = await dispatchAction(enrichedRequest, { confirmed: options.confirmed });

  return { request, enrichedRequest, contactResolutionStatus, result };
}
