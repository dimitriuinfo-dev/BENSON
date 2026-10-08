package expo.modules.foregroundservice

import android.service.voice.VoiceInteractionService

/**
 * PASUL 2a (07.10.2026) — exists only to qualify BENSON for RoleManager.ROLE_ASSISTANT and the
 * "app which provides the VoiceInteractionService" foreground-service background-start exemption
 * (developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). No hotword
 * detection here — AlwaysOnHotwordDetector/CAPTURE_AUDIO_HOTWORD are @SystemApi, unavailable to a
 * third-party app regardless of this class. HEED in BensonForegroundService stays the sole wake
 * engine, unchanged; this service is never bound to by BENSON's own code.
 */
class BensonVoiceInteractionService : VoiceInteractionService()
