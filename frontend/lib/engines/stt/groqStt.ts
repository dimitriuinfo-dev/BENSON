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
const WAV_HEADER_BYTES = 44;

// Single source of truth: the `file://` URI used for size checks AND for the multipart upload.
// Exported — reused as-is by deepgramStt.ts (same capture file, same 0-byte-bare-path bug class)
// instead of duplicating it.
export function toFileUri(pathOrUri: string): string {
  return pathOrUri.startsWith('file://') ? pathOrUri : `file://${pathOrUri}`;
}

async function statSize(uri: string): Promise<{ exists: boolean; size: number }> {
  const info = await FileSystem.getInfoAsync(uri).catch(() => null);
  const exists = !!(info && info.exists);
  return { exists, size: exists && typeof info?.size === 'number' ? info.size : 0 };
}

export async function waitForWavReady(fileUri: string, captureEndAt?: number): Promise<number> {
  const since = () => (captureEndAt ? String(Date.now() - captureEndAt) : 'n/a');
  const deadline = Date.now() + FILE_READY_TIMEOUT_MS;
  logAudioDiag('WAV_READY_START', `path=${JSON.stringify(fileUri)} tSinceFinishMs=${since()}`);

  const first = await statSize(fileUri);
  logAudioDiag(
    'AUDIO_FILE',
    `engine=groq phase=first_read path=${JSON.stringify(fileUri)} existsAtRead=${first.exists} bytes=${first.size} tSinceFinishMs=${since()}`,
  );
  // Native writes the WAV synchronously before firing onCaptureEnd (see this file's header
  // comment) — a non-trivial size on the very FIRST read already proves a complete file. No
  // additional elapsed-time gate (there used to be one, requiring 250ms since capture end before
  // trusting an already-correct size) — that gate is exactly what forced an already-good file into
  // the retry loop below unnecessarily.
  if (first.size > WAV_HEADER_BYTES) {
    logAudioDiag('AUDIO_FILE', `engine=groq phase=ready bytes=${first.size} tSinceFinishMs=${since()}`);
    logAudioDiag('WAV_READY_SUCCESS', `bytes=${first.size} tSinceFinishMs=${since()}`);
    return first.size;
  }

  // BACKGROUND-SAFE RETRY (2026-09-17) — was `await new Promise(r => setTimeout(r, FILE_READY_POLL_MS))`
  // between checks. A plain JS setTimeout can freeze while BENSON is backgrounded (the same class
  // of bug already fixed elsewhere in this codebase by moving critical timers to native
  // Handler.postDelayed — e.g. BensonBubbleService.kt's auto-dismiss, the STT/TTS session
  // watchdogs). A frozen setTimeout here meant this function — and everything awaiting it
  // (transcribeWithGroq/transcribeWithDeepgram) — hung indefinitely until the UNRELATED 30s STT
  // session watchdog force-closed the session, silently losing the whole command
  // (device-confirmed 2026-09-17). No artificial delay now: each iteration is itself a real
  // awaited native-module round trip (FileSystem.getInfoAsync, routed through Expo's native
  // module bridge, NOT React Native's JS timer module), which naturally paces the loop without
  // depending on any timer surviving backgrounding. Still hard-bounded by the same Date.now()
  // deadline as before — this can never hang indefinitely.
  let last = first.size;
  while (Date.now() < deadline) {
    logAudioDiag('WAV_READY_CHECK', `lastBytes=${last} tSinceFinishMs=${since()}`);
    const cur = await statSize(fileUri);
    if (cur.size > WAV_HEADER_BYTES && cur.size === last) {
      logAudioDiag('AUDIO_FILE', `engine=groq phase=ready bytes=${cur.size} tSinceFinishMs=${since()}`);
      logAudioDiag('WAV_READY_SUCCESS', `bytes=${cur.size} tSinceFinishMs=${since()}`);
      return cur.size;
    }
    last = cur.size;
  }
  logAudioDiag('AUDIO_FILE', `engine=groq phase=timeout bytes=${last} tSinceFinishMs=${since()}`);
  logAudioDiag('WAV_READY_TIMEOUT', `bytes=${last} tSinceFinishMs=${since()}`);
  return last;
}

function resolvedBaseUrl(config: EngineConfig): string {
  return config.baseUrl || GROQ_STT_DEFAULT_BASE_URL;
}
function resolvedModel(config: EngineConfig): string {
  return config.model || GROQ_STT_DEFAULT_MODEL;
}

// DECISION_GROQ_PRIMARY_1 (2026-09-14) — Groq whisper-large-v3-turbo is BENSON's primary STT
// provider (product-owner decision, $0.04/audio-hour paid tier). temperature=0 for deterministic
// output; verbose_json (real per-segment no_speech_prob/avg_logprob from the model itself) instead
// of plain json, so transcript-quality rejection is based on the provider's own confidence signal,
// not a text-content guess.
function buildForm(uri: string, model: string, langCode: string): FormData {
  const form = new FormData();
  // React Native's fetch recognizes this {uri,name,type} shape and streams the file directly — it
  // also sets the multipart Content-Type/boundary header itself; do not set it manually (same
  // pattern as lib/agents/openaiSTT.ts).
  form.append('file', { uri, name: 'audio.wav', type: 'audio/wav' } as unknown as Blob);
  form.append('model', model);
  form.append('language', langCode);
  form.append('temperature', '0');
  form.append('response_format', 'verbose_json');
  return form;
}

interface GroqSegment {
  no_speech_prob?: number;
  avg_logprob?: number;
  compression_ratio?: number;
}

