// DEVELOPMENT STT provider — Deepgram Nova-3 (2026-09-16). Groq's request quota is exhausted
// (see memory project_groq_quota_blocker) so real-device testing is blocked on the primary
// provider; this is the temporary dev-only tier for main-command STT. Groq stays fully in place
// as the intended low-cost PRODUCTION provider — see voiceAgent.ts's transcribeAudio() dev-routing
// constant for the single switch point. No local-Whisper fallback: a Deepgram failure here must
// surface as STT_PROVIDER_UNAVAILABLE with no transcript, never fall through.
import * as FileSystem from 'expo-file-system/legacy';
import { logAudioDiag } from 'benson-foreground-service';
import { toFileUri, waitForWavReady } from './groqStt';
import type { EngineConfig, SttEngine } from '../types';

export const DEEPGRAM_STT_DEFAULT_BASE_URL = 'https://api.deepgram.com/v1/listen';
export const DEEPGRAM_STT_DEFAULT_MODEL = 'nova-3';

const REQUEST_TIMEOUT_MS = 20000;

function withTimeout<T>(p: Promise<T>, ms: number): Promise<T> {
  return new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error('Deepgram request timed out')), ms);
    p.then((v) => { clearTimeout(t); resolve(v); }, (e) => { clearTimeout(t); reject(e); });
  });
}

function resolvedBaseUrl(config: EngineConfig): string {
  return config.baseUrl || DEEPGRAM_STT_DEFAULT_BASE_URL;
}
function resolvedModel(config: EngineConfig): string {
  return config.model || DEEPGRAM_STT_DEFAULT_MODEL;
}

interface DeepgramAlternative {
  transcript?: string;
  confidence?: number;
}

export async function transcribeWithDeepgram(
  wavFilePath: string,
  lang: string,
  config: EngineConfig,
  captureEndAt?: number,
): Promise<string> {
  const langCode = lang.split('-')[0].toLowerCase();
  const uri = toFileUri(wavFilePath);
  const baseUrl = resolvedBaseUrl(config);
  const model = resolvedModel(config);

  const bytes = await waitForWavReady(uri, captureEndAt);
  logAudioDiag('STT_REQUEST', `engine=deepgram bytes=${bytes}`);
  logAudioDiag('STT_PROVIDER_ATTEMPT', 'provider=deepgram');

  // Deepgram's pre-recorded /listen endpoint takes the raw audio bytes as the request body (no
  // multipart) — FileSystem.uploadAsync's BINARY_CONTENT mode streams the file directly, same
  // "one canonical file:// URI" discipline as groqStt.ts's multipart upload.
  const url = `${baseUrl}?model=${encodeURIComponent(model)}&language=${encodeURIComponent(langCode)}`;
  logAudioDiag('STT_UPLOAD_START', `provider=deepgram bytes=${bytes}`);
  const startedAt = Date.now();
  let res: FileSystem.FileSystemUploadResult;
  try {
    res = await withTimeout(
      FileSystem.uploadAsync(url, uri, {
        httpMethod: 'POST',
        uploadType: FileSystem.FileSystemUploadType.BINARY_CONTENT,
        headers: { Authorization: `Token ${config.apiKey}`, 'Content-Type': 'audio/wav' },
      }),
      REQUEST_TIMEOUT_MS,
    );
  } catch (e) {
    logAudioDiag('STT_PROVIDER_RESULT', `provider=deepgram status=network_error http_status=n/a error="${String(e)}"`);
    throw e;
  }

  logAudioDiag('HTTP_STATUS', `provider=deepgram code=${res.status}`);
  const ok = res.status >= 200 && res.status < 300;
  logAudioDiag('STT_PROVIDER_RESULT', `provider=deepgram status=${ok ? 'ok' : 'error'} http_status=${res.status}`);
  if (!ok) {
    logAudioDiag('STT_HTTP_ERROR_BODY', `engine=deepgram status=${res.status} body=${res.body.slice(0, 400)}`);
    throw new Error(`Deepgram transcription failed: ${res.status}`);
  }

  const data = JSON.parse(res.body || '{}');
  const alt: DeepgramAlternative | undefined = data?.results?.channels?.[0]?.alternatives?.[0];
  const rawText = (typeof alt?.transcript === 'string' ? alt.transcript : '').trim();
  const confidence = typeof alt?.confidence === 'number' ? alt.confidence : null;
  const elapsedMs = Date.now() - startedAt;

  logAudioDiag('STT_RAW_TRANSCRIPT', `text="${rawText}"`);
  logAudioDiag('STT_CONFIDENCE', `value=${confidence ?? 'n/a'}`);
  logAudioDiag('STT_RESULT', `engine=deepgram elapsedMs=${elapsedMs} chars=${rawText.length} confidence=${confidence ?? 'n/a'} text="${rawText}"`);

  const accepted = rawText.length > 0;
  logAudioDiag('MISSION_INPUT_ALLOWED', `value=${accepted}`);
  return rawText;
}

export function createDeepgramSttEngine(config: EngineConfig): SttEngine {
  return { id: 'deepgram', transcribe: (wavPath, lang) => transcribeWithDeepgram(wavPath, lang, config) };
}
