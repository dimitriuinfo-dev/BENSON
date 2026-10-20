package expo.modules.foregroundservice

// WORKSTREAM A — Application Knowledge Agent (AKA), faza izolata (D0 aprobat, 19.10.2026).
// Overlay de capabilitati VERIFICABILE peste StaffRegistryCore existent — NU un al doilea
// inventar de aplicatii (Legea Personalului, punctul 1: "un singur inventar in tot codul").
// StaffRegistryCore.kt ramane neatins de aceasta runda.
// Pur, zero Context — persistenta reala si legarea de StaffRegistryCore.initialCaps() raman
// pentru runda de integrare, dupa aprobare si verificare pe dispozitiv (corectia 13).

// AKA UNIVERSAL (20.10.2026, "CONTRACTUL COMUN DE CUNOAȘTERE") — extensie ADITIVĂ, cu valori
// implicite, peste contractul D0 existent (19.10.2026): niciun apelant vechi al acestui
// constructor nu se rupe. Câmpurile noi acoperă exact cele 12 puncte cerute în mandat (Identitate/
// Semnificație/Intrări/Ieșiri/Acces/Risc/Autorizație/Verificare/Prospețime/Proveniență/
// Consumatori/Invalidare/Erori) — "verificare" și "invalidare" existau deja (state/
// invalidatedReason), restul sunt noi. Reutilizează RiskLevel din BensonLiveContracts.kt (același
// enum, nu unul paralel).
data class CapabilityRecord(
  val appPackage: String,
  val operation: String, // acelasi vocabular cu StaffRegistryCore.CAP_*
  val state: CapabilityVerificationState,
  val requiresConfirmation: Boolean,
  val lastVerifiedBuild: String? = null,
  val invalidatedReason: String? = null,
  // Identitate — versiunea aplicației la care s-a observat ultima oară această capabilitate.
  val appVersion: String? = null,
  // Semnificație — ce face operația, independent de limbă/etichetă de buton. Null = operație
  // generică de interfață (ui_control:*/aka_fn:*/aka_nav:*), fără descriere semantică proprie.
  val meaning: String? = null,
  // Acces — mecanismul real prin care BENSON poate invoca această operație azi.
  val accessMethod: AccessMethod = AccessMethod.ACCESSIBILITY,
  // Risc — reutilizează clasificarea deja existentă (ConfirmationGate); requiresConfirmation
  // rămâne câmpul autoritar pentru compatibilitate, risk e doar forma lui structurată.
  val risk: RiskLevel = if (requiresConfirmation) RiskLevel.CONFIRM_REQUIRED else RiskLevel.LOW,
  // Consumatori — ce agenți specializați (TRAVEL/COMMUNICATIONS/SHOPPING/CALENDAR/...) ar
  // consuma rezultatul acestei operații. Listă mică, explicită — nu o taxonomie nouă.
  val consumerAgents: List<String> = emptyList(),
  // Erori — motiv structurat pentru care operația NU e încă disponibilă ca operațională, DOAR
  // când există o dovadă reală (ex. ecran de autentificare întâlnit) — niciodată presupus.
  val unavailableReason: String? = null,
)

// Metodele de acces REALE existente în proiect azi (secțiunea III.D a mandatului) — nu o listă
// aspirațională; fiecare valoare corespunde unui mecanism deja scris în cod undeva în BENSON.
enum class AccessMethod { ACCESSIBILITY, DEEP_LINK, ANDROID_INTENT, CONTENT_PROVIDER, SYSTEM_SERVICE }

// Semnificația operațiilor NUMITE (StaffRegistryCore.CAP_*) — SINGURA sursă pentru text semantic,
// aceeași pe care namedCapabilityDomainHints o folosește deja pentru verificarea de compatibilitate
// semantică LIVE+AKA (zero a doua taxonomie). Operațiile generice (ui_control:*/aka_fn:*/aka_nav:*)
// nu au o intrare aici — rămân descrise doar de eticheta observată pe ecran.
internal fun meaningForOperation(operation: String): String? = StaffRegistryCore.NAMED_CAPABILITY_DOMAIN[operation]

// Consumatorii STRUCTURALI ai fiecărei operații NUMITE — mic, explicit, revizuit manual; nu o
// inferență automată. Operațiile generice de interfață nu au consumatori impliciți (depind de
// misiunea curentă, nu de operație).
internal val OPERATION_CONSUMERS: Map<String, List<String>> = mapOf(
  StaffRegistryCore.CAP_NAVIGATE to listOf("TRAVEL"),
  StaffRegistryCore.CAP_MESSAGE to listOf("COMMUNICATIONS"),
  StaffRegistryCore.CAP_CALL to listOf("COMMUNICATIONS"),
  StaffRegistryCore.CAP_VIDEO_CALL to listOf("COMMUNICATIONS"),
  StaffRegistryCore.CAP_SEARCH_PRODUCTS to listOf("SHOPPING"),
  StaffRegistryCore.CAP_MEDIA_SEARCH to listOf("MULTIMEDIA"),
  StaffRegistryCore.CAP_MEDIA_CONTROL to listOf("MULTIMEDIA"),
  StaffRegistryCore.CAP_CALCULATE to listOf("PRODUCTIVITY"),
  StaffRegistryCore.CAP_WEB_SEARCH to listOf("SEARCH"),
)

