package expo.modules.foregroundservice

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * PASUL 2a (07.10.2026) — minimal no-op, exists only to satisfy the voice-interaction-service
 * XML schema's required `recognitionService` attribute. BENSON's own STT (Deepgram, via
 * wakeVerifyTranscribe / NativeConfirmationListener) is completely separate; nothing calls into
 * this class.
 */
class BensonRecognitionService : RecognitionService() {
  override fun onStartListening(recognizerIntent: Intent?, listener: Callback) {
    try { listener.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
  }
  override fun onCancel(listener: Callback) {}
  override fun onStopListening(listener: Callback) {}
}
