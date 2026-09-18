// Shared network-timeout helper — added 2026-08-25 (product-owner-confirmed live bug): a plain
// `fetch()` call to a cloud STT/chat provider has NO timeout by default; if the request hangs
// (bad connection, provider-side stall) instead of erroring out, `await fetch(...)` never
// resolves at all. Confirmed live: a Gemini transcription request hung for 75+ seconds with
// ZERO log output — not slow, genuinely stuck — leaving the entire conversation loop dead (no
// error, no fallback, nothing) because none of the existing try/catch fallback chains
// (voiceAgent.ts's transcribeAudio, orchestrator.ts's Gemini/OpenAI-to-Claude fallback) can catch
// a promise that never settles. Wrapping every cloud provider call in this timeout turns a silent
// hang into a real, catchable error so the existing fallback chains actually get a chance to run.
export async function fetchWithTimeout(url: string, options: RequestInit, timeoutMs: number): Promise<Response> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    return await fetch(url, { ...options, signal: controller.signal });
  } catch (e) {
    if (controller.signal.aborted) throw new Error(`Request timed out after ${timeoutMs}ms: ${url}`);
    throw e;
  } finally {
    clearTimeout(timer);
  }
}
