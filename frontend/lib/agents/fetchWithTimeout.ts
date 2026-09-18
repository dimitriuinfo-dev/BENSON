// Shared network-timeout helper — added 2026-08-25 (product-owner-confirmed live bug): a plain
// `fetch()` call to a cloud STT/chat provider has NO timeout by default; if the request hangs
// (bad connection, provider-side stall) instead of erroring out, `await fetch(...)` never
// resolves at all. Confirmed live: a Gemini transcription request hung for 75+ seconds with
// ZERO log output — not slow, genuinely stuck — leaving the entire conversation loop dead (no
// error, no fallback, nothing) because none of the existing try/catch fallback chains
// (voiceAgent.ts's transcribeAudio, orchestrator.ts's Gemini/OpenAI-to-Claude fallback) can catch
// a promise that never settles. Wrapping every cloud provider call in this timeout turns a silent
// hang into a real, catchable error so the existing fallback chains actually get a chance to run.
//
// CLOUD_FETCH_WATCHDOG_NATIVE_1 (2026-09-17, device-confirmed) — the abort trigger used to be a
// plain JS `setTimeout(() => controller.abort(), timeoutMs)`. Same root-cause class already fixed
// three other times this session (WAV-readiness polling, the post-TTS mic-resume retry): that
// timer can go inert while BENSON is backgrounded, so `await fetch(...)` above never settles at
// all — not even as an error. The abort trigger now comes from a native Handler.postDelayed timer
// instead (armCloudFetchWatchdog/addCloudFetchTimeoutListener in benson-foreground-service) —
// ID-keyed because cloud calls can genuinely overlap.
//
// CLOUD_FETCH_RACE_1 (2026-09-18, device-confirmed) — the native watchdog firing turned out not to
// be enough by itself: device log showed CLOUD_FETCH_TIMEOUT_FIRE fire correctly at the 15s mark,
// but neither CLOUD_FETCH_ERROR nor CLOUD_FETCH_FINALLY ever logged afterward — `controller.abort()`
// did not make the underlying `await fetch(...)` settle on this device/RN stack, leaving loadingRef
// stuck true and mic ownership frozen at COMMAND_STT forever (BENSON went totally deaf after one
// unrecognized command routed to the Groq /models discovery call). Fix: stop depending on abort()
// to reject the fetch promise — race it against a promise that rejects directly off the native
// timer event, so this function's own returned promise is guaranteed to settle the instant the
// watchdog fires, regardless of whether the underlying fetch ever notices the abort.
import { armCloudFetchWatchdog, cancelCloudFetchWatchdog, addCloudFetchTimeoutListener, logAudioDiag } from 'benson-foreground-service';

let requestSeq = 0;

export async function fetchWithTimeout(url: string, options: RequestInit, timeoutMs: number): Promise<Response> {
  const requestId = `fetch-${Date.now()}-${++requestSeq}`;
  const controller = new AbortController();
  logAudioDiag('CLOUD_FETCH_START', `requestId=${requestId} url=${url}`);
  let rejectTimeout: ((e: Error) => void) | null = null;
  const timeoutPromise = new Promise<Response>((_, reject) => { rejectTimeout = reject; });
  const timeoutSub = addCloudFetchTimeoutListener((firedId) => {
    if (firedId !== requestId) return;
    try { controller.abort(); } catch {}
    rejectTimeout?.(new Error(`Request timed out after ${timeoutMs}ms: ${url}`));
  });
  try {
    armCloudFetchWatchdog(requestId, timeoutMs);
    const res = await Promise.race([fetch(url, { ...options, signal: controller.signal }), timeoutPromise]);
    logAudioDiag('CLOUD_FETCH_SUCCESS', `requestId=${requestId} status=${res.status}`);
    return res;
  } catch (e) {
    logAudioDiag('CLOUD_FETCH_ERROR', `requestId=${requestId} error="${String(e)}"`);
    throw e;
  } finally {
    try { cancelCloudFetchWatchdog(requestId); } catch {}
    try { timeoutSub.remove(); } catch {}
    logAudioDiag('CLOUD_FETCH_FINALLY', `requestId=${requestId}`);
  }
}
