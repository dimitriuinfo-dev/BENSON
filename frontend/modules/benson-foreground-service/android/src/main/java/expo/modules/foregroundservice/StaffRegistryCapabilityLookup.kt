package expo.modules.foregroundservice

// BENSON NEXT — AKA cablat la StaffRegistry (19.10.2026), prin contractele LIVE existente
// (ApplicationCapabilityLookup, BensonLiveContracts.kt) — NU un registru nou, NU un orchestrator
// nou. Reutilizeaza 1:1 capabilityRecordsFromStaffMember/allCapabilityRecords/selectBestCapability/
// isSafeToStateAsOperational (ApplicationCapabilityRegistry.kt, deja scrise si testate).
//
// `staffProvider` ramane o functie injectata (nu StaffRegistry.load(ctx) apelat direct aici) —
// acelasi tipar de separare pur/Context ca StaffRegistryCore/StaffRegistry: clasa asta e testabila
// JUnit cu o lista falsa de StaffMember; cablarea REALA (staffProvider = { StaffRegistry.load(ctx) })
// ramane un pas separat, pe dispozitiv, validat separat — nu facuta aici.
internal class StaffRegistryCapabilityLookup(
  private val staffProvider: () -> List<StaffMember>,
) : ApplicationCapabilityLookup {

  // Proveniența + nivelul de validare se verifica AICI, nu se presupun: selectBestCapability
  // alege cea mai avansata inregistrare pentru operatia ceruta, DAR AKA nu vouch-uie pentru ea
  // decat daca e efectiv DEVICE_VERIFIED/RUNTIME_VERIFIED (isSafeToStateAsOperational) — o
  // capabilitate doar DECLARED/INFERRED/UNIT_TESTED NU se confunda cu una verificata pe telefon:
  // AKA intoarce null, nu o inregistrare nesigura pe care apelantul ar putea-o folosi din greseala.
  override fun selectCapability(operation: String): CapabilityRecord? {
    val best = selectBestCapability(allCapabilityRecords(staffProvider()), operation) ?: return null
    return if (isSafeToStateAsOperational(best)) best else null
  }
}
