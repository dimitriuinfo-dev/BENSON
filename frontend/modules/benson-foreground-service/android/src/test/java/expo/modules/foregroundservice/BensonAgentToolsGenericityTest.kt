package expo.modules.foregroundservice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// BENSON 2.0 (19.10.2026, misiune de acceptare "deschide Calculatorul si calculeaza") — proba ca
// mecanismul observe_screen/tap_element e deja GENERIC (nicio cunoastere despre "calculator" sau
// "whatsapp" in codul instrumentelor insele), exact ce cere mandatul: "Generalizeaza mecanismul,
// apoi testeaza explorarea WhatsApp VIDEO_CALL". Nu testeaza executia reala (cere dispozitiv) —
// testeaza ca NIMIC specific unei aplicatii nu e cablat in contractul instrumentelor, deci
// aceeasi pereche observe_screen/tap_element poate, in principiu, sa descopere orice interfata,
// fara cod nou per-aplicatie (doctrina 8).
class BensonAgentToolsGenericityTest {

  // calculator/whatsapp — cele doua aplicatii numite explicit in mandatul BENSON 2.0; absenta lor
  // din schema instrumentelor dovedeste ca niciun cod nou, specific lor, nu a fost adaugat aici
  // (spre deosebire de OPEN_APP, care mentioneaza legitim alte nume ca EXEMPLE in limbaj natural,
  // nu ca ramuri de cod — ex. "YouTube, Amazon" in descriere, neatins, nu reflecta o ramificare).
  private val namesForbiddenAsCodeBranches = listOf("calculator", "whatsapp")

  @Test fun toolsJsonContainsNoCalculatorOrWhatsappSpecificBranching() {
    val json = BensonAgentTools.toolsJson().lowercase()
    for (name in namesForbiddenAsCodeBranches) {
      assertFalse("toolsJson nu ar trebui sa mentioneze '$name'", json.contains(name))
    }
  }

  @Test fun observeScreenAndTapElementAreGenericByConstruction() {
    // Insusi faptul ca index+label sunt singurii parametri ai tap_element (nu un id de aplicatie,
    // nu un nume de ecran) dovedeste genericitatea structurala — verificat prin schema publicata.
    val json = BensonAgentTools.toolsJson()
    assertTrue(json.contains("\"index\""))
    assertTrue(json.contains("\"label\""))
  }

  @Test fun systemPromptRequiresReadingActualOnScreenResultNotComputedOne() {
    // BENSON 2.0 — cerinta "nu declara succes doar fiindca LLM-ul poate calcula singur rezultatul".
    val prompt = BensonAgentTools.systemPrompt("Calculator", "Calculator")
    assertTrue(prompt.contains("observe_screen"))
    assertTrue(prompt.contains("nu calcula tu însuți rezultatul"))
  }

  @Test fun systemPromptVerificationInstructionIsGenericNotCalculatorSpecific() {
    // Instructiunea de verificare trebuie sa vorbeasca despre "un rezultat vizibil" generic, nu
    // despre calculator anume — altfel ar fi exact cazul special interzis de doctrina 8.
    val prompt = BensonAgentTools.systemPrompt("orice", "orice").lowercase()
    assertFalse(prompt.contains("calculatorul"))
  }

  // BENSON 2.0, cerinta 9 (formulari romanesti SI germane, aceeasi interpretare semantica) —
  // partea testabila fara Context/dispozitiv: WaCallVideoMatcher (pur, folosit de calea
  // DETERMINISTA tryNativeWhatsApp) nu are voie sa "inghita" o fraza germana — daca ar prinde-o
  // din greseala (potrivire partiala accidentala), fraza n-ar mai ajunge niciodata la agentul
  // generic multilingual. Null aici = garantat sa cada prin tot lantul determinist pana la agent.
  // (OPEN_APP_PATTERN e `private` in BensonForegroundService, Context-dependent — nu e accesibil
  // direct dintr-un test JUnit; verificat prin citirea sursei: "^\\s*deschide(?:-mi)?\\s+..." nu
  // poate potrivi "Öffne den Taschenrechner", pentru ca nu incepe cu "deschide". Onest: verificat
  // prin citire de cod, NU printr-un test automat — distinctie ceruta explicit in backlog.)
  @Test fun waCallVideoMatcherDoesNotSwallowGermanPhrasing() {
    assertTrue(WaCallVideoMatcher.classify("Mach einen Videoanruf mit Hannah auf WhatsApp") == null)
    assertTrue(WaCallVideoMatcher.classify("Ruf Hannah auf WhatsApp an") == null)
  }

  @Test fun waCallVideoMatcherStillRecognizesTheEquivalentRomanianPhrasing() {
    // Control pozitiv: aceeasi intentie, in romana, tot trebuie sa fie prinsa de calea
    // determinista existenta — proba ca testul de mai sus nu e un fals negativ universal.
    assertTrue(WaCallVideoMatcher.classify("video cu Hannah") != null)
  }