// A no_speech_prob this high means the model itself flagged the audio as (near-)silence/noise —
// real evidence, not a text-content heuristic. Rejecting on it stops exactly the class of
// hallucination already seen live (a confident-sounding sentence generated from noise).
const NO_SPEECH_PROB_REJECT_THRESHOLD = 0.6;

interface ConfidenceAssessment {
  reject: boolean;
  avgNoSpeechProb: number | null;
  avgLogprob: number | null;
  avgCompressionRatio: number | null;
}

function avgOf(segments: GroqSegment[], field: keyof GroqSegment): number | null {
  const vals = segments.map((s) => s[field]).filter((v): v is number => typeof v === 'number');
  if (vals.length === 0) return null;
  return vals.reduce((a, b) => a + b, 0) / vals.length;
}

function assessConfidence(segments: unknown): ConfidenceAssessment {
  if (!Array.isArray(segments) || segments.length === 0) {
    return { reject: false, avgNoSpeechProb: null, avgLogprob: null, avgCompressionRatio: null };
  }
  const segs = segments as GroqSegment[];
  const avgNoSpeechProb = avgOf(segs, 'no_speech_prob');
  const avgLogprob = avgOf(segs, 'avg_logprob');
  const avgCompressionRatio = avgOf(segs, 'compression_ratio');
  const reject = avgNoSpeechProb !== null && avgNoSpeechProb >= NO_SPEECH_PROB_REJECT_THRESHOLD;
  return { reject, avgNoSpeechProb, avgLogprob, avgCompressionRatio };
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

  logAudioDiag('STT_PROVIDER_ATTEMPT', 'provider=groq');
  logAudioDiag('STT_UPLOAD_START', `provider=groq bytes=${bytes}`);
  const startedAt = Date.now();
  let res: Response;
  try {
    res = await postTranscription(baseUrl, config.apiKey, uri, model, langCode);
  } catch (e) {
    // Network-level failure only (includes the timeout fetchWithTimeout throws on a hung
    // connection) — exactly one retry, per Task 2. A real HTTP response (including a 4xx) lands in
    // the res.ok check below instead of this catch, and is never retried.
    logAudioDiag('STT_RETRY', 'engine=groq reason=network');
    try {
      res = await postTranscription(baseUrl, config.apiKey, uri, model, langCode);
    } catch (e2) {
      logAudioDiag('STT_PROVIDER_RESULT', 'provider=groq status=network_error http_status=n/a retry_after=n/a');
      throw e2;
    }
  }

  {
    const retryAfterHeader = res.headers.get('retry-after');
    logAudioDiag(
      'STT_PROVIDER_RESULT',
      `provider=groq status=${res.ok ? 'ok' : 'error'} http_status=${res.status} retry_after=${retryAfterHeader ?? 'none'}`,
    );
  }

  // DECISION_GROQ_PRIMARY_1 — item 5: visibility into remaining quota on every response (success
  // or failure), so a provider health decision can be made from real data instead of only
  // discovering exhaustion after a 429. Logged, not yet used to preemptively skip a call — that
  // would be guessing ahead of the provider's own authoritative answer.
  const rlRemaining = res.headers.get('x-ratelimit-remaining-requests');
  const rlReset = res.headers.get('x-ratelimit-reset-requests');
  if (rlRemaining !== null || rlReset !== null) {
    logAudioDiag('STT_RATE_LIMIT_HEADERS', `engine=groq remaining=${rlRemaining ?? 'n/a'} resetRequests=${rlReset ?? 'n/a'}`);
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
  const confidence = assessConfidence(data.segments);
  logAudioDiag(
    'STT_RESULT',
    `engine=groq elapsedMs=${elapsedMs} chars=${rawText.length} avgNoSpeechProb=${confidence.avgNoSpeechProb ?? 'n/a'} text="${rawText}"`,
  );
  logAudioDiag('STT_RAW_TRANSCRIPT', `text="${rawText}"`);

  const validationFields = (acceptReason: string, rejectReason: string) =>
    `avg_logprob=${confidence.avgLogprob ?? 'n/a'} no_speech_prob=${confidence.avgNoSpeechProb ?? 'n/a'} ` +
    `compression_ratio=${confidence.avgCompressionRatio ?? 'n/a'} accept_reason=${acceptReason} reject_reason=${rejectReason}`;

  if (rawText && confidence.reject) {
    // DECISION_GROQ_PRIMARY_1 — item 3/10: a confident-sounding transcript from audio the model
    // itself flags as (near-)silence/noise must not reach MISSION_INPUT. Real provider evidence,
    // not a text-content guess.
    logAudioDiag('STT_LOW_CONFIDENCE', `engine=groq avgNoSpeechProb=${confidence.avgNoSpeechProb} rejectedText="${rawText}"`);
    logAudioDiag('STT_VALIDATION', `status=rejected ${validationFields('n/a', 'low_confidence_no_speech')}`);
    logAudioDiag('MISSION_INPUT_ALLOWED', 'value=false');
    return '';
  }

  // Task 2: the existing hallucination/empty-result filter applies to the cloud result too.
  const finalText = applyHallucinationFilter(rawText, langCode, 'groq');
  const accepted = finalText.length > 0;
  logAudioDiag(
    'STT_VALIDATION',
    `status=${accepted ? 'accepted' : 'rejected'} ${validationFields(accepted ? 'passed_confidence_and_hallucination_filter' : 'n/a', accepted ? 'n/a' : 'empty_or_hallucination_filtered')}`,
  );
  logAudioDiag('MISSION_INPUT_ALLOWED', `value=${accepted}`);
  return finalText;
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
