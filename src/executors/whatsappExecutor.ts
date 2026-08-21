// BENSON Action Engine — WhatsApp Executor.
// OPEN_WHATSAPP: generic open, no contact. OPEN_WHATSAPP_CONTACT: opens a chat with an
// already-resolved phone number, message pre-filled but never sent — the user always presses
// send themselves. No contact resolution here (Contact Resolver's job, wired in later).

import { openDeepLink } from '../core/action-engine/androidActionExecutor';
import type { ActionIntent, ActionRequest, ActionResult, Executor } from '../core/action-engine';
import { successResult, notFoundResult, failedResult, unsupportedResult } from '../core/action-engine';

const LOG_TAG = '[WhatsAppExecutor]';

function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

// MESSAGE_CONTACT is handled here too when channel is (or defaults to) 'whatsapp' — the Intent
// Engine emits it for channel-ambiguous phrasing ("scrie-i lui Hannah că plec acum") where
// OPEN_WHATSAPP_CONTACT is for WhatsApp-specific phrasing. Same flow either way.
const HANDLED_INTENTS: ActionIntent[] = ['OPEN_WHATSAPP', 'OPEN_WHATSAPP_CONTACT', 'MESSAGE_CONTACT'];

// Removes spaces, parentheses, hyphens, a leading '+' — anything that isn't a digit — since
// wa.me expects bare country-code-prefixed digits, no punctuation.
function sanitizeForWaMe(raw: string): string {
  return raw.replace(/\D/g, '');
}

function buildWaMeUrl(digits: string, message?: string): string {
  const base = `https://wa.me/${digits}`;
  return message ? `${base}?text=${encodeURIComponent(message)}` : base;
}

async function openUrl(requestId: string, appLabel: string, url: string): Promise<ActionResult> {
  devLog('opening', appLabel, url);
  const outcome = await openDeepLink(url);
  if (outcome.success) return successResult(requestId, `Opened ${appLabel}.`, { appOpened: appLabel, data: { url } });
  devLog('openURL error', url, outcome.error);
  return failedResult(requestId, `Could not open ${appLabel}.`, {
    errorCode: 'LINKING_OPEN_URL_ERROR',
    errorDetails: outcome.error,
  });
}

export const WhatsAppExecutor: Executor = {
  name: 'WhatsAppExecutor',

  canHandle(intent: ActionIntent): boolean {
    return HANDLED_INTENTS.includes(intent);
  },

  async execute(request: ActionRequest): Promise<ActionResult> {
    const contactName = typeof request.parameters.contactName === 'string' ? request.parameters.contactName : undefined;
    const contactId = typeof request.parameters.contactId === 'string' ? request.parameters.contactId : undefined;
    const rawPhone = typeof request.parameters.phoneNumber === 'string' ? request.parameters.phoneNumber.trim() : '';
    const message = typeof request.parameters.message === 'string' ? request.parameters.message : undefined;

    devLog('execute', request.intent, { contactName, contactId, hasMessage: Boolean(message) });

    if (request.intent === 'OPEN_WHATSAPP') {
      return openUrl(request.id, 'WhatsApp', 'whatsapp://');
    }

    if (request.intent === 'OPEN_WHATSAPP_CONTACT' || request.intent === 'MESSAGE_CONTACT') {
      if (!rawPhone) {
        devLog('no phone number provided');
        return notFoundResult(request.id, `Nu găsesc contactul${contactName ? ` ${contactName}` : ''}. Vrei să alegi manual?`);
      }

      const digits = sanitizeForWaMe(rawPhone);
      devLog('sanitized phone number', digits);

      if (!digits) {
        devLog('phone number sanitized to empty string');
        return notFoundResult(request.id, `Nu găsesc contactul${contactName ? ` ${contactName}` : ''}. Vrei să alegi manual?`);
      }

      const url = buildWaMeUrl(digits, message);

      // "sună pe X pe WhatsApp" — there's no public Android deep-link/Intent to start a WhatsApp
      // VOICE call directly (only to open a chat); reliably tapping the in-app call button would
      // need Accessibility Service UI automation, out of scope for Phase A. Open the chat and say
      // so honestly instead of silently placing a plain phone call or claiming a call was made.
      if (request.parameters.mode === 'voice_call') {
        devLog('voice_call requested, falling back to opening the chat', url);
        const outcome = await openDeepLink(url);
        if (!outcome.success) {
          return failedResult(request.id, `Nu am putut deschide WhatsApp pentru ${contactName ?? 'contact'}.`, {
            errorCode: 'LINKING_OPEN_URL_ERROR',
            errorDetails: outcome.error,
          });
        }
        return successResult(
          request.id,
          `Am deschis WhatsApp pentru ${contactName ?? 'contact'}. Apelul vocal trebuie pornit manual dacă Android nu permite comanda directă.`,
          { appOpened: 'WhatsApp', data: { url } },
        );
      }

      return openUrl(request.id, 'WhatsApp', url);
    }

    return unsupportedResult(request.id, `WhatsAppExecutor does not handle ${request.intent}.`);
  },
};
