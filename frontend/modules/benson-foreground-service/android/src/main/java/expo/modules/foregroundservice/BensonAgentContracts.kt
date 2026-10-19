package expo.modules.foregroundservice

// D0 — SHARED AGENT CONTRACTS (BENSON LAW 00 + APPROVAL D0, 19.10.2026).
// Pur Kotlin, zero Context, zero org.json — testabil JUnit fara dispozitiv, exact ca
// StaffRegistryCore.kt. Niciun fisier existent nu e modificat de aceasta runda (D0 nu atinge
// BensonForegroundService.kt / BensonCommandRegistry.kt / CLAUDE.md — toate deja "dirty").
//
// Corectiile obligatorii din mesajul de aprobare, fiecare localizata mai jos:
//   1. BrainOutput / CanonicalIntent / AgentRoutingDecision — trei tipuri separate, trei pasi.
//   2. Mission + MissionStep — multi-pas, multi-agent, dependinte, anulare, confirmare, verificare.
//   3. DomainEntity (sealed) — entitati tipizate, nu Map<String,String>.
//   4. SemanticConfidence / CapabilityVerificationState / ExecutionOutcome — trei dimensiuni
//      separate, niciodata confundate (nu mai exista un singur enum "VerificationState" partajat).
//   5. ModelProvider — un singur punct de model, interschimbabil; orchestratorul e unic.
//   6. MemoryWriteAuthorization — nu se poate construi dintr-o eticheta "USER" declarata de agent;
//      cere o dovada de autorizare a turei (acelasi mecanism cu LEGEA TACERII: wake/follow_up).
//      MemoryReadRequest e scopat pe domenii, nu acces total implicit.
//   7. MemoryMigrationPlan — doar tipul; D0 nu migreaza si nu sterge nimic.
//   8. CapabilityVerificationState — cinci niveluri distincte (nu trei).
//   9. RouteInfo — geometrie / estimare / trafic live separate explicit; o garda interzice
//      "trafficAware=true" fara sursa live.
//  10. Nimic de aici nu cere schimbarea wake/WhatsApp/AccessibilityService/LEGEA TACERII.
//  11. Fisier nou, nu editare.
//  12-14. Doar contracte + teste; nicio integrare live, niciun DEVICE_PASS revendicat aici.

// ───────────────────────── 3. Entitati tipizate (nu Map<String,String>) ─────────────────────────

sealed class DomainEntity {
  data class Destination(val rawQuery: String, val canonicalPlaceId: String? = null) : DomainEntity()
  data class Contact(val rawName: String, val resolvedContactId: String? = null) : DomainEntity()
  data class Application(val rawName: String, val packageName: String? = null) : DomainEntity()
  data class Message(val text: String) : DomainEntity()
  data class MediaQuery(val query: String, val appHint: String? = null) : DomainEntity()
  data class FreeText(val text: String) : DomainEntity()
}

// ───────────── 4. Trei dimensiuni separate — niciodata confundate intre ele ─────────────

// Cat de sigur e MODELUL de interpretarea semantica (intentia). NU spune nimic despre daca
// actiunea poate fi efectiv executata pe acest telefon.
enum class SemanticConfidence { HIGH, MEDIUM, LOW }

// Cat de verificata e o CAPABILITATE a unei aplicatii (AKA / Corectia 8) — cinci niveluri.
// Promovarea se face STRICT in ordine; nimic nu sare direct la RUNTIME_VERIFIED.
enum class CapabilityVerificationState { DECLARED, INFERRED, UNIT_TESTED, DEVICE_VERIFIED, RUNTIME_VERIFIED }

// Daca O EXECUTIE CONCRETA (acest pas, acum) a reusit efectiv — dovada runtime, nu presupunere.
sealed class ExecutionOutcome {
  data class Success(val verifiedBy: String, val evidence: String? = null) : ExecutionOutcome()
  data class Failed(val reason: String) : ExecutionOutcome()
  object Unverified : ExecutionOutcome()
}

// ───────────────────── 1. BrainOutput -> CanonicalIntent -> AgentRoutingDecision ─────────────────────
// LLM-ul produce DOAR BrainOutput. BENSON Core valideaza (CanonicalIntent) si abia apoi decide
// ruta (AgentRoutingDecision) — determinist, fara un al doilea apel de model pentru rutare.

