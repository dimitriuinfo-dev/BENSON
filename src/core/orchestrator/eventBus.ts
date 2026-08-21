// BENSON Mission Orchestrator — Event Bus.
// Simple in-memory emit/subscribe/ring-buffer. Purpose per the plan: the Debug Panel can show
// mission events; Memory/Result Verifier/Learning modules (Phase B) can subscribe later without
// this file changing shape.

import type { OrchestratorEvent } from './orchestratorTypes';

export type EventListener = (event: OrchestratorEvent) => void;

const MAX_EVENTS = 50;
const recentEvents: OrchestratorEvent[] = [];
const listeners: EventListener[] = [];

let eventCounter = 0;
function nextEventId(): string {
  eventCounter += 1;
  return `event_${Date.now()}_${eventCounter}`;
}

export function emitEvent(type: string, payload?: Record<string, unknown>, missionId?: string, taskId?: string): OrchestratorEvent {
  const event: OrchestratorEvent = {
    id: nextEventId(),
    type,
    timestamp: Date.now(),
    missionId,
    taskId,
    payload,
  };
  recentEvents.push(event);
  if (recentEvents.length > MAX_EVENTS) recentEvents.shift();
  for (const listener of listeners) {
    try {
      listener(event);
    } catch {
      // A listener throwing must never break the mission it's observing.
    }
  }
  return event;
}

export function subscribe(listener: EventListener): () => void {
  listeners.push(listener);
  return () => {
    const idx = listeners.indexOf(listener);
    if (idx >= 0) listeners.splice(idx, 1);
  };
}

export function getRecentEvents(limit = MAX_EVENTS): OrchestratorEvent[] {
  return recentEvents.slice(-limit).reverse();
}
