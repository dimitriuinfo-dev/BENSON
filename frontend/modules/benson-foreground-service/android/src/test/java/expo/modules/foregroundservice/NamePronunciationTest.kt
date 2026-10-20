package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

// FIX_NAME_PRONUNCE_1 — cazul raportat de Master: "has-a-ne-ne-a-has" in loc de "Hannah".
class NamePronunciationTest {

  @Test fun hannahBecomesPronounceable() {
    assertEquals("Hana", NamePronunciation.speakableName("Hannah"))
  }

  // CAUZA RADACINA a plangerii Masterului, dovedita de numarul de litere auzite: un cuvant
  // integral MAJUSCUL e citit de TTS ca acronim, litera cu litera. Headerul WhatsApp da "HANNAH".
  // Forma rostita nu are voie sa fie niciodata integral majuscula.
  @Test fun uppercaseFromContactHeaderIsNeverSpelledOut() {
    assertEquals("Hana", NamePronunciation.speakableName("HANNAH"))
  }

  @Test fun noOutputIsAllUppercase() {
    for (n in listOf("HANNAH", "MAMA", "BABY", "INGRID", "MÜLLER")) {
      val spoken = NamePronunciation.speakableName(n)
      assertFalse("'$spoken' ar fi silabisit de TTS", spoken == spoken.uppercase() && spoken.length > 1)
    }
  }

  @Test fun doubledConsonantsCollapse() {
    assertEquals("Ana", NamePronunciation.speakableName("Anna"))
    assertEquals("Emil", NamePronunciation.speakableName("Emill"))
  }

  @Test fun foreignClustersMapToRomanianSounds() {
    assertEquals("Filip", NamePronunciation.speakableName("Phillip"))
    assertEquals("Tomas", NamePronunciation.speakableName("Thomass"))
  }

  @Test fun wBecomesV() {
    assertEquals("Valter", NamePronunciation.speakableName("Walter"))
  }

  @Test fun romanianNamesAreLeftIntact() {
    assertEquals("Ioana", NamePronunciation.speakableName("Ioana"))
    assertEquals("Mihai", NamePronunciation.speakableName("Mihai"))
    assertEquals("Rares", NamePronunciation.speakableName("Rares"))
  }

  @Test fun multiWordNamesHandledPerWord() {
    assertEquals("Hana Mulan", NamePronunciation.speakableName("Hannah Mullan"))
  }

  @Test fun trailingHAfterConsonantIsKept() {
    // Regula taie "h"-ul final doar dupa VOCALA ("Hannah" -> "Hana"). Dupa consoana, "h" se
    // pronunta, deci rămâne. (Testul initial folosea "Vukovih", care are vocala inainte de h —
    // asteptarea mea era greșită, nu codul.)
    assertEquals("Baruch", NamePronunciation.speakableName("Baruch"))
  }

  @Test fun trailingHAfterVowelIsDropped() {
    assertEquals("Sara", NamePronunciation.speakableName("Sarah"))
    assertEquals("Nica", NamePronunciation.speakableName("Nicah"))
  }

  @Test fun emptyAndBlankAreSafe() {
    assertEquals("", NamePronunciation.speakableName(""))
    assertEquals("", NamePronunciation.speakableName("   ").trim())
  }

  // Garantia esentiala: forma rostita NU se foloseste la potrivire, dar oricum nu trebuie sa
  // produca ceva gol sau cu cifre, care ar suna si mai rau.
  @Test fun neverProducesEmptyForRealName() {
    for (n in listOf("Hannah", "Baby", "Müller", "Jean-Luc", "O'Brien")) {
      assertFalse(NamePronunciation.speakableName(n).isBlank())
    }
  }
}
