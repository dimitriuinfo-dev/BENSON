import { initWhisper, type WhisperContext, type TranscribeOptions } from 'whisper.rn';
import { logAudioDiag } from 'benson-foreground-service';
import * as FileSystem from 'expo-file-system/legacy';
import type { SttEngine } from '../engines/types';

// BENSON's own local listening system — replaces dependence on Android's SpeechRecognizer
// (cloud or on-device) for active command capture.
//
// The model is NOT bundled inside the APK anymore (the ~141MB binary is git-ignored and is not
// present in the Emergent build environment, so "Publish"-generated APKs would ship without it and
// on-device transcription would silently break — see the deployment readiness audit 2026-06).
// Instead it is downloaded ONCE at first launch to the app's writable document directory and then
// opened from there (isBundleAsset:false). Zero per-command cost, zero per-command network — only
// the very first run needs Wi-Fi. Subsequent launches reuse the cached file.
// Reverted ggml-small back to ggml-base (product-owner-confirmed 2026-08-24, one day after the
// small upgrade below). "small" was meaningfully more accurate, but confirmed live to be
// catastrophically slow whenever BENSON is backgrounded (the normal, primary way a hands-free
// assistant is actually used) — a real transcription on this device took 137580ms (2m17s) for a
// short utterance while another app (Google Maps) was in the foreground, vs ~15s for a similar
// clip while BENSON's own Activity was foregrounded. The OS clearly deprioritizes this process's
// CPU time heavily once it's not the visible Activity, and "small"'s much larger compute cost
// turns that into a multi-minute stall — unusable for the primary use case. A fast reply that
// sometimes mishears a word beats a perfect transcription that arrives two minutes late.
// Previously (2026-08-23): upgraded from ggml-base to ggml-small after confirming "base" (74M
// params) mistranscribes clear, non-clipped, correctly-formatted Romanian speech into unrelated
// English-sounding text ("Benson, sună-o pe Hannah pe WhatsApp" -> "Master, Naw Prin Clark she
// vrei pare Camessando preso F S") — verified by pulling and analyzing the actual captured WAV
// (16kHz/mono/16-bit, 0% clipping, healthy RMS), ruling out an audio-capture bug. That accuracy
// problem is real and unsolved by this revert — traded back deliberately for usable latency.
//
// The model is NOT bundled inside the APK anymore (the ~141MB binary is git-ignored and is not
// present in the Emergent build environment, so "Publish"-generated APKs would ship without it and
// on-device transcription would silently break — see the deployment readiness audit 2026-06).
// Instead it is downloaded ONCE at first launch to the app's writable document directory and then
// opened from there (isBundleAsset:false). Zero per-command cost, zero per-command network — only
// the very first run needs Wi-Fi. Subsequent launches reuse the cached file.
const MODEL_URL = 'https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.bin';
// Exact byte size of ggml-base.bin (multilingual) — used to detect a partial/corrupt download and
// force a clean re-download instead of feeding whisper.rn a broken file.
const MODEL_EXPECTED_BYTES = 147951465;
const MODEL_DIR = `${FileSystem.documentDirectory}benson-models/`;
const MODEL_PATH = `${MODEL_DIR}ggml-base.bin`;

// This device has 8 CPU cores; the library's own default (2-4 threads) left most of them idle
// and a ~5s clip took ~55s to transcribe — unusable. Android has no GPU path here (useGpu is
// iOS/CoreML-only per the library), so more CPU threads is the only lever available.
// STT reliability round (2026-08-27): SM7675 is NOT 8 homogeneous cores — it's
// 3x1.90GHz + 4x2.61GHz + 1x2.80GHz. whisper.cpp barrier-syncs all worker threads every step, so
// mixing in the slow 1.90GHz cluster turns it into a straggler every fast thread has to wait on.
// 4 = the homogeneous 2.61GHz cluster only. Previous value: 6.
const THREADS = 4;

// ── Revert switches — STT reliability round, 2026-08-27 (see STT_REPORT.md) ────────────────────
// Every behavioural change made in this round lives behind one of these four constants. Flipping
// all four to `false` restores this file's pre-round behaviour exactly.
const WHISPER_NO_CONTEXT = true;
const WHISPER_STRICT_DECODE = true;
const WHISPER_FRESH_CONTEXT_PER_TAKE = true;
const WHISPER_HALLUCINATION_FILTER = true;

// ── Download status broadcast (drives the on-screen "downloading the voice model" banner) ───────
export type WhisperStatus =
  | { state: 'idle' }
  | { state: 'downloading'; progress: number } // 0..1
  | { state: 'ready' }
  | { state: 'error'; error: string };

