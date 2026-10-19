package expo.modules.foregroundservice

// SOS KWS — modul IZOLAT (decizie KWS, 19.10.2026). NU modifica HeedWakeWord.kt sau
// BensonForegroundService.kt — asta vine abia la integrarea aprobata separat, cu checkpoint Git
// si validare pe telefon. Aici doar logica de DECIZIE a declansarii, pura, identica in forma cu
// cea deja folosita de HEED (consecutive_frames + refractory_seconds, confirmat din
// heed_candidate.json SI din wake.json al candidatului AJUTOR — aceleasi doua campuri, aceleasi
// valori implicite: 2 cadre consecutive, 0.7s de refractie).
//
// Interfata comuna ceruta ("un singur proprietar de microfon, doua modele independente"): HEED
// ar calcula caracteristicile mel O SINGURA DATA per cadru de 100ms (identice intre cele doua
// modele - confirmat din wake.json: aceiasi n_mels/n_fft/hop_length/preprocessing), le trimite la
// AMBELE sesiuni ONNX (Benson + AJUTOR), iar fiecare scor trece prin propria poarta de declansare
// de mai jos. Niciun model nu detine microfonul; ambele consuma acelasi flux de cadre.
//
// Nu poate fi testat JUnit pentru PARTEA de inferenta ONNX (onnxruntime-android e artefact AAR,
// indisponibil in JVM simplu - aceeasi limita documentata deja la PersonalMemoryStorage.kt). Dar
// decizia de declansare de mai jos NU depinde de ONNX - e aritmetica pura pe scoruri, testabila
// acum, fara dispozitiv.

data class SosTriggerConfig(
  val threshold: Float,
  val consecutiveFrames: Int,
  val refractoryMs: Long,
)

class SosTriggerGate(private val config: SosTriggerConfig) {
  private var aboveCount = 0
  // null = "niciodata declansat inca" - FIX: o santinela Long (ex. Long.MIN_VALUE) comparata prin
  // scadere directa (nowMs - lastTriggerAtMs) face overflow pe PRIMUL apel (nowMs - Long.MIN_VALUE
  // depaseste intervalul Long), blocand poarta permanent in "refractorie" din start — defect real
  // gasit de SosTriggerGateTest (5/7 teste cadeau), nu doar o eroare de calcul in test.
  private var lastTriggerAtMs: Long? = null

  // Un cadru = un scor ONNX pentru acel cadru audio de 100ms, la momentul nowMs. Intoarce true
  // DOAR in cadrul exact in care declansarea devine valida (nu la fiecare cadru cat ramane peste
  // prag) - apelantul actioneaza o singura data per declansare, exact ca la HEED.
  fun onScore(score: Float, nowMs: Long): Boolean {
    val sinceLastTrigger = lastTriggerAtMs?.let { nowMs - it }
    if (sinceLastTrigger != null && sinceLastTrigger < config.refractoryMs) {
      // In refractie: nu acumuleaza si nu declanseaza, dar nici nu "pierde" un scor real sub prag
      // ca sa nu creeze o secventa falsa quando refractia se termina chiar la mijlocul unei voci.
      aboveCount = 0
      return false
    }
    if (score > config.threshold) {
      aboveCount += 1
      if (aboveCount >= config.consecutiveFrames) {
        aboveCount = 0
        lastTriggerAtMs = nowMs
        return true
      }
      return false
    }
    aboveCount = 0
    return false
  }

  fun reset() {
    aboveCount = 0
    lastTriggerAtMs = null
  }
}
