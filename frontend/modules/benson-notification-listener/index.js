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
