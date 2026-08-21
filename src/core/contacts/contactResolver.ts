// BENSON Contact Resolver — turns a spoken/typed name or alias into a contact candidate list.
// Pure function over an already-supplied contact array: no device contacts access, no storage,
// no permissions. Whoever calls this (a future executor) is responsible for supplying the
// TrustedContact[] from wherever it actually lives.

import type { ContactResolveRequest, ContactResolveResult, TrustedContact } from './contactTypes';

// Lowercase + trim + strip Romanian/German diacritics where practical, so "Ștefan"/"stefan",
// "Mamă"/"mama", "Müller"/"muller" all normalize to the same comparable string. NFD decomposition
// handles most accented letters (ă, â, î, ș, ț, ä, ö, ü -> base letter + combining mark, which we
// then strip); ß has no such decomposition, so it's replaced explicitly.
export function normalizeName(input: string): string {
  return input
    .trim()
    .toLowerCase()
    .replace(/ß/g, 'ss')
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '');
}

function matchesExact(candidate: string, normalizedQuery: string): boolean {
  return normalizeName(candidate) === normalizedQuery;
}

function matchesPartial(candidate: string, normalizedQuery: string): boolean {
  const normalizedCandidate = normalizeName(candidate);
  return normalizedCandidate.includes(normalizedQuery) || normalizedQuery.includes(normalizedCandidate);
}

// Confirmed live (2026-07-17): the same physical contact can be enumerated more than once by
// expo-contacts (e.g. linked/synced across accounts at the OS level) even though the device's own
// Contacts app shows it once — the raw ContactsProvider query for "Baby" returned exactly one row,
// yet the candidate list here had it twice. Two entries with the same name AND the same phone
// number aren't a real choice for the user to make ("which Baby?" has no meaningful answer when
// both options dial the identical number) — collapse them before ambiguity is ever decided.
function dedupeContacts(contacts: TrustedContact[]): TrustedContact[] {
  const seen = new Map<string, TrustedContact>();
  for (const c of contacts) {
    const key = `${normalizeName(c.displayName)}|${(c.phoneNumbers ?? []).join(',')}`;
    if (!seen.has(key)) seen.set(key, c);
  }
  return [...seen.values()];
}

// Tiered matching: try the strongest tier first (exact displayName), and only fall through to a
// weaker tier if the current one found nothing at all. A tier that finds 2+ hits is ambiguous
// right there — it doesn't matter whether a weaker tier might have found fewer/other matches.
function findMatches(normalizedQuery: string, contacts: TrustedContact[]): TrustedContact[] {
  const exactDisplayName = dedupeContacts(contacts.filter((c) => matchesExact(c.displayName, normalizedQuery)));
  if (exactDisplayName.length > 0) return exactDisplayName;

  const exactAlias = dedupeContacts(contacts.filter((c) => (c.aliases ?? []).some((a) => matchesExact(a, normalizedQuery))));
  if (exactAlias.length > 0) return exactAlias;

  const partialDisplayName = dedupeContacts(contacts.filter((c) => matchesPartial(c.displayName, normalizedQuery)));
  if (partialDisplayName.length > 0) return partialDisplayName;

  const partialAlias = dedupeContacts(contacts.filter((c) => (c.aliases ?? []).some((a) => matchesPartial(a, normalizedQuery))));
  if (partialAlias.length > 0) return partialAlias;

  return [];
}

// Read-only name search (Item 1: "arată-mi contactele" / "caută-l pe X" / "cine e X") — unlike
// resolveContact, never requires a phone number and never returns 'ambiguous'/'missing_phone'
// statuses; it just ranks candidates for the caller to speak, capped at maxResults so a broad
// query never reads the whole address book aloud.
export function searchContacts(rawName: string, contacts: TrustedContact[], maxResults = 5): TrustedContact[] {
  const normalizedQuery = normalizeName(rawName);
  if (!normalizedQuery) return [];
  return findMatches(normalizedQuery, contacts).slice(0, maxResults);
}

export function resolveContact(request: ContactResolveRequest): ContactResolveResult {
  const normalizedQuery = normalizeName(request.rawName);

  if (!normalizedQuery) {
    return { status: 'not_found', normalizedQuery, message: 'No name was given.' };
  }

  const matches = findMatches(normalizedQuery, request.contacts);

  if (matches.length === 0) {
    return {
      status: 'not_found',
      normalizedQuery,
      message: `No contact matching "${request.rawName}" was found.`,
    };
  }

  if (matches.length > 1) {
    return {
      status: 'ambiguous',
      candidates: matches,
      normalizedQuery,
      message: `Multiple contacts match "${request.rawName}": ${matches.map((m) => m.displayName).join(', ')}. Which one?`,
    };
  }

  const contact = matches[0];
  const channel = request.preferredChannel;
  const needsPhone = channel === 'phone' || channel === 'whatsapp' || channel === 'sms';

  if (needsPhone && (!contact.phoneNumbers || contact.phoneNumbers.length === 0)) {
    return {
      status: 'missing_phone',
      contact,
      normalizedQuery,
      message: `${contact.displayName} has no phone number saved.`,
    };
  }

  return { status: 'resolved', contact, normalizedQuery, message: `Resolved to ${contact.displayName}.` };
}
