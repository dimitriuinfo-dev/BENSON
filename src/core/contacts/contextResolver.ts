// ROUND_CONTACT_IDENTITY_CONTINUITY_1 — links a PersonReference (LLM's structured read of the
// utterance, see lib/engines/types.ts) to a real, previously-verified identity or a local contact.
// Entirely local: never calls the LLM, never sends contacts/phone numbers anywhere. The LLM
// interprets language; this resolves it against reality.

import type { PersonReference } from '../../../lib/engines/types';
import type { TrustedContact } from './contactTypes';
import { searchContacts, normalizeName } from './contactResolver';
import type { VerifiedIdentity } from './verifiedIdentityStore';

export type ResolutionStatus = 'RESOLVED' | 'NEEDS_CONFIRMATION' | 'UNRESOLVED';

export interface ResolvedPerson {
  stableContactId?: string;
  normalizedPhoneNumber?: string;
  verifiedDisplayName: string;
  resolutionSource: 'recent_verified' | 'verified_alias' | 'learned_relation' | 'exact_contact' | 'fuzzy_contact';
  confidence: number;
}

export interface ResolutionCandidate {
  verifiedDisplayName: string;
  source: string;
}

export interface ResolutionResult {
  status: ResolutionStatus;
  resolved?: ResolvedPerson;
  candidates?: ResolutionCandidate[]; // never more than 3
}

export interface ResolveContext {
  personRef: PersonReference;
  verifiedIdentities: VerifiedIdentity[];
  localContacts: TrustedContact[];
}

function toResolved(v: VerifiedIdentity, source: ResolvedPerson['resolutionSource'], confidence: number): ResolvedPerson {
  return {
    stableContactId: v.stableContactId,
    normalizedPhoneNumber: v.normalizedPhoneNumber,
    verifiedDisplayName: v.verifiedDisplayName,
    resolutionSource: source,
    confidence,
  };
}

function fromContact(c: TrustedContact, source: ResolvedPerson['resolutionSource'], confidence: number): ResolvedPerson {
  return {
    stableContactId: c.id,
    normalizedPhoneNumber: c.phoneNumbers?.[0],
    verifiedDisplayName: c.displayName,
    resolutionSource: source,
    confidence,
  };
}

// Small self-contained Levenshtein — contactResolver.ts's own fuzzy helpers are private to that
// file; duplicated here rather than exported cross-purpose, same "no shared dependency, literal
// duplicate" idiom already used elsewhere in this codebase (NativeCloudWake vs BensonAudioCapture).
function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  const m = a.length, n = b.length;
  if (m === 0) return n;
  if (n === 0) return m;
  let prev = Array.from({ length: n + 1 }, (_, i) => i);
  for (let i = 1; i <= m; i++) {
    const cur = [i];
    for (let j = 1; j <= n; j++) {
      cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + (a[i - 1] === b[j - 1] ? 0 : 1));
    }
    prev = cur;
  }
  return prev[n];
}

// A phonetic STT variant ("Hana"/"Luhana" for "Hannah") is typically a substring/superstring or a
// short edit distance away — never an exact match, or normalizeName's own exact check above
// would already have caught it.
function phoneticallyClose(a: string, b: string): boolean {
  if (a.length < 2 || b.length < 2) return false;
  if (a.includes(b) || b.includes(a)) return true;
  const maxDist = Math.max(a.length, b.length) >= 5 ? 2 : 1;
  return levenshtein(a, b) <= maxDist;
}