// Metoda de acces REALĂ per operație numită — derivată din ce e DEJA scris în cod (nu o presupunere
// nouă): navigația foloseşte deep link-uri (navigationLaunchUris, waze://+google.navigation:),
// restul operaţiilor numite trec prin Accessibility (parserele native apasă tot prin
// BensonAccessibilityService, la fel ca EXECUTOR-ul generic).
internal fun accessMethodForOperation(operation: String): AccessMethod =
  if (operation == StaffRegistryCore.CAP_NAVIGATE) AccessMethod.DEEP_LINK else AccessMethod.ACCESSIBILITY

// Ordinea de promovare (corectia 8, cinci niveluri distincte) — strict secventiala, niciun nivel
// nu se sare.
private val PROMOTION_ORDER = listOf(
  CapabilityVerificationState.DECLARED,
  CapabilityVerificationState.INFERRED,
  CapabilityVerificationState.UNIT_TESTED,
  CapabilityVerificationState.DEVICE_VERIFIED,
  CapabilityVerificationState.RUNTIME_VERIFIED,
)

fun canPromote(from: CapabilityVerificationState, to: CapabilityVerificationState): Boolean {
  val fromIdx = PROMOTION_ORDER.indexOf(from)
  val toIdx = PROMOTION_ORDER.indexOf(to)
  return toIdx == fromIdx + 1
}

fun promote(record: CapabilityRecord, to: CapabilityVerificationState, build: String? = null): CapabilityRecord? {
  if (!canPromote(record.state, to)) return null
  return record.copy(state = to, lastVerifiedBuild = build ?: record.lastVerifiedBuild, invalidatedReason = null)
}

// O capabilitate INFERRED/DECLARED/UNIT_TESTED nu are voie sa fie raportata catre voce/creier ca
// fiind operationala (Legea Personalului, punctul 2: "vocea declara operational DOAR verified").
fun isSafeToStateAsOperational(record: CapabilityRecord): Boolean =
  record.state == CapabilityVerificationState.DEVICE_VERIFIED ||
    record.state == CapabilityVerificationState.RUNTIME_VERIFIED

// Update de aplicatie retrogradeaza DEVICE_VERIFIED/RUNTIME_VERIFIED la UNIT_TESTED (M4: "update ->
// verified devine reverify"), nu la DECLARED — testul JUnit tot a trecut, doar comportamentul pe
// acest telefon trebuie reconfirmat.
fun invalidateAfterAppUpdate(record: CapabilityRecord, reason: String): CapabilityRecord =
  if (record.state == CapabilityVerificationState.DEVICE_VERIFIED || record.state == CapabilityVerificationState.RUNTIME_VERIFIED) {
    record.copy(state = CapabilityVerificationState.UNIT_TESTED, invalidatedReason = reason)
  } else {
    record
  }

// Selectia pentru Travel/alti agenti: dintre mai multe inregistrari pentru aceeasi operatie, cea
// mai avansata stare verificata — determinist, fara apel de model.
fun selectBestCapability(records: List<CapabilityRecord>, operation: String): CapabilityRecord? =
  records.filter { it.operation == operation }
    .maxByOrNull { PROMOTION_ORDER.indexOf(it.state) }

// ───────────── Puntea catre StaffRegistryCore REAL (nu un inventar competitor) ─────────────
// CapLevel (StaffRegistryCore) are 3 niveluri; ACR are 5 (corectia 8). Maparea e cat se poate de
// conservatoare: CapLevel.VERIFIED al registrului REAL inseamna deja "test trecut pe telefon"
// (Legea Personalului: "verified e semanat EXCLUSIV din Comportamente dovedite") — abia acel nivel
// se mapeaza la DEVICE_VERIFIED aici. Niciun JUnit al acestui fisier nu poate, prin el insusi,
// produce DEVICE_VERIFIED: nivelul vine STRICT din datele reale ale StaffRegistryCore.
internal fun verificationStateFromCapLevel(level: CapLevel): CapabilityVerificationState = when (level) {
  CapLevel.KNOWN -> CapabilityVerificationState.DECLARED
  CapLevel.REVERIFY -> CapabilityVerificationState.UNIT_TESTED
  CapLevel.VERIFIED -> CapabilityVerificationState.DEVICE_VERIFIED
}

// Citeste capabilitatile unui StaffMember REAL (din StaffRegistry.kt, Context-dependent, neatins
// de aceasta runda) si le transforma in CapabilityRecord — o singura transformare peste inventarul
// existent, nu o a doua enumerare de aplicatii.
internal fun capabilityRecordsFromStaffMember(member: StaffMember): List<CapabilityRecord> =
  member.caps.map { (operation, level) ->
    CapabilityRecord(
      appPackage = member.packageName,
      operation = operation,
      state = verificationStateFromCapLevel(level),
      requiresConfirmation = true, // Confirmation Gate existent ramane obligatoriu, neschimbat
      appVersion = member.versionName.ifBlank { null },
      meaning = meaningForOperation(operation),
      accessMethod = accessMethodForOperation(operation),
      consumerAgents = OPERATION_CONSUMERS[operation] ?: emptyList(),
    )
  }

// BENSON NEXT (19.10.2026) — agregat peste TOT registrul (StaffRegistry.load(ctx) intoarce lista
// completa), nu o a doua enumerare: fiecare StaffMember isi aduce propriile capabilitati prin
// functia de mai sus, aici doar le adunam intr-o singura lista pentru selectBestCapability.
internal fun allCapabilityRecords(members: List<StaffMember>): List<CapabilityRecord> =
  members.flatMap { capabilityRecordsFromStaffMember(it) }
