package expo.modules.notificationlistener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// ROUND_WA2_MESSAGE_READING_1 (2026-09-23) — was enable-detection only ("this service
// intentionally does nothing with the notifications it receives yet"). Still does nothing
// PROACTIVE (no voice, no logic here) — per the round's explicit rule, receiving a message must
// never start speech on its own. This only keeps a live `instance` reference so
// BensonNotificationListenerModule.getWhatsAppNotifications() can call the OS's own
// getActiveNotifications() ON DEMAND (a pull, only when the user explicitly asks to be read
// something) — no notification content is stored, cached, or acted on here at post/remove time.
class BensonNotificationListenerService : NotificationListenerService() {
  companion object {
    @Volatile var instance: BensonNotificationListenerService? = null
  }

  override fun onListenerConnected() {
    super.onListenerConnected()
    instance = this
  }

  override fun onListenerDisconnected() {
    super.onListenerDisconnected()
    if (instance === this) instance = null
  }

  override fun onNotificationPosted(sbn: StatusBarNotification?) {}
  override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
