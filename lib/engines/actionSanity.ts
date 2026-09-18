// Round 2 / Build B — the single sanity checkpoint every action-with-a-contact-parameter passes
// through, no matter which route produced it: the deterministic parser OR the brain. It runs
// immediately before the Confirmation Gate. A contact parameter that plainly is not a person's
// name — a wake/control word ("wake up", "ok", "stop"), a bare imperative verb, or text whose
// script does not belong to the active (Latin-script) language — is NOT executed: the caller turns
// it into a `clarify` ("Pe cine să sun?") instead of letting the contact resolver hunt for
// nonsense.
//
// Deliberately conservative: it only rejects the clearly-not-a-name cases. It is a heuristic, not
// a language identifier — a real but unusual name in the active language passes; a foreign-script
// blob or an obvious command word does not. Known limits are in ROUND2_REPORT.md.
//
// A truly single physical call site (inside the Confirmation Gate) is impossible here because that
// gate lives in src/core/mission/missionExecutor.ts, which is on this round's forbidden list — so
// this is one function, called from each route just before it dispatches.
import { logAudioDiag } from 'benson-foreground-service';

export type SanitySource = 'parser' | 'brain';
export type SanityVerdict = { ok: true } | { ok: false; reason: string };

// Wake / control / affirmation words that STT commonly leaves sitting in a "name" slot — RO/DE/EN.
const CONTROL_WORDS = new Set([
  'benson', 'wake up', 'wake', 'hey', 'hello', 'hi', 'salut', 'buna', 'bună',
  'ok', 'okay', 'oké', 'yes', 'no', 'da', 'nu', 'ja', 'nein',
  'stop', 'cancel', 'gata', 'anuleaza', 'anulează', 'renunta', 'renunță',
  'opreste', 'oprește', 'inchide', 'închide', 'weiter', 'abbrechen',
]);

// Leading bare-imperative verbs — if the "name" starts with one of these, STT caught a verb, not
// a person. RO/DE/EN, matched at the start only.
const LEADING_VERB =
  /^(spune|zi|deschide|sun[ăa]|suna|trimite|scrie|caut[ăa]|cauta|opre[șs]te|porne[șs]te|arat[ăa]|arata|mergi|du|pleac[ăa]|vino|ascult[ăa]|open|call|send|write|search|play|go|close|show|tell|start|stop|öffne|ruf|schreib|geh|zeig|starte)\b/i;

// Any character from a non-Latin script the app's languages (ro/de/en/fr/it/es) never use — a
// "name" containing one means STT latched onto foreign on-screen text.
const NON_LATIN_SCRIPT = /[Ѐ-ӿ֐-׿؀-ۿ぀-ヿㇰ-ㇿ㐀-䶿一-鿿가-힯]/;

// At least one Latin letter (incl. common accented forms) — a "name" of only digits/punctuation
// is not a name.
const HAS_LATIN_LETTER = /[a-zàâäãåçéèêëíìîïñóòôöõúùûüýÿ]/i;

export function looksLikePersonName(raw: string): SanityVerdict {
  const v = (raw || '').trim().replace(/\s+/g, ' ');
  const low = v.toLowerCase();
  if (!v) return { ok: false, reason: 'empty' };
  if (v.length < 2) return { ok: false, reason: 'too_short' };
  if (CONTROL_WORDS.has(low)) return { ok: false, reason: 'control_word' };
  if (LEADING_VERB.test(low)) return { ok: false, reason: 'verb' };
  if (NON_LATIN_SCRIPT.test(v)) return { ok: false, reason: 'foreign_script' };
  if (!HAS_LATIN_LETTER.test(v)) return { ok: false, reason: 'no_letters' };
  if (v.split(' ').length > 4) return { ok: false, reason: 'too_many_words' };
  return { ok: true };
}

// The one function both routes call. Always logs SANITY_CHECK so a rejection is visible in logcat
// regardless of which route asked.
export function sanityCheckContactParam(source: SanitySource, contact: string, _lang: string): SanityVerdict {
  const verdict = looksLikePersonName(contact);
  logAudioDiag(
    'SANITY_CHECK',
    `source=${source} param="${(contact || '').slice(0, 40)}" verdict=${verdict.ok ? 'ok' : 'rejected'}` +
      (verdict.ok ? '' : ` reason=${verdict.reason}`),
  );
  return verdict;
}
