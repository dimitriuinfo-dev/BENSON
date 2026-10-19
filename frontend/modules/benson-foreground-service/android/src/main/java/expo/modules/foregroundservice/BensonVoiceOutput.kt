package expo.modules.foregroundservice

// BENSON NEXT — iesirea vocala pentru orice MissionResult produs de BensonLiveOrchestrator. NU e
// un orchestrator nou: mapeaza rezultatul deja decis la text + foloseste VoiceOutputPolicy
// (deja scrisa, D0/ONE VOICE) pentru decizia vorbit-vs-text. Apelul HTTP real catre OpenAI TTS
// (sinteza efectiva, redare audio) ramane o integrare separata, pe dispozitiv — aici doar
// `OpenAiVoiceOutputClient.isAvailable()`, o verificare ieftina (cheie configurata, buget
// neconsumat), nu un apel de sinteza.
interface OpenAiVoiceOutputClient {
  fun isAvailable(): Boolean
}

object BensonVoiceOutput {
  fun textFor(result: MissionResult): String = when (result) {
    is MissionResult.Replied -> result.text
    is MissionResult.Clarify -> result.question
    is MissionResult.Rejected -> "Nu am putut duce asta la capăt."
    is MissionResult.AwaitingConfirmation -> "Confirmi?"
    is MissionResult.Executed -> when (result.outcome) {
      is ExecutionOutcome.Success -> "Gata."
      is ExecutionOutcome.Failed -> "Nu am reușit."
      ExecutionOutcome.Unverified -> "Nu pot confirma rezultatul."
    }
  }

  // ONE VOICE, OPENAI FIRST: niciodata inlocuire tacita cu alt motor conversational — indisponibil
  // -> text (VoiceOutputDecision.TextOnly), niciodata alta voce.
  fun decide(result: MissionResult, missionId: String, voiceClient: OpenAiVoiceOutputClient): VoiceOutputDecision =
    VoiceOutputPolicy.decide(VoiceOutputRequest(textFor(result), missionId), voiceClient.isAvailable())
}