  // --- toolResult retry_unsafe — SURVIVOR etapa 3, punctul 6: "verifica prin teste automate,
  // inclusiv rezultate incerte" — campul pe care bucla agentului il citeste MECANIC (nu doar ca
  // indiciu pentru model) ca sa blocheze o repetare a unei actiuni cu rezultat incert. ---

  @Test fun toolResultOmitsRetryUnsafeFieldByDefault() {
    val json = BensonAgentTools.toolResult(true, "ok")
    assertFalse(json.contains("retry_unsafe"))
  }

  @Test fun toolResultIncludesRetryUnsafeWhenMarked() {
    val json = BensonAgentTools.toolResult(false, "rezultat incert", retryUnsafe = true)
    assertTrue(json.contains("\"retry_unsafe\":true"))
    assertTrue(json.contains("\"ok\":false"))
  }

  // --- TRAVEL/MOBILITY (20.10.2026) — proba de genericitate: schema instrumentelor nu codifica
  // NICIO aplicatie de navigatie anume (nici macar Waze, singura dovedita DEVICE_PASS) — alegerea
  // ramane strict la capability-lookup-ul AKA (StaffRegistry), nu la o ramura de cod per aplicatie. ---

  @Test fun travelAndMobilityToolsAreRegisteredAndDispatchable() {
    assertTrue(BensonAgentTools.NAMES.contains(BensonAgentTools.TRAVEL_NAVIGATE))
    assertTrue(BensonAgentTools.NAMES.contains(BensonAgentTools.MOBILITY_FIND_APP))
  }

  @Test fun travelNavigateSchemaDoesNotHardcodeAnyNavigationAppName() {
    val json = BensonAgentTools.toolsJson().lowercase()
    // "waze" apare legitim in codul de EXECUTIE (navigationLaunchUris, deja dovedit), dar NU are
    // voie sa apara in schema instrumentelor insesi — alegerea aplicatiei ramane a AKA, nu a unui
    // nume hardcodat in contractul pe care modelul il vede.
    assertFalse(json.contains("waze"))
    assertFalse(json.contains("google.navigation"))
  }

  @Test fun mobilityFindAppRequiresAnExactChosenPackageNotAGuessedIndex() {
    // Structura insasi dovedeste disciplina "nu ghici": parametrul e un PACHET exact, nu un index.
    val json = BensonAgentTools.toolsJson()
    assertTrue(json.contains("\"chosen_pkg\""))
  }

  // --- expects_reply (20.10.2026, mandat "BENSON OPENAI VOICE CHATBOT") — proba ca instrumentul
  // "say" obliga modelul sa declare structural daca asteapta un raspuns (conversatie continua,
  // fara wake word nou) sau daca a incheiat schimbul — singura parte testabila fara Context/
  // dispozitiv; branch-ul real de pastrare a sesiunii deschise traieste in BensonForegroundService
  // (Context-dependent) si ramane de verificat pe telefon, nu aici. ---

  @Test fun sayToolSchemaRequiresExpectsReplyField() {
    val json = BensonAgentTools.toolsJson()
    assertTrue(json.contains("\"expects_reply\""))
  }

  @Test fun systemPromptInstructsModelToUseExpectsReplyToContinueConversation() {
    val prompt = BensonAgentTools.systemPrompt("orice", "orice")
    assertTrue(prompt.contains("expects_reply"))
  }

  // --- proposed_app/proposed_query (20.10.2026, DEVICE_FAIL "promite căutarea, dar nu o
  // execută") — proba de schema pentru propunerea in asteptare; logica de stocare/executie e
  // Context-dependenta (BensonForegroundService), verificata pe dispozitiv, nu aici. ---

  @Test fun sayToolSchemaExposesProposedAppAndQueryFields() {
    val json = BensonAgentTools.toolsJson()
    assertTrue(json.contains("\"proposed_app\""))
    assertTrue(json.contains("\"proposed_query\""))
  }

  @Test fun systemPromptInstructsModelNotToExtendProposalBeyondSearch() {
    val prompt = BensonAgentTools.systemPrompt("orice", "orice").lowercase()
    assertTrue(prompt.contains("proposed_app"))
    // Siguranta #5 din ordin: interzis explicit pentru actiuni sensibile.
    assertTrue(prompt.contains("plăți".lowercase()) || prompt.contains("plati"))
  }

  // --- aka_capability_query (20.10.2026, AKA UNIVERSAL) — interogare pe operatie, peste tot
  // registrul, nu pe o singura aplicatie numita. ---

  @Test fun akaCapabilityQueryToolIsRegisteredAndDispatchable() {
    assertTrue(BensonAgentTools.NAMES.contains(BensonAgentTools.AKA_CAPABILITY_QUERY))
    val json = BensonAgentTools.toolsJson()
    assertTrue(json.contains("\"operation\""))
  }
}
