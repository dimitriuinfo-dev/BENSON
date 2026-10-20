package expo.modules.accessibility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// AKA — teste pe fixturi SINTETICE, deliberat NU Calculator/Ceas/Calendar/WhatsApp (ordinul
// 20.10.2026, punctul 7: "Demonstreaza generalizarea"). Trei aplicatii IMAGINARE, cu stiluri de
// navigare diferite — tab-uri, buton flotant + lista, meniu de setari — ca sa dovedeasca ca
// mecanismul foloseste doar STRUCTURA (clickable/scrollable/bounds), nu nume sau etichete
// cunoscute dinainte.
class AkaKnowledgeTest {

  private fun el(
    index: Int, label: String, clickable: Boolean = true, scrollable: Boolean = false,
    bounds: String = "0,0,100,100", editable: Boolean = false,
  ) = ScreenElement(
    index = index, role = ElementRole.BUTTON, label = label, bounds = bounds,
    clickable = clickable, editable = editable, scrollable = scrollable, enabled = true,
    focused = false, redacted = false, safety = ButtonSafetyClassifier.Verdict.FREE,
  )

  // --- Aplicatie imaginara 1: "NotiFast" — notite, cu lista derulabila + buton flotant + tab-uri ---

  private val notiFastScreen = listOf(
    el(0, "Note", clickable = false, bounds = "0,0,1080,100"),
    el(1, "Lista notite", clickable = true, scrollable = true, bounds = "0,100,1080,2000"),
    el(2, "Cumparaturi de weekend", clickable = true, bounds = "40,120,1040,300"), // RAND din lista
    el(3, "Idei pentru vacanta", clickable = true, bounds = "40,320,1040,500"),   // RAND din lista
    el(4, "Notita noua", clickable = true, bounds = "900,2100,1040,2240"),        // buton flotant
    el(5, "Notite", clickable = true, bounds = "0,2300,360,2450"),                // tab
    el(6, "Cos de gunoi", clickable = true, bounds = "360,2300,720,2450"),        // tab
    el(7, "Setari", clickable = true, bounds = "720,2300,1080,2450"),             // tab
  )

  @Test fun notiFastExcludesListRowsButKeepsFloatingButtonAndTabs() {
    val candidates = AkaKnowledge.discoverCandidateFunctions(notiFastScreen).map { it.label }
    assertFalse(candidates.contains("Cumparaturi de weekend"))
    assertFalse(candidates.contains("Idei pentru vacanta"))
    assertTrue(candidates.contains("Notita noua"))
    assertTrue(candidates.contains("Notite"))
    assertTrue(candidates.contains("Cos de gunoi"))
    assertTrue(candidates.contains("Setari"))
  }

  // --- Aplicatie imaginara 2: "SkyNow" — meteo, lista orara derulabila + tab-uri sus + camp de cautare ---

  private val skyNowScreen = listOf(
    el(0, "Cauta oras", clickable = false, editable = true, bounds = "0,0,1080,150"),
    el(1, "Astazi", clickable = true, bounds = "0,150,360,300"),
    el(2, "Saptamana", clickable = true, bounds = "360,150,720,300"),
    el(3, "Radar", clickable = true, bounds = "720,150,1080,300"),
    el(4, "Prognoza orara", clickable = true, scrollable = true, bounds = "0,300,1080,2200"),
    el(5, "14:00 · 21°C", clickable = true, bounds = "40,320,1040,480"),   // RAND din lista orara
    el(6, "15:00 · 20°C", clickable = true, bounds = "40,500,1040,660"),  // RAND din lista orara
  )

  @Test fun skyNowExcludesHourlyRowsButKeepsTopTabsAndSearchField() {
    val candidates = AkaKnowledge.discoverCandidateFunctions(skyNowScreen).map { it.label }
    assertFalse(candidates.contains("14:00 · 21°C"))
    assertFalse(candidates.contains("15:00 · 20°C"))
    assertTrue(candidates.contains("Astazi"))
    assertTrue(candidates.contains("Saptamana"))
    assertTrue(candidates.contains("Radar"))
  }

