// BENSON Action Engine — the contract every executor (App Launcher, Navigation, Contact
// Resolver, WhatsApp, Phone Call, ...) implements. Pure types only.

import type { ActionIntent } from './actionTypes';
import type { ActionRequest } from './actionRequest';
import type { ActionResult } from './actionResult';

export interface Executor {
  name: string;
  canHandle(intent: ActionIntent): boolean;
  execute(request: ActionRequest): Promise<ActionResult>;
}
