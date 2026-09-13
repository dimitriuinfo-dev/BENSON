// node --experimental-strip-types --test src/core/contacts/contactResolver.test.ts
// RECOVERY_L4 (2026-09-13) — the LIVE resolver used by whatsappTool.ts for CALL/WRITE contact
// resolution today (contextResolver.ts / PERSON_RESOLVE is disabled at its app/index.tsx call
// site; this file was previously untested). Pure-logic tests, zero mocks needed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveContact, normalizeName } from './contactResolver.ts';
import type { TrustedContact } from './contactTypes.ts';

function contact(overrides: Partial<TrustedContact> & { id: string; displayName: string }): TrustedContact {
  return { phoneNumbers: ['+40712345678'], ...overrides };
}

const hannah = contact({ id: '1', displayName: 'Hannah' });
const baby = contact({ id: '2', displayName: 'Baby' });

test('Hannah: exact display-name match resolves directly, no ambiguity', () => {
  const r = resolveContact({ rawName: 'Hannah', preferredChannel: 'whatsapp', contacts: [hannah, baby] });
  assert.equal(r.status, 'resolved');
  assert.equal(r.contact?.id, '1');
});

test('Hana: no exact/alias/partial match, fuzzy tier rescues it to Hannah', () => {
  const r = resolveContact({ rawName: 'Hana', preferredChannel: 'whatsapp', contacts: [hannah, baby] });
  assert.equal(r.status, 'resolved');
  assert.equal(r.contact?.id, '1');
});

test('Baby: exact display-name match resolves directly', () => {
  const r = resolveContact({ rawName: 'Baby', preferredChannel: 'whatsapp', contacts: [hannah, baby] });
  assert.equal(r.status, 'resolved');
  assert.equal(r.contact?.id, '2');
});

test('an exact match is never overridden by a fuzzy match elsewhere in the list', () => {
  // "Hanna" (a contact whose name is fuzzy-close to the query "Hana") must not beat an exact
  // "Hana" contact if one exists.
  const exactHana = contact({ id: '3', displayName: 'Hana' });
  const fuzzyHanna = contact({ id: '4', displayName: 'Hanna' });
  const r = resolveContact({ rawName: 'Hana', preferredChannel: 'whatsapp', contacts: [fuzzyHanna, exactHana] });
  assert.equal(r.status, 'resolved');
  assert.equal(r.contact?.id, '3');
});

test('two real contacts fuzzy-matching the same query produce ambiguous, not a silent pick', () => {
  const hanna1 = contact({ id: '5', displayName: 'Hanna Popescu' });
  const hanna2 = contact({ id: '6', displayName: 'Hanna Ionescu' });
  const r = resolveContact({ rawName: 'Hana', preferredChannel: 'whatsapp', contacts: [hanna1, hanna2] });
  assert.equal(r.status, 'ambiguous');
});

test('an unrelated name never resolves and never fuzzy-matches a short, dissimilar query', () => {
  const unrelated = contact({ id: '7', displayName: 'Peter Pane Johannes Gunzel' });
  const r = resolveContact({ rawName: 'Hana', preferredChannel: 'whatsapp', contacts: [unrelated] });
  assert.equal(r.status, 'not_found');
});

test('a verified alias (exact) resolves even when it differs from the display name', () => {
  const withAlias = contact({ id: '8', displayName: 'H. Muller', aliases: ['Hannah'] });
  const r = resolveContact({ rawName: 'Hannah', preferredChannel: 'whatsapp', contacts: [withAlias] });
  assert.equal(r.status, 'resolved');
  assert.equal(r.contact?.id, '8');
});

test('a contact with no phone number is missing_phone for a whatsapp-channel request', () => {
  const noPhone = contact({ id: '9', displayName: 'Hannah', phoneNumbers: [] });
  const r = resolveContact({ rawName: 'Hannah', preferredChannel: 'whatsapp', contacts: [noPhone] });
  assert.equal(r.status, 'missing_phone');
});

test('normalizeName strips diacritics and case for comparison', () => {
  assert.equal(normalizeName('Ștefan'), normalizeName('stefan'));
});
