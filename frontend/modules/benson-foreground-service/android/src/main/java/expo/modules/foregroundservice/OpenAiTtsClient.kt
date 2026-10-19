package expo.modules.foregroundservice

import android.content.Context
import android.media.MediaPlayer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

// BENSON NEXT — client REAL OpenAI TTS (19.10.2026, "ONE VOICE, OPENAI FIRST"). Cod complet,
// NEINVOCAT cu o cheie reala in aceasta sesiune: o sinteza reala costa bani pe un serviciu
// neutilizat pana acum in acest fel (TTS, nu doar STT/chat) — mandatul BENSON BUILD AUTOPILOT
// exclude explicit "servicii noi contra cost" din autonomia implicita. Reutilizeaza acelasi tipar
// HTTP deja dovedit (postToOpenAiTranscribe, BensonForegroundService.kt: timeout explicit, acelasi
// OkHttpClient style) si ACELASI OpenAiUsageMeter (buget zilnic, nu un contor nou) — zero sistem
// paralel de costuri.
// Context-dependent -> fara test JUnit (acelasi motiv ca StaffRegistry.kt/PersonalMemoryStorage.kt).
internal class OpenAiTtsClient(
  private val apiKey: String,
  private val voiceId: String = DEFAULT_VOICE_ID,
  private val model: String = "gpt-4o-mini-tts",
  private val baseUrl: String = "https://api.openai.com/v1",
) {
  companion object {
    const val DEFAULT_VOICE_ID = "marin" // o singura identitate configurata - ONE VOICE
    // Placeholder explicit, NU pretul oficial OpenAI - de confirmat din documentatia reala inainte
    // de prima facturare, acelasi principiu ca restul OpenAiUsageMeter: "niciun numar inventat".
    const val ESTIMATED_USD_PER_CHARACTER = 0.000015
  }

  private val http = OkHttpClient.Builder()
    .callTimeout(15, TimeUnit.SECONDS)
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

  fun estimatedCostUsd(text: String): Double = text.length * ESTIMATED_USD_PER_CHARACTER

  // Controlul costurilor: verifica bugetul EXISTENT (OpenAiUsageMeter, acelasi folosit de STT/
  // brain) inainte de orice apel real - nu un plafon nou, separat. Apelantul (integrarea reala,
  // neinceputa aici) trebuie sa cheme aceasta verificare inainte de synthesize().
  fun isWithinBudget(context: Context): Boolean = !OpenAiUsageMeter.isBudgetExceeded(context)

  // Sinteza REALA — NEINVOCATA in aceasta sesiune (fara cheie reala, fara autorizare explicita de
  // cost). Intoarce bytes audio (MP3) sau null la orice esec, niciodata o exceptie nereceptionata
  // (acelasi tipar ca postToOpenAiTranscribe). Apelantul inregistreaza costul REAL (din header-ele
  // raspunsului, daca exista, altfel estimatedCostUsd) prin OpenAiUsageMeter.recordUsage - nefacut
  // automat aici ca sa nu ascunda costul intr-o functie "muta".
  fun synthesize(text: String): ByteArray? {
    val body = JSONObject()
      .put("model", model)
      .put("voice", voiceId)
      .put("input", text)
      .toString()
      .toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
      .url("$baseUrl/audio/speech")
      .addHeader("Authorization", "Bearer $apiKey")
      .post(body)
      .build()
    return try {
      http.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return null
        resp.body?.bytes()
      }
    } catch (_: Exception) {
      null
    }
  }
}

// Redarea audio — MediaPlayer, acelasi mecanism standard Android folosit oriunde in acest proiect
// pentru playback local (nicio dependinta noua). Fisier temporar in cacheDir, sters dupa redare.
internal class TtsAudioPlayer(private val context: Context) {
  private var player: MediaPlayer? = null

  fun play(audioBytes: ByteArray, onDone: () -> Unit) {
    stop()
    val tempFile = File.createTempFile("benson_tts_", ".mp3", context.cacheDir)
    tempFile.writeBytes(audioBytes)
    val mp = MediaPlayer()
    player = mp
    try {
      mp.setDataSource(tempFile.absolutePath)
      mp.setOnCompletionListener {
        onDone()
        try { tempFile.delete() } catch (_: Exception) {}
      }
      mp.prepare()
      mp.start()
    } catch (_: Exception) {
      try { tempFile.delete() } catch (_: Exception) {}
      onDone()
    }
  }

  fun stop() {
    try { player?.stop(); player?.release() } catch (_: Exception) {}
    player = null
  }
}