let currentStatus: WhisperStatus = { state: 'idle' };
const listeners = new Set<(s: WhisperStatus) => void>();

function emit(s: WhisperStatus) {
  currentStatus = s;
  for (const cb of listeners) {
    try { cb(s); } catch { /* never let a UI listener break the engine */ }
  }
}

/** Subscribe to model download/load status. Fires immediately with the current status. */
export function subscribeWhisperStatus(cb: (s: WhisperStatus) => void): () => void {
  listeners.add(cb);
  cb(currentStatus);
  return () => { listeners.delete(cb); };
}

let modelPromise: Promise<string> | null = null;

// Ensures the model file exists locally (downloading it once if missing/incomplete) and returns its
// absolute path. Idempotent + safe to call concurrently (single in-flight promise).
function ensureModel(): Promise<string> {
  if (!modelPromise) {
    modelPromise = (async () => {
      const info = await FileSystem.getInfoAsync(MODEL_PATH);
      if (info.exists && info.size === MODEL_EXPECTED_BYTES) {
        emit({ state: 'ready' });
        return MODEL_PATH;
      }
      // Stale/partial file from an interrupted earlier download — remove before retrying.
      if (info.exists) {
        await FileSystem.deleteAsync(MODEL_PATH, { idempotent: true }).catch(() => {});
      }
      await FileSystem.makeDirectoryAsync(MODEL_DIR, { intermediates: true }).catch(() => {});
      emit({ state: 'downloading', progress: 0 });
      logAudioDiag('WHISPER_MODEL_DOWNLOAD_START', `url=${MODEL_URL}`);
      const dl = FileSystem.createDownloadResumable(MODEL_URL, MODEL_PATH, {}, (p) => {
        const total = p.totalBytesExpectedToWrite || MODEL_EXPECTED_BYTES;
        const progress = total > 0 ? Math.min(1, p.totalBytesWritten / total) : 0;
        emit({ state: 'downloading', progress });
      });
      const res = await dl.downloadAsync();
      const after = await FileSystem.getInfoAsync(MODEL_PATH);
      if (!res || res.status !== 200 || !after.exists || after.size !== MODEL_EXPECTED_BYTES) {
        await FileSystem.deleteAsync(MODEL_PATH, { idempotent: true }).catch(() => {});
        throw new Error(`model download incomplete (status=${res?.status} size=${after.exists ? after.size : 'none'})`);
      }
      logAudioDiag('WHISPER_MODEL_DOWNLOAD_DONE', `bytes=${after.size}`);
      emit({ state: 'ready' });
      return MODEL_PATH;
    })().catch((e: unknown) => {
      modelPromise = null; // allow a retry on the next call instead of caching the failure
      const msg = e instanceof Error ? e.message : String(e);
      emit({ state: 'error', error: msg });
      logAudioDiag('WHISPER_MODEL_DOWNLOAD_FAIL', msg);
      throw e;
    });
  }
  return modelPromise;
}

// ── Context lifecycle (Task C, STT reliability round) ───────────────────────────────────────────
// Previously: one WhisperContext created lazily on first use and kept alive for the entire app
// session, reused for every take. Under WHISPER_FRESH_CONTEXT_PER_TAKE, transcribeLocally()
// releases it after every take and ensureContext() below recreates it lazily on the next call —
// cheap insurance against any native-side state (KV cache, internal buffers) accumulating across
// takes, on top of the JS-level resets in Task A. See STT_REPORT.md for the measured init cost.
let contextPromise: Promise<WhisperContext> | null = null;
// Gates a new ensureContext() behind an in-flight releaseContext() so the two can never race and
// hand back a context that is mid-release (guards against double-init and use-after-release).
let releasingPromise: Promise<void> | null = null;

async function ensureContext(): Promise<WhisperContext> {
  if (releasingPromise) await releasingPromise;
  if (!contextPromise) {
    contextPromise = (async () => {
      const filePath = await ensureModel();
      const startedAt = Date.now();
      const ctx = await initWhisper({ filePath, isBundleAsset: false });
      logAudioDiag('CTX_INIT', `elapsedMs=${Date.now() - startedAt}`);
      return ctx;
    })().catch((e: unknown) => {
      contextPromise = null; // allow retry on next use instead of caching a permanent failure
      throw e;
    });
  }
  return contextPromise;
}

async function releaseContext(): Promise<void> {
  const promise = contextPromise;
  if (!promise) return;
  contextPromise = null; // claim it immediately so a concurrent ensureContext() can't hand it out
  releasingPromise = (async () => {
    try {
      const ctx = await promise;
      await ctx.release();
      logAudioDiag('CTX_RELEASE', 'ok=true');
    } catch (e: unknown) {
      logAudioDiag('CTX_RELEASE', `ok=false error="${String(e)}"`);
    }
  })();
  await releasingPromise;
  releasingPromise = null;
}

