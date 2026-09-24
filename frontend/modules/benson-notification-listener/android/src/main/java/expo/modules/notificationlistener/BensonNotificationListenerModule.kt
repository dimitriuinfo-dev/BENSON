package expo.modules.notificationlistener

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import org.json.JSONArray
import org.json.JSONObject

private const val WHATSAPP_PACKAGE = "com.whatsapp"

class BensonNotificationListenerModule : Module() {
  override fun definition() = ModuleDefinition {
    Name("BensonNotificationListener")

    // No runtime-permission callback exists for this — the caller must re-check after the user
    // returns from the settings screen, same pattern as benson-accessibility's isServiceEnabled.
    Function("isEnabled") {
      val context = appContext.reactContext ?: return@Function false
      val flat = ComponentName(context, BensonNotificationListenerService::class.java).flattenToString()
      val enabledListeners = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
      enabledListeners?.split(":")?.any { it == flat } == true
    }

    // "Notification access" is a special permission Android will not grant via the normal
    // runtime prompt — this opens the dedicated system settings screen for it.
    Function("openNotificationListenerSettings") {
      val context = appContext.reactContext ?: return@Function Unit
      val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
      try { context.startActivity(intent) } catch (_: Exception) {}
    }

    // ── ROUND_WA2_MESSAGE_READING_1 ───────────────────────────────────────────────────────────
    // On-demand PULL only — called strictly when the user explicitly asks to be read something
    // (TASK 1 of WA2). Never invoked from onNotificationPosted/any background path, so receiving
    // a message can never itself trigger speech, per the round's explicit rule. Reads Android's
    // OWN currently-active status bar notifications via NotificationListenerService.
    // getActiveNotifications() — no separate storage/cache of message content anywhere in this
    // module. Returns a JSON array of {sender, text, whenMs}, oldest first, WhatsApp only, group
    // summaries excluded (they duplicate the individual message notifications, never real
    // content of their own), or "SECURITY_EXCEPTION" if the listener isn't actually connected —
    // the caller must show that honestly, never fall back to inventing content.
    Function("getWhatsAppNotifications") {
      val service = BensonNotificationListenerService.instance
        ?: return@Function "SECURITY_EXCEPTION"
      try {
        val out = JSONArray()
        val sbns = service.activeNotifications ?: emptyArray()
        for (sbn in sbns) {
          if (sbn.packageName != WHATSAPP_PACKAGE) continue
          val n = sbn.notification ?: continue
          if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) continue
          val extras = n.extras ?: continue
          val fallbackSender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
          val messages = extractMessagingStyleMessages(extras)
          if (messages.isNotEmpty()) {
            for (m in messages) {
              out.put(JSONObject().apply {
                put("sender", m.first.ifBlank { fallbackSender ?: "" })
                put("text", m.second)
                put("whenMs", sbn.notification.`when`)
              })
            }
          } else {
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
              ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            if (!fallbackSender.isNullOrBlank() && !text.isNullOrBlank()) {
              out.put(JSONObject().apply {
                put("sender", fallbackSender)
                put("text", text)
                put("whenMs", sbn.notification.`when`)
              })
            }
          }
        }
        Log.i("BENSON_AUDIO", "WA_NOTIFICATIONS_READ count=${out.length()} sources=notification_listener")
        out.toString()
      } catch (_: SecurityException) {
        "SECURITY_EXCEPTION"
      } catch (e: Exception) {
        Log.e("BensonNotificationListener", "getWhatsAppNotifications failed", e)
        "[]"
      }
    }

    // ── ROUND_MEDIA_GOVERNANCE_1 ──────────────────────────────────────────────────────────────
    // Priority-1 media control per the round spec: Android's own MediaSession framework, not
    // Accessibility button-pressing. MediaSessionManager.getActiveSessions() requires exactly the
    // permission this module already exists for and is already onboarded for (a live
    // NotificationListenerService component) — no new permission, no new onboarding step. Every
    // call here is synchronous (plain OS binder calls, same as getForegroundPackage() elsewhere)
    // and wrapped so a SecurityException (listener not actually enabled right now) is reported
    // honestly, never crashes the caller.
    Function("getActiveMediaSessions") {
      mediaSessionManager()?.let { msm ->
        try {
          val out = JSONArray()
          for (c in activeControllers(msm)) {
            val state = c.playbackState
            out.put(JSONObject().apply {
              put("packageName", c.packageName)
              put("state", state?.state ?: PlaybackState.STATE_NONE)
              put("actions", state?.actions ?: 0L)
            })
          }
          out.toString()
        } catch (_: SecurityException) {
          "SECURITY_EXCEPTION"
        } catch (_: Exception) {
          "[]"
        }
      } ?: "[]"
    }

