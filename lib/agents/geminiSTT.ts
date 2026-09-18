import { File } from 'expo-file-system';
import { logAudioDiag } from 'benson-foreground-service';
import { fetchWithTimeout } from './fetchWithTimeout';

// Confirmed live 2026-08-25: a real transcription request hung for 75+ seconds with zero log
// output, freezing the whole conversation loop — see fetchWithTimeout.ts's doc comment. 20s is
// generous for a short voice-command clip (real successful calls this session completed in a
// few seconds) while still being far shorter than "the app looks permanently dead."
const REQUEST_TIMEOUT_MS = 20000;

// Gemini cloud transcription — a second, free-tier cloud STT option alongside openaiSTT.ts,
// added 2026-08-24 (product-owner-directed): local Whisper (base or small, plain or beam+prompt)
// was tried in four configurations today and none reached usable accuracy on this device/voice,
// and OpenAI's cloud STT (openaiSTT.ts) is blocked on unconfigured billing (confirmed live: every
// request returns 429). Gemini's API has a genuine free tier (no card required to generate a key
// in Google AI Studio) and Google's own Gemini app was confirmed live on this exact phone to
// transcribe/understand the same kind of Romanian commands correctly on the first try — this is
// the same underlying speech understanding, reached through the API instead of the app.
// gemini-3.7-flash — confirmed live 2026-08-25, the hard way: gemini-2.5-flash, gemini-2.0-flash,
// AND gemini-1.5-flash all 404'd against the user's real API key with Google's own error body
// saying "models/X is not found for API version v1beta, or is not supported for generateContent"
// — these older model generations have apparently been fully retired by this date (not a key/
// billing/project issue — a deliberately invalid key correctly returns 400 INVALID_ARGUMENT, not
// 404, ruling that out). Current live API documentation (fetched today) lists gemini-3.7-flash as
// the current stable Flash model — using that instead of guessing at another older name.
const GEMINI_MODEL = 'gemini-3.7-flash';

const TRANSCRIBE_PROMPT: Record<string, string> = {
  ro: 'Transcrie EXACT ce se aude în această înregistrare audio, în limba română. Răspunde DOAR cu textul transcris, fără explicații, fără ghilimele, fără introducere.',
  en: 'Transcribe EXACTLY what is said in this audio recording, in English. Reply with ONLY the transcribed text — no explanation, no quotes, no preamble.',
  de: 'Transkribiere GENAU, was in dieser Audioaufnahme gesagt wird, auf Deutsch. Antworte NUR mit dem transkribierten Text — keine Erklärung, keine Anführungszeichen, keine Einleitung.',
};

export async function transcribeWithGemini(wavFilePath: string, apiKey: string, lang: string): Promise<string> {
  const langCode = lang.split('-')[0].toLowerCase();
  const uri = wavFilePath.startsWith('file://') ? wavFilePath : `file://${wavFilePath}`;
  const file = new File(uri);
  const base64Audio = await file.base64();

  const res = await fetchWithTimeout(
    `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent`,
    {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-goog-api-key': apiKey,
      },
      body: JSON.stringify({
        contents: [{
          parts: [
            { text: TRANSCRIBE_PROMPT[langCode] ?? TRANSCRIBE_PROMPT.en },
            { inlineData: { mimeType: 'audio/wav', data: base64Audio } },
          ],
        }],
      }),
    },
    REQUEST_TIMEOUT_MS,
  );
  if (!res.ok) {
    // Diagnostic-only (product-owner-directed 2026-08-25): a 404 here was ambiguous — could mean
    // wrong model ID, "Generative Language API" not enabled for this key's project, or something
    // else entirely — and guessing at model names one rebuild at a time wastes cycles. Logging
    // Google's own error body (truncated) gives the real reason on the next attempt instead.
    const bodyText = await res.text().catch(() => '');
    const retryAfter = res.headers.get('retry-after');
    logAudioDiag('GEMINI_STT_ERROR_BODY', `status=${res.status} model=${GEMINI_MODEL} retryAfter=${retryAfter ?? 'none'} body=${bodyText.slice(0, 400)}`);
    throw new Error(`Gemini transcription failed: ${res.status}${retryAfter ? ` retryAfterSec=${retryAfter}` : ''}`);
  }
  const data = await res.json();
  const text: string | undefined = data.candidates?.[0]?.content?.parts?.[0]?.text;
  return (text ?? '').trim();
}