// Call ahead of first use (e.g. when the user selects the Local engine in Settings) so the model
// is downloaded (first launch) and loaded into memory by the time a command needs transcribing.
export function preloadLocalWhisper() {
  ensureContext().catch((e: unknown) => console.warn('[LocalWhisper] preload failed', e));
}

// Reverted (2026-08-24, same day as added): beamSize+prompt were meant to improve accuracy
// without a model swap, but confirmed live to make BOTH things worse at once — transcription
// time roughly doubled (2-5s clips now taking 10-13s) and the actual text got WORSE, not better
// ("Nu, cei nu?" for "Benson, deschide Waze"), likely because beam search converging toward the
// short, "safe" high-probability candidate combined badly with the initial-prompt bias on
// already-unclear audio. Reverting to plain greedy decoding, no prompt — the config actually
// measured as faster and no worse than this attempt.
// STT reliability round (2026-08-27): beamSize:1/bestOf:1 below is greedy decoding stated
// explicitly rather than left to the library default — NOT the beam-search attempt reverted
// above (that used beamSize>1, which switches the native strategy to WHISPER_SAMPLING_BEAM_SEARCH;
// see RNWhisperJSI.cpp). temperature:0/temperatureInc:0 additionally disables random-sampling
// fallback on a low-confidence decode, which is a separate lever from beam width.

// Task E — hallucination / empty-result filter. Whisper's failure mode on bad audio is not
// silence, it's confident nonsense (a lone comma, or English training-set boilerplate out of
// Romanian audio) — never let that reach the parser as if it were a real command.
const HALLUCINATION_BLOCKLIST = [
  'thanks for watching',
  'thank you for watching',
  '(thud)',
  '[music]',
  'subtitles by',
  'amara.org',
  'transcription by',
  'please subscribe',
].map((s) => s.toLowerCase());

// Romanian/German diacritics — a transcription in ro-RO audio that contains NONE of these while
// also containing plain Latin letters is suspect (logged as WHISPER_LANG_MISMATCH, not rejected
// on this signal alone per Task E).
const RO_DE_DIACRITICS = /[ăâîșşțţäöüß]/i;

function classifyTranscription(raw: string): { rejected: boolean; reason?: 'short' | 'blocklist' } {
  const trimmed = raw.trim();
  if (trimmed.length < 3) return { rejected: true, reason: 'short' };
  const normalized = trimmed.toLowerCase();
  if (HALLUCINATION_BLOCKLIST.some((phrase) => normalized.includes(phrase))) {
    return { rejected: true, reason: 'blocklist' };
  }
  return { rejected: false };
}

// RECOVERY_STT_FAILOVER_1 (2026-09-14, device-log-proven) — previously returned a fixed Romanian
// sentence here instead of the raw hallucinated text, reasoning that it would "fail intent parsing
// safely" downstream. It does not: this return value is treated exactly like a real user
// transcript everywhere upstream (MISSION_INPUT, the brain, and — critically — classifyConfirmation
// during an ACTIVE pending confirmation, where this sentence's own leading "Nu" would silently
// read as NO and cancel a real mission the user never touched). A rejected/garbage transcription
// must be indistinguishable from true no-speech, which the caller already has a safe, proven path
// for (app/index.tsx's STT result handler only dispatches `if (transcript)` — an empty string is
// silently ignored and the hands-free loop just re-arms, identical to a genuine no-speech capture).

// Exported (STT convergence round, 2026-08-27) so lib/engines/stt/groqStt.ts's cloud transcription
// can run through the exact same hallucination/empty-result checks as this local engine instead of
// a second, potentially-drifting copy — the GO round's Task 2 requires the existing filter apply
// to cloud results too. `engine`/`context` are purely for the log line, to tell which engine and
// (for local) which take produced a given rejection.
export function applyHallucinationFilter(
  rawText: string,
  langCode: string,
  engine: 'local' | 'groq',
  context?: string,
): string {
  if (!WHISPER_HALLUCINATION_FILTER) return rawText;
  const prefix = context ? `${context} ` : '';

  const looksEnglishNotRo = langCode === 'ro' && /[A-Za-z]/.test(rawText) && !RO_DE_DIACRITICS.test(rawText);
  if (looksEnglishNotRo) {
    logAudioDiag('WHISPER_LANG_MISMATCH', `${prefix}engine=${engine} raw="${rawText}"`);
  }

  const verdict = classifyTranscription(rawText);
  if (verdict.rejected) {
    logAudioDiag('WHISPER_REJECTED', `${prefix}engine=${engine} reason=${verdict.reason} raw="${rawText}"`);
    return '';
  }
  return rawText;
}

