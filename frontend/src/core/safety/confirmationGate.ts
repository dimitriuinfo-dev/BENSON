// BENSON Action Engine — Confirmation Gate.
// The single place that decides whether an ActionRequest may proceed straight to execution, must
// be confirmed by the user first, or must never be allowed to run at all. Pure decision logic
// only — no UI, no Alert, no Linking, no storage, no executor calls. Whatever wires this in later
// (a dispatcher) is responsible for actually showing a confirmation prompt and re-submitting.

import type { ActionIntent, ActionRequest, ActionRiskLevel } from '../action-engine';

export type ConfirmationDecision = 'allowed' | 'requires_confirmation' | 'blocked';

// Reserved for future context (time of day, driving state, user preferences, ...) — no rule uses
// it yet, but evaluateConfirmation already accepts it so call sites don't change again later.
export interface ConfirmationContext {
  [key: string]: unknown;
}

export interface ConfirmationPolicy {
  decision: ConfirmationDecision;
  reason: string;
  riskLevel: ActionRiskLevel;
  userFacingMessage: string;
}

// Payment/transfer/checkout/purchase language, in English/Romanian/German (the languages this
// app already supports) — checked against rawText AND every string-valued parameter. A match is
// an absolute block regardless of which intent was detected; no executor may ever complete a
// financial transaction.
const PAYMENT_PATTERN =
  /\b(pay|payment|checkout|purchase|buy now|bank transfer|wire transfer|credit card|plat[ăa]|pl[ăa]te[șs]te\w*|cump[ăa]r\w*|achizi[țt]ion\w*|transfer bancar|kaufen|bezahl\w*|überweisung|einkauf\w*|zahlung)\b/i;

const CONFIRMATION_REQUIRED_INTENTS: ActionIntent[] = [
  'CALL_CONTACT',
  'OPEN_WHATSAPP_CONTACT',
  'MESSAGE_CONTACT',
  'SEND_SMS',
  'SHARE_LOCATION',
  'CREATE_REMINDER',
  'FAMILY_LOCATION',
  'EMAIL_ACTION',
  'SOS',
];

// Risk level is a property of the action itself, independent of whether it currently requires
// confirmation — CREATE_REMINDER is LOW risk but still gated "for now" per the reminder rule.
const RISK_BY_INTENT: Partial<Record<ActionIntent, ActionRiskLevel>> = {
  OPEN_APP: 'LOW',
  CLOSE_APP: 'LOW',
  RETURN_TO_BENSON: 'LOW',
  OPEN_WAZE: 'LOW',
  OPEN_GOOGLE_MAPS: 'LOW',
  NAVIGATE_TO_PLACE: 'LOW',
  OPEN_WHATSAPP: 'LOW',
  CHAT: 'LOW',
  CALL_CONTACT: 'MEDIUM',
  OPEN_WHATSAPP_CONTACT: 'MEDIUM',
  MESSAGE_CONTACT: 'MEDIUM',
  SEND_SMS: 'MEDIUM',
  CREATE_REMINDER: 'LOW',
  READ_MESSAGES: 'LOW',
  EMAIL_ACTION: 'MEDIUM',
  CALENDAR_ACTION: 'LOW',
  CONTACTS_LIST: 'LOW',
  CONTACTS_SEARCH: 'LOW',
  MEDIA_PLAY: 'LOW',
  FAMILY_LOCATION: 'MEDIUM',
  SHARE_LOCATION: 'HIGH',
  SOS: 'CRITICAL',
  HELP: 'LOW',
  UNKNOWN: 'MEDIUM',
};

function containsPaymentIntent(request: ActionRequest): boolean {
  if (PAYMENT_PATTERN.test(request.rawText)) return true;
  return Object.values(request.parameters).some(
    (value) => typeof value === 'string' && PAYMENT_PATTERN.test(value),
  );
}

function riskFor(intent: ActionIntent, fallback: ActionRiskLevel = 'MEDIUM'): ActionRiskLevel {
  return RISK_BY_INTENT[intent] ?? fallback;
}

function buildConfirmationPrompt(intent: ActionIntent): string {
  switch (intent) {
    case 'CALL_CONTACT':
      return 'Shall I call this contact?';
    case 'OPEN_WHATSAPP_CONTACT':
    case 'MESSAGE_CONTACT':
      return 'Shall I send this message?';
    case 'SEND_SMS':
      return 'Shall I prepare this text message?';
    case 'SHARE_LOCATION':
      return 'Shall I share your location with this contact?';
    case 'CREATE_REMINDER':
      return 'Shall I set this reminder?';
    case 'FAMILY_LOCATION':
      return 'Shall I check where they are?';
    case 'EMAIL_ACTION':
      return 'Shall I prepare this email?';
    case 'SOS':
      return 'Shall I prepare an SOS alert?';
    default:
      return 'Shall I proceed?';
  }
}

export function evaluateConfirmation(request: ActionRequest, _context?: ConfirmationContext): ConfirmationPolicy {
  // Hard block, checked first and overrides everything else — no intent, however apparently
  // harmless, may carry a payment/transfer/checkout/purchase through to an executor.
  if (containsPaymentIntent(request)) {
    return {
      decision: 'blocked',
      reason: 'Payment, transfer, checkout, or purchase language detected in the request.',
      riskLevel: 'CRITICAL',
      userFacingMessage: "I can't handle payments or transfers — you'll need to complete that yourself in the app.",
    };
  }

  // No 'unsupported' value exists on ConfirmationDecision — 'blocked' is the closest available
  // semantic for "there is nothing safe to execute here."
  if (request.intent === 'UNKNOWN') {
    return {
      decision: 'blocked',
      reason: 'Intent could not be classified — nothing to safely execute.',
      riskLevel: riskFor('UNKNOWN'),
      userFacingMessage: "I'm not sure what you'd like me to do.",
    };
  }

  // A read-only calendar query ("ce am azi în calendar") is not sensitive — only a future
  // modify-calendar action (Phase B) would need confirmation.
  if (request.intent === 'CALENDAR_ACTION' && request.parameters.readOnly === true) {
    return {
      decision: 'allowed',
      reason: 'CALENDAR_ACTION is read-only.',
      riskLevel: riskFor('CALENDAR_ACTION', 'LOW'),
      userFacingMessage: '',
    };
  }

  // SOS requires confirmation unless the user has explicitly pre-configured it to fire without
  // one (a deliberate opt-in, not the default).
  if (request.intent === 'SOS' && request.parameters.explicitlyConfigured === true) {
    return {
      decision: 'allowed',
      reason: 'SOS is explicitly configured to skip confirmation.',
      riskLevel: riskFor('SOS'),
      userFacingMessage: '',
    };
  }

  if (CONFIRMATION_REQUIRED_INTENTS.includes(request.intent)) {
    return {
      decision: 'requires_confirmation',
      reason: `${request.intent} is on the confirmation-required list.`,
      riskLevel: riskFor(request.intent),
      userFacingMessage: buildConfirmationPrompt(request.intent),
    };
  }

  return {
    decision: 'allowed',
    reason: `${request.intent} does not require confirmation.`,
    riskLevel: riskFor(request.intent, 'LOW'),
    userFacingMessage: '',
  };
}
