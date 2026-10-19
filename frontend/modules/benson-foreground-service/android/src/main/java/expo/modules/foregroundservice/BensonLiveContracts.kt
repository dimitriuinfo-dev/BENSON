package expo.modules.foregroundservice

// BENSON NEXT — contractele BENSON LIVE (19.10.2026). ARCHITECT -> BUILDER: aceste interfete sunt
// ce consuma orchestratorul (BensonLiveOrchestrator.kt); implementarile reale (model provider
// conectat la un LLM real, AKA legat de StaffRegistry, executor legat de AccessibilityService) se
// cablează separat, într-o rundă ulterioară, fiecare validată pe dispozitiv înainte de a înlocui
// drumul LEGACY din BensonForegroundService.kt (punctul 9 — Waze/WhatsApp raman neatinse pana la
// inlocuire demonstrata). Reutilizeaza D0 (BensonAgentContracts.kt) — nu redefineste
// CanonicalIntent/Mission/MissionStep/ExecutionOutcome/CapabilityRecord.

// ModelProvider e deja definit in BensonAgentContracts.kt (D0, sectiunea 5) — reutilizat aici,
// nu redeclarat.

// AKA — consuma ApplicationCapabilityRegistry.kt (deja construit); aici doar interfata pe care
// LIVE o foloseste, ca sa nu depinda direct de StaffRegistry/Context.
interface ApplicationCapabilityLookup {
  fun selectCapability(operation: String): CapabilityRecord?
}

// MEMORY — consuma PersonalMemoryStore (deja construit) pentru rezolvarea destinatiilor numite
// (nume -> SavedPlace). Interfata, nu implementarea - un fake in teste, PersonalMemoryStore real
// la integrare. Nu inventeaza nicio adresa: null = destinatia nu e cunoscuta, LIVE cere clarificare.
interface DestinationMemoryLookup {
  fun resolve(requesterAgent: String, query: String): SavedPlace?
}

// EXECUTOR — interfata catre executia reala Android (AccessibilityService/Intent/etc., ramasa
// AFARA din BENSON NEXT pana la o rundă dedicată). Rezultatul e deja tipul D0 (ExecutionOutcome) —
// "verificat", nu doar "incercat".
interface ExecutionEngine {
  fun execute(request: ExecutionRequest) : ExecutionOutcome
}

enum class RiskLevel { LOW, CONFIRM_REQUIRED, REFUSE }

data class ExecutionRequest(
  val targetAgent: String,
  val action: String,
  val params: Map<String, String>,
  val risk: RiskLevel,
)

// ONE VOICE, OPENAI FIRST (19.10.2026, decizie de produs): o singura identitate vocala
// configurata; Android TTS NU inlocuieste tacit vocea OpenAI. Daca serviciul vocal nu e
// disponibil, raspunsul ramane TEXT — niciodata substituire tacita cu alt motor/voce.
// Implementarea reala a apelului OpenAI TTS (HTTP, redare audio) ramane o runda separata; aici
// doar DECIZIA (vorbit vs. text), testabila fara retea.
data class VoiceOutputRequest(val text: String, val missionId: String)

sealed class VoiceOutputDecision {
  data class Speak(val text: String, val voiceId: String) : VoiceOutputDecision()
  data class TextOnly(val text: String, val reason: String) : VoiceOutputDecision()
}

object VoiceOutputPolicy {
  const val CONFIGURED_VOICE_ID = "openai-tts:configured" // placeholder explicit, nu un apel real

  fun decide(request: VoiceOutputRequest, voiceServiceAvailable: Boolean): VoiceOutputDecision =
    if (voiceServiceAvailable) {
      VoiceOutputDecision.Speak(request.text, CONFIGURED_VOICE_ID)
    } else {
      VoiceOutputDecision.TextOnly(request.text, "voice_service_unavailable")
    }
}
