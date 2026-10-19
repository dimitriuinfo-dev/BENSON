package expo.modules.foregroundservice

// WORKSTREAM A — Application Knowledge Agent (AKA), faza izolata (D0 aprobat, 19.10.2026).
// Overlay de capabilitati VERIFICABILE peste StaffRegistryCore existent — NU un al doilea
// inventar de aplicatii (Legea Personalului, punctul 1: "un singur inventar in tot codul").
// StaffRegistryCore.kt ramane neatins de aceasta runda.
// Pur, zero Context — persistenta reala si legarea de StaffRegistryCore.initialCaps() raman
// pentru runda de integrare, dupa aprobare si verificare pe dispozitiv (corectia 13).

data class CapabilityRecord(
  val appPackage: String,
  val operation: String, // acelasi vocabular cu StaffRegistryCore.CAP_*
  val state: CapabilityVerificationState,
  val requiresConfirmation: Boolean,
  val lastVerifiedBuild: String? = null,
  val invalidatedReason: String? = null,
)

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
    )
  }