enum class Channel { SYSTEM, USER_VOICE, UNTRUSTED_DATA }
data class ConversationTurn(val channel: Channel, val text: String)

// Ce intoarce modelul, brut — inainte de orice validare. "action" e un string liber (semantic),
// nu un dictionar fix RO/DE/EN (LAW 00, regula 10).
sealed class BrainOutput {
  data class Reply(val text: String, val detectedLanguage: String) : BrainOutput()
  data class Clarify(val question: String, val detectedLanguage: String) : BrainOutput()
  data class ActionRequest(
    val action: String,
    val entities: List<DomainEntity>,
    val confidence: SemanticConfidence,
    val detectedLanguage: String,
    val dependsOnStepId: String? = null,
  ) : BrainOutput()
  data class Plan(val steps: List<ActionRequest>, val detectedLanguage: String) : BrainOutput()
  data class Failure(val reason: String) : BrainOutput()
}

// Forma VALIDATA de BENSON Core — doar actiuni din vocabularul cunoscut ajung aici. O
// ActionRequest care nu valideaza devine Clarify, niciodata o executie ghicita.
data class CanonicalIntent(
  val action: String,
  val entities: List<DomainEntity>,
  val confidence: SemanticConfidence,
  val detectedLanguage: String,
)

// Validare: Corectia 1 ("LLM-ul interpreteaza; BENSON Core valideaza si ruteaza").
fun validateCanonicalIntent(output: BrainOutput.ActionRequest, knownActions: Set<String>): CanonicalIntent? {
  if (output.action !in knownActions) return null
  if (output.entities.isEmpty() && output.confidence == SemanticConfidence.LOW) return null
  return CanonicalIntent(output.action, output.entities, output.confidence, output.detectedLanguage)
}

// Decizia de rutare — produsa de Core, NICIODATA de model. Determinista: acelasi CanonicalIntent
// produce aceeasi decizie, indiferent de limba sursa.
data class AgentRoutingDecision(
  val targetAgent: String, // "AKA" | "MEMORY" | "TRAVEL" | "EXECUTION"
  val missionStepId: String,
  val requiresConfirmation: Boolean,
)

fun routeCanonicalIntent(intent: CanonicalIntent, actionToAgent: Map<String, String>, confirmRequiredActions: Set<String>): AgentRoutingDecision? {
  val agent = actionToAgent[intent.action] ?: return null
  return AgentRoutingDecision(
    targetAgent = agent,
    missionStepId = "step_${intent.action}_${intent.entities.size}",
    requiresConfirmation = intent.action in confirmRequiredActions,
  )
}

// ETAPA 3 (19.10.2026) — defect gasit la verificare, reparat ADITIV: validateCanonicalIntent()
// de mai sus intoarce null si pentru "actiune necunoscuta" SI pentru "actiune cunoscuta, dar
// confidenta/entitati insuficiente" — apelantul nu poate distinge "refuz" de "trebuie sa cer
// clarificare", ceea ce incalca cerinta de stare de clarificare explicita si neambigua. Nu am
// schimbat validateCanonicalIntent() (ar fi o schimbare arbitrara de API, interzisa explicit) —
// e lasata neatinsa pentru compatibilitate, dar noii apelanti (integrarea viitoare) ar trebui sa
// foloseasca functia de mai jos, care distinge explicit cele trei cazuri.
sealed class IntentValidationOutcome {
  data class Validated(val intent: CanonicalIntent) : IntentValidationOutcome()
  data class NeedsClarification(val reason: String) : IntentValidationOutcome()
  data class Rejected(val reason: String) : IntentValidationOutcome()
}