let takeIndex = 0;
// Task C concurrency guard: a second take starting while one is already running is rejected
// outright (logged), never silently queued behind the first.
let transcribing = false;

export async function transcribeLocally(wavFilePath: string, lang: string, captureEndAt?: number): Promise<string> {
  if (transcribing) {
    logAudioDiag('WHISPER_BUSY', `takeIndex=${takeIndex} rejected=true`);
    throw new Error('local whisper transcription already in progress');
  }
  transcribing = true;
  takeIndex += 1;
  const thisTake = takeIndex;
  try {
    const ctx = await ensureContext();
    const langCode = lang.split('-')[0].toLowerCase();

    // Task A/B — resolved decode options. Only fields that actually exist on the installed
    // whisper.rn TranscribeOptions type are set here; see STT_REPORT.md for what was requested
    // but is not exposed by this version (suppressNonSpeechTokens, singleSegment, a JS-settable
    // no_context/noContext flag).
    const options: TranscribeOptions = { language: langCode, maxThreads: THREADS };
    if (WHISPER_NO_CONTEXT) {
      // `prompt` explicitly empty (never a previous result) and `maxContext` (maps to the native
      // n_max_text_ctx) capped to 0. Belt-and-suspenders: the installed binding already hardcodes
      // `no_context = true` unconditionally at the native/JSI layer regardless of any JS option
      // (cpp/jsi/RNWhisperJSI.cpp) — see STT_REPORT.md — so cross-take prompt carryover through
      // whisper.cpp's own internal state was already structurally impossible before this change.
      options.prompt = '';
      options.maxContext = 0;
    }
    if (WHISPER_STRICT_DECODE) {
      options.temperature = 0;
      options.temperatureInc = 0;
      options.beamSize = 1;
      options.bestOf = 1;
      options.translate = false;
    }

    logAudioDiag('WHISPER_OPTS', JSON.stringify(options));

    const fileInfo = await FileSystem.getInfoAsync(wavFilePath);
    const bytes = fileInfo.exists && typeof fileInfo.size === 'number' ? fileInfo.size : 0;
    // Same AUDIO_FILE diagnosis line the Groq route emits — so the two routes' read timing (and
    // whether either ever sees an unflushed/empty file) can be compared directly in one log. This
    // read happens only AFTER `await ensureContext()` above, which is exactly why this route has
    // never seen bytes=0 while the Groq route did.
    logAudioDiag(
      'AUDIO_FILE',
      `engine=local path=${JSON.stringify(wavFilePath)} existsAtRead=${!!fileInfo.exists} bytes=${bytes} tSinceFinishMs=${captureEndAt ? Date.now() - captureEndAt : 'n/a'}`,
    );
    // BENSON's capture format is 16kHz mono 16-bit PCM with a standard 44-byte WAV header (see
    // BensonAudioCaptureModule.kt) — bytes-to-seconds is a direct arithmetic conversion, not an
    // estimate.
    const durationSec = bytes > 44 ? (bytes - 44) / (16000 * 2) : 0;

    // Timing instrumentation (product-owner-directed, prerequisite for any threading/model-size
    // decision) — pairs with BensonAudioCaptureModule.kt's CAPTURE_ENDED to compute the real
    // Capture-ended -> rawText gap instead of the unmeasured "~55s" figure this file used to cite.
    logAudioDiag(
      'TRANSCRIBE_START',
      `takeIndex=${thisTake} bytes=${bytes} durationSec=${durationSec.toFixed(2)} threads=${THREADS} model=ggml-base`,
    );
    const startedAt = Date.now();
    const { promise } = ctx.transcribe(wavFilePath, options);
    const result = await promise;
    const elapsedMs = Date.now() - startedAt;
    const rawText = (result.result || '').trim();
    logAudioDiag(
      'TRANSCRIBE_END',
      `takeIndex=${thisTake} elapsedMs=${elapsedMs} chars=${rawText.length} text="${rawText}"`,
    );

    return applyHallucinationFilter(rawText, langCode, 'local', `takeIndex=${thisTake}`);
  } finally {
    transcribing = false;
    if (WHISPER_FRESH_CONTEXT_PER_TAKE) {
      await releaseContext();
    }
  }
}

// Task 1 (GO round, 2026-08-27) — this engine's conformance to the shared SttEngine interface
// (lib/engines/types.ts), so lib/engines/registry.ts can select it interchangeably with the cloud
// STT engines. No behavior change: transcribeLocally() above is untouched, this just exposes it
// under the shared shape.
export const localSttEngine: SttEngine = { id: 'local', transcribe: transcribeLocally };
