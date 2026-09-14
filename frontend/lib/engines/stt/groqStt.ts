// Task 2 — Groq cloud transcription (whisper-large-v3-turbo), free tier, 2000 requests/day. This
// is the large Whisper model this phone's SoC could never run locally at usable speed (see
// lib/agents/localWhisperEngine.ts's header comments for that history) — over the network
// instead, at no cost. Becomes the default/first-tried STT tier for command capture (wired at
// lib/agents/voiceAgent.ts's transcribeAudio()); local Whisper remains the offline-only reserve.
import * as FileSystem from 'expo-file-system/legacy';
import { logAudioDiag } from 'benson-foreground-service';
import { fetchWithTimeout } from '../../agents/fetchWithTimeout';
import { applyHallucinationFilter } from '../../agents/localWhisperEngine';
import type { EngineConfig, SttEngine } from '../types';

export const GROQ_STT_DEFAULT_BASE_URL = 'https://api.groq.com/openai/v1';
export const GROQ_STT_DEFAULT_MODEL = 'whisper-large-v3-turbo';

const REQUEST_TIMEOUT_MS = 20000;

// ── WAV size / readiness (2026-08-29) ─────────────────────────────────────────────────────────
// The native capture module (BensonAudioCaptureModule.kt) reports its file as a BARE absolute
// path — `/data/user/0/com.benson.butler/cache/benson_capture_*.wav`, no scheme. expo-file-system's
// getInfoAsync() only stats `file://` URIs (and its own cache/document dirs); given a bare path it
// returns `{ exists: false }` without throwing. That — NOT a write race — is why every
// STT_REQUEST here logged `bytes=0` even though the 100+ KB file was really on disk (RN's fetch
// still uploaded it fine, because buildForm() below already prefixes `file://`). So: ONE canonical
// `file://` URI, used for BOTH the size read and the upload. waitForWavReady stays as a short
// guard against reading a file mid-write, but with a real stat it returns after ~one poll instead
// of burning the full timeout.
const FILE_READY_TIMEOUT_MS = 1500;
const FILE_READY_POLL_MS = 100;
const WAV_HEADER_BYTES = 44;

// Single source of truth: the `file://` URI used for size checks AND for the multipart upload.
function toFileUri(pathOrUri: string): string {
  return pathOrUri.startsWith('file://') ? pathOrUri : `file://${pathOrUri}`;
}

async function statSize(uri: string): Promise<{ exists: boolean; size: number }> {
  const info = await FileSystem.getInfoAsync(uri).catch(() => null);
  const exists = !!(info && info.exists);
  return { exists, size: exists && typeof info?.size === 'number' ? info.size : 0 };
}

async function waitForWavReady(fileUri: string, captureEndAt?: number): Promise<number> {
  const since = () => (captureEndAt ? String(Date.now() - captureEndAt) : 'n/a');
  const deadline = Date.now() + FILE_READY_TIMEOUT_MS;

  const first = await statSize(fileUri);
  logAudioDiag(
    'AUDIO_FILE',
    `engine=groq phase=first_read path=${JSON.stringify(fileUri)} existsAtRead=${first.exists} bytes=${first.size} tSinceFinishMs=${since()}`,
  );
  // Already present and non-trivial, and capture ended long enough ago that the write is done:
  // no reason to poll again.
  if (first.size > WAV_HEADER_BYTES && (captureEndAt ? Date.now() - captureEndAt >= 250 : false)) {
    logAudioDiag('AUDIO_FILE', `engine=groq phase=ready bytes=${first.size} tSinceFinishMs=${since()}`);
    return first.size;
  }

  let last = first.size;
  for (;;) {
    if (Date.now() >= deadline) {
      logAudioDiag('AUDIO_FILE', `engine=groq phase=timeout bytes=${last} tSinceFinishMs=${since()}`);
      return last;
    }
    await new Promise((r) => setTimeout(r, FILE_READY_POLL_MS));
    const cur = await statSize(fileUri);
    if (cur.size > WAV_HEADER_BYTES && cur.size === last) {
      logAudioDiag('AUDIO_FILE', `engine=groq phase=ready bytes=${cur.size} tSinceFinishMs=${since()}`);
      return cur.size;
    }
    last = cur.size;
  }
}

function resolvedBaseUrl(config: EngineConfig): string {
  return config.baseUrl || GROQ_STT_DEFAULT_BASE_URL;
}
function resolvedModel(config: EngineConfig): string {
  return config.model || GROQ_STT_DEFAULT_MODEL;
}

