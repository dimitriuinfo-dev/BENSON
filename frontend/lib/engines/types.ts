// BENSON's swappable engine layer (STT / LLM / TTS) — Task 1 of the "GO" round (2026-08-27).
// Every engine is selected and configured entirely from Settings (baseUrl/model/apiKey — see
// app/settings.tsx's "NUCLEE" section and registry.ts), never hardcoded to a single provider
// anywhere in code. This file is types only — no fetch calls, no storage access.

// ── STT ──────────────────────────────────────────────────────────────────────────────────────
export interface SttEngine {
  id: string;
  transcribe(wavPath: string, lang: string): Promise<string>;
}

// ── LLM / Brain — anti-corruption message channels (Task 4.1) ──────────────────────────────────
//
// Exactly three kinds of Turn, never a fourth. Each carries a private `__brand` field so the
// three are nominally, not just structurally, typed: a function declared to accept
// `SystemTurn | UserVoiceTurn` rejects an `UntrustedDataTurn` at compile time even though all
// three share the same `{ role, text }` shape — turning one into another requires an explicit,
// visible reconstruction through the matching constructor in lib/engines/llm/messageChannels.ts,
// never a silent structural coercion or cast. That file is also the ONLY place allowed to
// construct these — see its own header comment for the (review-enforced) discipline on top of
// this type-level guarantee, in particular that SYSTEM text may only ever come from compiled-in
// constants.
export type ChannelRole = 'SYSTEM' | 'USER_VOICE' | 'UNTRUSTED_DATA';

export interface SystemTurn { readonly role: 'SYSTEM'; readonly text: string; readonly __brand: 'system' }
export interface UserVoiceTurn { readonly role: 'USER_VOICE'; readonly text: string; readonly __brand: 'user_voice' }
export interface UntrustedDataTurn { readonly role: 'UNTRUSTED_DATA'; readonly text: string; readonly __brand: 'untrusted_data' }
export type Turn = SystemTurn | UserVoiceTurn | UntrustedDataTurn;

export type ChatOpts = { temperature?: number };

// The action vocabulary the Brain is allowed to propose (Task 4.2) — a closed enum, not a free
// string, so an injected instruction has no representable form to become an action through.
// Deliberately small and PROVISIONAL: this is a new contract introduced this round, not yet wired
// to the live mission system (lib/agents/missionValidator.ts / missionExecutor.ts are out of this
// round's allowed-file scope — see GO_REPORT.md). Extend this enum — never accept a raw string —
// when real actions are wired in.
export type KnownAction =
  | 'open_app'
  | 'call_contact'
  | 'send_whatsapp_message'
  | 'navigate'
  | 'search_web'
  | 'set_reminder';

export const KNOWN_ACTIONS: readonly KnownAction[] = [
  'open_app', 'call_contact', 'send_whatsapp_message', 'navigate', 'search_web', 'set_reminder',
];

// ROUND_CONTACT_IDENTITY_CONTINUITY_1 — the LLM's structured read of a PERSON REFERENCE in the
// utterance. Deliberately narrow: no phoneNumber, no contactId, no verified/display name field —
// the LLM interprets language, it never invents or resolves an identity. surfaceText is exactly
// what the user said (verbatim substring), never corrected/re-spelled by the model. Resolution
// against reality happens entirely in contextResolver.ts, outside the LLM.
export type PersonReferenceType = 'NAMED' | 'PRONOUN' | 'RECENT_PERSON' | 'RELATION';
export interface PersonReference {
  surfaceText: string | null; // null for PRONOUN/RECENT_PERSON/RELATION (nothing nameable was said)
  referenceType: PersonReferenceType;
  relation?: string; // only for RELATION, e.g. "wife" — still not a name, never invented
}

// `confidence` (0..1, optional) — the model's own stated certainty about the classification, used
// only for logging (BRAIN_INTENT … confidence=…), never as a gate. Absent when the model didn't
// provide one.
export type BrainOutput =
  | { kind: 'speak'; text: string }
  | {
      kind: 'action';
      action: KnownAction;
      params: Record<string, string>;
      confidence?: number;
      entities?: { person?: PersonReference };
    }
  | { kind: 'clarify'; question: string; confidence?: number };

export interface LlmBrain {
  id: string;
  chat(turns: Turn[], opts?: ChatOpts): Promise<BrainOutput>;
}

// ── TTS ──────────────────────────────────────────────────────────────────────────────────────
export interface TtsVoice {
  id: string;
  speak(text: string, lang: string): Promise<void>;
}

// ── Engine configuration (Settings-driven, never hardcoded) ────────────────────────────────────
export type EngineConfig = {
  baseUrl: string;
  model: string;
  apiKey: string;
};
