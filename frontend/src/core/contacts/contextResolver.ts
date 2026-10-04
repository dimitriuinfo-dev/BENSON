// ROUND_CONTACT_IDENTITY_CONTINUITY_1 — links a PersonReference (LLM's structured read of the
// utterance, see lib/engines/types.ts) to a real, previously-verified identity or a local contact.
// Entirely local: never calls the LLM, never sends contacts/phone numbers anywhere. The LLM
// interprets language; this resolves it against reality.

import type { PersonReference } from '../../../lib/engines/types';
import type { TrustedContact } from './contactTypes';
import { searchContacts, normalizeName, namesPhoneticallyMatch } from './contactResolver';
import type { VerifiedIdentity } from './verifiedIdentityStore';

export type ResolutionStatus = 'RESOLVED' | 'NEEDS_CONFIRMATION' | 'UNRESOLVED';

export interface ResolvedPerson {
  stableContactId?: string;
  normalizedPhoneNumber?: string;
  verifiedDisplayName: string;
  resolutionSource: 'recent_verified' | 'verified_alias' | 'learned_relation' | 'exact_contact' | 'fuzzy_contact';
  confidence: number;
  // BENSON CONTACT+CALL round (2026-09-23) — looked up from localContacts by stableContactId when
  // available (VerifiedIdentity itself doesn't store a photo); undefined when there's no local
  // contact link or the OS has no photo for it.
  imageUri?: string;
}

export interface ResolutionCandidate {
  verifiedDisplayName: string;
  source: string;
  // BENSON CONTACT+CALL round (2026-09-23) — added so a pending "Te referi la X?" can show a real
  // number/photo, not just a name, and so a chosen candidate carries its opaque contact id through
  // (never invented — undefined when the candidate came from a VerifiedIdentity with no local
  // contact link, e.g. 'verified_alias'/'learned_relation' sources).
  contactId?: string;
  phoneNumber?: string;
  imageUri?: string;
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

function toResolved(
  v: VerifiedIdentity, source: ResolvedPerson['resolutionSource'], confidence: number, localContacts: TrustedContact[] = [],
): ResolvedPerson {
  const linked = v.stableContactId ? localContacts.find((c) => c.id === v.stableContactId) : undefined;
  return {
    stableContactId: v.stableContactId,
    normalizedPhoneNumber: v.normalizedPhoneNumber,
    verifiedDisplayName: v.verifiedDisplayName,
    resolutionSource: source,
    confidence,
    imageUri: linked?.imageUri,
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

// Named-identity matching uses the same shared phonetic comparator as live device contacts below.
export function resolvePerson(ctx: ResolveContext): ResolutionResult {
  const { personRef, verifiedIdentities, localContacts } = ctx;

  // 1. RECENT_PERSON / PRONOUN — no nameable word was said; only continuity with the most
  // recently used verified identity applies. Never falls back to guessing a local contact.
  if (personRef.referenceType === 'RECENT_PERSON' || personRef.referenceType === 'PRONOUN') {
    const mostRecent = [...verifiedIdentities].sort((a, b) => b.lastUsedAt - a.lastUsedAt)[0];
    if (mostRecent) return { status: 'RESOLVED', resolved: toResolved(mostRecent, 'recent_verified', 0.9, localContacts) };
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
      if (verified) return { status: 'RESOLVED', resolved: toResolved(verified, 'learned_relation', 0.85, localContacts) };
      return { status: 'NEEDS_CONFIRMATION', candidates: [{ verifiedDisplayName: relMatches[0].displayName, source: 'relation_unverified', contactId: relMatches[0].id, phoneNumber: relMatches[0].phoneNumbers?.[0], imageUri: relMatches[0].imageUri }] };
    }
    if (relMatches.length > 1) {
      return { status: 'NEEDS_CONFIRMATION', candidates: relMatches.slice(0, 3).map((c) => ({ verifiedDisplayName: c.displayName, source: 'relation', contactId: c.id, phoneNumber: c.phoneNumbers?.[0], imageUri: c.imageUri })) };
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
  if (exactAlias.length === 1) return { status: 'RESOLVED', resolved: toResolved(exactAlias[0], 'verified_alias', 0.95, localContacts) };
  if (exactAlias.length > 1) {
    return { status: 'NEEDS_CONFIRMATION', candidates: exactAlias.slice(0, 3).map((v) => ({ verifiedDisplayName: v.verifiedDisplayName, source: 'verified_alias', contactId: v.stableContactId, phoneNumber: v.normalizedPhoneNumber })) };
  }

  // 3b. verified alias — phonetic ("Hana"/"Luhana" -> "Hannah"). Unique match resolves; more than
  // one still-plausible verified identity asks rather than guesses.
  const fuzzyAlias = verifiedIdentities.filter((v) =>
    namesPhoneticallyMatch(normSurface, normalizeName(v.verifiedDisplayName)) ||
    v.observedAliases.some((a) => namesPhoneticallyMatch(normSurface, normalizeName(a))));
  if (fuzzyAlias.length === 1) return { status: 'RESOLVED', resolved: toResolved(fuzzyAlias[0], 'verified_alias', 0.8, localContacts) };
  if (fuzzyAlias.length > 1) {
    return { status: 'NEEDS_CONFIRMATION', candidates: fuzzyAlias.slice(0, 3).map((v) => ({ verifiedDisplayName: v.verifiedDisplayName, source: 'verified_alias', contactId: v.stableContactId, phoneNumber: v.normalizedPhoneNumber })) };
  }

  // 4. exact local contact match — known, but never verified on-screen by BENSON itself yet.
  const exactContacts = searchContacts(surface, localContacts, 5).filter((c) => normalizeName(c.displayName) === normSurface);
  if (exactContacts.length === 1) return { status: 'NEEDS_CONFIRMATION', candidates: [{ verifiedDisplayName: exactContacts[0].displayName, source: 'exact_contact', contactId: exactContacts[0].id, phoneNumber: exactContacts[0].phoneNumbers?.[0], imageUri: exactContacts[0].imageUri }] };
  if (exactContacts.length > 1) {
    return { status: 'NEEDS_CONFIRMATION', candidates: exactContacts.slice(0, 3).map((c) => ({ verifiedDisplayName: c.displayName, source: 'exact_contact', contactId: c.id, phoneNumber: c.phoneNumbers?.[0], imageUri: c.imageUri })) };
  }

  // 5. phonetic/fuzzy local candidates — always a confirmation, never an execution (the round's
  // own rule: "nu selectează niciodată arbitrar alt contact").
  const fuzzyContacts = searchContacts(surface, localContacts, 5);
  if (fuzzyContacts.length > 0) {
    return { status: 'NEEDS_CONFIRMATION', candidates: fuzzyContacts.slice(0, 3).map((c) => ({ verifiedDisplayName: c.displayName, source: 'fuzzy_contact', contactId: c.id, phoneNumber: c.phoneNumbers?.[0], imageUri: c.imageUri })) };
  }

  // 6. stop safely — no invented contact.
  return { status: 'UNRESOLVED' };
}
