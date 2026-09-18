import { Audio } from 'expo-av';
import { File, Paths } from 'expo-file-system';
import { setNormalAudioMode } from './audioMode';
import { fetchWithTimeout } from './fetchWithTimeout';
import { logAudioDiag } from 'benson-foreground-service';

// Gemini TTS — replaces the robotic on-device voice (product-owner-directed 2026-08-25: "vreau
// gemini... inclusiv felul de a vorbi"). Only the newer Interactions API documents TTS as of this
// date (classic models/{model}:generateContent has no documented speechConfig) — a preview
// surface, so this logs the full error body on any failure the same way geminiSTT.ts's model
// hunt did, instead of guessing blind through multiple rebuild cycles.
const GEMINI_TTS_MODEL = 'gemini-2.5-flash-preview-tts';
const REQUEST_TIMEOUT_MS = 20000;

let currentSound: Audio.Sound | null = null;
let audioModeSet = false;

async function ensurePlaybackAudioMode(): Promise<void> {
  if (audioModeSet) return;
  await setNormalAudioMode();
  audioModeSet = true;
}

// Builds a standard 16-bit PCM WAV header — Gemini TTS returns raw 24kHz mono PCM16 (per current
// docs), which expo-av's Audio.Sound cannot play directly without a container.
function buildWavHeader(dataSize: number, sampleRate: number): Uint8Array {
  const header = new ArrayBuffer(44);
  const view = new DataView(header);
  const writeStr = (offset: number, s: string) => { for (let i = 0; i < s.length; i++) view.setUint8(offset + i, s.charCodeAt(i)); };
  writeStr(0, 'RIFF');
  view.setUint32(4, 36 + dataSize, true);
  writeStr(8, 'WAVE');
  writeStr(12, 'fmt ');
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true); // block align
  view.setUint16(34, 16, true); // bits per sample
  writeStr(36, 'data');
  view.setUint32(40, dataSize, true);
  return new Uint8Array(header);
}

function base64ToBytes(base64: string): Uint8Array {
  const binary = atob(base64);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export async function speakWithGemini(
  text: string,
  apiKey: string,
  voice: string = 'Kore',
  onDone?: () => void,
): Promise<void> {
  const res = await fetchWithTimeout(
    'https://generativelanguage.googleapis.com/v1beta/interactions',
    {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'x-goog-api-key': apiKey },
      body: JSON.stringify({
        model: GEMINI_TTS_MODEL,
        input: text,
        response_format: { type: 'audio' },
        generation_config: { speech_config: [{ voice }] },
      }),
    },
    REQUEST_TIMEOUT_MS,
  );
  if (!res.ok) {
    const bodyText = await res.text().catch(() => '');
    logAudioDiag('GEMINI_TTS_ERROR_BODY', `status=${res.status} model=${GEMINI_TTS_MODEL} body=${bodyText.slice(0, 400)}`);
    throw new Error(`Gemini TTS failed: ${res.status}`);
  }
  const data = await res.json();
  // Confirmed live 2026-08-25/26: the request succeeds (status 200) and Google's own usage stats
  // confirm audio WAS generated (output_tokens_by_modality: audio), but the documented
  // `output_audio.data` path is wrong for this endpoint. Real path, read directly from a live
  // response body (not guessed): `steps[0].content[0].data` — base64 PCM. Old candidate paths
  // kept as fallback in case the API varies the shape across models/versions.
  const base64Audio: string | undefined =
    data.steps?.[0]?.content?.[0]?.data ??
    data.output_audio?.data ??
    data.output?.[0]?.audio?.data ??
    data.output?.find((o: any) => o.type === 'audio')?.data ??
    data.output?.find((o: any) => o.type === 'audio')?.audio?.data;
  if (!base64Audio) {
    logAudioDiag('GEMINI_TTS_ERROR_BODY', `status=200 no_audio_in_response body=${JSON.stringify(data)}`);
    throw new Error('Gemini TTS returned no audio');
  }

  const pcmBytes = base64ToBytes(base64Audio);
  const sampleRate = 24000;
  const header = buildWavHeader(pcmBytes.length, sampleRate);
  const wavBytes = new Uint8Array(header.length + pcmBytes.length);
  wavBytes.set(header, 0);
  wavBytes.set(pcmBytes, header.length);

  const file = new File(Paths.cache, `benson-gemini-tts-${Date.now()}.wav`);
  file.create();
  file.write(wavBytes);

  await stopGeminiTTS();
  await ensurePlaybackAudioMode();
  const { sound } = await Audio.Sound.createAsync({ uri: file.uri }, { shouldPlay: true });
  currentSound = sound;
  sound.setOnPlaybackStatusUpdate((status) => {
    if (status.isLoaded && status.didJustFinish) {
      if (currentSound === sound) currentSound = null;
      // Await unloadAsync before onDone — see openaiTTS.ts's identical comment: expo-av holds
      // audio focus until the player actually stops/unloads, not just at playback end. Firing
      // onDone (which triggers the next STT session) before that settles races the recognizer
      // starting while this TTS still holds the mic/audio focus.
      sound.unloadAsync().catch(() => {}).finally(() => onDone?.());
    }
  });
}

export async function stopGeminiTTS(): Promise<void> {
  if (currentSound) {
    try { await currentSound.stopAsync(); } catch {}
    try { await currentSound.unloadAsync(); } catch {}
    currentSound = null;
  }
}
