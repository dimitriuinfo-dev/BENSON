// BENSON Action Engine — core enums shared by every request, result, and executor.
// Pure types only: no runtime logic, no side effects, no imports from the rest of the app.

export type ActionIntent =
  | 'OPEN_APP'
  | 'CLOSE_APP'
  | 'RETURN_TO_BENSON'
  | 'OPEN_WAZE'
  | 'OPEN_GOOGLE_MAPS'
  | 'NAVIGATE_TO_PLACE'
  | 'CALL_CONTACT'
  | 'OPEN_WHATSAPP'
  | 'OPEN_WHATSAPP_CONTACT'
  | 'MESSAGE_CONTACT'
  | 'SEND_SMS'
  | 'SHARE_LOCATION'
  | 'CREATE_REMINDER'
  | 'READ_MESSAGES'
  | 'EMAIL_ACTION'
  | 'CALENDAR_ACTION'
  | 'CONTACTS_LIST'
  | 'CONTACTS_SEARCH'
  | 'MEDIA_PLAY'
  | 'FAMILY_LOCATION'
  | 'SOS'
  | 'HELP'
  | 'CHAT'
  | 'UNKNOWN';

export type ActionRiskLevel = 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL';

export type ActionStatus =
  | 'success'
  | 'needs_confirmation'
  | 'needs_permission'
  | 'needs_disambiguation'
  | 'not_found'
  | 'unsupported'
  | 'failed'
  | 'cancelled';

// Where a request originated — lets an executor or the confirmation gate reason about the
// source without needing to touch the voice loop or UI layer directly.
export type ActionSource = 'voice' | 'touch' | 'text' | 'system';
