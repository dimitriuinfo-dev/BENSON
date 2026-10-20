package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// LEGEA PERSONALULUI BENSON — teste pe regulile pure (fara dispozitiv, fara Context).
class StaffRegistryCoreTest {

  private fun member(
    pkg: String,
    name: String = pkg,
    versionName: String = "1.0",
    versionCode: Long = 1L,
    firstInstall: Long = 100L,
  ) = StaffMember(
    packageName = pkg,
    displayName = name,
    category = "",
    launcher = true,
    firstInstallTime = firstInstall,
    lastUpdateTime = firstInstall,
    versionName = versionName,
    versionCode = versionCode,
  )

  // --- Nivelurile de capabilitate (P1.2): verified NU se inventeaza ---

  @Test fun whatsappSeedsVerifiedFromProvenBehaviors() {
    val caps = StaffRegistryCore.initialCaps("com.whatsapp")
    assertEquals(CapLevel.VERIFIED, caps[StaffRegistryCore.CAP_CALL])
    assertEquals(CapLevel.VERIFIED, caps[StaffRegistryCore.CAP_VIDEO_CALL])
  }

  // CLAUDE.md declara Spotify explicit PARTIAL ("prototip functional partial dovedit"), deci
  // legea il tine la known. Daca cineva il promoveaza la verified fara test pe telefon, cade aici.
  @Test fun spotifyStaysKnownNeverVerified() {
    val caps = StaffRegistryCore.initialCaps("com.spotify.music")
    assertEquals(CapLevel.KNOWN, caps[StaffRegistryCore.CAP_MEDIA_SEARCH])
    assertFalse(caps.values.contains(CapLevel.VERIFIED))
  }

  @Test fun unknownAppHasNoCapabilities() {
    assertTrue(StaffRegistryCore.initialCaps("com.example.habar.nam").isEmpty())
  }

  // --- M4: increderea scade dupa update ---

  @Test fun updateDemotesVerifiedToReverifyButKeepsKnown() {
    val caps = mapOf(
      StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED,
      StaffRegistryCore.CAP_MESSAGE to CapLevel.KNOWN,
    )
    val after = StaffRegistryCore.demoteAfterUpdate(caps)
    assertEquals(CapLevel.REVERIFY, after[StaffRegistryCore.CAP_CALL])
    assertEquals(CapLevel.KNOWN, after[StaffRegistryCore.CAP_MESSAGE])
  }

  @Test fun reconcileDemotesVerifiedOnVersionChange() {
    val old = listOf(member("com.whatsapp").copy(caps = mapOf(StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED)))
    val fresh = listOf(member("com.whatsapp", versionName = "2.0", versionCode = 2L))
    val out = StaffRegistryCore.reconcile(old, fresh)
    assertEquals(CapLevel.REVERIFY, out.single().caps[StaffRegistryCore.CAP_CALL])
  }

  @Test fun reconcileKeepsVerifiedWhenVersionUnchanged() {
    val old = listOf(member("com.whatsapp").copy(caps = mapOf(StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED)))
    val out = StaffRegistryCore.reconcile(old, listOf(member("com.whatsapp")))
    assertEquals(CapLevel.VERIFIED, out.single().caps[StaffRegistryCore.CAP_CALL])
  }

  // --- M3: miscari de personal ---

  @Test fun diffDetectsArrivalDepartureAndUpdate() {
    val old = listOf(member("a"), member("b"))
    val fresh = listOf(member("a", versionName = "2.0", versionCode = 2L), member("c"))
    val events = StaffRegistryCore.diff(old, fresh)
    assertEquals(StaffEventKind.ACTUALIZAT, events.first { it.packageName == "a" }.kind)
    assertEquals(StaffEventKind.PLECAT, events.first { it.packageName == "b" }.kind)
    assertEquals(StaffEventKind.SOSIT, events.first { it.packageName == "c" }.kind)
  }

