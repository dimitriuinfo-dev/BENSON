package expo.modules.foregroundservice

// FIX_TTS_VOICE_CACHE_1 (19.10.2026, P0 — "accent indian" dupa comenzi tastate). Logica
// DECIZIONALA pura din spatele ensureNativeTtsVoiceCurrent() (BensonForegroundService.kt) —
// orchestrarea efectiva (apeluri reale catre android.speech.tts.TextToSpeech) ramane acolo,
// netestabila JUnit (Context-dependent, acelasi motiv ca StaffRegistry.kt/PersonalMemoryStorage.kt
// din aceasta sesiune). Aici doar REGULILE: cand se reincearca, ce stare rezulta, ce voce se alege.
//
// CAUZA ORIGINALA (gasita, nu presupusa): vechiul cod seta `lastBoundTtsEngine` INAINTE de a sti
// daca selectia a reusit, apoi sarea peste orice reselectie cat motorul ramanea neschimbat — o
// alegere gresita facuta o singura data (ex. inainte ca motorul sa-si fi incarcat vocile ro-RO)
// ramanea legata pentru tot restul procesului. Tiparea imediat dupa pornire e cazul cel mai expus
// la aceasta cursa (fereastra mai scurta pana la prima rostire decat o comanda vocala reala).

enum class TtsVoiceState { NOT_INITIALIZED, VOICES_NOT_READY, CONFIRMED, SELECTION_FAILED }

data class TtsVoiceBinding(
  val engine: String?,
  val state: TtsVoiceState,
  val voiceName: String? = null,
  val language: String? = null,
)

data class TtsVoiceCandidate(val name: String, val language: String, val country: String = "")

object TtsVoiceSelector {
  const val MAX_RETRIES = 3
  const val MIN_RETRY_INTERVAL_MS = 2_000L

  // FIX_MULTILINGUAL_TTS_1 (21.10.2026, "INVESTOR READY VOICE") — cascada veche (ro -> de -> en-GB
  // -> en) ramane REZERVA, neschimbata; `preferredLanguage` (limba REPLICII curente, detectata din
  // text — vezi detectTextLanguage) e incercata ÎNTÂI, ca o conversatie germana/engleza sa nu mai
  // fie fortata pe o voce romaneasca. Nicio voce in limba preferata pe acest motor -> cascada veche
  // decide, exact ca inainte (zero regresie cand preferredLanguage e null sau indisponibil).
  fun pickBest(voices: List<TtsVoiceCandidate>, preferredLanguage: String? = null): TtsVoiceCandidate? {
    fun pick(lang: String, country: String? = null) = voices
      .filter { it.language == lang && (country == null || it.country == country) }
      .let { pool -> pool.firstOrNull { !it.name.contains("-network") } ?: pool.firstOrNull() }
    val preferred = preferredLanguage?.let { pick(it) }
    return preferred ?: pick("ro") ?: pick("de") ?: pick("en", "GB") ?: pick("en")
  }

  // Semnale TARI, specifice (diacritice ro/de) verificate ÎNTÂI; cuvinte-reper engleze NEAMBIGUE
  // (nu apar in romana/germana curenta) abia apoi — un text romanesc fara diacritice (foarte comun
  // in STT/raspunsuri scurte) nu trebuie sa fie clasificat gresit ca engleza doar pentru ca e ASCII.
  // null = nesigur -> apelantul pastreaza vocea curenta (nicio schimbare tacita).
  private val ENGLISH_MARKER_WORDS = setOf(
    "the", "you", "your", "please", "sure", "here", "what", "how", "yes", "okay", "ok",
    "will", "i'll", "i'm", "hello", "hi", "good", "morning", "thanks", "thank",
  )

  // FIX_MULTILINGUAL_TTS_2 (21.10.2026, DOVADA PE TELEFON) — CAUZA REALA: "Master, 2 plus 2
  // ergibt 4." (germana corecta a modelului) nu are NICIUN umlaut, deci cadea pe null, iar vocea
  // RAMANEA cea engleza de la replica anterioara (shouldAttemptSelection nu reselecteaza pe
  // desiredLanguage=null) — exact bug-ul "nu mai prioritiza româna" reaparut invers, pe engleza
  // ramasa blocata. Cuvinte-functie germane, foarte frecvente, fara coliziune cu ro/en.
  private val GERMAN_MARKER_WORDS = setOf(
    "ich", "und", "ist", "das", "mit", "nicht", "sehr", "heute", "morgen", "bitte", "danke",
    "guten", "wie", "kann", "ergibt", "ja", "nein",
  )

