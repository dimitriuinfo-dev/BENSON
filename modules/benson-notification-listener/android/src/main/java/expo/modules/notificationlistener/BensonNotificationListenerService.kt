package expo.modules.notificationlistener

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

// Enable-detection + settings deep-link only for now (onboarding wizard step). Reading and
// parsing notification content (e.g. WhatsApp message text) is a separate future task — this
// service intentionally does nothing with the notifications it receives yet.
class BensonNotificationListenerService : NotificationListenerService() {
  override fun onNotificationPosted(sbn: StatusBarNotification?) {}
  override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
