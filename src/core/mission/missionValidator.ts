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
  if (!contactName) return { valid: false, reason: 'Contact name is missing.' };

  // Doctrine (product-owner-directed 2026-07-31): the WhatsApp call route no longer reads the
  // phone's contact list at all — BENSON governs WhatsApp's own UI (search by name, tap the first
  // result, tap call), the same way a human would, and resolving a name against a real person is
  // WhatsApp's job, not BENSON's. This used to call whatsappTool.resolveContact(contactName,
  // contacts) here to correct STT mishears (e.g. "Hanna" -> "Hannah") against the device address
  // book before typing into WhatsApp's search — that device-contacts read is the path being
  // disconnected. buildCallSearchString only strips leaked Romanian clitics/prepositions
  // (sună-O PE Hannah), it never touches contacts.
  if (request.action === 'placeCall') {
    const searchString = whatsappTool.buildCallSearchString(contactName);
    if (!searchString) return { valid: false, reason: 'No name was given to search for.' };
    return { valid: true, enrichedParams: { contactName: searchString } };
  }

  if (request.action === 'prepareMessage') {
    const message = typeof request.params.message === 'string' ? request.params.message.trim() : '';
    if (!message) return { valid: false, reason: 'Message text is missing.' };
  }

  // Doctrine (product-owner-directed 2026-08-01): openContact/prepareMessage now follow the same
  // no-device-contacts-read pattern as placeCall by default — search string only, WhatsApp's own
  // UI resolves it. See whatsappTool.ts's WHATSAPP_MESSAGE_VIA_ACCESSIBILITY doc comment for the
  // single-constant revert switch back to the resolveContact()/E.164 path below.
  if (whatsappTool.WHATSAPP_MESSAGE_VIA_ACCESSIBILITY) {
    const searchString = whatsappTool.buildCallSearchString(contactName);
    if (!searchString) return { valid: false, reason: 'No name was given to search for.' };
    return { valid: true, enrichedParams: { contactName: searchString } };
  }

  const resolved = await whatsappTool.resolveContact(contactName, contacts);

  if (resolved.status === 'ambiguous') {
    const names = (resolved.candidates ?? [])
      .map((c) => `${c.displayName} (${whatsappTool.maskPhone(c.phoneNumbers?.[0] ?? '')})`)
      .join(', ');
    return { valid: false, reason: `Multiple contacts match "${contactName}": ${names}. Which one?` };
  }
  if (resolved.status === 'not_found') {
    // Device contacts couldn't be searched (permission not granted) AND the caller-supplied
    // stand-in list (contacts param — e.g. TEST_CONTACTS today, real trusted contacts later)
    // didn't have a match either — surface the permission gap as the likely cause instead of a
    // bare "not found", since granting it is the actionable fix. If a stand-in match WOULD have
    // resolved it, permission was never actually needed and this branch isn't reached.
    const hasPermission = await whatsappTool.hasContactsPermission();
    const reason = hasPermission
      ? `No contact matching "${contactName}" was found.`
      : `No contact matching "${contactName}" was found, and Contacts permission is not granted (device contacts couldn't be searched).`;
    return { valid: false, reason };
  }
  if (resolved.status === 'missing_phone') {
    return { valid: false, reason: `${resolved.contact?.displayName ?? contactName} has no phone number saved.` };
  }

  const rawPhone = resolved.contact?.phoneNumbers?.[0] ?? '';
  const e164 = whatsappTool.toE164(rawPhone);
  if (!e164) {
    return {
      valid: false,
      reason: `${resolved.contact?.displayName ?? contactName}'s number isn't saved with a country code — can't confirm it reliably.`,
    };
  }

  const enrichedParams: Record<string, unknown> = {
    ...request.params,
    contactName: resolved.contact?.displayName ?? contactName,
    phoneNumber: e164,
  };
  return { valid: true, enrichedParams };
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