export function resolvePerson(ctx: ResolveContext): ResolutionResult {
  const { personRef, verifiedIdentities, localContacts } = ctx;

  // 1. RECENT_PERSON / PRONOUN — no nameable word was said; only continuity with the most
  // recently used verified identity applies. Never falls back to guessing a local contact.
  if (personRef.referenceType === 'RECENT_PERSON' || personRef.referenceType === 'PRONOUN') {
    const mostRecent = [...verifiedIdentities].sort((a, b) => b.lastUsedAt - a.lastUsedAt)[0];
    if (mostRecent) return { status: 'RESOLVED', resolved: toResolved(mostRecent, 'recent_verified', 0.9) };
    return { status: 'UNRESOLVED' };
  }

  // 2. RELATION — a learned relation counts only once it has ALSO been verified on-screen at
  // least once; a relation known only from raw local contacts, never verified, asks rather than
  // executes (still safer than silently trusting an unverified device-contacts relation field).
  if (personRef.referenceType === 'RELATION' && personRef.relation) {
    const rel = personRef.relation.toLowerCase();
    const relMatches = localContacts.filter((c) => c.relation && c.relation.toLowerCase() === rel);
    if (relMatches.length === 1) {
      const verified = verifiedIdentities.find((v) =>
        v.stableContactId === relMatches[0].id || v.verifiedDisplayName === relMatches[0].displayName);
      if (verified) return { status: 'RESOLVED', resolved: toResolved(verified, 'learned_relation', 0.85) };
      return { status: 'NEEDS_CONFIRMATION', candidates: [{ verifiedDisplayName: relMatches[0].displayName, source: 'relation_unverified' }] };
    }
    if (relMatches.length > 1) {
      return { status: 'NEEDS_CONFIRMATION', candidates: relMatches.slice(0, 3).map((c) => ({ verifiedDisplayName: c.displayName, source: 'relation' })) };
    }
    return { status: 'UNRESOLVED' };
  }

  // 3. NAMED — surfaceText given, resolved against reality, never against the LLM's own spelling.
  const surface = (personRef.surfaceText || '').trim();
  if (!surface) return { status: 'UNRESOLVED' };
  const normSurface = normalizeName(surface);

  // 3a. verified alias — exact. Checked as a filter, not find(): two different verified people
  // can each have an exact claim on the same surface text (one's display name, another's stored
  // alias) — that is a real ambiguity, never silently resolved to "whichever came first".
  const exactAlias = verifiedIdentities.filter((v) =>
    normalizeName(v.verifiedDisplayName) === normSurface || v.observedAliases.some((a) => normalizeName(a) === normSurface));
  if (exactAlias.length === 1) return { status: 'RESOLVED', resolved: toResolved(exactAlias[0], 'verified_alias', 0.95) };
  if (exactAlias.length > 1) {
    return { status: 'NEEDS_CONFIRMATION', candidates: exactAlias.slice(0, 3).map((v) => ({ verifiedDisplayName: v.verifiedDisplayName, source: 'verified_alias' })) };
  }

  // 3b. verified alias — phonetic ("Hana"/"Luhana" -> "Hannah"). Unique match resolves; more than
  // one still-plausible verified identity asks rather than guesses.
  const fuzzyAlias = verifiedIdentities.filter((v) =>
    phoneticallyClose(normSurface, normalizeName(v.verifiedDisplayName)) ||
    v.observedAliases.some((a) => phoneticallyClose(normSurface, normalizeName(a))));
  if (fuzzyAlias.length === 1) return { status: 'RESOLVED', resolved: toResolved(fuzzyAlias[0], 'verified_alias', 0.8) };
  if (fuzzyAlias.length > 1) {
    return { status: 'NEEDS_CONFIRMATION', candidates: fuzzyAlias.slice(0, 3).map((v) => ({ verifiedDisplayName: v.verifiedDisplayName, source: 'verified_alias' })) };
  }

  // 4. exact local contact match — known, but never verified on-screen by BENSON itself yet.
  const exactContacts = searchContacts(surface, localContacts, 5).filter((c) => normalizeName(c.displayName) === normSurface);
  if (exactContacts.length === 1) return { status: 'NEEDS_CONFIRMATION', candidates: [{ verifiedDisplayName: exactContacts[0].displayName, source: 'exact_contact' }] };
  if (exactContacts.length > 1) {
    return { status: 'NEEDS_CONFIRMATION', candidates: exactContacts.slice(0, 3).map((c) => ({ verifiedDisplayName: c.displayName, source: 'exact_contact' })) };
  }

  // 5. phonetic/fuzzy local candidates — always a confirmation, never an execution (the round's
  // own rule: "nu selectează niciodată arbitrar alt contact").
  const fuzzyContacts = searchContacts(surface, localContacts, 5);
  if (fuzzyContacts.length > 0) {
    return { status: 'NEEDS_CONFIRMATION', candidates: fuzzyContacts.slice(0, 3).map((c) => ({ verifiedDisplayName: c.displayName, source: 'fuzzy_contact' })) };
  }

  // 6. stop safely — no invented contact.
  return { status: 'UNRESOLVED' };
}
