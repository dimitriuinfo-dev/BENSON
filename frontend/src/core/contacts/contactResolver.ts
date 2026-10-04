// BENSON Contact Resolver — turns a spoken/typed name or alias into a contact candidate list.
// Pure function over an already-supplied contact array: no device contacts access, no storage,
// no permissions. Whoever calls this (a future executor) is responsible for supplying the
// TrustedContact[] from wherever it actually lives.

import type { ContactResolveRequest, ContactResolveResult, TrustedContact } from './contactTypes';
import { logAudioDiag } from 'benson-foreground-service';

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
  if (normalizedQuery.length < 3 || normalizedCandidate.length < 3) return false;
  // A short contact name embedded in a longer STT transcript is not a confident identity:
  // “Hana” contains the distinct contact “Ana”, and previously won the partial tier before the
  // fuzzy Hannah candidate could be considered. Allow a longer contact to contain a query
  // fragment, or a full query token to equal a shorter contact, but never arbitrary containment.
  return normalizedCandidate.includes(normalizedQuery) || normalizedQuery.split(/\s+/).includes(normalizedCandidate);
}

// Levenshtein edit distance — small, dependency-free. Used only in the fuzzy tier below.
function levenshtein(a: string, b: string): number {
  if (a === b) return 0;
  if (a.length === 0) return b.length;
  if (b.length === 0) return a.length;
  let prev = Array.from({ length: b.length + 1 }, (_, i) => i);
  let curr = new Array<number>(b.length + 1);
  for (let i = 1; i <= a.length; i++) {
    curr[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      curr[j] = Math.min(curr[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost);
    }
    [prev, curr] = [curr, prev];
  }
  return prev[b.length];
}

// Two normalized strings are "close" if their edit distance is within ~1/3 of the longer one.
// This is what rescues imperfect voice transcripts: "hana" -> "hannah" (distance 2, threshold 2),
// "steffan" -> "stefan", "adriaan" -> "adriana". Both must be >=3 chars so 1-2 letter fragments
// never fuzzy-match half the address book.
//
// BENSON CONTACT+CALL round (2026-09-23) — ROUND_WHATSAPP_REGRESSION_REVERT_1's root cause, found
// and fixed here: Math.ceil produced a threshold of 2 for a 4-letter query, which let "hana" fuzzy-
// match the unrelated token "pane" (edit distance 2, inside "Peter Pane Johannes Günzel") — the
// exact wrong-candidate collision that forced PERSON_RESOLVE_ENABLED off. Math.floor keeps the
// documented rescues above working (still <= their required distance) while rejecting that
// collision (floor(4/3)=1, distance 2 > 1 -> correctly no match).
// BENSON CONTACT+CALL round (2026-09-23), second fix, found on-device (real test, not a guess):
// the floor() fix above stopped the distance-2 "pane" collision but not distance-1 same-length
// collisions — "hana" (4 letters) fuzzy-matched "dana", "oana", and the token "hans" (all real
// contacts, all edit distance 1) alongside the one correct match (HANNAH), returning 7 candidates
// for a single query. A same-length single-substitution match between two SHORT strings is almost
// always two genuinely different short names, not an STT artifact — the documented rescues
// ("hana"->"hannah", "steffan"->"stefan") are all LENGTH-CHANGING (an added/dropped sound), which
// is what real STT mishears actually look like for a name. Requiring either a length difference or
// both strings being long enough (>=6) keeps every documented rescue working (verified below) while
// rejecting same-length short-word collisions.
function fuzzyClose(a: string, b: string): boolean {
  if (a.length < 3 || b.length < 3) return false;
  if (phoneticClose(a, b)) return true;
  const threshold = Math.floor(Math.max(a.length, b.length) / 3);
  const dist = levenshtein(a, b);
  if (dist > threshold) return false;
  if (a.length === b.length && Math.max(a.length, b.length) < 6) return false;
  return true;
}

// Fuzzy tier: compare the query against the whole normalized candidate AND against each of its
// name tokens, so "hana" matches the "hannah" token inside a "Hannah Müller" display name.
function matchesFuzzy(candidate: string, normalizedQuery: string): boolean {
  const nc = normalizeName(candidate);
  if (fuzzyClose(nc, normalizedQuery)) return true;
  if (phoneticClose(nc, normalizedQuery)) return true;
  return nc.split(/\s+/).some((tok) => fuzzyClose(tok, normalizedQuery) || phoneticClose(tok, normalizedQuery));
}

// When a spoken query is an exact saved display name, only retain a second candidate if its
// individual name token is a plausible STT length-change of that exact name. The broad phonetic
// key intentionally proposes candidates for fuzzy-only queries, but short keys ("baby" → "bebe",
// "bass") are too collision-prone to veto a unique exact hit. Preserve real STT collisions such as
// Hana/Hannah, where the token differs by a dropped/added ending sound.
function plausibleExactNameCollision(candidate: TrustedContact, query: string): boolean {
  const q = normalizeName(query);
  const names = [candidate.displayName, ...(candidate.aliases ?? [])];
  return names.some((name) => normalizeName(name).split(/\s+/).some((token) => {
    if (!token || token === q) return true;
    const lengthDelta = Math.abs(token.length - q.length);
    if (lengthDelta === 0 || lengthDelta > 2 || Math.max(token.length, q.length) < 5) return false;
    const distance = levenshtein(token, q);
    return lengthDelta === 1 ? distance <= 1 : distance <= 2;
  }));
}

/** Shared phonetic comparison for device contacts and verified identity aliases. */
export function namesPhoneticallyMatch(a: string, b: string): boolean {
  const left = normalizeName(a), right = normalizeName(b);
  return Boolean(left && right && matchesFuzzy(left, right));
}

// Lightweight multilingual pronunciation key for common Romanian/German/English STT slips.
// This only generates candidates; it never authorizes an action. Multiple candidates remain
// ambiguous and externally visible actions still pass their existing explicit confirmation gate.
function phoneticKey(value: string): string {
  return normalizeName(value).split(/\s+/).filter(Boolean).map((raw) => {
    let token = raw
      .replace(/^michael$/, 'maikel')
      .replace(/^maichel$/, 'maikel')
      .replace(/^chr(?=[a-z])/, 'kr')
      .replace(/sch/g, 's')
      .replace(/gh(?=[ei])/g, 'g')
      .replace(/ph/g, 'f')
      .replace(/th/g, 't')
      .replace(/ck/g, 'k')
      .replace(/x/g, 'ks')
      .replace(/dt$/g, 't')
      .replace(/sh$/g, 's')
      .replace(/h/g, '')
      .replace(/(.)\1+/g, '$1');
    token = token.replace(/^[jy]/, 'i').replace(/[aeiy]$/, '');
    return token;
  }).join(' ');
}

function phoneticClose(a: string, b: string): boolean {
  const ka = phoneticKey(a), kb = phoneticKey(b);
  if (ka === kb) return true;
  if (ka.length < 3 || kb.length < 3) {
    // Permit a short key only when the transcript/contact lengths show a real truncation or
    // expansion (Ana/Hannah, Hanei/Hannah); equal-length short names such as Hana/Pane stay distinct.
    if (Math.max(a.length, b.length) < 5 || a.length === b.length) return false;
    return levenshtein(ka, kb) <= 1;
  }
  const distance = levenshtein(ka, kb);
  return distance <= (Math.max(ka.length, kb.length) >= 5 ? 2 : 1);
}

// ROUND_CONTACT_AMBIGUITY_DIAG_1 — read-only, for the CONTACT_AMBIGUOUS_CANDIDATES log below only.
// Recomputes, per candidate, which tier of findMatches() it satisfies and (for the fuzzy tier) the
// edit distance that qualified it. Does not change findMatches()/resolveContact()'s behavior or
// return values in any way — purely descriptive of an already-decided match.
function describeMatchReason(candidate: TrustedContact, normalizedQuery: string): string {
  const nameNorm = normalizeName(candidate.displayName);
  if (matchesExact(candidate.displayName, normalizedQuery)) return `exact_display(${nameNorm})`;
  const exactAlias = (candidate.aliases ?? []).find((a) => matchesExact(a, normalizedQuery));
  if (exactAlias) return `exact_alias(${normalizeName(exactAlias)})`;
  if (matchesPartial(candidate.displayName, normalizedQuery)) return `partial_display(${nameNorm})`;
  const partialAlias = (candidate.aliases ?? []).find((a) => matchesPartial(a, normalizedQuery));
  if (partialAlias) return `partial_alias(${normalizeName(partialAlias)})`;
  if (matchesFuzzy(candidate.displayName, normalizedQuery)) {
    const dist = Math.min(
      levenshtein(nameNorm, normalizedQuery),
      ...nameNorm.split(/\s+/).map((tok) => levenshtein(tok, normalizedQuery)),
    );
    return `fuzzy_display(${nameNorm},dist=${dist})`;
  }
  const fuzzyAlias = (candidate.aliases ?? []).find((a) => matchesFuzzy(a, normalizedQuery));
  if (fuzzyAlias) {
    const an = normalizeName(fuzzyAlias);
    return `fuzzy_alias(${an},dist=${levenshtein(an, normalizedQuery)})`;
  }
  return 'unknown_tier';
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
    const key = `${normalizeName(c.displayName)}|${(c.phoneNumbers ?? []).join(',')}|${(c.emailAddresses ?? []).map((e) => e.toLowerCase()).join(',')}`;
    if (!seen.has(key)) seen.set(key, c);
  }
  return [...seen.values()];
}

