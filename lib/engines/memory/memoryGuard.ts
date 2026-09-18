// Task 5 — memory discipline. The one permanent corruption path in this app is a "fact" written
// to long-term memory that changes BENSON's own future behavior instead of recording something
// about the user. This file is the write-time gate for that (5.2) plus the helper for wiring
// remembered facts back into a conversation the way 5.3 requires (never as a rule).
//
// NOT WIRED IN — see GO_REPORT.md for the full account. In short: the function that actually
// executes a memory write, `appendFact()`, lives in app/index.tsx, which is outside this round's
// allowed-file scope (forbidden list). And the LLM-facing entry point that lets a model trigger a
// write at all — the `remember` tool in lib/agents/tools.ts, executed via `ctx.onRememberFact` in
// lib/agents/orchestrator.ts — is also outside this round's allowed-file scope (not on the
// "poți modifica DOAR" list). Both would need to change for Task 5.1's "the brain must never be
// able to write memory on its own" to actually hold; neither was touched. checkMemoryWrite() below
// is the ready-to-call filter for wherever appendFact() ends up gated.
import { logAudioDiag } from 'benson-foreground-service';
import { buildUntrustedDataTurn } from '../llm/messageChannels';
import type { UntrustedDataTurn } from '../types';

// Patterns that would change BENSON's own behavior rather than record a fact about the user —
// Romanian, German, English, matched case-insensitively. A memory write containing one of these is
// rejected outright, never partially sanitized and stored.
const BEHAVIOR_CHANGE_PATTERNS: RegExp[] = [
  // Romanian
  /nu\s+mai\s+cere\s+confirmare/i,
  /ignor[ăa]/i,
  /de\s+acum\s+[îi]nainte/i,
  /regula\s+ta/i,
  /nu\s+mai\s+[îi]ntreba/i,
  // German
  /frag[e]?\s+nicht\s+mehr/i,
  /ignorier/i,
  /ab\s+jetzt/i,
  /deine\s+regel/i,
  // English
  /don'?t\s+ask\s+(for\s+confirmation|again)/i,
  /ignore\s+(the\s+rules|your\s+(instructions|rules))/i,
  /from\s+now\s+on/i,
  /your\s+(new\s+)?rule/i,
  /no\s+longer\s+ask/i,
];

export type MemoryWriteVerdict = { allowed: true } | { allowed: false; reason: string };

// Task 5.2 — call this immediately before any actual persistence of a remembered fact. Rejects
// and logs; never partially stores.
export function checkMemoryWrite(fact: string): MemoryWriteVerdict {
  const text = fact.trim();
  if (!text) {
    return { allowed: false, reason: 'empty' };
  }
  for (const pattern of BEHAVIOR_CHANGE_PATTERNS) {
    if (pattern.test(text)) {
      logAudioDiag('MEMORY_REJECTED', `reason=behavior_change raw="${text}"`);
      return { allowed: false, reason: 'behavior_change' };
    }
  }
  return { allowed: true };
}

// Task 5.3 — "memoria nu intră niciodată în canalul SYSTEM. Intră ca USER_VOICE sau ca context
// marcat — niciodată ca reguli." Remembered facts inform an answer; they never command behavior,
// so they're wrapped exactly like any other read-only data (UNTRUSTED_DATA, never SYSTEM).
export function buildMemoryContextTurn(facts: readonly string[]): UntrustedDataTurn | null {
  if (facts.length === 0) return null;
  return buildUntrustedDataTurn(`Remembered facts about the user:\n- ${facts.join('\n- ')}`);
}