  // M4: "Plecat → fisa se arhiveaza, nu se sterge (revine la reinstalare cu istoricul)."
  @Test fun departedMemberIsArchivedNotDeleted() {
    val old = listOf(member("b").copy(caps = mapOf(StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED)))
    val out = StaffRegistryCore.reconcile(old, emptyList())
    assertEquals(1, out.size)
    assertTrue(out.single().archived)
    assertEquals(CapLevel.VERIFIED, out.single().caps[StaffRegistryCore.CAP_CALL])
  }

  @Test fun reinstallRestoresArchivedDossierAndFirstInstallTime() {
    val archived = member("b", firstInstall = 50L).copy(archived = true)
    val out = StaffRegistryCore.reconcile(listOf(archived), listOf(member("b", firstInstall = 999L)))
    assertFalse(out.single().archived)
    assertEquals(50L, out.single().firstInstallTime)
  }

  @Test fun versionHistoryIsCappedAtTen() {
    var state = listOf(member("a", versionName = "1", versionCode = 1L))
    for (i in 2..15) {
      state = StaffRegistryCore.reconcile(state, listOf(member("a", versionName = "$i", versionCode = i.toLong())))
    }
    assertEquals(StaffRegistryCore.MAX_VERSION_HISTORY, state.single().versionHistory.size)
    assertEquals("15", state.single().versionHistory.last())
  }

  // --- Garda de vizibilitate (bug dovedit 14.10.2026) ---

  @Test fun filteredEnumerationIsRejectedAgainstRicherPreviousState() {
    assertFalse(StaffRegistryCore.isPlausibleEnumeration(freshCount = 49, previousActiveCount = 274))
  }

  @Test fun firstRunOnCleanPhoneIsAccepted() {
    assertTrue(StaffRegistryCore.isPlausibleEnumeration(freshCount = 12, previousActiveCount = 0))
  }

  @Test fun fullEnumerationIsAlwaysAccepted() {
    assertTrue(StaffRegistryCore.isPlausibleEnumeration(freshCount = 850, previousActiveCount = 274))
  }

  // --- Restrictii (doctrina 3 + P1.2) ---

  @Test fun bankingIsRefused() {
    assertTrue(StaffRegistryCore.isRefused("com.revolut.revolut", ""))
    assertTrue(StaffRegistryCore.isRefused("com.whatever.app", "banking"))
  }

  @Test fun ordinaryAppIsNotRefused() {
    assertFalse(StaffRegistryCore.isRefused("com.google.android.youtube", ""))
  }

  @Test fun irreversibleCapabilitiesRequireConfirmation() {
    assertEquals(CapRisk.CONFIRM, StaffRegistryCore.riskOf(StaffRegistryCore.CAP_MESSAGE))
    assertEquals(CapRisk.CONFIRM, StaffRegistryCore.riskOf(StaffRegistryCore.CAP_CALL))
    assertEquals(CapRisk.NONE, StaffRegistryCore.riskOf(StaffRegistryCore.CAP_NAVIGATE))
  }

  // O capabilitate necunoscuta e tratata ca riscanta, nu ca libera.
  @Test fun unknownCapabilityDefaultsToConfirm() {
    assertEquals(CapRisk.CONFIRM, StaffRegistryCore.riskOf("stergere_totala"))
  }

  // --- Persistenta ---

  @Test fun serializeDeserializeRoundTrip() {
    val list = listOf(
      member("com.whatsapp", name = "WhatsApp").copy(
        caps = mapOf(StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED, StaffRegistryCore.CAP_MESSAGE to CapLevel.KNOWN),
        versionHistory = listOf("1.0", "2.0"),
      ),
      member("com.waze", name = "Waze").copy(archived = true),
    )
    val back = StaffRegistryCore.deserialize(StaffRegistryCore.serialize(list))
    assertEquals(list, back)
  }

