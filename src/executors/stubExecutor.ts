// BENSON Action Engine — Stub Executor.
// Handles intents the Intent Engine already recognizes but that have no real capability behind
// them yet (calendar read, email, SMS-only messaging read, family location/Life360). Each one
// replies honestly that it isn't built yet rather than silently failing or pretending to
// succeed — per Module 9's "never say 'I did it' if it can't verify" principle, applied here
// one step earlier: never say "I did it" for something that was never attempted at all.

import type { ActionIntent, ActionRequest, ActionResult, Executor } from '../core/action-engine';
import { unsupportedResult } from '../core/action-engine';

const LOG_TAG = '[StubExecutor]';

function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

const HANDLED_INTENTS: ActionIntent[] = ['READ_MESSAGES', 'EMAIL_ACTION', 'CALENDAR_ACTION', 'FAMILY_LOCATION', 'SOS'];

const NOT_YET_MESSAGE: Partial<Record<ActionIntent, string>> = {
  READ_MESSAGES: 'Nu pot citi mesajele încă.',
  EMAIL_ACTION: 'Nu pot gestiona emailurile încă.',
  CALENDAR_ACTION: 'Nu pot citi calendarul încă.',
  FAMILY_LOCATION: 'Nu pot verifica locația familiei încă.',
  SOS: 'SOS nu este configurat încă.',
};

export const StubExecutor: Executor = {
  name: 'StubExecutor',

  canHandle(intent: ActionIntent): boolean {
    return HANDLED_INTENTS.includes(intent);
  },

  async execute(request: ActionRequest): Promise<ActionResult> {
    devLog('execute', request.intent, request.parameters);
    const message = NOT_YET_MESSAGE[request.intent] ?? `${request.intent} nu este implementat încă.`;
    return unsupportedResult(request.id, message);
  },
};
