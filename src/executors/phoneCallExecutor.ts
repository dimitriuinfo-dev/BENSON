// BENSON Action Engine — Phone Call Executor.
// Item 2: places a DIRECT native call (Intent.ACTION_CALL) when CALL_PHONE is granted — no
// chooser, no dialer screen, an actually placed call. Falls back to the dialer (ACTION_DIAL via
// tel:, pre-filled, one tap to call) only when the permission is denied, and is explicit in the
// spoken result about which of the two actually happened — the dialer fallback is never described
// or logged as a completed call.

import { PermissionsAndroid, Platform } from 'react-native';
import { placeDirectCall, hasCallPhonePermission } from 'benson-app-registry';
import { dial } from '../core/action-engine/androidActionExecutor';
import type { ActionIntent, ActionRequest, ActionResult, Executor } from '../core/action-engine';
import { successResult, notFoundResult, failedResult, unsupportedResult } from '../core/action-engine';

const LOG_TAG = '[PhoneCallExecutor]';

function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

const HANDLED_INTENTS: ActionIntent[] = ['CALL_CONTACT'];

// Strips everything except digits and a leading '+' — same cleanup already used elsewhere in
// this codebase (lib/notepad/actions.ts) before building a tel:/wa.me URL.
function sanitizePhoneNumber(raw: string): string {
  return raw.replace(/[^\d+]/g, '');
}

// Requests CALL_PHONE once per app-process lifetime if not already decided — repeatedly
// re-prompting on every call attempt after an explicit "don't allow" would be exactly the kind of
// nagging Android's own permission UX is designed to prevent. A later grant (user changed it in
// Settings) is picked up automatically next time since this always checks current state first,
// never a cached "already asked" boolean by itself.
async function ensureCallPhonePermission(): Promise<'granted' | 'denied'> {
  const already = await hasCallPhonePermission();
  if (already) return 'granted';

  const result = await PermissionsAndroid.request(PermissionsAndroid.PERMISSIONS.CALL_PHONE, {
    title: 'BENSON',
    message: 'BENSON are nevoie de permisiunea de a suna direct, ca să poată apela contactele fără să deschidă telefonul manual.',
    buttonPositive: 'OK',
    buttonNegative: 'Nu, mulțumesc',
  });
  return result === PermissionsAndroid.RESULTS.GRANTED ? 'granted' : 'denied';
}

export const PhoneCallExecutor: Executor = {
  name: 'PhoneCallExecutor',

  canHandle(intent: ActionIntent): boolean {
    return HANDLED_INTENTS.includes(intent);
  },

  async execute(request: ActionRequest): Promise<ActionResult> {
    const contactName = typeof request.parameters.contactName === 'string' ? request.parameters.contactName : undefined;
    const contactId = typeof request.parameters.contactId === 'string' ? request.parameters.contactId : undefined;
    const rawPhone = typeof request.parameters.phoneNumber === 'string' ? request.parameters.phoneNumber.trim() : '';

    devLog('execute', request.intent, { contactName, contactId });

    if (!HANDLED_INTENTS.includes(request.intent)) {
      return unsupportedResult(request.id, `PhoneCallExecutor does not handle ${request.intent}.`);
    }

    if (!rawPhone) {
      devLog('no phone number provided');
      return notFoundResult(request.id, 'Nu am un număr de telefon pentru acest contact.');
    }

    const sanitized = sanitizePhoneNumber(rawPhone);
    devLog('sanitized phone number present:', sanitized.length > 0);

    if (!sanitized) {
      devLog('phone number sanitized to empty string');
      return notFoundResult(request.id, 'Nu am un număr de telefon pentru acest contact.');
    }

    const contactLabel = contactName ?? 'acest contact';

    if (Platform.OS === 'android') {
      const permission = await ensureCallPhonePermission();
      if (permission === 'granted') {
        const placed = placeDirectCall(sanitized);
        if (placed) {
          devLog('placed direct call via ACTION_CALL');
          return successResult(request.id, `Îl sun pe ${contactLabel}.`, { appOpened: 'Phone' });
        }
        devLog('placeDirectCall returned false despite granted permission — falling back to dialer');
      } else {
        devLog('CALL_PHONE denied — falling back to dialer');
      }

      // Denial (or a native placeDirectCall failure despite a grant) — fall back to the dialer,
      // pre-filled, one tap to call. Never described as a completed call: the spoken message says
      // exactly what happened and why, per Item 2's requirement.
      const dialOutcome = await dial(sanitized);
      if (dialOutcome.success) {
        return successResult(
          request.id,
          `Nu am putut suna direct pe ${contactLabel} — nu am permisiunea. Am deschis telefonul cu numărul pregătit, apasă tu pe apel.`,
          { appOpened: 'Phone (dialer)' },
        );
      }
      return failedResult(request.id, `Nu am putut deschide nici telefonul pentru ${contactLabel}.`, {
        errorCode: 'LINKING_OPEN_URL_ERROR',
        errorDetails: dialOutcome.error,
      });
    }

    // Non-Android (unused today, kept honest rather than silently pretending to place a call).
    const outcome = await dial(sanitized);
    if (outcome.success) return successResult(request.id, 'Am deschis telefonul.', { appOpened: 'Phone' });
    return failedResult(request.id, 'Nu am putut deschide telefonul.', {
      errorCode: 'LINKING_OPEN_URL_ERROR',
      errorDetails: outcome.error,
    });
  },
};