  @Test fun nameWithTabOrNewlineDoesNotCorruptStorage() {
    val list = listOf(member("com.x", name = "Rau\tscris\nde tot"))
    val back = StaffRegistryCore.deserialize(StaffRegistryCore.serialize(list))
    assertEquals(1, back.size)
    assertEquals("com.x", back.single().packageName)
    assertEquals("Rau scris de tot", back.single().displayName)
  }

  @Test fun unknownFormatYieldsEmptyRegistryNotCrash() {
    assertTrue(StaffRegistryCore.deserialize("v99\ngarbage").isEmpty())
    assertTrue(StaffRegistryCore.deserialize(null).isEmpty())
    assertTrue(StaffRegistryCore.deserialize("").isEmpty())
  }

  // --- Numaratoarea pentru linia de log REGISTRY ---

  @Test fun countsIgnoreArchivedAndCountLevels() {
    val list = listOf(
      member("com.whatsapp").copy(caps = mapOf(StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED)),
      member("com.spotify.music").copy(caps = mapOf(StaffRegistryCore.CAP_MEDIA_SEARCH to CapLevel.KNOWN)),
      member("com.nimic"),
      member("com.plecat").copy(archived = true, caps = mapOf(StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED)),
    )
    val (total, known, verified) = StaffRegistryCore.counts(list)
    assertEquals(3, total)
    assertEquals(2, known)
    assertEquals(1, verified)
  }

  @Test fun promoteVerifiedLiftsOnlyTheNamedCapability() {
    val caps = mapOf(
      StaffRegistryCore.CAP_MEDIA_SEARCH to CapLevel.REVERIFY,
      StaffRegistryCore.CAP_MEDIA_CONTROL to CapLevel.REVERIFY,
    )
    val after = StaffRegistryCore.promoteVerified(caps, StaffRegistryCore.CAP_MEDIA_SEARCH)
    assertEquals(CapLevel.VERIFIED, after[StaffRegistryCore.CAP_MEDIA_SEARCH])
    assertEquals(CapLevel.REVERIFY, after[StaffRegistryCore.CAP_MEDIA_CONTROL])
    assertNull(after["inexistent"])
  }

  // --- AKA V1 (20.10.2026), CORECTAT: ui_control:<eticheta> e o cheie SPECIFICA per control, nu
  // un flag generic pe toata aplicatia — un singur buton verificat nu demonstreaza toate
  // functiile. Se promoveaza pe orice pachet, inclusiv unul complet necunoscut tabelului KNOWN
  // (generalizare, nu doar Calculator), in ACELASI map `caps` (niciun registru nou). ---

  @Test fun uiControlCapabilityIdIsSpecificToTheControlNotGenericPerApp() {
    assertEquals("ui_control:7", StaffRegistryCore.uiControlCapabilityId("7"))
    assertEquals("ui_control:cauta", StaffRegistryCore.uiControlCapabilityId("Caută"))
    // Doua controale diferite ale ACELEIASI aplicatii produc chei DIFERITE — dovada ca a verifica
    // unul nu "demonstreaza" automat celalalt.
    assertTrue(StaffRegistryCore.uiControlCapabilityId("7") != StaffRegistryCore.uiControlCapabilityId("8"))
  }

  @Test fun uiControlCapabilityCarriesNoRiskRegardlessOfWhichControl() {
    // Apasarea insasi e verificata de ButtonSafetyClassifier la momentul actiunii; aceasta
    // capabilitate doar inregistreaza ca EXECUTOR+SURVIVOR au confirmat o apasare reala o data.
    assertEquals(CapRisk.NONE, StaffRegistryCore.riskOf(StaffRegistryCore.uiControlCapabilityId("7")))
    assertEquals(CapRisk.NONE, StaffRegistryCore.riskOf(StaffRegistryCore.uiControlCapabilityId("cauta")))
  }

