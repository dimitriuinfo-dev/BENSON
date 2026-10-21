package expo.modules.foregroundservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TtsVoiceSelectorTest {

  private fun voice(name: String, lang: String, country: String = "") = TtsVoiceCandidate(name, lang, country)

  // --- pickBest: cascada ro -> de -> en-GB -> en, preferand non-network ---

  @Test fun picksRomanianWhenAvailable() {
    val voices = listOf(voice("en-US-local", "en", "US"), voice("ro-RO-local", "ro", "RO"))
    assertEquals("ro-RO-local", TtsVoiceSelector.pickBest(voices)?.name)
  }

  @Test fun fallsBackThroughCascadeWhenNoRomanian() {
    val voices = listOf(voice("de-DE-local", "de", "DE"), voice("en-GB-local", "en", "GB"))
    assertEquals("de-DE-local", TtsVoiceSelector.pickBest(voices)?.name)
  }

  @Test fun prefersNonNetworkVoiceOverNetwork() {
    val voices = listOf(voice("ro-RO-network", "ro", "RO"), voice("ro-RO-local", "ro", "RO"))
    assertEquals("ro-RO-local", TtsVoiceSelector.pickBest(voices)?.name)
  }

  @Test fun noVoicesAtAllReturnsNull() {
    assertNull(TtsVoiceSelector.pickBest(emptyList()))
  }

  @Test fun noMatchingLanguageAtAllReturnsNull() {
    val voices = listOf(voice("fr-FR-local", "fr", "FR"))
    assertNull(TtsVoiceSelector.pickBest(voices))
  }

  // --- pickBest(preferredLanguage) — FIX_MULTILINGUAL_TTS_1: vocea urmeaza limba replicii ---

  @Test fun preferredLanguageWinsOverTheRomanianCascadeDefault() {
    val voices = listOf(voice("ro-RO-local", "ro", "RO"), voice("de-DE-local", "de", "DE"))
    assertEquals("de-DE-local", TtsVoiceSelector.pickBest(voices, preferredLanguage = "de")?.name)
  }

  @Test fun preferredLanguageFallsBackToCascadeWhenNotAvailable() {
    val voices = listOf(voice("ro-RO-local", "ro", "RO"))
    assertEquals("ro-RO-local", TtsVoiceSelector.pickBest(voices, preferredLanguage = "en")?.name)
  }

  @Test fun nullPreferredLanguageBehavesExactlyLikeBefore() {
    val voices = listOf(voice("de-DE-local", "de", "DE"))
    assertEquals("de-DE-local", TtsVoiceSelector.pickBest(voices, preferredLanguage = null)?.name)
  }

  // --- detectTextLanguage — diacritice TARI intai, cuvinte-reper engleze neambigue abia apoi ---

  @Test fun germanDiacriticsAreDetectedAsGerman() {
    assertEquals("de", TtsVoiceSelector.detectTextLanguage("Könnte ich dir heute helfen?"))
  }

  @Test fun plainGermanWithoutUmlautsIsStillDetectedViaMarkerWords() {
    // FIX_MULTILINGUAL_TTS_2, dovada pe telefon: "Master, 2 plus 2 ergibt 4." (germana corecta,
    // fara niciun umlaut) ramanea pe vocea engleza anterioara fara cuvinte-reper germane.
    assertEquals("de", TtsVoiceSelector.detectTextLanguage("Guten Morgen, wie kann ich dir helfen?"))
    assertEquals("de", TtsVoiceSelector.detectTextLanguage("Master, 2 plus 2 ergibt 4."))
  }

  @Test fun romanianDiacriticsAreDetectedAsRomanian() {
    assertEquals("ro", TtsVoiceSelector.detectTextLanguage("Bună dimineața, cu ce te pot ajuta?"))
  }

  @Test fun unambiguousEnglishMarkerWordsAreDetectedAsEnglish() {
    assertEquals("en", TtsVoiceSelector.detectTextLanguage("Sure, here is what I can do for you."))
  }

  @Test fun plainRomanianWithoutDiacriticsIsNotMisreadAsEnglish() {
    // Caz real frecvent: raspuns romanesc fara diacritice (STT/text simplu) — NU trebuie clasificat
    // gresit ca engleza doar pentru ca e ASCII.
    assertNull(TtsVoiceSelector.detectTextLanguage("Nu am inteles comanda, repeta te rog"))
  }

  @Test fun ambiguousShortTextReturnsNullInsteadOfGuessing() {
    assertNull(TtsVoiceSelector.detectTextLanguage("18"))
  }

  // --- shouldAttemptSelection(desiredLanguage) — reselecteaza DOAR cand limba difera de legatura CONFIRMATA ---

  @Test fun confirmedBindingStillSkipsWhenDesiredLanguageMatchesBoundLanguage() {
    val binding = TtsVoiceBinding("com.google.android.tts", TtsVoiceState.CONFIRMED, "ro-RO-local", "ro")
    assertFalse(
      TtsVoiceSelector.shouldAttemptSelection(binding, retryCount = 0, nowMs = 100_000L, lastAttemptAtMs = 0L, desiredLanguage = "ro"),
    )
  }

  @Test fun confirmedBindingReselectsWhenDesiredLanguageDiffersFromBoundLanguage() {
    val binding = TtsVoiceBinding("com.google.android.tts", TtsVoiceState.CONFIRMED, "ro-RO-local", "ro")
    assertTrue(
      TtsVoiceSelector.shouldAttemptSelection(binding, retryCount = 0, nowMs = 100_000L, lastAttemptAtMs = 0L, desiredLanguage = "de"),
    )
  }

  @Test fun confirmedBindingWithNullDesiredLanguageBehavesExactlyLikeBefore() {
    val binding = TtsVoiceBinding("com.google.android.tts", TtsVoiceState.CONFIRMED, "ro-RO-local", "ro")
    assertFalse(
      TtsVoiceSelector.shouldAttemptSelection(binding, retryCount = 0, nowMs = 100_000L, lastAttemptAtMs = 0L, desiredLanguage = null),
    )
  }

  // --- shouldAttemptSelection: cerinta 1+4 — confirmat nu se reincearca; esuat se reincearca plafonat ---

  @Test fun confirmedBindingNeverRetries() {
    val binding = TtsVoiceBinding("com.google.android.tts", TtsVoiceState.CONFIRMED, "ro-RO-local", "ro")
    assertFalse(TtsVoiceSelector.shouldAttemptSelection(binding, retryCount = 0, nowMs = 100_000L, lastAttemptAtMs = 0L))
  }

  @Test fun notReadyBindingRetriesAfterMinInterval() {
    val binding = TtsVoiceBinding("com.google.android.tts", TtsVoiceState.VOICES_NOT_READY)
    assertFalse(
      TtsVoiceSelector.shouldAttemptSelection(binding, retryCount = 1, nowMs = 1_000L, lastAttemptAtMs = 0L),
    ) // prea devreme (< MIN_RETRY_INTERVAL_MS)
    assertTrue(
      TtsVoiceSelector.shouldAttemptSelection(binding, retryCount = 1, nowMs = 3_000L, lastAttemptAtMs = 0L),
    ) // dupa interval, OK
  }

  @Test fun stopsRetryingAfterMaxRetries() {
    val binding = TtsVoiceBinding("com.google.android.tts", TtsVoiceState.SELECTION_FAILED)
    assertFalse(
      TtsVoiceSelector.shouldAttemptSelection(
        binding, retryCount = TtsVoiceSelector.MAX_RETRIES, nowMs = 1_000_000L, lastAttemptAtMs = 0L,
      ),
    )
  }

  // --- resetForNewEngine: cerinta 8 ---

  @Test fun engineChangeResetsStateAndRetryCount() {
    val (binding, retries) = TtsVoiceSelector.resetForNewEngine("com.other.tts")
    assertEquals(TtsVoiceState.NOT_INITIALIZED, binding.state)
    assertEquals("com.other.tts", binding.engine)
    assertEquals(0, retries)
  }

  // --- classifyAttempt: cerinta 2+3+5+6 — confirmare REALA, nu doar atribuire ---

  @Test fun emptyVoiceListMeansNotReadyNotFailed() {
    val state = TtsVoiceSelector.classifyAttempt(
      voicesAvailable = emptyList(), chosen = null,
      setLanguageSucceeded = false, setVoiceSucceeded = false, actuallyBoundVoiceName = null,
    )
    assertEquals(TtsVoiceState.VOICES_NOT_READY, state)
  }

  @Test fun nullVoiceListReadErrorMeansNotReady() {
    val state = TtsVoiceSelector.classifyAttempt(
      voicesAvailable = null, chosen = null,
      setLanguageSucceeded = false, setVoiceSucceeded = false, actuallyBoundVoiceName = null,
    )
    assertEquals(TtsVoiceState.VOICES_NOT_READY, state)
  }

  @Test fun voicesPresentButNoneMatchMeansSelectionFailed() {
    val state = TtsVoiceSelector.classifyAttempt(
      voicesAvailable = listOf(voice("fr-FR-local", "fr", "FR")), chosen = null,
      setLanguageSucceeded = false, setVoiceSucceeded = false, actuallyBoundVoiceName = null,
    )
    assertEquals(TtsVoiceState.SELECTION_FAILED, state)
  }

  @Test fun engineRefusingSetLanguageMeansSelectionFailedEvenWithAChosenVoice() {
    val chosen = voice("ro-RO-local", "ro", "RO")
    val state = TtsVoiceSelector.classifyAttempt(
      voicesAvailable = listOf(chosen), chosen = chosen,
      setLanguageSucceeded = false, setVoiceSucceeded = true, actuallyBoundVoiceName = chosen.name,
    )
    assertEquals(TtsVoiceState.SELECTION_FAILED, state)
  }

  @Test fun engineRefusingSetVoiceMeansSelectionFailed() {
    val chosen = voice("ro-RO-local", "ro", "RO")
    val state = TtsVoiceSelector.classifyAttempt(
      voicesAvailable = listOf(chosen), chosen = chosen,
      setLanguageSucceeded = true, setVoiceSucceeded = false, actuallyBoundVoiceName = chosen.name,
    )
    assertEquals(TtsVoiceState.SELECTION_FAILED, state)
  }

  @Test fun succeedingCallsButDifferentActuallyBoundVoiceMeansSelectionFailed() {
    // motorul a "acceptat" apelurile dar vocea efectiv legata NU e cea ceruta - nu se considera succes.
    val chosen = voice("ro-RO-local", "ro", "RO")
    val state = TtsVoiceSelector.classifyAttempt(
      voicesAvailable = listOf(chosen), chosen = chosen,
      setLanguageSucceeded = true, setVoiceSucceeded = true, actuallyBoundVoiceName = "en-IN-default",
    )
    assertEquals(TtsVoiceState.SELECTION_FAILED, state)
  }

  @Test fun fullyConfirmedOnlyWhenEverythingMatches() {
    val chosen = voice("ro-RO-local", "ro", "RO")
    val state = TtsVoiceSelector.classifyAttempt(
      voicesAvailable = listOf(chosen), chosen = chosen,
      setLanguageSucceeded = true, setVoiceSucceeded = true, actuallyBoundVoiceName = chosen.name,
    )
    assertEquals(TtsVoiceState.CONFIRMED, state)
  }

  // --- isSafeToSpeakAudio: cerinta 5+6 ---

  @Test fun onlyConfirmedStateIsSafeToSpeakAudio() {
    assertTrue(TtsVoiceSelector.isSafeToSpeakAudio(TtsVoiceBinding(null, TtsVoiceState.CONFIRMED)))
    assertFalse(TtsVoiceSelector.isSafeToSpeakAudio(TtsVoiceBinding(null, TtsVoiceState.NOT_INITIALIZED)))
    assertFalse(TtsVoiceSelector.isSafeToSpeakAudio(TtsVoiceBinding(null, TtsVoiceState.VOICES_NOT_READY)))
    assertFalse(TtsVoiceSelector.isSafeToSpeakAudio(TtsVoiceBinding(null, TtsVoiceState.SELECTION_FAILED)))
  }

  // --- shouldSpeakAudioForOrigin: cerinta 7 ---

  @Test fun typedOriginWithoutPreferenceStaysTextOnly() {
    assertFalse(TtsVoiceSelector.shouldSpeakAudioForOrigin("debug_inject", debugInjectAudioEnabled = false))
  }

  @Test fun typedOriginWithPreferenceEnabledSpeaksAudio() {
    assertTrue(TtsVoiceSelector.shouldSpeakAudioForOrigin("debug_inject", debugInjectAudioEnabled = true))
  }

  @Test fun voiceOriginsAlwaysSpeakRegardlessOfTypedPreference() {
    for (origin in listOf("wake", "follow_up", "headset_button")) {
      assertTrue(TtsVoiceSelector.shouldSpeakAudioForOrigin(origin, debugInjectAudioEnabled = false))
    }
  }
}
