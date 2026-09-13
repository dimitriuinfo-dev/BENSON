// node --experimental-strip-types --test src/core/contacts/contextResolver.test.ts
// Pure-logic tests, zero mocks needed (resolvePerson takes plain data in, plain data out).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolvePerson } from './contextResolver.ts';
import type { VerifiedIdentity } from './verifiedIdentityStore.ts';
import type { TrustedContact } from './contactTypes.ts';

function hannah(overrides: Partial<VerifiedIdentity> = {}): VerifiedIdentity {
  return {
    verifiedDisplayName: 'Hannah',
    provider: 'whatsapp',
    observedAliases: ['Hana'],
    verificationSource: 'call_header',
    verifiedAt: Date.now() - 5 * 60_000,
    lastUsedAt: Date.now() - 5 * 60_000,
    successfulUseCount: 1,
    ...overrides,
  };
}

// A. Hannah verificată -> "scrie-i Hanei" (surfaceText="Hanei", NAMED) -> RESOLVED Hannah.
test('A: verified Hannah + NAMED "Hanei" resolves to Hannah', () => {
  const r = resolvePerson({
    personRef: { surfaceText: 'Hanei', referenceType: 'NAMED' },
    verifiedIdentities: [hannah()],
    localContacts: [],
  });
  assert.equal(r.status, 'RESOLVED');
  assert.equal(r.resolved?.verifiedDisplayName, 'Hannah');
  assert.equal(r.resolved?.resolutionSource, 'verified_alias');
});

// B. STT "Luhana" (unique candidate) -> resolves Hannah via phonetic closeness.
test('B: NAMED "Luhana" (STT variant) resolves to Hannah when unique', () => {
  const r = resolvePerson({
    personRef: { surfaceText: 'Luhana', referenceType: 'NAMED' },
    verifiedIdentities: [hannah()],
    localContacts: [],
  });
  assert.equal(r.status, 'RESOLVED');
  assert.equal(r.resolved?.verifiedDisplayName, 'Hannah');
});

// "scrie-i" alone (RECENT_PERSON, no name said) -> most recently used verified identity.
test('RECENT_PERSON with no surfaceText resolves to the most recently used verified identity', () => {
  const older = hannah({ verifiedDisplayName: 'Ana', observedAliases: [], lastUsedAt: Date.now() - 60_000 });
  const newer = hannah({ lastUsedAt: Date.now() });
  const r = resolvePerson({
    personRef: { surfaceText: null, referenceType: 'RECENT_PERSON' },
    verifiedIdentities: [older, newer],
    localContacts: [],
  });
  assert.equal(r.status, 'RESOLVED');
  assert.equal(r.resolved?.verifiedDisplayName, 'Hannah');
  assert.equal(r.resolved?.resolutionSource, 'recent_verified');
});

// RELATION resolved only once verified.
test('RELATION "wife" resolves only when the matching contact is also a verified identity', () => {
  const wife: TrustedContact = { id: 'c1', displayName: 'Hannah', relation: 'wife' };
  const r = resolvePerson({
    personRef: { surfaceText: null, referenceType: 'RELATION', relation: 'wife' },
    verifiedIdentities: [hannah({ stableContactId: 'c1' })],
    localContacts: [wife],
  });
  assert.equal(r.status, 'RESOLVED');
  assert.equal(r.resolved?.resolutionSource, 'learned_relation');
});

test('RELATION known locally but never verified asks instead of executing', () => {
  const wife: TrustedContact = { id: 'c1', displayName: 'Hannah', relation: 'wife' };
  const r = resolvePerson({
    personRef: { surfaceText: null, referenceType: 'RELATION', relation: 'wife' },
    verifiedIdentities: [],
    localContacts: [wife],
  });
  assert.equal(r.status, 'NEEDS_CONFIRMATION');
});

// C. Hannah AND Ana both plausible candidates -> NEEDS_CONFIRMATION, never auto-picks.
test('C: two plausible verified candidates ask for confirmation, never auto-pick', () => {
  const ana = hannah({ verifiedDisplayName: 'Hana', observedAliases: [] }); // a second, DIFFERENT real person actually named "Hana"
  const r = resolvePerson({
    personRef: { surfaceText: 'Hana', referenceType: 'NAMED' },
    verifiedIdentities: [hannah(), ana],
    localContacts: [],
  });
  assert.equal(r.status, 'NEEDS_CONFIRMATION');
  assert.ok((r.candidates?.length ?? 0) <= 3);
});

// D. No history, no confident candidate -> UNRESOLVED, never invents a contact.
test('D: unknown name with no history and no local contact is UNRESOLVED', () => {
  const r = resolvePerson({
    personRef: { surfaceText: 'Zbigniew', referenceType: 'NAMED' },
    verifiedIdentities: [],
    localContacts: [],
  });
  assert.equal(r.status, 'UNRESOLVED');
});

// E. A verified identity is reused across missions (call now, message 5 min later) without
// re-asking "how do you spell it" — same alias-match path as test A, just phrased as continuity.
test('E: identity verified at call time is reused at message time via the same alias match', () => {
  const r = resolvePerson({
    personRef: { surfaceText: 'Hana', referenceType: 'NAMED' },
    verifiedIdentities: [hannah()],
    localContacts: [],
  });
  assert.equal(r.status, 'RESOLVED');
  assert.equal(r.resolved?.verifiedDisplayName, 'Hannah');
});

test('an exact local contact match (never verified on-screen) still asks rather than executing', () => {
  const c: TrustedContact = { id: 'c9', displayName: 'Stefan' };
  const r = resolvePerson({
    personRef: { surfaceText: 'Stefan', referenceType: 'NAMED' },
    verifiedIdentities: [],
    localContacts: [c],
  });
  assert.equal(r.status, 'NEEDS_CONFIRMATION');
});

test('never returns more than 3 candidates', () => {
  const many: TrustedContact[] = Array.from({ length: 6 }, (_, i) => ({ id: `c${i}`, displayName: `Ionela${i}` }));
  const r = resolvePerson({
    personRef: { surfaceText: 'Ionela', referenceType: 'NAMED' },
    verifiedIdentities: [],
    localContacts: many,
  });
  assert.ok((r.candidates?.length ?? 0) <= 3);
});