  @Test fun uiControlCapabilityCanBePromotedOnAnAppWithNoKnownCapabilitiesAtAll() {
    val caps = StaffRegistryCore.initialCaps("com.un.app.complet.necunoscuta") // gol - nu e in KNOWN
    assertTrue(caps.isEmpty())
    val id = StaffRegistryCore.uiControlCapabilityId("setari rapide")
    val after = StaffRegistryCore.promoteVerified(caps, id)
    assertEquals(CapLevel.VERIFIED, after[id])
  }

  @Test fun verifyingOneControlDoesNotPromoteADifferentControlOfTheSameApp() {
    val caps = StaffRegistryCore.promoteVerified(emptyMap(), StaffRegistryCore.uiControlCapabilityId("7"))
    assertNull(caps[StaffRegistryCore.uiControlCapabilityId("8")])
  }

  @Test fun uiControlCapabilityIsAlsoDemotedAfterAnAppUpdateLikeAnyOther() {
    // M4 e deja generic (mapValues peste TOATE capabilitatile) — proba explicita ca noua familie
    // de capabilitati nu e un caz special scutit de reverificare dupa update.
    val id = StaffRegistryCore.uiControlCapabilityId("7")
    val caps = mapOf(id to CapLevel.VERIFIED)
    val after = StaffRegistryCore.demoteAfterUpdate(caps)
    assertEquals(CapLevel.REVERIFY, after[id])
  }

  @Test fun formatKnownUiControlsListsOnlyVerifiedOnesForThisApp() {
    val caps = mapOf(
      StaffRegistryCore.uiControlCapabilityId("7") to CapLevel.VERIFIED,
      StaffRegistryCore.uiControlCapabilityId("cauta") to CapLevel.REVERIFY, // inca neconfirmat din nou
      StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED, // nu e ui_control, nu apare aici
    )
    val text = StaffRegistryCore.formatKnownUiControls(caps)
    assertTrue(text != null && text.contains("7"))
    assertTrue(text != null && !text.contains("cauta"))
  }

  @Test fun formatKnownUiControlsIsNullWhenNothingVerifiedYet() {
    assertNull(StaffRegistryCore.formatKnownUiControls(emptyMap()))
  }

  // FIX_AKA_CAP_KEY_COLON_1 (20.10.2026, CAUZA REALA gasita pe dispozitiv real, nu presupusa):
  // scrierea unui "ui_control:5" reusea (REGISTRY_EVENT logat), dar imediat dupa un
  // StaffRegistry.invalidate() (ce forteaza un reload din serializare), capsOf intorcea GOL — o
  // reincarcare reala (exact ce testeaza punctul 4 - persistenta peste un restart) ar fi pierdut
  // tacit toata cunostinta AKA. Cauza: deserialize impartea pe ':' presupunand exact 2 bucati;
  // "ui_control:5:VERIFIED" producea 3. Testul round-trip existent (serializeDeserializeRoundTrip)
  // nu prindea asta pentru ca folosea doar chei SIMPLE (CAP_CALL), fara ':' in ele.
  @Test fun serializeDeserializeRoundTripSurvivesACapabilityKeyContainingAColon() {
    val id = StaffRegistryCore.uiControlCapabilityId("5")
    val list = listOf(member("com.oneplus.calculator").copy(caps = mapOf(id to CapLevel.VERIFIED)))
    val out = StaffRegistryCore.deserialize(StaffRegistryCore.serialize(list))
    assertEquals(CapLevel.VERIFIED, out.single().caps[id])
  }

  // --- attachKnownUiControls — CONTRACTUL COMPLET (20.10.2026, cerinta explicita: "verifica local
  // contractul complet prin care LIVE primeste si utilizeaza cunostintele persistate", nu doar
  // formatarea izolat). Aceasta e EXACT compunerea pe care runAgentToolLocked o aplica inainte sa
  // trimita textul observe_screen catre model — testata aici fara Context, pe datele reale deja
  // dovedite pe dispozitiv (ui_control:5, ui_control:9 pentru Calculator). ---