  // --- Aplicatie imaginara 3: "ConfigPanel" — meniu de setari, randuri clickabile FARA lista derulabila ---
  // (multe aplicatii de setari pun randurile direct intr-un LinearLayout, fara scrollable=true pe
  // un container explicit) — aici fiecare rand E o functie reala (navigheaza spre un sub-ecran),
  // deci TREBUIE sa fie recunoscut ca functie candidata, nu exclus.

  private val configPanelScreen = listOf(
    el(0, "Wi-Fi", clickable = true, bounds = "0,100,1080,250"),
    el(1, "Bluetooth", clickable = true, bounds = "0,250,1080,400"),
    el(2, "Afisare", clickable = true, bounds = "0,400,1080,550"),
  )

  @Test fun configPanelTreatsPlainMenuRowsAsCandidateFunctionsWhenNoScrollableContainerExists() {
    val candidates = AkaKnowledge.discoverCandidateFunctions(configPanelScreen).map { it.label }
    assertEquals(listOf("Wi-Fi", "Bluetooth", "Afisare"), candidates)
  }

  // --- Reguli generale, independente de orice aplicatie ---

  @Test fun nonClickableElementIsNeverACandidateFunction() {
    val root = listOf(el(0, "Titlu static", clickable = false))
    assertTrue(AkaKnowledge.discoverCandidateFunctions(root).isEmpty())
  }

  @Test fun blankLabelIsNeverACandidateFunctionEvenIfClickable() {
    val root = listOf(el(0, "", clickable = true))
    assertTrue(AkaKnowledge.discoverCandidateFunctions(root).isEmpty())
  }

  // --- ScreenSignature — amprenta STRUCTURALA, nu de continut ---

  @Test fun sameStructureProducesTheSameSignatureEvenWhenDynamicContentDiffers() {
    // Simuleaza DOUA observari ale ACELUIASI ecran SkyNow, la ore diferite — continutul orar
    // (randurile din lista) difera, dar tab-urile/structura raman identice.
    val laterObservation = skyNowScreen.map {
      if (it.label.startsWith("14:") || it.label.startsWith("15:")) it.copy(label = "16:00 · 19°C") else it
    }
    val sigA = AkaKnowledge.ScreenSignature.compute("com.example.skynow", skyNowScreen)
    val sigB = AkaKnowledge.ScreenSignature.compute("com.example.skynow", laterObservation)
    assertEquals(sigA, sigB)
  }

  @Test fun structurallyDifferentScreenProducesADifferentSignature() {
    val sigSkyNow = AkaKnowledge.ScreenSignature.compute("com.example.skynow", skyNowScreen)
    val sigNotiFast = AkaKnowledge.ScreenSignature.compute("com.example.notifast", notiFastScreen)
    assertTrue(sigSkyNow != sigNotiFast)
  }

  @Test fun signatureIncludesThePackageNameSoTwoAppsNeverCollideByAccident() {
    val sigA = AkaKnowledge.ScreenSignature.compute("com.app.a", configPanelScreen)
    val sigB = AkaKnowledge.ScreenSignature.compute("com.app.b", configPanelScreen)
    assertTrue(sigA.startsWith("com.app.a:"))
    assertTrue(sigB.startsWith("com.app.b:"))
    assertTrue(sigA != sigB)
  }

  @Test fun addingARealNewCandidateFunctionChangesTheSignature() {
    val withExtraTab = skyNowScreen + el(7, "Alerte", clickable = true, bounds = "0,2200,1080,2350")
    val sigBefore = AkaKnowledge.ScreenSignature.compute("com.example.skynow", skyNowScreen)
    val sigAfter = AkaKnowledge.ScreenSignature.compute("com.example.skynow", withExtraTab)
    assertTrue(sigBefore != sigAfter)
  }
}
