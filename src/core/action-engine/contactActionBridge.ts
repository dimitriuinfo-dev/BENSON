// BENSON Action Engine — Contact Resolution Bridge.
// Enriches a CALL_CONTACT/OPEN_WHATSAPP_CONTACT request's contactName parameter into a real
// phoneNumber/contactId, using an already-supplied TrustedContact[] list. Pure function: no
// device contacts access, no storage, no Linking, no executor calls — resolveContact() itself
// does the actual matching (src/core/contacts), this just wires its result into an ActionRequest.

import type { ActionIntent } from './actionTypes';
import type { ActionRequest } from './actionRequest';
import { resolveContact } from '../contacts';
import type { ContactResolveStatus, TrustedContact } from '../contacts';

export interface ContactActionBridgeResult {
  request: ActionRequest;
  status: ContactResolveStatus;
  candidates?: TrustedContact[];
  message: string;
}

const HANDLED_INTENTS: ActionIntent[] = ['CALL_CONTACT', 'OPEN_WHATSAPP_CONTACT', 'MESSAGE_CONTACT', 'FAMILY_LOCATION'];

function preferredChannelFor(request: ActionRequest): 'phone' | 'whatsapp' {
  if (request.intent === 'CALL_CONTACT' || request.intent === 'FAMILY_LOCATION') return 'phone';
  // MESSAGE_CONTACT carries its own channel param (defaults to whatsapp in the Intent Engine).
  return request.parameters.channel === 'sms' ? 'phone' : 'whatsapp';
}

export function enrichContactAction(request: ActionRequest, contacts: TrustedContact[]): ContactActionBridgeResult {
  if (!HANDLED_INTENTS.includes(request.intent)) {
    return { request, status: 'not_found', message: `contactActionBridge does not handle ${request.intent}.` };
  }

  const contactName = typeof request.parameters.contactName === 'string' ? request.parameters.contactName : '';
  if (!contactName.trim()) {
    return { request, status: 'not_found', message: 'No contact name was given.' };
  }

  const resolved = resolveContact({
    rawName: contactName,
    preferredChannel: preferredChannelFor(request),
    contacts,
  });

  // Never a silent/generic failure here — the report's own required wording, so a bad contact
  // match is always followed by a clear, actionable question rather than nothing at all.
  if (resolved.status === 'ambiguous') {
    const candidates = resolved.candidates ?? [];
    const names = candidates.map((c) => c.displayName).join(', ');
    return {
      request,
      status: 'ambiguous',
      candidates,
      message: `Am găsit mai mulți contacți pentru "${contactName}": ${names}. Pe care îl vrei?`,
    };
  }

  if (resolved.status === 'not_found') {
    return {
      request,
      status: 'not_found',
      message: `Nu găsesc contactul ${contactName}. Vrei să alegi manual?`,
    };
  }

  if (resolved.status === 'missing_phone') {
    return {
      request,
      status: 'missing_phone',
      message: `${resolved.contact?.displayName ?? contactName} nu are un număr de telefon salvat.`,
    };
  }

  // resolved — first phone number is used; TrustedContact allows more than one but there's no
  // signal here for picking a different one.
  const contact = resolved.contact!;
  const enrichedRequest: ActionRequest = {
    ...request,
    parameters: {
      ...request.parameters,
      contactName: contact.displayName,
      contactId: contact.id,
      phoneNumber: contact.phoneNumbers?.[0],
    },
  };

  return { request: enrichedRequest, status: 'resolved', message: resolved.message };
}