    // action: "play" | "pause" | "stop" | "next" | "previous". packageName: "" = no filter (picks
    // the first actively-PLAYING session, else the first active session at all — same fallback
    // idiom as getForegroundPackage-style "best effort, never guess a coordinate").
    Function("mediaControl") { packageName: String, action: String ->
      val msm = mediaSessionManager() ?: return@Function false
      try {
        val target = pickController(activeControllers(msm), packageName) ?: return@Function false
        val tc = target.transportControls
        when (action) {
          "play" -> tc.play()
          "pause" -> tc.pause()
          "stop" -> tc.stop()
          "next" -> tc.skipToNext()
          "previous" -> tc.skipToPrevious()
          else -> return@Function false
        }
        true
      } catch (_: Exception) {
        false
      }
    }

    // Returns {"packageName": string|null, "state": int, "title": string|null, "artist": string|null}.
    // state: -1 = no active session found, -2 = SecurityException (listener not enabled),
    // otherwise a PlaybackState.STATE_* constant (0=NONE, 1=STOPPED, 2=PAUSED, 3=PLAYING, ...).
    // ROUND_SPOTIFY_SELECT_2 — title/artist added (purely additive; packageName/state unchanged)
    // so a caller can verify not just THAT something is playing but that the metadata matches
    // what was actually selected, per that round's explicit acceptance requirement.
    Function("getPlaybackState") { packageName: String ->
      val msm = mediaSessionManager()
        ?: return@Function JSONObject().apply { put("packageName", JSONObject.NULL); put("state", -1) }.toString()
      try {
        val target = pickController(activeControllers(msm), packageName)
          ?: return@Function JSONObject().apply { put("packageName", JSONObject.NULL); put("state", -1) }.toString()
        val md = target.metadata
        JSONObject().apply {
          put("packageName", target.packageName)
          put("state", target.playbackState?.state ?: PlaybackState.STATE_NONE)
          put("title", md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: JSONObject.NULL)
          put("artist", md?.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: JSONObject.NULL)
        }.toString()
      } catch (_: SecurityException) {
        JSONObject().apply { put("packageName", JSONObject.NULL); put("state", -2) }.toString()
      } catch (_: Exception) {
        JSONObject().apply { put("packageName", JSONObject.NULL); put("state", -1) }.toString()
      }
    }
  }

  // ROUND_WA2_MESSAGE_READING_1 — WhatsApp posts MessagingStyle notifications: when several
  // unread messages from the SAME chat stack, EXTRA_TEXT only ever holds the LAST one; the full
  // set lives in EXTRA_MESSAGES (Parcelable[] of Bundles), which Notification.MessagingStyle's
  // own public parser (getMessagesFromBundleArray, API 24+) turns into real Message objects —
  // using that instead of hand-parsing the Bundle keys, so this stays correct across Android
  // versions without guessing WhatsApp's exact key names. Returns (sender, text) pairs, oldest
  // first (WhatsApp/Android already order EXTRA_MESSAGES chronologically); empty list if this
  // notification isn't MessagingStyle (falls back to EXTRA_TEXT at the call site).
  private fun extractMessagingStyleMessages(extras: android.os.Bundle): List<Pair<String, String>> {
    val arr = extras.getParcelableArray(Notification.EXTRA_MESSAGES) as? Array<Parcelable> ?: return emptyList()
    val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)
    return messages.mapNotNull { m ->
      val text = m.text?.toString()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      @Suppress("DEPRECATION")
      val sender = m.sender?.toString() ?: ""
      sender to text
    }
  }

  private fun mediaSessionManager(): MediaSessionManager? {
    val context = appContext.reactContext ?: return null
    return context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
  }

  private fun activeControllers(msm: MediaSessionManager): List<MediaController> {
    val context = appContext.reactContext ?: return emptyList()
    val component = ComponentName(context, BensonNotificationListenerService::class.java)
    return msm.getActiveSessions(component)
  }

  // packageName empty -> prefer whichever session is actually PLAYING right now, else the first
  // active session (most-recently-active first, per Android's own ordering) — this is the "no
  // filter needed" path a caller that doesn't know the exact package (e.g. a radio app opened via
  // the generic app-launcher) can still rely on.
  private fun pickController(controllers: List<MediaController>, packageName: String): MediaController? {
    if (packageName.isNotEmpty()) {
      controllers.firstOrNull { it.packageName == packageName }?.let { return it }
    }
    controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }?.let { return it }
    return controllers.firstOrNull()
  }
}
