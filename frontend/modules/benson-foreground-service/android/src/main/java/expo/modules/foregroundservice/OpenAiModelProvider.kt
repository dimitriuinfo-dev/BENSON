package expo.modules.foregroundservice

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// BENSON NEXT — ModelProvider REAL (19.10.2026), reutilizand tiparul deja dovedit in LEGACY
// (brainTurn(), BensonForegroundService.kt): un singur apel HTTP, acelasi model gpt-5-nano,
// acelasi buget existent (OpenAiUsageMeter — nu un contor nou). Cheia vine ca parametru de
// constructor (din SharedPreferences la integrarea reala, ca in LEGACY) — NICIODATA hardcodata in
// sursa. NEINVOCAT cu o cheie reala in aceasta sesiune — fara apel platit neautorizat.
internal class OpenAiModelProvider(
  private val apiKey: String,
  private val baseUrl: String = "https://api.openai.com/v1",
  private val model: String = "gpt-5-nano",
) : ModelProvider {
  override val providerId = "openai:$model"

  private val http = OkHttpClient.Builder()
    .callTimeout(25, TimeUnit.SECONDS)
    .connectTimeout(25, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .build()

  override fun converse(turns: List<ConversationTurn>, knownActions: Set<String>): BrainOutput {
    val messages = JSONArray()
    for (turn in turns) {
      // Cele trei canale (D0) raman distincte in TEXTUL trimis (marcate explicit), nu promovate
      // silentios la rol "system" in API-ul OpenAI — doar USER_VOICE are autoritate reala.
      val role = if (turn.channel == Channel.SYSTEM) "system" else "user"
      messages.put(JSONObject().put("role", role).put("content", turn.text))
    }
    val body = JSONObject().put("model", model).put("messages", messages)
      .toString().toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
      .url("$baseUrl/chat/completions")
      .addHeader("Authorization", "Bearer $apiKey")
      .post(body)
      .build()
    return try {
      http.newCall(request).execute().use { resp ->
        if (!resp.isSuccessful) return BrainOutput.Failure("http_${resp.code}")
        val json = resp.body?.string() ?: return BrainOutput.Failure("empty_response")
        val content = JSONObject(json).optJSONArray("choices")
          ?.optJSONObject(0)?.optJSONObject("message")?.optString("content")
        if (content.isNullOrBlank()) BrainOutput.Failure("empty_content") else parseContent(content, knownActions)
      }
    } catch (e: Exception) {
      BrainOutput.Failure(e.javaClass.simpleName)
    }
  }

  // Parsarea raspunsului - testabila separat, fara retea (nu are nevoie de Context/HTTP).
  internal fun parseContent(content: String, knownActions: Set<String>): BrainOutput {
    val obj = try { JSONObject(content) } catch (_: Exception) { return BrainOutput.Reply(content, "ro") }
    return when (obj.optString("type")) {
      "chat" -> BrainOutput.Reply(obj.optString("reply"), obj.optString("language", "ro"))
      "clarify" -> BrainOutput.Clarify(obj.optString("question"), obj.optString("language", "ro"))
      "command" -> {
        val action = obj.optString("action")
        // LLM-ul interpreteaza, Core valideaza (corectia D0) - un action in afara vocabularului
        // cunoscut nu devine niciodata o ActionRequest "oarba".
        if (action !in knownActions) BrainOutput.Failure("unknown_action:$action")
        else BrainOutput.ActionRequest(action, emptyList(), SemanticConfidence.MEDIUM, obj.optString("language", "ro"))
      }
      else -> BrainOutput.Reply(content, "ro")
    }
  }
}