// FIX (control tehnic 19.10.2026) — defect real gasit: nimic nu verifica ca o actiune care CERE
// o entitate (ex. NAVIGATE fara nicio Destination) are efectiv una. O actiune cu confidenta
// MEDIE/MARE si zero entitati trecea drept Validated — ar fi ajuns la un executor fara nimic de
// executat. `requiredEntityTypes` e opțional (implicit gol) ca sa nu schimbe comportamentul
// apelantilor existenti; un apelant nou (integrarea SURVIVOR) declara ce entitate cere fiecare
// actiune, functia verifica generic, fara un tabel hardcodat aici pentru toate actiunile BENSON.
fun classifyIntentValidation(
  output: BrainOutput.ActionRequest,
  knownActions: Set<String>,
  requiredEntityTypes: Map<String, Class<out DomainEntity>> = emptyMap(),
): IntentValidationOutcome {
  if (output.action !in knownActions) return IntentValidationOutcome.Rejected("unknown_action")
  val requiredType = requiredEntityTypes[output.action]
  if (requiredType != null && output.entities.none { requiredType.isInstance(it) }) {
    return IntentValidationOutcome.NeedsClarification("missing_required_entity:${requiredType.simpleName}")
  }
  if (output.entities.isEmpty() && output.confidence == SemanticConfidence.LOW) {
    return IntentValidationOutcome.NeedsClarification("low_confidence_no_entities")
  }
  return IntentValidationOutcome.Validated(
    CanonicalIntent(output.action, output.entities, output.confidence, output.detectedLanguage),
  )
}

// ETAPA 3 — a doua lipsa gasita: nimic nu impiedica reexecutarea aceluiasi pas cu efect extern
// (ex. un retry care ar porni navigatia de doua ori). Garda pura, minimala: apelantul (la
// integrare) tine o multime de chei deja executate si o verifica INAINTE de executie.
data class ExecutionIdempotencyKey(val missionId: String, val stepId: String)

fun isDuplicateExecution(alreadyExecuted: Set<ExecutionIdempotencyKey>, missionId: String, stepId: String): Boolean =
  ExecutionIdempotencyKey(missionId, stepId) in alreadyExecuted

// ───────────────────── 2. Mission / MissionStep — multi-pas, multi-agent ─────────────────────

enum class MissionState { CREATED, EXECUTING, CONFIRMING, COMPLETED, FAILED, CANCELLED }

data class MissionStep(
  val stepId: String,
  val targetAgent: String,
  val action: String,
  val entities: List<DomainEntity>,
  val dependsOn: List<String> = emptyList(),
  val requiresConfirmation: Boolean = false,
  val outcome: ExecutionOutcome? = null,
)

data class Mission(
  val missionId: String,
  val steps: List<MissionStep>,
  val state: MissionState,
  val cancelled: Boolean = false,
)

// O dependinta trebuie sa existe in aceeasi misiune si niciun ciclu nu e permis (verificare
// simpla, suficienta pentru liste mici de pasi).
fun validateMissionSteps(steps: List<MissionStep>): Boolean {
  val ids = steps.map { it.stepId }.toSet()
  if (ids.size != steps.size) return false // id duplicat
  for (step in steps) {
    if (step.dependsOn.any { it !in ids || it == step.stepId }) return false
  }
  return !hasDependencyCycle(steps)
}

private fun hasDependencyCycle(steps: List<MissionStep>): Boolean {
  val byId = steps.associateBy { it.stepId }
  val visiting = mutableSetOf<String>()
  val visited = mutableSetOf<String>()
  fun visit(id: String): Boolean {
    if (id in visited) return false
    if (id in visiting) return true
    visiting += id
    val step = byId[id] ?: return false
    for (dep in step.dependsOn) if (visit(dep)) return true
    visiting -= id
    visited += id
    return false
  }
  return steps.any { visit(it.stepId) }
}

// Un pas e rulabil doar daca toate dependintele lui s-au terminat cu succes.
fun isStepReady(step: MissionStep, completed: Map<String, ExecutionOutcome>): Boolean =
  step.dependsOn.all { completed[it] is ExecutionOutcome.Success }

// Anularea unei misiuni: pasii deja cu Success raman asa (nu se re-scriu); restul devin Unverified
// prin absenta outcome-ului — misiunea intreaga trece CANCELLED. Reutilizeaza principiul deja
// dovedit (agentRunId/interruptAll): un singur steag, verificat inainte de orice pas nou.
fun cancelMission(mission: Mission): Mission =
  mission.copy(cancelled = true, state = MissionState.CANCELLED)

