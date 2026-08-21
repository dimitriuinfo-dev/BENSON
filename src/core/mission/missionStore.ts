// BENSON Mission Governance — persistence. Every state transition is written to AsyncStorage
// immediately (governance clarification 6/7) so a cold start with an active Waze/WhatsApp mission
// can be restored instead of silently forgotten. In-memory mirror kept for synchronous reads
// within a single session; AsyncStorage is the durable copy.

import AsyncStorage from '@react-native-async-storage/async-storage';
import type { Mission, MissionState } from './missionTypes';

const ACTIVE_MISSION_KEY = 'benson_mission_governance_active_v1';
const LAST_WAZE_DESTINATION_KEY = 'benson_mission_governance_last_waze_destination_v1';

let activeMission: Mission | null = null;
let hydrated = false;

// A mission waiting for the user for longer than this is abandoned, not "still pending" — the
// user moved on. Confirmed live 2026-07-16: a mission stuck in WaitingUser (never resolved, e.g.
// by the exact self-echo loop hardened against elsewhere this session) survived every app
// restart via this same hydrate call and got re-announced on every foreground transition,
// indefinitely, with no natural expiry. Auto-clearing here targets only this one stale record —
// every other AsyncStorage key (name, API key, preferences, facts, history) is untouched.
const STALE_WAITING_MISSION_MS = 5 * 60 * 1000; // 5 minutes

export async function hydrateActiveMission(): Promise<Mission | null> {
  if (hydrated) return activeMission;
  hydrated = true;
  try {
    const raw = await AsyncStorage.getItem(ACTIVE_MISSION_KEY);
    activeMission = raw ? (JSON.parse(raw) as Mission) : null;
    const waitingStates: MissionState[] = ['WaitingUser', 'WaitingConfirmation'];
    if (activeMission && waitingStates.includes(activeMission.state)) {
      const age = Date.now() - (activeMission.updatedAt ?? 0);
      if (!activeMission.updatedAt || age > STALE_WAITING_MISSION_MS) {
        activeMission = null;
        await AsyncStorage.removeItem(ACTIVE_MISSION_KEY);
      }
    }
  } catch {
    activeMission = null;
  }
  return activeMission;
}

// Same staleness rule as hydrateActiveMission's boot-time check, applied here too — the
// AppState-triggered re-announce (app/index.tsx) calls this on every foreground transition, not
// just at boot, so a mission going stale WHILE the app stays running needs to self-clear here as
// well, not only once at the next cold start.
const WAITING_STATES_FOR_STALENESS: MissionState[] = ['WaitingUser', 'WaitingConfirmation'];

export function getActiveMission(): Mission | null {
  if (activeMission && WAITING_STATES_FOR_STALENESS.includes(activeMission.state)) {
    const age = Date.now() - (activeMission.updatedAt ?? 0);
    if (!activeMission.updatedAt || age > STALE_WAITING_MISSION_MS) {
      activeMission = null;
      persist().catch(() => {});
    }
  }
  return activeMission;
}

async function persist(): Promise<void> {
  try {
    if (activeMission) {
      await AsyncStorage.setItem(ACTIVE_MISSION_KEY, JSON.stringify(activeMission));
    } else {
      await AsyncStorage.removeItem(ACTIVE_MISSION_KEY);
    }
  } catch {}
}

export async function persistMission(mission: Mission): Promise<Mission> {
  activeMission = mission;
  await persist();
  return activeMission;
}

export async function transitionMission(state: MissionState, patch: Partial<Mission> = {}): Promise<Mission | null> {
  if (!activeMission) return null;
  activeMission = { ...activeMission, ...patch, state, updatedAt: Date.now() };
  await persist();
  return activeMission;
}

// Terminal states clear the active slot so a NEW command isn't blocked by a finished mission —
// the mission object itself is still returned to the caller for a final user-facing message.
export async function clearIfTerminal(): Promise<void> {
  if (!activeMission) return;
  const terminal: MissionState[] = ['Completed', 'Failed', 'Cancelled'];
  if (terminal.includes(activeMission.state)) {
    activeMission = null;
    await persist();
  }
}

export async function setLastWazeDestination(destination: string): Promise<void> {
  try {
    await AsyncStorage.setItem(LAST_WAZE_DESTINATION_KEY, destination);
  } catch {}
}

export async function getLastWazeDestination(): Promise<string | null> {
  try {
    return await AsyncStorage.getItem(LAST_WAZE_DESTINATION_KEY);
  } catch {
    return null;
  }
}