// Tiered matching: try the strongest tier first (exact displayName), and only fall through to a
// weaker tier if the current one found nothing at all. A tier that finds 2+ hits is ambiguous
// right there — it doesn't matter whether a weaker tier might have found fewer/other matches.
function findMatches(normalizedQuery: string, contacts: TrustedContact[]): TrustedContact[] {
  const exactDisplayName = dedupeContacts(contacts.filter((c) => matchesExact(c.displayName, normalizedQuery)));
  if (exactDisplayName.length > 0) {
    // A short STT result can exactly spell a different contact while also sounding like the
    // intended longer name (e.g. “Ana” for Hannah). Preserve the exact hit, but surface nearby
    // phonetic identities too so the caller asks instead of silently selecting the wrong person.
    const close = dedupeContacts(contacts.filter((c) => !exactDisplayName.includes(c) && plausibleExactNameCollision(c, normalizedQuery)));
    return close.length ? dedupeContacts([...exactDisplayName, ...close]) : exactDisplayName;
  }

  const exactAlias = dedupeContacts(contacts.filter((c) => (c.aliases ?? []).some((a) => matchesExact(a, normalizedQuery))));
  if (exactAlias.length > 0) {
    const close = dedupeContacts(contacts.filter((c) => !exactAlias.includes(c) && matchesFuzzy(c.displayName, normalizedQuery)));
    return close.length ? dedupeContacts([...exactAlias, ...close]) : exactAlias;
  }

  const partialDisplayName = dedupeContacts(contacts.filter((c) => matchesPartial(c.displayName, normalizedQuery)));
  if (partialDisplayName.length > 0) return partialDisplayName;

  const partialAlias = dedupeContacts(contacts.filter((c) => (c.aliases ?? []).some((a) => matchesPartial(a, normalizedQuery))));
  if (partialAlias.length > 0) return partialAlias;

  // Weakest tier — fuzzy (edit-distance) match, to rescue imperfect voice transcripts of a name
  // (the "Hannah" heard as "Hana" bug). Only reached when every stricter tier found nothing, so a
  // clean exact/substring hit is never overridden by a looser fuzzy one. 2+ fuzzy hits still
  // resolve as 'ambiguous' upstream, which safely asks the user "which one?" rather than guessing.
  const fuzzyDisplayName = dedupeContacts(contacts.filter((c) => matchesFuzzy(c.displayName, normalizedQuery)));
  if (fuzzyDisplayName.length > 0) return fuzzyDisplayName;

  const fuzzyAlias = dedupeContacts(contacts.filter((c) => (c.aliases ?? []).some((a) => matchesFuzzy(a, normalizedQuery))));
  if (fuzzyAlias.length > 0) return fuzzyAlias;

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
    // ROUND_CONTACT_AMBIGUITY_DIAG_1 — diagnostic only, logcat-side; does not change the spoken
    // response (still the one bounded prompt below, never an enumerated list to the user).
    logAudioDiag('CONTACT_AMBIGUOUS_CANDIDATES', `rawName=${JSON.stringify(request.rawName)} normalizedQuery=${JSON.stringify(normalizedQuery)} count=${matches.length} candidates=${JSON.stringify(matches.map((c) => ({ name: c.displayName, reason: describeMatchReason(c, normalizedQuery) })))}`);
    // BENSON_STABILIZATION_1 — NEVER enumerate candidate names. A long spoken list of contact
    // names is BENSON output that gets re-heard as user input / typed into WhatsApp. One short
    // bounded prompt only; callers phrase it for the user.
    return {
      status: 'ambiguous',
      candidates: matches.slice(0, 1),
      normalizedQuery,
      message: 'Nu sunt sigur de nume. Spune numele din nou.',
    };
  }

  const contact = matches[0];
  const channel = request.preferredChannel;
  if (channel === 'email' && (!contact.emailAddresses || contact.emailAddresses.length === 0)) {
    return {
      status: 'missing_email',
      contact,
      normalizedQuery,
      message: `${contact.displayName} has no email address saved.`,
    };
  }

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
