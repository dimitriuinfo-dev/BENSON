// BENSON Mission Orchestrator — Goal Extractor.
// Turns normalized text into one or more user-purpose-level Goals, built on top of the existing
// (already-tested) Intent Engine rather than re-implementing its pattern matching.
//
// Conservative split rule (deliberate, see BENSON 21 plan): try the WHOLE text through the
// Intent Engine first. If that alone resolves to a specific intent, treat it as ONE goal and
// stop — this is what preserves BENSON 20's "deschide Waze și du-mă la Sibiu" fix (one merged
// NAVIGATE_TO_PLACE goal, not two). Only look for a second goal if there's a conjunction AND the
// clause after it independently resolves to a different, non-CHAT intent — that's what
// distinguishes a genuine second goal ("...și scrie-i lui Hannah...") from a compound phrase the
// Intent Engine already merges correctly on its own.

import { parseCommandToActionRequest } from '../action-engine';
import type { ActionRequest } from '../action-engine';
import type { Goal, GoalType } from './orchestratorTypes';

const INTENT_TO_GOAL_TYPE: Record<string, GoalType> = {
  NAVIGATE_TO_PLACE: 'TRAVEL',
  OPEN_WAZE: 'TRAVEL',
  OPEN_GOOGLE_MAPS: 'TRAVEL',
  OPEN_WHATSAPP_CONTACT: 'COMMUNICATION',
  MESSAGE_CONTACT: 'COMMUNICATION',
  SEND_SMS: 'COMMUNICATION',
  CALL_CONTACT: 'COMMUNICATION',
  READ_MESSAGES: 'COMMUNICATION',
  EMAIL_ACTION: 'COMMUNICATION',
  MEDIA_PLAY: 'MEDIA',
  CALENDAR_ACTION: 'SCHEDULE',
  CREATE_REMINDER: 'SCHEDULE',
  CONTACTS_LIST: 'SEARCH',
  CONTACTS_SEARCH: 'SEARCH',
  FAMILY_LOCATION: 'FAMILY_CHECK',
  OPEN_APP: 'DEVICE_CONTROL',
  OPEN_WHATSAPP: 'DEVICE_CONTROL',
  CLOSE_APP: 'DEVICE_CONTROL',
  RETURN_TO_BENSON: 'DEVICE_CONTROL',
  HELP: 'HELP',
  SOS: 'SOS',
};

const CONJUNCTION_PATTERN = /\s+(?:și|si|apoi|and|und)\s+/i;

let goalCounter = 0;
function nextGoalId(): string {
  goalCounter += 1;
  return `goal_${Date.now()}_${goalCounter}`;
}

function toGoal(rawText: string, clauseText: string, request: ActionRequest): Goal {
  const type = INTENT_TO_GOAL_TYPE[request.intent] ?? 'UNKNOWN';
  return {
    id: nextGoalId(),
    type,
    rawText,
    normalizedText: clauseText,
    entities: {
      destination: typeof request.parameters.destinationLabel === 'string' ? request.parameters.destinationLabel : undefined,
      contact: typeof request.parameters.contactName === 'string' ? request.parameters.contactName : undefined,
      message: typeof request.parameters.message === 'string' ? request.parameters.message : undefined,
      app: typeof request.parameters.targetApp === 'string' ? request.parameters.targetApp : undefined,
      mediaType: typeof request.parameters.mediaType === 'string' ? request.parameters.mediaType : undefined,
      stationName: typeof request.parameters.stationName === 'string' ? request.parameters.stationName : undefined,
      mode: typeof request.parameters.mode === 'string' ? request.parameters.mode : undefined,
    },
    confidence: typeof request.parameters.confidence === 'number' ? request.parameters.confidence : 0,
    sourceIntent: request.intent,
  };
}

// Exposed so Mission Planner/Problem Solver can rebuild an ActionRequest for a goal without
// re-parsing text — same intent/parameters the Intent Engine already produced for that clause.
export function goalToActionRequest(goal: Goal): ActionRequest {
  return parseCommandToActionRequest(goal.normalizedText, 'voice');
}

export function extractGoals(rawText: string, normalizedText: string): Goal[] {
  const wholeRequest = parseCommandToActionRequest(normalizedText, 'voice');

  if (wholeRequest.intent === 'CHAT') {
    // Nothing recognized at all in the whole text — Problem Solver's broader fallback patterns
    // get a chance next; Goal Extractor itself has nothing to offer here.
    return [];
  }

  const firstGoal = toGoal(rawText, normalizedText, wholeRequest);

  const match = normalizedText.match(CONJUNCTION_PATTERN);
  if (!match || match.index === undefined) return [firstGoal];

  const secondClause = normalizedText.slice(match.index + match[0].length).trim();
  if (!secondClause) return [firstGoal];

  const secondRequest = parseCommandToActionRequest(secondClause, 'voice');
  if (secondRequest.intent === 'CHAT' || secondRequest.intent === wholeRequest.intent) {
    // Either nothing there, or the same domain already captured by the whole-text parse
    // (e.g. the whole-text NAVIGATE_TO_PLACE already merged "Waze" + "Sibiu") — one goal only.
    return [firstGoal];
  }

  const secondGoal = toGoal(rawText, secondClause, secondRequest);
  return [firstGoal, secondGoal];
}
