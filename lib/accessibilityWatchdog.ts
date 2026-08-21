import * as Notifications from 'expo-notifications';
import { getConnectionState } from 'benson-accessibility';

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
export function startAccessibilityWatch(params: { onDropped: () => void }): AccessibilityWatchHandle {
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

  return {
    stop: () => {
      stopped = true;
      clearInterval(interval);
    },
  };
}
