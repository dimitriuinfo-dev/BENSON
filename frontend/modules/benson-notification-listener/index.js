import { requireNativeModule } from 'expo-modules-core';

const NativeModule = requireNativeModule('BensonNotificationListener');

// Checks Settings.Secure's enabled_notification_listeners for our exact service component —
// Android has no runtime-permission callback for this, so polling on return from Settings is
// the only way to know whether the user actually flipped it on.
export function isEnabled() {
  return NativeModule.isEnabled();
}

// Opens the system "Notification access" settings screen. Our listener service only appears in
// that list once it's declared in the manifest — Android won't grant this via a normal runtime
// permission dialog, the user must find BENSON in the list and toggle it manually.
export function openNotificationListenerSettings() {
  return NativeModule.openNotificationListenerSettings();
}

// ROUND_WA2_MESSAGE_READING_1 — on-demand pull of currently-active WhatsApp notifications (JSON
// string of {sender, text, whenMs}[]) or "SECURITY_EXCEPTION". Call ONLY in direct response to an
// explicit user request to be read messages — never from a background/notification-arrival path.
export function getWhatsAppNotifications() {
  return NativeModule.getWhatsAppNotifications();
}

// ROUND_MEDIA_GOVERNANCE_1 — Priority-1 media control via Android's own MediaSession framework.
// JSON string of [{packageName, state, actions}], "SECURITY_EXCEPTION" if the notification
// listener isn't actually enabled right now, or "[]".
export function getActiveMediaSessions() {
  return NativeModule.getActiveMediaSessions();
}

// action: "play" | "pause" | "stop" | "next" | "previous". packageName: "" = no filter (picks the
// actively-playing session, else the first active session).
export function mediaControl(packageName, action) {
  return NativeModule.mediaControl(packageName, action);
}

// JSON string of {packageName, state}. state: -1 = no session, -2 = listener not enabled,
// otherwise a PlaybackState.STATE_* constant (0=NONE,1=STOPPED,2=PAUSED,3=PLAYING,...).
export function getPlaybackState(packageName) {
  return NativeModule.getPlaybackState(packageName);
}
