// BENSON Mission Orchestrator — Context Bus.
// In-memory singleton context store. Per the plan: do not overbuild a database now, persistence
// is a Phase B concern (this resets on app restart, same as everything else in the Action Engine
// today) — this just gives Mission Orchestrator somewhere to keep "what were we just doing."

import type { OrchestratorContext } from './orchestratorTypes';

let context: OrchestratorContext = {
  defaultApps: {
    navigation: 'Waze',
    messaging: 'WhatsApp',
    media: 'YouTube',
    email: 'BlueMail',
  },
  savedPlaces: {},
  favoriteContacts: [],
};

export function getContext(): OrchestratorContext {
  return context;
}

export function updateContext(patch: Partial<OrchestratorContext>): OrchestratorContext {
  context = { ...context, ...patch };
  return context;
}

export function setActiveMission(id: string): void {
  context = { ...context, activeMissionId: id };
}

export function resetActiveMission(): void {
  context = { ...context, lastMissionId: context.activeMissionId, activeMissionId: undefined };
}
