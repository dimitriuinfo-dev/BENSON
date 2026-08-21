// BENSON Action Engine — Help Executor.
// Deterministic, fixed capabilities summary — never routed to Claude. If the user asks "what can
// you do", the answer must always be immediate and identical, not an LLM improvisation that could
// overclaim (promising a capability that isn't actually built yet).

import type { ActionIntent, ActionRequest, ActionResult, Executor } from '../core/action-engine';
import { successResult } from '../core/action-engine';

const HANDLED_INTENTS: ActionIntent[] = ['HELP'];

const CAPABILITIES_MESSAGE =
  'Pot să deschid aplicații, să pornesc Waze sau Google Maps, să deschid WhatsApp, YouTube, ' +
  'muzică sau radio, să pregătesc apeluri și mesaje către contacte, să revin în Benson, să creez ' +
  'misiuni simple precum drum spre Sibiu plus mesaj către cineva. Calendarul, Life360 și citirea ' +
  'mesajelor sunt pregătite ca module, dar încă nu sunt implementate complet.';

export const HelpExecutor: Executor = {
  name: 'HelpExecutor',

  canHandle(intent: ActionIntent): boolean {
    return HANDLED_INTENTS.includes(intent);
  },

  async execute(request: ActionRequest): Promise<ActionResult> {
    return successResult(request.id, CAPABILITIES_MESSAGE);
  },
};
