package expo.modules.foregroundservice

// BENSON NEXT — BENSON LIVE, orchestratorul unic (19.10.2026). Primul flux complet:
// TEXT -> LIVE (intelegere + validare) -> AKA (capabilitate) -> EXECUTOR -> REZULTAT VERIFICAT.
// Pur, testabil, zero Context/Android — orchestrarea reala (model provider legat de un LLM real,
// AKA legat de StaffRegistry, executor legat de AccessibilityService) ramane cablata separat.
// Reutilizeaza D0 (CanonicalIntent/classifyIntentValidation/routeCanonicalIntent/MissionStep) si
// ApplicationCapabilityRegistry (isSafeToStateAsOperational) — nicio redefinire.

sealed class MissionResult {
  data class Replied(val text: String) : MissionResult()
  data class Clarify(val question: String) : MissionResult()
  data class Rejected(val reason: String) : MissionResult()
  // Fluxul real de confirmare (ConfirmationGate-ul propriu-zis ramane in LEGACY, neatins) — DOAR
  // produs cand constructorul cere explicit pasul separat (requireExplicitConfirmationStep=true).
  // Implicit FALSE -> comportament identic cu inainte (Executed direct), zero schimbare pentru
  // apelantii/testele existente.
  data class AwaitingConfirmation(val missionId: String, val action: String, val targetAgent: String) : MissionResult()
  data class Executed(val missionId: String, val outcome: ExecutionOutcome) : MissionResult()
}

class BensonLiveOrchestrator(
  private val modelProvider: ModelProvider,
  private val knownActions: Set<String>,
  private val actionToAgent: Map<String, String>,
  private val confirmRequiredActions: Set<String>,
  private val capabilityLookup: ApplicationCapabilityLookup,
  private val executionEngine: ExecutionEngine,
  // MEMORY — optional (null = fara rezolvare de destinatii, comportament neschimbat pentru
  // actiuni care nu au nevoie de ea). Un singur LIVE Mission Owner consuma MEMORY ca unealta,
  // nu un al doilea orchestrator paralel.
  private val memoryLookup: DestinationMemoryLookup? = null,
  private val requireExplicitConfirmationStep: Boolean = false,
) {
  private val pendingExecutions = mutableMapOf<String, ExecutionRequest>()

  // Raspunsul utilizatorului la o AwaitingConfirmation in asteptare. Nu reimplementeaza
  // ConfirmationGate-ul real (ramane in LEGACY) — doar decizia LIVE: confirmat -> executa prin
  // ACELASI executor, orice altceva -> respinge, fara executie.
  fun confirmPendingMission(missionId: String, confirmed: Boolean): MissionResult {
    val request = pendingExecutions.remove(missionId)
      ?: return MissionResult.Rejected("no_pending_mission:$missionId")
    return if (confirmed) MissionResult.Executed(missionId, executionEngine.execute(request))
    else MissionResult.Rejected("user_declined:$missionId")
  }

  // Punctul de intrare unic: text (tastat SAU deja transcris din voce — LIVE nu stie si nu-i
  // pasa de sursa, exact cerinta "aceeasi configuratie TTS/intelegere pentru tastat si vocal").
  fun handleTextInput(text: String, missionId: String): MissionResult {
    val turns = listOf(ConversationTurn(Channel.USER_VOICE, text))
    return when (val brainOutput = modelProvider.converse(turns, knownActions)) {
      is BrainOutput.Reply -> MissionResult.Replied(brainOutput.text)
      is BrainOutput.Clarify -> MissionResult.Clarify(brainOutput.question)
      is BrainOutput.Failure -> MissionResult.Rejected(brainOutput.reason)
      is BrainOutput.Plan -> MissionResult.Rejected("compound_plan_not_yet_supported_in_next")
      is BrainOutput.ActionRequest -> {
        when (val validation = classifyIntentValidation(brainOutput, knownActions)) {
          is IntentValidationOutcome.Validated -> runMission(validation.intent, missionId)
          is IntentValidationOutcome.NeedsClarification -> MissionResult.Clarify(validation.reason)
          is IntentValidationOutcome.Rejected -> MissionResult.Rejected(validation.reason)
        }
      }
    }
  }

  // AKA -> EXECUTOR -> rezultat verificat. LLM-ul NU executa nimic — doar a produs intentia;
  // de aici incolo totul e determinist (Doctrina 7, reutilizata din LEGACY, nu reinventata).
  private fun runMission(intent: CanonicalIntent, missionId: String): MissionResult {
    val routing = routeCanonicalIntent(intent, actionToAgent, confirmRequiredActions)
      ?: return MissionResult.Rejected("no_agent_for_action:${intent.action}")

    // MEMORY — doar daca a fost furnizata (compatibilitate cu actiuni fara nevoie de destinatie,
    // ex. WHATSAPP_CALL): o Destination neresolvata se rezolva aici sau se cere clarificare.
    // Nu se inventeaza NICIODATA o adresa — null de la memorie = clarificare, nu ghicire.
    val resolvedEntities = mutableListOf<DomainEntity>()
    for (entity in intent.entities) {
      if (memoryLookup != null && entity is DomainEntity.Destination && entity.canonicalPlaceId == null) {
        val place = memoryLookup.resolve(routing.targetAgent, entity.rawQuery)
          ?: return MissionResult.Clarify("destination_unknown:${entity.rawQuery}")
        resolvedEntities += DomainEntity.Destination(entity.rawQuery, place.canonicalName)
      } else {
        resolvedEntities += entity
      }
    }

    val capability = capabilityLookup.selectCapability(intent.action)
    if (capability == null || !isSafeToStateAsOperational(capability)) {
      // Nu se executa pe o capabilitate doar DECLARED/INFERRED/UNIT_TESTED — exact regula D0.
      return MissionResult.Rejected("no_verified_capability:${intent.action}")
    }

    val risk = if (routing.requiresConfirmation) RiskLevel.CONFIRM_REQUIRED else RiskLevel.LOW
    val resolvedPlaceId = resolvedEntities.filterIsInstance<DomainEntity.Destination>().firstOrNull()?.canonicalPlaceId
    val params = resolvedPlaceId?.let { mapOf("resolvedDestination" to it) } ?: emptyMap()
    val request = ExecutionRequest(routing.targetAgent, intent.action, params, risk)
    if (requireExplicitConfirmationStep && routing.requiresConfirmation) {
      pendingExecutions[missionId] = request
      return MissionResult.AwaitingConfirmation(missionId, intent.action, routing.targetAgent)
    }
    val outcome = executionEngine.execute(request)
    return MissionResult.Executed(missionId, outcome)
  }
}