function buildForm(uri: string, model: string, langCode: string): FormData {
  const form = new FormData();
  // React Native's fetch recognizes this {uri,name,type} shape and streams the file directly — it
  // also sets the multipart Content-Type/boundary header itself; do not set it manually (same
  // pattern as lib/agents/openaiSTT.ts).
  form.append('file', { uri, name: 'audio.wav', type: 'audio/wav' } as unknown as Blob);
  form.append('model', model);
  form.append('language', langCode);
  form.append('response_format', 'json');
  return form;
}

async function postTranscription(
  baseUrl: string,
  apiKey: string,
  uri: string,
  model: string,
  langCode: string,
): Promise<Response> {
  return fetchWithTimeout(`${baseUrl}/audio/transcriptions`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${apiKey}` },
    body: buildForm(uri, model, langCode),
  }, REQUEST_TIMEOUT_MS);
}

export async function transcribeWithGroq(
  wavFilePath: string,
  lang: string,
  config: EngineConfig,
  captureEndAt?: number,
): Promise<string> {
  const langCode = lang.split('-')[0].toLowerCase();
  const uri = toFileUri(wavFilePath); // same URI for the size read AND the upload
  const baseUrl = resolvedBaseUrl(config);
  const model = resolvedModel(config);

  // Wait for the WAV to actually be on disk before measuring/uploading it (see waitForWavReady).
  const bytes = await waitForWavReady(uri, captureEndAt);
  // Same 16kHz/mono/16-bit-PCM-plus-44-byte-header arithmetic as localWhisperEngine.ts.
  const durationSec = bytes > WAV_HEADER_BYTES ? (bytes - WAV_HEADER_BYTES) / (16000 * 2) : 0;
  logAudioDiag('STT_REQUEST', `engine=groq bytes=${bytes} durationSec=${durationSec.toFixed(2)}`);

  const startedAt = Date.now();
  let res: Response;
  try {
    res = await postTranscription(baseUrl, config.apiKey, uri, model, langCode);
  } catch (e) {
    // Network-level failure only (includes the timeout fetchWithTimeout throws on a hung
    // connection) — exactly one retry, per Task 2. A real HTTP response (including a 4xx) lands in
    // the res.ok check below instead of this catch, and is never retried.
    logAudioDiag('STT_RETRY', 'engine=groq reason=network');
    res = await postTranscription(baseUrl, config.apiKey, uri, model, langCode);
  }

  if (!res.ok) {
    // RECOVERY_STT_FAILOVER_1 — same diagnostic capture as geminiSTT.ts's GEMINI_STT_ERROR_BODY:
    // a bare status code alone can't distinguish a per-minute rate limit from a daily quota from a
    // billing/plan issue. Groq's error body and Retry-After header (when present) carry that.
    const bodyText = await res.text().catch(() => '');
    const retryAfter = res.headers.get('retry-after');
    logAudioDiag('STT_HTTP_ERROR_BODY', `engine=groq status=${res.status} retryAfter=${retryAfter ?? 'none'} body=${bodyText.slice(0, 400)}`);
    throw new Error(`Groq transcription failed: ${res.status}${retryAfter ? ` retryAfterSec=${retryAfter}` : ''}`);
  }
  const data = await res.json();
  const elapsedMs = Date.now() - startedAt;
  const rawText = (typeof data.text === 'string' ? data.text : '').trim();
  logAudioDiag('STT_RESULT', `engine=groq elapsedMs=${elapsedMs} chars=${rawText.length} text="${rawText}"`);

  // Task 2: the existing hallucination/empty-result filter applies to the cloud result too.
  return applyHallucinationFilter(rawText, langCode, 'groq');
}

export function createGroqSttEngine(config: EngineConfig): SttEngine {
  return { id: 'groq', transcribe: (wavPath, lang) => transcribeWithGroq(wavPath, lang, config) };
}

// Settings "TEST" button (Task 7) — a lightweight authenticated GET (no audio available from the
// Settings screen to run a real transcription against), just enough to confirm baseUrl/apiKey
// actually reach Groq.
export async function testGroqConnection(config: EngineConfig): Promise<{ ok: true } | { ok: false; error: string }> {
  const baseUrl = resolvedBaseUrl(config);
  try {
    const res = await fetchWithTimeout(`${baseUrl}/models`, {
      headers: { Authorization: `Bearer ${config.apiKey}` },
    }, REQUEST_TIMEOUT_MS);
    if (!res.ok) return { ok: false, error: `HTTP ${res.status}` };
    return { ok: true };
  } catch (e) {
    return { ok: false, error: e instanceof Error ? e.message : String(e) };
  }
}
