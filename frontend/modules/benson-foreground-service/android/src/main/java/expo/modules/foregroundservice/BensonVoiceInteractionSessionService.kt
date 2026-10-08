package expo.modules.foregroundservice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/** PASUL 2a (07.10.2026) — see BensonVoiceInteractionService. Only triggered by the system assist
 *  gesture, never by BENSON's own "Benson" wake path. */
class BensonVoiceInteractionSessionService : VoiceInteractionSessionService() {
  override fun onNewSession(args: Bundle?): VoiceInteractionSession = BensonVoiceInteractionSession(this)
}
