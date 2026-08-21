import * as Notifications from 'expo-notifications';
import { getConnectionState } from 'benson-accessibility';
import { AppState } from 'react-native';

export type AccessibilityWatchHandle = { stop: () => void };

const CHECK_INTERVAL_MS = 60_000;
export const ACCESSIBILITY_ALERT_NOTIFICATION_TAG = 'benson_accessibility_dropped';

// Proactive detector (2026-07-27) — Android gives no push/broadcast a regular app can rely on for
// "the user (or the OEM) just turned off one of your accessibility services"; OxygenOS/ColorOS are
// known to silently auto-revoke it as a security measure, independent of anything the user did.
// Polling is the only reliable option (same reasoning as the existing Guardian heartbeat in
// BensonForegroundService/BensonAccessibilityService). Only fires on a CONNECTED -> not-connected
// transition, never repeatedly while already known to be off — the user has already been told
// once; re-alerting every interval would be the exact "eternal loop" class of bug fixed earlier
// this session.
export function startAccessibilityWatch(params: { onDropped: () => void; onStatus?: (connected: boolean) => void }): AccessibilityWatchHandle {
  let wasConnected = true; // optimistic — the first real check corrects this within one interval
  let stopped = false;

  async function check() {
    if (stopped) return;
    let state: string;
    try {
      state = await getConnectionState();
    } catch {
      return;
    }
    const connected = state === 'enabled_connected';
    // Report the CURRENT state on every check (not just the drop transition) so callers can keep a
    // live in-app banner in sync — showing it while the service is off, clearing it once the user
    // re-enables it. Fired every interval + on each foreground return below.
    try { params.onStatus?.(connected); } catch {}
    if (wasConnected && !connected) {
      params.onDropped();
      try {
        await Notifications.scheduleNotificationAsync({
          content: {
            title: 'BENSON are nevoie de atenție',
            body: 'Serviciul de Accesibilitate s-a dezactivat. Apasă aici pentru a-l reactiva.',
            data: { tag: ACCESSIBILITY_ALERT_NOTIFICATION_TAG },
          },
          trigger: null,
        });
      } catch {}
    }
    wasConnected = connected;
  }

  const interval = setInterval(check, CHECK_INTERVAL_MS);
  check();
  // Re-check immediately whenever the app returns to the foreground — the user most likely just
  // came back from the system Accessibility settings screen, and a 60s poll would otherwise leave
  // the banner stale for up to a minute after they fixed it.
  const appStateSub = AppState.addEventListener('change', (next) => {
    if (next === 'active') check();
  });

  return {
    stop: () => {
      stopped = true;
      clearInterval(interval);
      appStateSub.remove();
    },
  };
}