  fun detectTextLanguage(text: String): String? {
    if (text.any { it in "äöüÄÖÜß" }) return "de"
    if (text.any { it in "ăâîșțĂÂÎȚȘ" }) return "ro"
    val words = text.lowercase().split(Regex("[^a-z']+")).filter { it.isNotBlank() }
    if (words.any { it in GERMAN_MARKER_WORDS }) return "de"
    return if (words.any { it in ENGLISH_MARKER_WORDS }) "en" else null
  }

  // Cerinta 8: motorul s-a schimbat (Setari) -> totul se reia de la zero, inclusiv retry-urile.
  fun resetForNewEngine(engine: String?): Pair<TtsVoiceBinding, Int> =
    TtsVoiceBinding(engine, TtsVoiceState.NOT_INITIALIZED) to 0

  // Cerinta 1+4: o selectie CONFIRMATA nu se reincearca la fiecare rostire (cache-ul ramane, dar
  // DOAR dupa succes real). Una esuata/neincarcata se reincearca, dar plafonat (MAX_RETRIES) si nu
  // mai des decat MIN_RETRY_INTERVAL_MS — fara bucla infinita, fara reincercare la fiecare replica.
  // FIX_MULTILINGUAL_TTS_1 — `desiredLanguage` fortează o reselecție chiar pe o legătură deja
  // CONFIRMATĂ, DOAR când diferă de limba efectiv legată acum (conversația a trecut de la
  // română la germană/engleză, de exemplu) — niciodată pe fiecare replică (desiredLanguage==
  // binding.language sare direct la cache-ul existent, comportament identic cu înainte).
  fun shouldAttemptSelection(
    binding: TtsVoiceBinding,
    retryCount: Int,
    nowMs: Long,
    lastAttemptAtMs: Long,
    desiredLanguage: String? = null,
  ): Boolean {
    if (binding.state == TtsVoiceState.CONFIRMED) {
      return desiredLanguage != null && binding.language != null && desiredLanguage != binding.language
    }
    if (retryCount >= MAX_RETRIES) return false
    if (nowMs - lastAttemptAtMs < MIN_RETRY_INTERVAL_MS) return false
    return true
  }

  // Cerinta 2+3: separa explicit "voci inca neincarcate" de "selectie incercata si esuata", si
  // cere CONFIRMAREA reala (codurile de rezultat + vocea efectiv legata), nu doar atribuirea.
  fun classifyAttempt(
    voicesAvailable: List<TtsVoiceCandidate>?,
    chosen: TtsVoiceCandidate?,
    setLanguageSucceeded: Boolean,
    setVoiceSucceeded: Boolean,
    actuallyBoundVoiceName: String?,
  ): TtsVoiceState = when {
    voicesAvailable.isNullOrEmpty() -> TtsVoiceState.VOICES_NOT_READY
    chosen == null -> TtsVoiceState.SELECTION_FAILED
    setLanguageSucceeded && setVoiceSucceeded && actuallyBoundVoiceName == chosen.name -> TtsVoiceState.CONFIRMED
    else -> TtsVoiceState.SELECTION_FAILED
  }

  // Cerinta 5+6: se vorbeste CU VOCE doar pe o stare CONFIRMATA pentru motorul curent — niciodata
  // pe o stare esuata/neincarcata, care ar insemna sa lasam motorul sa aleaga singur (exact
  // mecanismul care a produs vocea nedorita en-IN).
  fun isSafeToSpeakAudio(binding: TtsVoiceBinding): Boolean = binding.state == TtsVoiceState.CONFIRMED

  // Cerinta 7: comenzile tastate (origin="debug_inject") respecta preferinta de redare audio a
  // utilizatorului; fara activare explicita, raspunsul ramane text. Orice alt origine (wake,
  // follow_up, headset_button) nu e afectata — neschimbat.
  fun shouldSpeakAudioForOrigin(origin: String, debugInjectAudioEnabled: Boolean): Boolean =
    origin != "debug_inject" || debugInjectAudioEnabled
}
