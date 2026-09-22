// ROUND_BENSON_CHAT_1 — pure, non-visual bridge so a diagnostic screen (app/debug.tsx) can submit
// typed text into the EXACT SAME conversation pipeline voice uses (handleIncomingText, inside the
// BensonApp component in app/index.tsx) instead of a second, lower-level entry point. Per the
// standing "user owns all visual/UI design" boundary, this file touches no component, no style —
// it is only a call-through + a reply subscription, wired up from inside app/index.tsx itself.
type SubmitFn = (text: string) => void;
type ReplyListener = (text: string) => void;

let submitFn: SubmitFn | null = null;
const replyListeners = new Set<ReplyListener>();

// Called once from app/index.tsx (useEffect) with a closure over the real handleIncomingText.
export function setChatSubmit(fn: SubmitFn | null): void {
  submitFn = fn;
}

// Returns false if BENSON's conversation pipeline isn't mounted/ready yet — callers should treat
// that as "not available", not silently swallow the text.
export function submitTypedText(text: string): boolean {
  const t = text.trim();
  if (!t || !submitFn) return false;
  submitFn(t);
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