  @Test fun attachKnownUiControlsAppendsTheAkaLineWhenSomethingIsVerified() {
    val caps = mapOf(
      StaffRegistryCore.uiControlCapabilityId("5") to CapLevel.VERIFIED,
      StaffRegistryCore.uiControlCapabilityId("9") to CapLevel.VERIFIED,
    )
    val result = StaffRegistryCore.attachKnownUiControls("[0] button \"5\"\n[1] button \"9\"", caps)
    assertTrue(result.startsWith("[0] button \"5\"\n[1] button \"9\"\n"))
    assertTrue(result.contains("Cunoștințe AKA anterioare"))
    assertTrue(result.contains("5") && result.contains("9"))
  }

  // Zero regresie (cerinta implicita a punctului 3+4): un ecran/aplicatie FARA istoric AKA
  // (Calculator/WhatsApp/Waze inainte de aceasta runda, sau orice aplicatie noua) trebuie sa
  // primeasca EXACT textul original — nicio linie noua, niciun comportament schimbat.
  @Test fun attachKnownUiControlsLeavesTextUnchangedWhenNothingIsVerifiedYet() {
    val original = "[0] button \"AC\"\n[1] button \"7\""
    assertEquals(original, StaffRegistryCore.attachKnownUiControls(original, emptyMap()))
  }

  @Test fun attachKnownUiControlsIgnoresReverifyNotYetConfirmedAgain() {
    // Un update al aplicatiei retrogradeaza VERIFIED -> REVERIFY (M4) — textul NU trebuie sa
    // afirme in continuare ca e "deja verificat" pentru ceva care asteapta reconfirmare.
    val original = "ecran"
    val caps = mapOf(StaffRegistryCore.uiControlCapabilityId("5") to CapLevel.REVERIFY)
    assertEquals(original, StaffRegistryCore.attachKnownUiControls(original, caps))
  }

  // Punctul 4 (20.10.2026): cunostinta per-control NU se confunda cu "intelegerea functiilor
  // aplicatiei" — un CAP_* numit (functie) si un ui_control:* (control specific) sunt randuri
  // SEPARATE in ACELASI map, fara promovare automata intre ele. Verificarea unui control nu
  // promoveaza, prin ea insasi, nicio capabilitate numita.
  @Test fun verifyingAUiControlNeverPromotesANamedCapabilityByItself() {
    val caps = StaffRegistryCore.promoteVerified(emptyMap(), StaffRegistryCore.uiControlCapabilityId("5"))
    assertNull(caps[StaffRegistryCore.CAP_CALCULATE]) // nicio promovare incrucisata, implicita
  }

  @Test fun attachKnownUiControlsNeverListsNamedCapabilitiesAsIfTheyWereControls() {
    // CAP_CALL e o capabilitate NUMITA (functie), nu un control specific — nu trebuie sa apara
    // in lista "cunostinte despre controale", care e strict despre ui_control:*.
    val caps = mapOf(StaffRegistryCore.CAP_CALL to CapLevel.VERIFIED)
    assertEquals("ecran", StaffRegistryCore.attachKnownUiControls("ecran", caps))
  }

  // --- AKA — cunoasterea FUNCTIILOR si a NAVIGATIEI (20.10.2026, corectie arhitecturala: "AKA nu
  // e memorarea apasarilor, e cunoasterea autonoma a aplicatiilor"). Semnaturi de ecran FICTIVE
  // ("ecranA"/"ecranB"), deliberat fara nicio aplicatie reala in nume — proba ca mecanismul e
  // generic, nu legat de Calculator/Ceas/Calendar/WhatsApp. ---

  @Test fun akaFunctionCapabilityIdIsScopedToOneScreenNotTheWholeApp() {
    val idA = StaffRegistryCore.akaFunctionCapabilityId("ecranA", "Setări")
    val idB = StaffRegistryCore.akaFunctionCapabilityId("ecranB", "Setări")
    assertTrue(idA != idB) // ACEEASI eticheta, ecrane DIFERITE -> dovezi SEPARATE
  }