// ──────────────── 5. ModelProvider — autoritate unica, model interschimbabil ────────────────

interface ModelProvider {
  val providerId: String
  fun converse(turns: List<ConversationTurn>, knownActions: Set<String>): BrainOutput
}

// ─────────────────── 6. Memorie — autorizare verificabila, citire scopata ───────────────────

// Aceeasi forma cu authorizeTurn()/isTurnAuthorized() din LEGEA TACERII (necomisa, dar NEATINSA
// de aceasta runda) — D0 nu importa acel cod (ar insemna sa editeze fisierul dirty), ci defineste
// contractul pe care integrarea viitoare il va lega 1:1 de el.
data class TurnAuthorizationProof(val turnOrigin: String, val authorizedAtMs: Long)

private val AUTHORIZED_WRITE_ORIGINS = setOf("wake", "follow_up")

// O eticheta "USER" declarata de agent NU e suficienta (corectia 6) — constructorul e privat;
// singura cale de a obtine o instanta e prin dovada reala de tura, verificata aici.
class MemoryWriteAuthorization private constructor(val proof: TurnAuthorizationProof) {
  companion object {
    fun fromTurnProof(proof: TurnAuthorizationProof, nowMs: Long, maxAgeMs: Long): MemoryWriteAuthorization? {
      if (proof.turnOrigin !in AUTHORIZED_WRITE_ORIGINS) return null
      if (nowMs - proof.authorizedAtMs > maxAgeMs) return null
      return MemoryWriteAuthorization(proof)
    }
  }
}

enum class MemoryDomain { PLACES, CONTACT_PREFERENCES, GENERAL_FACTS }

// Politica de acces e fixa in cod (nu negociabila de agent) — un agent cere, Core decide.
private val MEMORY_READ_POLICY: Map<String, Set<MemoryDomain>> = mapOf(
  "TRAVEL" to setOf(MemoryDomain.PLACES),
  "MEMORY" to MemoryDomain.values().toSet(),
  "AKA" to emptySet(),
)

fun isMemoryReadAllowed(requesterAgent: String, domain: MemoryDomain): Boolean =
  MEMORY_READ_POLICY[requesterAgent]?.contains(domain) == true

data class MemoryReadRequest(val requesterAgent: String, val domain: MemoryDomain, val query: String)

data class SavedPlace(
  val canonicalName: String,
  val aliases: Map<String, String>, // tag limba (bcp47-ish) -> alias rostit
  val latitude: Double,
  val longitude: Double,
  val confirmedByUser: Boolean,
)

data class MemoryWriteRequest(
  val requesterAgent: String,
  val authorization: MemoryWriteAuthorization,
  val place: SavedPlace? = null,
  val factText: String? = null,
)

// ───────────────────────── 7. Migrare memorie — doar tipul, nicio actiune ─────────────────────────

enum class MigrationStrategy { NONE, READ_ONLY_BRIDGE, COPY_ONCE }

data class MemoryMigrationPlan(
  val sourceStoreId: String, // ex. "js_async_storage_facts_v2" — FACTS_KEY existent, neatins
  val targetStoreId: String, // ex. "native_personal_memory_v1"
  val strategy: MigrationStrategy,
  val deletesSource: Boolean = false, // trebuie mereu false pana la aprobare explicita separata
)

// ───────────────── 9. Ruta: geometrie / estimare / trafic live — niciodata confundate ─────────────────

enum class RouteDataSource { STATIC_GEOMETRY_ONLY, HISTORICAL_ESTIMATE, LIVE_TRAFFIC_AWARE }

data class RouteInfo(
  val distanceMeters: Int,
  val geometrySource: RouteDataSource,
  val etaMinutes: Int?,
  val etaSource: RouteDataSource,
  val trafficAware: Boolean,
)

// OSRM singur (sau orice geometrie statica) NU poate justifica trafficAware=true. Garda explicita,
// ceruta de corectia 9 — reutilizata oriunde se construieste un RouteInfo.
fun RouteInfo.isHonestAboutTraffic(): Boolean =
  if (trafficAware) etaSource == RouteDataSource.LIVE_TRAFFIC_AWARE else true
