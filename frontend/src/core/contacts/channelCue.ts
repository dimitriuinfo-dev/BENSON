// BENSON CONTACT+CALL round (2026-09-23) — deterministic channel-cue detection, shared by
// app/index.tsx (Brain-classified calls) and missionPlanner.ts (goal-based calls) so both
// recognize the same wording instead of drifting apart. Only fires on an unambiguous cue — silence
// keeps the existing/default channel unchanged, never guessed.
const PHONE_CHANNEL_PATTERN = /\bnu\s+pe\s+whats\s*app\b|\bnormal\b|\bpe\s+mobil\b|\bpe\s+num[ăa]r\b|\btelefon(?:ic)?\b/i;
const WHATSAPP_CHANNEL_PATTERN = /\bwhats\s*app\b/i;

export function detectChannelCue(text: string): 'phone' | 'whatsapp' | null {
  const t = (text || '').trim();
  if (!t) return null;
  // "nu pe WhatsApp" must win over the bare "whatsapp" mention inside it.
  if (PHONE_CHANNEL_PATTERN.test(t)) return 'phone';
  if (WHATSAPP_CHANNEL_PATTERN.test(t)) return 'whatsapp';
  return null;
}
