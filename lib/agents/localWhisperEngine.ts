import { initWhisper, type WhisperContext } from 'whisper.rn';
import { logAudioDiag } from 'benson-foreground-service';

// BENSON's own local listening system — replaces dependence on Android's SpeechRecognizer
// (cloud or on-device) for active command capture. Model is bundled as a raw Android asset
// (android/app/src/main/assets/models/ggml-tiny.bin, multilingual ggml-tiny, ~75MB) so it works
// with zero network calls and zero per-command cost; opened directly via isBundleAsset, no copy
// to a writable path needed.
const MODEL_ASSET_PATH = 'models/ggml-base.bin';

// This device has 8 CPU cores; the library's own default (2-4 threads) left most of them idle
// and a ~5s clip took ~55s to transcribe — unusable. Android has no GPU path here (useGpu is
// iOS/CoreML-only per the library), so more CPU threads is the only lever available.
const THREADS = 6;

let contextPromise: Promise<WhisperContext> | null = null;

function getContext(): Promise<WhisperContext> {
  if (!contextPromise) {
    const promise: Promise<WhisperContext> = initWhisper({ filePath: MODEL_ASSET_PATH, isBundleAsset: true });
    contextPromise = promise.catch((e: unknown) => {
      contextPromise = null; // allow retry on next use instead of caching a permanent failure
      throw e;
    });
  }
  return contextPromise;
}

// Call ahead of first use (e.g. when the user selects the Local engine in Settings) so the
// ~75MB model is already loaded into memory by the time a command needs transcribing.
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
  logAudioDiag('TRANSCRIBE_START', `model=${MODEL_ASSET_PATH} threads=${THREADS}`);
  const { promise } = ctx.transcribe(wavFilePath, { language: langCode, maxThreads: THREADS });
  const result = await promise;
  const elapsedMs = Date.now() - startedAt;
  logAudioDiag('TRANSCRIBE_END', `model=${MODEL_ASSET_PATH} threads=${THREADS} elapsedMs=${elapsedMs}`);
  return (result.result || '').trim();
}
