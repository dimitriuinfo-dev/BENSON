// BENSON Mission Governance — Validation Layer.
// Runs schema validation -> parameter validation (incl. contact resolution) -> install/permission
// preflight, in that order, per the mandated side-effect order. Never touches Linking/Android
// directly — reads-only checks (canOpenURL, expo-contacts permission status) plus the pure
// contact resolver. Failure here means MissionExecutor never reaches the Tool Layer.

import type { TrustedContact } from '../contacts';
import type { ActionRequest } from './missionTypes';
import * as wazeTool from './tools/wazeTool';
import * as whatsappTool from './tools/whatsappTool';

export interface ValidationResult {
  valid: boolean;
  reason?: string;
  enrichedParams?: Record<string, unknown>;
}

const KNOWN_ACTIONS: Record<'waze' | 'whatsapp', string[]> = {
  waze: ['openApp', 'openNavigation', 'openSearch'],
  whatsapp: ['openApp', 'openContact', 'prepareMessage', 'placeCall', 'endCall', 'muteCall'],
};

function validateSchema(request: ActionRequest): ValidationResult | null {
  const allowed = KNOWN_ACTIONS[request.tool];
  if (!allowed) return { valid: false, reason: `Unknown tool "${request.tool}".` };
  if (!allowed.includes(request.action)) {
    return { valid: false, reason: `Unknown action "${request.action}" for tool "${request.tool}".` };
  }
  return null; // schema OK, keep going
}

async function validateWazeParams(request: ActionRequest): Promise<ValidationResult | null> {
  if (request.action === 'openApp') return null;
  const destination = typeof request.params.destination === 'string' ? request.params.destination.trim() : '';
  if (!destination) return { valid: false, reason: 'Destination is missing.' };
  return { valid: true, enrichedParams: { destination } };
}

async function validateWhatsAppParams(
  request: ActionRequest,
  contacts: TrustedContact[],
): Promise<ValidationResult | null> {
  // openApp/endCall/muteCall all operate on WhatsApp itself or the currently active call, not on
  // a named contact — no contactName needed for any of them.
  if (request.action === 'openApp' || request.action === 'endCall' || request.action === 'muteCall') return null;

  const contactName = typeof request.params.contactName === 'string' ? request.params.contactName.trim() : '';
  if (request.action === 'prepareMessage' && request.params.currentChat === true) {
    const message = typeof request.params.message === 'string' ? request.params.message : '';
    if (!message.trim()) return null; // executor asks for the body and retains this target context
    return { valid: true, enrichedParams: { contactName: 'current conversation', currentChat: true } };
  }
  if (!contactName) return { valid: false, reason: 'Contact name is missing.' };

  // Resolve WhatsApp targets using its live search results and verified conversation header.
  // ROUND_WHATSAPP_REPLY_REGRESSION_1 — an empty message for prepareMessage is NOT a validation
  // failure: missionExecutor.ts's maybeRunWhatsAppWritePhaseA already handles this case by design
  // (asks "Ce să-i scriu lui X?" and keeps the mission alive for the reply — see its own "Hard
  // invariant" comment). This block used to hard-reject it here first, in English, before that
  // flow ever ran — the mission failed outright instead of asking. contactName resolution below
  // still runs unconditionally for prepareMessage; message (even empty) survives untouched in the
  // enrichedParams merge (missionExecutor.ts only overlays contactName here, never message).

  // WhatsApp is the authority for every WhatsApp target. Preserve the requested name as a search
  // query; the tool must identify a unique exact result in WhatsApp's live UI before acting.
  if (request.action === 'openContact' || request.action === 'placeCall' ||
      request.action === 'prepareMessage' || whatsappTool.WHATSAPP_MESSAGE_VIA_ACCESSIBILITY) {
    const searchString = whatsappTool.buildCallSearchString(contactName);
    if (!searchString) return { valid: false, reason: 'No name was given to search for.' };
    return { valid: true, enrichedParams: { ...request.params, contactName: searchString, channel: 'whatsapp' } };
  }

  return { valid: false, reason: 'WhatsApp target context is incomplete.' };
}

async function preflight(request: ActionRequest): Promise<ValidationResult | null> {
  // Waze: install status is informational only — the Tool Layer's own cascade already falls back
  // to a browser search when the app isn't present, so this never blocks.
  //
  // WhatsApp: only the app-installed check blocks unconditionally here. Contacts permission is
  // deliberately NOT a blanket gate — validateWhatsAppParams's resolveContact() call already
  // merges real device contacts (permission-gated) with the caller-supplied stand-in list
  // (TEST_CONTACTS today, real trusted contacts later); if the stand-in list alone resolves the
  // name, missing permission never mattered and must not block. Its absence is only surfaced
  // (see the 'not_found' branch above) when resolution genuinely needed it and didn't have it.
  if (request.tool === 'whatsapp' && request.action !== 'openApp') {
    const installed = await whatsappTool.isInstalled();
    if (!installed) return { valid: false, reason: 'WhatsApp is not installed on this device.' };
  }
  return null;
}

export async function validateActionRequest(
  request: ActionRequest,
  contacts: TrustedContact[] = [],
): Promise<ValidationResult> {
  const schemaResult = validateSchema(request);
  if (schemaResult) return schemaResult;

  const paramResult =
    request.tool === 'waze' ? await validateWazeParams(request) : await validateWhatsAppParams(request, contacts);
  if (paramResult && !paramResult.valid) return paramResult;

  const preflightResult = await preflight(request);
  if (preflightResult) return preflightResult;

  return { valid: true, enrichedParams: paramResult?.enrichedParams };
}

export function isInstalledCheck(tool: 'waze' | 'whatsapp'): Promise<boolean> {
  return tool === 'waze' ? wazeTool.isInstalled() : whatsappTool.isInstalled();
}