  @Test fun formatKnownFunctionsSeparatesDiscoveredFromVerified() {
    // Punctul 2 explicit: nu confunda "am vazut-o" cu "am apasat-o si a mers".
    val sig = "ecranA"
    val caps = mapOf(
      StaffRegistryCore.akaFunctionCapabilityId(sig, "Wi-Fi") to CapLevel.VERIFIED,
      StaffRegistryCore.akaFunctionCapabilityId(sig, "Bluetooth") to CapLevel.KNOWN,
    )
    val text = StaffRegistryCore.formatKnownFunctions(caps, sig)!!
    // Cheia normalizeaza eticheta (acelasi tratament ca ui_control:, deja stabilit) - verificam
    // forma normalizata, nu originalul cu majuscule.
    assertTrue(text.contains("confirmate prin apăsare reală") && text.contains("wi-fi"))
    assertTrue(text.contains("descoperite, neconfirmate") && text.contains("bluetooth"))
  }

  @Test fun formatKnownFunctionsIsScopedToTheRequestedScreenOnly() {
    val caps = mapOf(StaffRegistryCore.akaFunctionCapabilityId("ecranA", "X") to CapLevel.VERIFIED)
    assertNull(StaffRegistryCore.formatKnownFunctions(caps, "ecranB"))
  }

  @Test fun formatNavigationOptionsListsOnlyVerifiedEdgesFromTheGivenScreen() {
    val caps = mapOf(
      StaffRegistryCore.akaNavigationCapabilityId("ecranA", "Setări", "ecranB") to CapLevel.VERIFIED,
      StaffRegistryCore.akaNavigationCapabilityId("ecranA", "Ajutor", "ecranC") to CapLevel.KNOWN, // neconfirmat
    )
    val text = StaffRegistryCore.formatNavigationOptions(caps, "ecranA")!!
    assertTrue(text.contains("setari")) // normalizat: fara diacritice, minuscule
    assertFalse(text.contains("ajutor")) // KNOWN, nu VERIFIED - nu se afirma ca traseu confirmat
  }

  @Test fun formatNavigationOptionsDoesNotLeakEdgesFromAnotherScreen() {
    val caps = mapOf(StaffRegistryCore.akaNavigationCapabilityId("ecranX", "A", "ecranY") to CapLevel.VERIFIED)
    assertNull(StaffRegistryCore.formatNavigationOptions(caps, "ecranA"))
  }

  @Test fun akaFunctionAndNavigationCapabilitiesCarryNoRisk() {
    assertEquals(CapRisk.NONE, StaffRegistryCore.riskOf(StaffRegistryCore.akaFunctionCapabilityId("ecranA", "X")))
    assertEquals(CapRisk.NONE, StaffRegistryCore.riskOf(StaffRegistryCore.akaNavigationCapabilityId("ecranA", "X", "ecranB")))
  }

  @Test fun attachKnownUiControlsIncludesFunctionAndNavigationKnowledgeWhenSignatureGiven() {
    val sig = "ecranA"
    val caps = mapOf(
      StaffRegistryCore.akaFunctionCapabilityId(sig, "Wi-Fi") to CapLevel.VERIFIED,
      StaffRegistryCore.akaNavigationCapabilityId(sig, "Wi-Fi", "ecranB") to CapLevel.VERIFIED,
    )
    val result = StaffRegistryCore.attachKnownUiControls("digest original", caps, sig)
    assertTrue(result.startsWith("digest original\n"))
    assertTrue(result.contains("wi-fi"))
    assertTrue(result.contains("Trasee de navigare"))
  }

  @Test fun attachKnownUiControlsWithNoSignatureKeepsOldBehaviorUnchanged() {
    // Zero regresie: apelul fara `screenSignature` (folosit de orice cod vechi) se comporta
    // IDENTIC cu inainte de aceasta extensie.
    val caps = mapOf(StaffRegistryCore.akaFunctionCapabilityId("ecranA", "X") to CapLevel.VERIFIED)
    assertEquals("text", StaffRegistryCore.attachKnownUiControls("text", caps))
  }

