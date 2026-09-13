// OpenAI cloud transcription — optional, opt-in replacement for the on-device Whisper engine
// (localWhisperEngine.ts). Added 2026-08-24 (product-owner-directed): confirmed live that local
// Whisper has two unresolved, seemingly opposed problems on this device — "base" mistranscribes
// plain commands constantly, "small" is accurate but takes 100+ seconds once BENSON is
// backgrounded (the OS throttles this process's CPU hard once its Activity isn't visible, and
// "small"'s much larger compute cost turns that into a multi-minute stall). A cloud API call
// doesn't touch the phone's own CPU at all — it's a network upload, unaffected by that throttling
// — and gpt-4o-mini-transcribe is a meaningfully stronger model than either local option. Used
// only when the user has an OpenAI key configured (they already pay for ChatGPT as of this same
// session), gated at the call site (voiceAgent.ts) with a silent fallback to local Whisper on any
// failure (no key, no network, request error) so this is additive, never a new single point of
// failure.
import { fetchWithTimeout } from './fetchWithTimeout';

// See fetchWithTimeout.ts and geminiSTT.ts's identical constant — a plain fetch() with no timeout
// can hang forever on a stalled connection, silently freezing the whole conversation loop with no
// error and no fallback ever triggering.
const REQUEST_TIMEOUT_MS = 20000;

const WHISPER_PROMPT: Record<string, string> = {
  ro: 'Benson, deschide WhatsApp și sună-l pe Hannah. Navighează spre aeroport pe Waze. Trimite un mesaj pe WhatsApp. Deschide YouTube și caută o melodie.',
  en: 'Benson, open WhatsApp and call Hannah. Navigate to the airport on Waze. Send a WhatsApp message. Open YouTube and search for a song.',
  de: 'Benson, öffne WhatsApp und rufe Hannah an. Navigiere zum Flughafen mit Waze. Sende eine WhatsApp-Nachricht.',
};

export async function transcribeWithOpenAI(wavFilePath: string, apiKey: string, lang: string): Promise<string> {
  const langCode = lang.split('-')[0].toLowerCase();
  const uri = wavFilePath.startsWith('file://') ? wavFilePath : `file://${wavFilePath}`;
  const form = new FormData();
  // React Native's fetch recognizes this {uri,name,type} shape and streams the file directly —
  // it also sets the multipart Content-Type/boundary header itself; do not set it manually.
  form.append('file', { uri, name: 'audio.wav', type: 'audio/wav' } as unknown as Blob);
  form.append('model', 'gpt-4o-mini-transcribe');
  form.append('language', langCode);
  form.append('prompt', WHISPER_PROMPT[langCode] ?? WHISPER_PROMPT.en);

  const res = await fetchWithTimeout('https://api.openai.com/v1/audio/transcriptions', {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}` },
    body: form,
  }, REQUEST_TIMEOUT_MS);
  if (!res.ok) throw new Error(`OpenAI transcription failed: ${res.status}`);
  const data = await res.json();
  return (typeof data.text === 'string' ? data.text : '').trim();
}
