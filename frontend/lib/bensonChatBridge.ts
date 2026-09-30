// ROUND_BENSON_CHAT_1 — pure, non-visual bridge so a diagnostic screen (app/debug.tsx) can submit
// typed text into the EXACT SAME conversation pipeline voice uses (handleIncomingText, inside the
// BensonApp component in app/index.tsx) instead of a second, lower-level entry point. Per the
// standing "user owns all visual/UI design" boundary, this file touches no component, no style —
// it is only a call-through + a reply subscription, wired up from inside app/index.tsx itself.
type SubmitFn = (text: string, requestId?: string) => void;
type ReplyListener = (text: string) => void;

let submitFn: SubmitFn | null = null;
const replyListeners = new Set<ReplyListener>();

// Called once from app/index.tsx (useEffect) with a closure over the real handleIncomingText.
export function setChatSubmit(fn: SubmitFn | null): void {
  submitFn = fn;
}

// ROUND_INPUT_ROUTING_1 (2026-09-23) — requestId is optional and purely a trace correlator: the
// caller (app/debug.tsx) logs the raw field value under this id BEFORE this function's own
// trim(), and handleIncomingText logs the same id at MISSION_INPUT — so the exact same submission
// can be compared field-value-at-submit vs. what reached mission parsing, instead of guessing
// where a discrepancy was introduced. Never required; omitting it changes no behavior.
export function submitTypedText(text: string, requestId?: string): boolean {
  const t = text.trim();
  if (!t || !submitFn) return false;
  submitFn(t, requestId);
  return true;
}

export function isChatSubmitReady(): boolean {
  return submitFn !== null;
}

// Called from app/index.tsx's addMessage('benson', ...) — the ONE place a final BENSON reply is
// produced, regardless of whether the turn started as voice or typed text. Ensures "the same
// response feeds text and voice" instead of a second, divergent reply surface for typed input.
export function notifyBensonReply(text: string): void {
  for (const l of replyListeners) {
    try { l(text); } catch { /* a listener's own error must never break the reply pipeline */ }
  }
}

export function onBensonReply(listener: ReplyListener): () => void {
  replyListeners.add(listener);
  return () => { replyListeners.delete(listener); };
}
