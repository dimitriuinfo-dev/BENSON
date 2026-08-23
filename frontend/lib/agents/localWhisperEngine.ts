import { initWhisper, type WhisperContext } from 'whisper.rn';
import { logAudioDiag } from 'benson-foreground-service';
import * as FileSystem from 'expo-file-system/legacy';

// BENSON's own local listening system — replaces dependence on Android's SpeechRecognizer
// (cloud or on-device) for active command capture.
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
const THREADS = 6;

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

let contextPromise: Promise<WhisperContext> | null = null;

function getContext(): Promise<WhisperContext> {
  if (!contextPromise) {
    const promise: Promise<WhisperContext> = ensureModel().then((filePath) =>
      initWhisper({ filePath, isBundleAsset: false }),
    );
    contextPromise = promise.catch((e: unknown) => {
      contextPromise = null; // allow retry on next use instead of caching a permanent failure
      throw e;
    });
  }
  return contextPromise;
}

// Call ahead of first use (e.g. when the user selects the Local engine in Settings) so the model
// is downloaded (first launch) and loaded into memory by the time a command needs transcribing.
export function preloadLocalWhisper() {
  getContext().catch((e: unknown) => console.warn('[LocalWhisper] preload failed', e));
}

export async function transcribeLocally(wavFilePath: string, lang: string): Promise<string> {
  const ctx = await getContext();
  const langCode = lang.split('-')[0].toLowerCase();
  // Timing instrumentation (product-owner-directed, prerequisite for any threading/model-size
  // decision) — pairs with BensonAudioCaptureModule.kt's CAPTURE_ENDED to compute the real
  // Capture-ended -> rawText gap instead of the unmeasured "~55s" figure this file used to cite.
  const startedAt = Date.now();
  logAudioDiag('TRANSCRIBE_START', `model=ggml-base threads=${THREADS}`);
  const { promise } = ctx.transcribe(wavFilePath, { language: langCode, maxThreads: THREADS });
  const result = await promise;
  const elapsedMs = Date.now() - startedAt;
  logAudioDiag('TRANSCRIBE_END', `model=ggml-base threads=${THREADS} elapsedMs=${elapsedMs}`);
  return (result.result || '').trim();
}