  // --- Persistenta/invalidare/recuperare pentru aka_fn/aka_nav (ordin 20.10.2026, criteriul 9:
  // "teste de persistenta, invalidare, recuperare si regresie") — cheile aka_fn/aka_nav au MAI
  // MULTE ':' decat ui_control (semnatura de ecran insasi contine un ':') — exact tipul de cheie
  // care a cauzat FIX_AKA_CAP_KEY_COLON_1; testat explicit aici, nu doar presupus reparat. ---

  @Test fun serializeDeserializeRoundTripSurvivesFunctionAndNavigationKeysWithManyColons() {
    val fnId = StaffRegistryCore.akaFunctionCapabilityId("com.example.app:a3f2", "wi-fi")
    val navId = StaffRegistryCore.akaNavigationCapabilityId("com.example.app:a3f2", "wi-fi", "com.example.app:b891")
    val list = listOf(member("com.example.app").copy(caps = mapOf(fnId to CapLevel.VERIFIED, navId to CapLevel.VERIFIED)))
    val out = StaffRegistryCore.deserialize(StaffRegistryCore.serialize(list)).single()
    assertEquals(CapLevel.VERIFIED, out.caps[fnId])
    assertEquals(CapLevel.VERIFIED, out.caps[navId])
  }

  // FIX_AKA_CAP_LABEL_COMMA_1 (20.10.2026, CAUZA REALA gasita pe dispozitiv real — Ceas OnePlus,
  // butonul de meniu suplimentar): eticheta REALA era "Weitere Optionen,Neue Benachrichtigungen"
  // (virgula vine din contentDescription-ul Android insusi) — `serialize()` desparte
  // capabilitatile pe ',', deci cheia se rupea in doua la scriere, iar jumatatea a doua aparea
  // ca o capabilitate NUMITA falsa la citire. Acelasi tipar ca bug-ul cu ':', alt delimitator.
  @Test fun capabilityKeyWithACommaInTheOriginalLabelSurvivesSerializeDeserializeIntact() {
    val fnId = StaffRegistryCore.akaFunctionCapabilityId("com.example.app:a3f2", "Weitere Optionen,Neue Benachrichtigungen")
    val list = listOf(member("com.example.app").copy(caps = mapOf(fnId to CapLevel.VERIFIED)))
    val out = StaffRegistryCore.deserialize(StaffRegistryCore.serialize(list)).single()
    assertEquals(CapLevel.VERIFIED, out.caps[fnId])
    // Niciun fragment nu trebuie sa apara ca o capabilitate NUMITA separata, falsa.
    assertNull(out.caps["neue benachrichtigungen"])
  }

  @Test fun akaFunctionAndNavigationCapabilitiesAreDemotedAfterAppUpdateLikeAnyOther() {
    // Invalidare la schimbare de versiune (M4, deja generic) — proba explicita pentru noile familii.
    val fnId = StaffRegistryCore.akaFunctionCapabilityId("ecranA", "X")
    val navId = StaffRegistryCore.akaNavigationCapabilityId("ecranA", "X", "ecranB")
    val after = StaffRegistryCore.demoteAfterUpdate(mapOf(fnId to CapLevel.VERIFIED, navId to CapLevel.VERIFIED))
    assertEquals(CapLevel.REVERIFY, after[fnId])
    assertEquals(CapLevel.REVERIFY, after[navId])
  }

  @Test fun akaKnowledgeSurvivesReconcileWhenAppVersionIsUnchanged() {
    // Recuperare: un restart/reconciliere cu ACEEASI versiune nu trebuie sa stearga nimic.
    val fnId = StaffRegistryCore.akaFunctionCapabilityId("ecranA", "X")
    val old = listOf(member("com.example.app").copy(caps = mapOf(fnId to CapLevel.VERIFIED)))
    val fresh = listOf(member("com.example.app")) // aceeasi versiune (default "1.0"/1L din helper member())
    val out = StaffRegistryCore.reconcile(old, fresh)
    assertEquals(CapLevel.VERIFIED, out.single().caps[fnId])
  }

