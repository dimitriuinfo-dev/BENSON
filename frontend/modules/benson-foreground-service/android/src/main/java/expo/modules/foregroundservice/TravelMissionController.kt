package expo.modules.foregroundservice

// WORKSTREAM C — Travel Agent, faza izolata (D0 aprobat, 19.10.2026).
// Consuma interfetele A (CapabilityRecord) si B (SavedPlace) ca parametri injectati — nu importa
// logica interna a celorlalte, nu creeaza AccessibilityService, nu atinge microfonul, nu apeleaza
// direct ModelProvider. Reutilizeaza Confirmation Gate-ul existent la pasul de executie (doar
// requiresConfirmation=true aici — gate-ul real ramane cel din BensonForegroundService.kt,
// neatins, legat la integrare).

// Nu inventeaza adresa (mandat §C): daca destinatia nu e rezolvata, misiunea se opreste la primul
// pas si cere clarificare — nu trece niciodata la executie cu o tinta ghicita.
fun planNavigationMission(
  missionId: String,
  rawTarget: String,
  resolvedPlace: SavedPlace?,
  navigationCapability: CapabilityRecord?,
): Mission {
  val steps = mutableListOf<MissionStep>()
  steps += MissionStep(
    stepId = "resolve_destination",
    targetAgent = "MEMORY",
    action = "resolve_destination",
    entities = listOf(DomainEntity.Destination(rawTarget, resolvedPlace?.canonicalName)),
  )

  if (resolvedPlace == null) {
    return Mission(missionId, steps, MissionState.CREATED)
  }

  steps += MissionStep(
    stepId = "select_capability",
    targetAgent = "AKA",
    action = "select_navigation_capability",
    entities = emptyList(),
    dependsOn = listOf("resolve_destination"),
  )

  // Doar o capabilitate DEVICE_VERIFIED/RUNTIME_VERIFIED poate ajunge la executie (corectia 4:
  // confidenta semantica, verificarea capabilitatii si succesul executiei nu se confunda).
  if (navigationCapability == null || !isSafeToStateAsOperational(navigationCapability)) {
    return Mission(missionId, steps, MissionState.CREATED)
  }

  steps += MissionStep(
    stepId = "execute_navigation",
    targetAgent = "EXECUTION",
    action = "navigate",
    entities = listOf(DomainEntity.Destination(rawTarget, resolvedPlace.canonicalName)),
    dependsOn = listOf("select_capability"),
    requiresConfirmation = true,
  )

  return Mission(missionId, steps, MissionState.EXECUTING)
}

// Ruta de trafic/ETA: niciodata atasata misiunii daca pretinde trafficAware fara sursa live
// (corectia 9, garda RouteInfo.isHonestAboutTraffic() din D0).
fun honestRouteOrNull(route: RouteInfo?): RouteInfo? =
  if (route != null && route.isHonestAboutTraffic()) route else null

// Contract de verificare per pas + anulare (corectia 2). O misiune anulata nu mai accepta
// rezultate noi — un pas care se termina DUPA anulare nu poate re-invia misiunea (acelasi
// principiu ca interruptAll/agentRunId, deja dovedit pe telefon).
fun applyStepOutcome(mission: Mission, stepId: String, outcome: ExecutionOutcome): Mission {
  if (mission.cancelled) return mission
  val updatedSteps = mission.steps.map { if (it.stepId == stepId) it.copy(outcome = outcome) else it }
  val state = when {
    updatedSteps.any { it.outcome is ExecutionOutcome.Failed } -> MissionState.FAILED
    updatedSteps.all { it.outcome is ExecutionOutcome.Success } -> MissionState.COMPLETED
    else -> mission.state
  }
  return mission.copy(steps = updatedSteps, state = state)
}
