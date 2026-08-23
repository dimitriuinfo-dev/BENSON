// BENSON Mission Governance — AppState helper. Supporting evidence only for launch confirmation
// (per governance clarification 2): absence of a transition must never be treated as a failure by
// itself, only the ABSENCE of upgrade from launch_requested to app_switch_observed.

import { AppState } from 'react-native';

function isBackgroundish(state: string): boolean {
  return state === 'background' || state === 'inactive';
}

export function waitForBackground(timeoutMs: number): Promise<boolean> {
  return new Promise((resolve) => {
    if (isBackgroundish(AppState.currentState)) {
      resolve(true);
      return;
    }
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      sub.remove();
      resolve(false);
    }, timeoutMs);
    const sub = AppState.addEventListener('change', (next) => {
      if (settled || !isBackgroundish(next)) return;
      settled = true;
      clearTimeout(timer);
      sub.remove();
      resolve(true);
    });
  });
}

export function onReturnToForeground(callback: () => void): () => void {
  const sub = AppState.addEventListener('change', (next) => {
    if (next === 'active') callback();
  });
  return () => sub.remove();
}