  // --- formatAppKnowledgeSummary — "LIVE solicita informatii despre o aplicatie... identifica
  // lipsurile" (sectiunea "Colaborarea cu LIVE", 20.10.2026) ---

  @Test fun appKnowledgeSummaryReportsNoKnowledgeHonestlyWhenNeverExplored() {
    val text = StaffRegistryCore.formatAppKnowledgeSummary(member("com.example.unexplored", name = "AplicatieNeexplorata"))
    assertTrue(text.contains("nu a fost explorată"))
  }

  @Test fun appKnowledgeSummaryAggregatesAcrossScreensButOnlyForThisPackage() {
    val pkg = "com.example.app"
    val caps = mapOf(
      StaffRegistryCore.akaFunctionCapabilityId("$pkg:screen1", "a") to CapLevel.VERIFIED,
      StaffRegistryCore.akaFunctionCapabilityId("$pkg:screen2", "b") to CapLevel.KNOWN,
      StaffRegistryCore.akaNavigationCapabilityId("$pkg:screen1", "a", "$pkg:screen2") to CapLevel.VERIFIED,
      // Dovada de pe UN ALT pachet, cu o semnatura care contine ACELASI nume de pachet ca substring
      // accidental — nu trebuie sa se scurga in rezumatul lui `pkg`.
      StaffRegistryCore.akaFunctionCapabilityId("com.example.appOTHER:screen1", "c") to CapLevel.VERIFIED,
    )
    val text = StaffRegistryCore.formatAppKnowledgeSummary(member(pkg, name = "App").copy(caps = caps))
    assertTrue(text.contains("1 funcții de interfață confirmate"))
    assertTrue(text.contains("1 descoperite"))
    assertTrue(text.contains("1 trasee de navigare confirmate"))
  }

  @Test fun appKnowledgeSummaryIncludesNamedCapabilitiesSeparatelyFromDiscoveredOnes() {
    val caps = mapOf(StaffRegistryCore.CAP_CALCULATE to CapLevel.VERIFIED)
    val text = StaffRegistryCore.formatAppKnowledgeSummary(member("com.oneplus.calculator", name = "Calculator").copy(caps = caps))
    assertTrue(text.contains("funcții numite") && text.contains(StaffRegistryCore.CAP_CALCULATE))
  }

  // --- downgradeToReverify — SURVIVOR etapa 3: "solicita AKA reidentificarea functiilor cand
  // interfata s-a modificat", declansat de o dovada REALA de esec, nu de o presupunere. ---

  @Test fun downgradeToReverifyLowersAVerifiedCapabilityButNothingElse() {
    val id = StaffRegistryCore.uiControlCapabilityId("5")
    val other = StaffRegistryCore.uiControlCapabilityId("9")
    val caps = mapOf(id to CapLevel.VERIFIED, other to CapLevel.VERIFIED)
    val after = StaffRegistryCore.downgradeToReverify(caps, id)
    assertEquals(CapLevel.REVERIFY, after[id])
    assertEquals(CapLevel.VERIFIED, after[other]) // alta dovada, neatinsa
  }

  @Test fun downgradeToReverifyDoesNothingToACapabilityThatWasOnlyKnownNotVerified() {
    // Nu era o certitudine oricum - nimic de "stricat".
    val id = StaffRegistryCore.uiControlCapabilityId("5")
    val caps = mapOf(id to CapLevel.KNOWN)
    assertEquals(caps, StaffRegistryCore.downgradeToReverify(caps, id))
  }

  @Test fun downgradeToReverifyIsANoOpWhenTheCapabilityDoesNotExistAtAll() {
    assertEquals(emptyMap<String, CapLevel>(), StaffRegistryCore.downgradeToReverify(emptyMap(), "ui_control:necunoscut"))
  }
}
