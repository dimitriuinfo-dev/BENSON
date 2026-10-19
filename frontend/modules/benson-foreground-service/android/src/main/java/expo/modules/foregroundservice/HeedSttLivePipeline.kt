package expo.modules.foregroundservice

// BENSON NEXT — pipeline conceptual HEED -> STT -> LIVE NEXT -> EXECUTOR -> RESPONSE (19.10.2026).
// Pur, testabil: NU atinge BensonForegroundService.kt (fisier protejat, inca cu LEGEA TACERII
// necomisa) — modeleaza exact DECIZIA pe care acel fisier o ia deja (authorizeTurn/isTurnAuthorized),
// consumata ca parametru, nu reimplementata ca stare noua. HEED insusi (detectia acustica) si STT
// (transcrierea) raman exclusiv in LEGACY — acest pipeline incepe DUPA ce exista deja un transcript
// si o decizie de autorizare, exact ce ar primi daca ar fi chemat din acel fisier.
sealed class PipelineResult {
  data class Suppressed(val reason: String) : PipelineResult() // LEGEA TACERII - nici macar LIVE nu e consultat
  data class Processed(val missionResult: MissionResult, val voiceDecision: VoiceOutputDecision) : PipelineResult()
}

class HeedSttLivePipeline(
  private val orchestrator: BensonLiveOrchestrator,
  private val voiceClient: OpenAiVoiceOutputClient,
) {
  // LEGEA TACERII, respectata la INTRAREA in pipeline, nu doar la iesirea spre voce (ca in LEGACY
  // actual): un transcript fara tura autorizata nu ajunge NICIODATA la LIVE/AKA/EXECUTOR - exact
  // "IDLE inseamna zero trimitere catre LLM a vorbirii ambientale", cerut explicit intr-o runda
  // anterioara despre defectul real de pe telefon (televizorul).
  fun handleTranscript(transcript: String, turnOrigin: String, isTurnAuthorized: Boolean, missionId: String): PipelineResult {
    if (!isTurnAuthorized) {
      return PipelineResult.Suppressed("no_wake_authorization:$turnOrigin")
    }
    val missionResult = orchestrator.handleTextInput(transcript, missionId)
    val voiceDecision = BensonVoiceOutput.decide(missionResult, missionId, voiceClient)
    return PipelineResult.Processed(missionResult, voiceDecision)
  }
}
