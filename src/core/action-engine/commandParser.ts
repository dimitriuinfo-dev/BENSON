// BENSON Action Engine — Intent Engine (Module 3).
// Deterministic regex cascade (Romanian/German/English), converting raw text into an
// ActionRequest. No contact resolution, no saved-place resolution, no executor calls, no
// Claude — all of that happens downstream of this, later. Anything unmatched becomes CHAT.

import type { ActionIntent, ActionRiskLevel, ActionSource } from './actionTypes';
import type { ActionRequest } from './actionRequest';
import { createActionRequest } from './actionRequest';
import { normalizeTranscript } from './transcriptNormalizer';
import { cleanDiscourse } from './discourseCleaner';

// Leading boundary that works before a Romanian diacritic, unlike \b: JS's \b only treats
// [A-Za-z0-9_] as a "word" character, so \b immediately before "închide"/"întoarce-te" (both
// starting with î) never fires after a space — both sides look "non-word" to the regex engine,
// so there's no transition for \b to detect. This bit BENSON 21 live ("închide YouTube" silently
// fell through to Claude) — (?:^|\s) sidesteps it without needing lookbehind (Hermes' regex
// engine support for that is not something to gamble a fix on).
const LEADING_BOUNDARY = '(?:^|\\s)';

type ParseResult = {
  intent: ActionIntent;
  parameters: Record<string, unknown>;
  confidence: number;
};

// "Waze"/"Google Maps" mentioned anywhere in the text — used both to detect the bare open
// intent and to decide preferredApp when a NAVIGATE pattern also matches in the same utterance.
// Trailing "e" optional: English-mode STT on this device transcribed "Waze" as "Waz" (real
// live capture: RAW TRANSCRIPT "open Waz") — a plain \bwaze\b never matches a shorter query, so
// bare "Waz" fell through to the generic OPEN_APP path, which also failed to find "Waz" as an
// app name. Same story for the registry's own matchWords list below.
// oaza/oazei added 2026-07-16: Romanian-locale STT (now correctly ro-RO after the audio-chain
// fix) confirmed live to phonetically mishear "Waze" as "oaza"/"oazei" ("the oasis") — an
// English brand name has no natural Romanian pronunciation, so this recognizer substitutes the
// nearest real Romanian word instead. Same fuzzy-tolerance idiom as the "Waz" truncation fix.
const MENTIONS_WAZE = /\b(?:waze?|oaz[ae]i?)\b/i;
const MENTIONS_GOOGLE_MAPS = /\bgoogle\s*maps\b/i;

const RETURN_TO_BENSON_PATTERN = new RegExp(`${LEADING_BOUNDARY}(?:revino|întoarce-te|intoarce-te)\\s+la\\s+benson\\b`, 'i');

// Every destination/contact/message capture below stops at a conjunction boundary ("și", "and",
// "apoi", "und") instead of running to end-of-string — needed so the Mission Orchestrator's
// whole-text-first parse (BENSON 21) doesn't swallow a genuinely separate second clause into the
// first goal's entity ("du-mă la Sibiu ȘI scrie-i lui Hannah..." must capture just "Sibiu", not
// the whole rest of the sentence). Single-clause utterances are unaffected — the lookahead's `$`
// alternative still matches end-of-string exactly as the old `(.+)$` did.
const NEXT_CLAUSE_BOUNDARY = '(?=\\s+(?:și|si|apoi|and|und)\\s+|$)';

// CLOSE_APP — realistic MVP scope only: bring BENSON back to front (HOME/BACK aren't reliably
// controllable for a third-party app's own back stack without Accessibility/Device Owner/root).
const CLOSE_APP_PATTERNS: RegExp[] = [
  new RegExp(`${LEADING_BOUNDARY}(?:opre[șs]te|închide|inchide|ie[șs]i\\s+din|termin[ăa]|scoate)\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: oprește Waze / închide Waze / ieși din Waze
];

const OPEN_WHATSAPP_PATTERN = /\b(?:deschide|porne[șs]te|intr[ăa]\s+pe|vreau|pune|öffne|starte|open|start|launch)\s+whatsapp\b/i;

// ROUND_WA_GOVERNANCE_ROUTING — WhatsApp MESSAGE-family phrasings that name a contact but capture
// NO body ("scrie-i lui Hannah pe WhatsApp", "trimite WhatsApp lui Hannah", "schreib Hannah auf
// WhatsApp"). These are MESSAGE intents with an empty body: the router turns an empty body into
// MESSAGE_BODY_MISSING and BENSON asks what to write. They are NEVER an open-chat intent and
// never fall back to one. Checked AFTER the contact+body message patterns, BEFORE the open-chat
// patterns.
// (?:[- ]?i\b)? not (?:-i)?: this device's STT drops the clitic hyphen ("scrie-i" → "scrie i").
const WHATSAPP_MESSAGE_NOBODY_PATTERNS: RegExp[] = [
  new RegExp(`\\btrimite\\s+whatsapp\\s+lui\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: trimite WhatsApp lui Hannah
  /\bscrie(?:[- ]?i\b)?\s+(?:lui\s+)?(.+?)\s+(?:un\s+mesaj\s+)?pe\s+whats(?:app)?\b/i, // RO: scrie-i lui Hannah pe WhatsApp
  /\bschreib\s+(.+?)\s+auf\s+whatsapp\b/i, // DE: schreib Hannah auf WhatsApp
];

// Explicit OPEN-the-chat phrasings — no message, no send. "deschide conversația cu Hannah pe
// WhatsApp", "open the chat with Hannah on WhatsApp", "WhatsApp with Hannah". A distinct
// capability from messaging; NEVER produced as a fallback from a failed message parse.
const WHATSAPP_OPEN_CHAT_PATTERNS: RegExp[] = [
  new RegExp(
    `\\b(?:deschide|deschide-?mi|arat[ăa]-?mi|open|show|öffne)\\s+(?:-?mi\\s+)?(?:conversa[țt]ia|conversatia|discu[țt]ia|discutia|chat(?:ul)?|the\\s+chat)\\s+(?:cu\\s+|with\\s+)?(.+?)(?:\\s+(?:pe|auf|on)\\s+whats(?:app)?)?${NEXT_CLAUSE_BOUNDARY}`,
    'i',
  ),
  new RegExp(`\\bwhatsapp\\s+with\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // EN: open WhatsApp with Hannah
];

// EN: "write Hannah on WhatsApp: I have left." / "...on WhatsApp, I have left" / "...on WhatsApp
// that I have left" — no prior English contact+message capture existed (only the RO
// "scrie-i lui X că Y" form below), so this phrasing fell through to OPEN_WHATSAPP_PATTERN with
// the contact/message silently discarded (same class of bug as the RO/DE contact-swallowing fix
// above). Checked before MESSAGE_CONTACT_PATTERN since both set MESSAGE_CONTACT/OPEN_WHATSAPP_CONTACT.
const WHATSAPP_MESSAGE_EN_PATTERN = new RegExp(
  `\\bwrite\\s+(.+?)\\s+on\\s+whatsapp\\s*[:,]?\\s*(?:that\\s+)?(.+?)${NEXT_CLAUSE_BOUNDARY}`,
  'i',
);

// ROUND_WA_GOVERNANCE_ROUTING — RO: "scrie-i lui X pe WhatsApp că Y" / "... pe WhatsApp: Y" /
// "... pe WhatsApp, Y". The message form of the WhatsApp-contact phrasing. MUST be classified
// before WHATSAPP_MESSAGE_NOBODY_PATTERNS (whose "scrie ... pe whatsapp" entry would otherwise
// match the prefix and silently drop Y). contact = g1, message = g2 — both required by the caller.
const WHATSAPP_MESSAGE_RO_PATTERN = new RegExp(
  `\\bscrie(?:[- ]?i\\b)?\\s+(?:lui\\s+)?(.+?)\\s+(?:un\\s+mesaj\\s+)?pe\\s+whats(?:app)?(?:\\s*[:,]\\s*|\\s+c[ăa]\\s+)(.+?)${NEXT_CLAUSE_BOUNDARY}`,
  'i',
);

// "sună/apelează X pe WhatsApp" — checked BEFORE plain CALL_CONTACT_PATTERNS so the trailing
// "pe whatsapp" isn't captured as part of the contact name, and so BENSON gives the honest
// WhatsApp-voice-call-isn't-automatable-yet fallback instead of dialing the phone number.
// There is no public Android deep-link/Intent to start a WhatsApp VOICE call directly (only to
// open a chat, wa.me/<number>) — reliably tapping the in-app call button would need Accessibility
// Service UI automation, out of scope here; opening the chat + an honest sentence is the correct
// Phase A answer, not a silent failure or a plain phone call the user didn't ask for.
// `whats(?:app)?` (not the literal full word) — confirmed live 2026-07-16: the STT session ended
// mid-word on "...pe Whats" (the trailing "app" clipped by the endpointer even with generous
// silence-timeout tuning upstream), so the plain CALL_CONTACT_PATTERNS below matched instead and
// the whole thing was misrouted as a native phone call, which then failed contact resolution
// (WhatsApp-only contacts have no phone number match). Tolerating the truncated stem here is the
// same fuzzy-match idiom already used for "Waz" -> "Waze" (BENSON 25).
const WHATSAPP_CALL_PATTERNS: RegExp[] = [
  // Channel-first word order ("sun-o PE WHATSAPP pe mama") — confirmed live 2026-07-17: checked
  // BEFORE the name-first pattern below, because with that order the name-first pattern's lazy
  // capture backtracks onto the pronoun itself ("o") as a spuriously "valid" short match — "sun-o
  // pe whatsapp pe mama" DOES contain "o ... pe whatsapp" as a substring once the optional
  // pronoun-word group is skipped, so the name-first regex matched with contactName="o", losing
  // "mama" entirely. This pattern owns that word order explicitly instead of relying on the other
  // one to reject it correctly.
  new RegExp(`\\bsun[ăa]?-?[oli]?\\s+(?:(?:o|l|le|îl|il)\\s+)?pe\\s+whats(?:app)?\\s+pe\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // sun-o pe WhatsApp pe mama / sună pe Whats pe Hannah
  new RegExp(`\\bsun[ăa]?-?[oli]?\\s+(?:(?:o|l|le|îl|il)\\s+)?(?:pe\\s+|la\\s+|lui\\s+)?(.+?)\\s+pe\\s+whats(?:app)?\\b`, 'i'), // sună pe Hannah pe WhatsApp / sun o pe Hannah pe WhatsApp / sună la Hannah pe Whats
  new RegExp(`\\bapel(?:[ăa]|eaz[ăa])\\s+(?:pe\\s+)?(.+?)\\s+pe\\s+whats(?:app)?\\b`, 'i'), // apelează pe Hannah pe WhatsApp / pe Whats
];

// Channel-ambiguous "send this message to X" — no app named at all ("scrie-i lui Hannah că
// plec acum"). Defaults to WhatsApp (BENSON's existing default messaging channel) since nothing
// else was specified; SEND_SMS stays reachable via its own explicit "sms"/"mesaj text" wording.
const MESSAGE_CONTACT_PATTERN = new RegExp(
  // Same STT-drops-the-hyphen fix as WHATSAPP_CONTACT_PATTERNS' "scrie" pattern above, plus an
  // optional "un mesaj" filler between the name and "că" — confirmed live 2026-07-17: natural
  // phrasing "scrie-i lui Hannah UN MESAJ că..." otherwise got "un mesaj" swallowed into the lazy
  // name capture too (contactName="Hannah un mesaj" instead of just "Hannah") once the hyphen fix
  // above stopped it from also swallowing "i lui".
  `\\bscrie(?:[- ]?i\\b)?\\s+(?:lui\\s+)?(.+?)\\s+(?:un\\s+mesaj\\s+)?c[ăa]\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`,
  'i',
);

const CALL_CONTACT_PATTERNS: RegExp[] = [
  new RegExp(`\\bsun[ăa]?-?[oli]?\\s+(?:(?:o|l|le|îl|il)\\s+)?(?:pe\\s+|la\\s+|lui\\s+)?(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: sună Hannah / sună-o pe Hannah / sun-o pe mama / sun o pe mama / sună-l pe X / sună-i lui X / sună la Hannah
  new RegExp(`\\bapel(?:[ăa]|eaz[ăa])\\s+(?:pe\\s+|la\\s+)?(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: apelează pe Hannah / apel la Hannah
  new RegExp(`\\bd[ăa]-?i\\s+telefon\\s+(?:lui\\s+)?(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: dă-i telefon lui Hannah
  new RegExp(`\\bvorbesc\\s+cu\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: vreau să vorbesc cu mama (discourse cleaner strips "vreau să" upstream; bare form kept as belt-and-suspenders)
  /\bruf\s+(.+?)\s+an\b/i, // DE: ruf Hannah an (separable verb wraps the name)
  new RegExp(`\\bcall\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // EN: call Hannah
];

// Every phrase that means "go/navigate somewhere" — deliberately broad (not just the literal
// "navighează"/"navigate to" verbs) per the free-form-speech requirement: "hai cu Waze", "bagă
// navigația", "am nevoie de navigație", "caută-mi drumul spre X" should all reach NAVIGATE.
const NAVIGATE_PATTERNS: RegExp[] = [
  // "[- ]?" not a literal hyphen — STT drops the clitic hyphen ("du-mă" transcribed as "du mă"),
  // confirmed live 2026-07-18: "Du mă la aeroportul Cluj România" silently produced zero inferred
  // goals (problemType=UNKNOWN) because this pattern required the hyphen literally, same class of
  // bug already fixed elsewhere in this file (WHATSAPP_CONTACT_PATTERNS, CONTACTS_SEARCH_PATTERNS)
  // but missed here.
  new RegExp(`\\bdu[- ]?m[ăa]\\s+(?:la\\s+|spre\\s+)?(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: du-mă acasă / du-mă la Sibiu / du-mă spre Sibiu
  new RegExp(`\\bnavigheaz[ăa]\\s+(?:la\\s+|spre\\s+)?(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: navighează la Sibiu
  new RegExp(`\\b(?:merg|plec|vreau\\s+s[ăa]\\s+merg|hai)\\s+(?:la\\s+|spre\\s+)(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: merg la Sibiu / plec la Sibiu / hai spre Sibiu
  new RegExp(`\\bcaut[ăa]-?mi\\s+drumul\\s+spre\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: caută-mi drumul spre cabinet
  new RegExp(`\\bfahr\\s+(?:nach|zu)\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // DE: fahr nach Sibiu
  new RegExp(`\\bnavigate\\s+to\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // EN: navigate to Sibiu
];

// Bare "I need navigation"/"pull up Waze"-style phrases with NO destination named — these still
// mean NAVIGATE intent-wise, but there's nothing to extract yet, so they resolve to the bare
// OPEN_WAZE/OPEN_GOOGLE_MAPS open (no destination) rather than a NAVIGATE_TO_PLACE with nothing
// to navigate to.
const NAVIGATION_WITHOUT_DESTINATION_PATTERN =
  /\b(?:am\s+nevoie\s+de\s+naviga\w*|bag[ăa]\s+naviga\w*|hai\s+cu\s+waze)\b/i;

// "navigate to X using Waze" / "du-mă la X cu Waze" — NAVIGATE_PATTERNS' destination capture
// runs to end-of-string, so without this the app-selector clause gets swallowed into the
// destination itself ("Munich Airport using Waze" sent to Waze as the search query, instead of
// "Munich Airport"). MENTIONS_WAZE/MENTIONS_GOOGLE_MAPS below already detect which app was named
// for preferredApp purposes — this only needs to strip the trailing clause from the destination.
const APP_SELECTOR_SUFFIX_PATTERN =
  /\s+(?:using|with|via|cu|folosind|prin)\s+(?:waze|google\s*maps)\s*[.,!?]*$/i;

// Waze/Maps resolve a plain place name via `q=` — a trailing sentence-ending period/comma left
// over from STT punctuation ("Munich Airport.") would otherwise be sent as part of the query.
function cleanDestination(raw: string): string {
  const withoutAppSelector = raw.replace(APP_SELECTOR_SUFFIX_PATTERN, '');
  const withoutTrailingPunctuation = withoutAppSelector.replace(/[.,!?]+$/, '');
  const cleaned = withoutTrailingPunctuation.trim();
  return cleaned || raw.trim();
}

const CALENDAR_PATTERN =
  /\b(?:ce\s+am\s+azi(?:\s+în\s+calendar|\s+in\s+calendar)?|calendarul\s+meu|programul\s+meu(?:\s+de\s+azi)?)\b/i;

// Item 1 — read-only contacts lookup (RO/DE/EN). Checked before CALL_CONTACT_PATTERNS/
// OPEN_APP_PATTERN so "caută-l pe Hannah"/"cine e Hannah" resolve to a spoken lookup, not a call
// attempt or a generic app-open. NAVIGATE_PATTERNS' own "caută-mi drumul spre X" is checked earlier
// in classify() and is specific enough (requires literal "drumul spre") that it never collides.
const CONTACTS_LIST_PATTERN =
  /\b(?:arat[ăa]-mi\s+contactele|arat[ăa]\s+contactele|zeig\s+mir\s+(?:die\s+)?kontakte|show\s+me\s+(?:my\s+)?contacts)\b/i;

// Each pattern requires an explicit "this is a person" marker (RO clitic+"pe", DE/EN "who is") —
// deliberately NOT a bare "caută X"/"suche X", which would also match a place/thing search
// ("caută Sibiu") and wrongly swallow it into a contacts lookup with no contacts feature able to
// yield a useful answer. "search for X" is closer to the bare form but "for" still narrows it
// enough to avoid the plain NAVIGATE/OPEN_APP phrasings elsewhere in this file.
const CONTACTS_SEARCH_PATTERNS: RegExp[] = [
  new RegExp(`\\bcine\\s+e(?:ste)?\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: cine e Hannah / cine este Hannah
  // [- ]? not a bare literal hyphen: confirmed live 2026-07-17 — this device's STT drops the
  // clitic hyphen entirely ("caută-l" transcribed as "caută l"), and a required literal "-"
  // here made the whole pattern fail to match at all, falling through to Claude instead of the
  // deterministic contacts lookup.
  new RegExp(`\\bcaut[ăa][- ]?[oli]\\s+pe\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // RO: caută-l pe Hannah / caută-o pe Hannah
  new RegExp(`\\bwer\\s+ist\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // DE: wer ist Hannah
  new RegExp(`\\bwho\\s+is\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // EN: who is Hannah
  new RegExp(`\\bsearch\\s+for\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i'), // EN: search for Hannah
];

const FAMILY_LOCATION_PATTERN = new RegExp(`\\bunde\\s+este\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`, 'i');

const MUSIC_PLAY_PATTERN =
  // Trailing lookahead instead of \b: JS's \b treats "word character" as ASCII-only ([A-Za-z0-9_]),
  // so a literal \b right after the diacritic "ă" in "muzică" silently never matches (ă isn't a
  // \w character to begin with, so there's no word/non-word transition for \b to fire on) — this
  // caused "vreau muzică" to fall through to OPEN_APP instead of MEDIA_PLAY.
  /\b(?:deschide|porne[șs]te|pune|bag[ăa]|vreau)\s+(?:muzic[ăa]|music)(?=\s|$)/i;

// "deschide/pornește/dă drumul la/deschizi radio [Station Name]" — captures an optional trailing
// station name (empty means "any radio app"). "Radio România Actualități" starts literally with
// "radio", so this same capture naturally picks up the full station name when one is spoken.
const RADIO_PLAY_WITH_VERB_PATTERN = new RegExp(
  `${LEADING_BOUNDARY}(?:deschide|deschizi|porne[șs]te|pune|bag[ăa]|vreau|d[ăa]\\s+drumul\\s+la)\\s+radio(?:ul)?\\s*(.*?)${NEXT_CLAUSE_BOUNDARY}`,
  'i',
);

// Fallback for phrasings where "radio" isn't adjacent to a recognized verb at all ("am nevoie să
// deschizi un app cu radio de la mine de pe telefon") — "radio" is close to unambiguous in this
// app's vocabulary, so a bare mention is a strong enough signal on its own, same convention
// already used for MENTIONS_WAZE/MENTIONS_GOOGLE_MAPS above.
const MENTIONS_RADIO = /\bradio\b/i;

function matchMediaPlay(text: string): { mediaType: 'music' | 'radio'; stationName?: string } | null {
  const radioWithVerb = text.match(RADIO_PLAY_WITH_VERB_PATTERN);
  if (radioWithVerb) {
    const stationName = radioWithVerb[1]?.trim();
    return { mediaType: 'radio', stationName: stationName || undefined };
  }
  if (MUSIC_PLAY_PATTERN.test(text)) return { mediaType: 'music' };
  if (MENTIONS_RADIO.test(text)) return { mediaType: 'radio' };
  return null;
}

// Contact-specific WhatsApp/CALL/NAVIGATE patterns above are checked first; this is the generic
// "open X" fallback for anything else, broadened per the free-form-speech requirement — "vreau
// WhatsApp", "pune YouTube", "intră pe Waze" should all reach OPEN_APP/OPEN_WAZE just as well as
// the literal "deschide"/"open" verb.
// "vreau" excludes a following "să" (Romanian subjunctive marker) — "vreau WhatsApp" is a direct
// object (open this app), but "vreau să-i spun..."/"nu vreau să uit..." are verb clauses that
// happen to contain "vreau", not app-open requests; without this exclusion they were falsely
// caught here before Problem Solver's own broader patterns ever got a chance to run.
const OPEN_APP_PATTERN = new RegExp(
  // "(?:-\\w+)?" covers the attached clitic form "să-i"/"să-l" ("vreau să-i spun...") in
  // addition to the plain "vreau să " form — both mean "I want to VERB", not "open [app]".
  `\\b(?:deschide|porne[șs]te|intr[ăa]\\s+pe|vreau(?!\\s+s[ăa](?:-\\w+)?(?:\\s|$))|pune|bag[ăa]|öffne|starte|open|start|launch|get me)\\s+(.+?)${NEXT_CLAUSE_BOUNDARY}`,
  'i',
);

// Checked early since none of these trigger phrases overlap with any app/navigate/call pattern.
// "ce poți (să faci)" reduces to bare "ce faci" once the discourse cleaner strips "poți"/"poți
// să" — both the pre- and post-cleanup shapes are covered here. Same for "cum mă poți ajuta" ->
// "cum mă ajuta"/"cum mă ajută" once "poți" is stripped.
const HELP_PATTERN =
  /\b(?:ce\s+[șs]tii\s+(?:s[ăa]\s+)?faci|ce\s+faci\b|ce\s+func[țt]ii\s+ai|cum\s+m[ăa]\s+ajut[ăa]|ajutor|help|capabilities)\b/i;

// Trailing sentence punctuation left over from STT/typed text ("Hannah.", "Munich Airport,")
// is never meaningful in a captured name/destination — stripped here once so every pattern's
// capture benefits instead of leaking a stray period into a contact-match or map query.
function stripTrailingPunctuation(s: string): string {
  return s.replace(/[.,!?]+$/, '').trim();
}

// ROUND_WA_WRITE_MESSAGE_PAYLOAD — a wake-word token that bled into the captured MESSAGE body
// ("...pe WhatsApp că Benson" / mis-heard as "Benzim" / "benzină") is STT contamination, never
// the user's message. transcriptNormalizer only strips a LEADING wake word; a trailing/embedded
// one survives into the `că …` body. Strip every occurrence; if nothing meaningful is left, the
// body is EMPTY and the router stops with MESSAGE_BODY_MISSING — the wake word is NEVER sent as
// the message. Mirrors transcriptNormalizer.LEADING_WAKE_WORD_PATTERN's variant list.
const WAKE_WORD_TOKEN =
  /\b(?:benson|bensen|bensson|benzon|benzim|benzin|benzine|benzin[ăa]|bentson|bennson|bänson|bensn|penson|penzon|benz[ăa])\b/gi;
function sanitizeMessageBody(raw: string): string {
  return raw
    .replace(WAKE_WORD_TOKEN, ' ')
    .replace(/\s+/g, ' ')
    .replace(/^[\s,.:;–-]+|[\s,.:;–-]+$/g, '')
    .trim();
}
// The message body must never be the recipient token echoed back. Compares diacritic/case-folded.
function bodyEqualsContact(body: string, contact: string): boolean {
  const f = (s: string) => s.trim().toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/\s+/g, ' ');
  return f(body).length > 0 && f(body) === f(contact);
}

function firstMatch(patterns: RegExp[], text: string): string | null {
  for (const pattern of patterns) {
    const match = text.match(pattern);
    if (match && match[1] && match[1].trim()) return stripTrailingPunctuation(match[1]);
  }
  return null;
}

function classify(text: string): ParseResult {
  // RETURN_TO_BENSON — checked before CLOSE_APP so "revino la Benson" doesn't get swallowed by
  // CLOSE_APP_PATTERNS' generic "closeTarget" capture (which would otherwise capture "Benson").
  if (RETURN_TO_BENSON_PATTERN.test(text)) {
    return { intent: 'RETURN_TO_BENSON', parameters: {}, confidence: 1.0 };
  }

  const closeTarget = firstMatch(CLOSE_APP_PATTERNS, text);
  if (closeTarget) {
    return { intent: 'CLOSE_APP', parameters: { appName: closeTarget, targetApp: closeTarget }, confidence: 1.0 };
  }

  if (HELP_PATTERN.test(text)) {
    return { intent: 'HELP', parameters: {}, confidence: 1.0 };
  }

  // WhatsApp voice-call phrasing — checked before plain CALL_CONTACT_PATTERNS so "pe whatsapp"
  // isn't captured as part of the contact name and BENSON gives the honest fallback instead of
  // dialing the phone number.
  const whatsappCallContact = firstMatch(WHATSAPP_CALL_PATTERNS, text);
  if (whatsappCallContact) {
    return {
      intent: 'OPEN_WHATSAPP_CONTACT',
      parameters: { contactName: whatsappCallContact, channel: 'whatsapp', mode: 'voice_call' },
      confidence: 1.0,
    };
  }

  // Compound utterances — "Benson deschide Waze și du-mă la Sibiu" mentions Waze/Maps AND a
  // real destination in the same breath. A bare mention check + a destination check (rather
  // than a single first-match cascade) means the destination isn't silently discarded just
  // because "deschide Waze" appears earlier in the sentence.
  const rawDestinationLabel = firstMatch(NAVIGATE_PATTERNS, text);
  if (rawDestinationLabel) {
    const destinationLabel = cleanDestination(rawDestinationLabel);
    const preferredApp = MENTIONS_GOOGLE_MAPS.test(text) && !MENTIONS_WAZE.test(text) ? 'google_maps' : 'waze';
    return {
      intent: 'NAVIGATE_TO_PLACE',
      parameters: { destinationLabel, preferredApp, targetApp: preferredApp === 'waze' ? 'Waze' : 'Google Maps' },
      confidence: 1.0,
    };
  }

  if (MENTIONS_WAZE.test(text)) return { intent: 'OPEN_WAZE', parameters: { targetApp: 'Waze' }, confidence: 1.0 };
  if (MENTIONS_GOOGLE_MAPS.test(text)) {
    return { intent: 'OPEN_GOOGLE_MAPS', parameters: { targetApp: 'Google Maps' }, confidence: 1.0 };
  }
  if (NAVIGATION_WITHOUT_DESTINATION_PATTERN.test(text)) {
    return { intent: 'OPEN_WAZE', parameters: { targetApp: 'Waze' }, confidence: 0.7 };
  }

  if (CALENDAR_PATTERN.test(text)) {
    return { intent: 'CALENDAR_ACTION', parameters: { readOnly: true }, confidence: 1.0 };
  }

  if (CONTACTS_LIST_PATTERN.test(text)) {
    return { intent: 'CONTACTS_LIST', parameters: {}, confidence: 1.0 };
  }

  const contactsSearchName = firstMatch(CONTACTS_SEARCH_PATTERNS, text);
  if (contactsSearchName) {
    return { intent: 'CONTACTS_SEARCH', parameters: { contactName: contactsSearchName }, confidence: 1.0 };
  }

  const mediaMatch = matchMediaPlay(text);
  if (mediaMatch) {
    return {
      intent: 'MEDIA_PLAY',
      parameters: { mediaType: mediaMatch.mediaType, stationName: mediaMatch.stationName },
      confidence: 1.0,
    };
  }

  // ── WhatsApp messaging (message-family verbs: scrie / write / schreib / trimite) ─────────────
  // ROUND_WA_GOVERNANCE_ROUTING — ordered BEFORE the open-chat patterns so a message form is
  // never downgraded to an open. A message-family utterance ALWAYS resolves to MESSAGE_CONTACT;
  // an empty body is carried through (the router turns it into MESSAGE_BODY_MISSING).

  // Helper — a message-family match ALWAYS resolves to MESSAGE_CONTACT (never open-chat). The
  // body is sanitized (wake-word bleed removed); if it empties, or it's just the recipient token
  // echoed back, the body stays "" and the router stops with MESSAGE_BODY_MISSING.
  const messageContact = (contactRaw: string, bodyRaw: string, confidence: number): ParseResult => {
    const contactName = stripTrailingPunctuation(contactRaw).replace(/\s+pe\s+whats(?:app)?$/i, '').trim();
    let message = sanitizeMessageBody((bodyRaw ?? '').trim());
    if (bodyEqualsContact(message, contactName)) message = '';
    return { intent: 'MESSAGE_CONTACT', parameters: { contactName, message, channel: 'whatsapp' }, confidence };
  };

  // 1a. "write X on whatsapp Y"
  const whatsappMessageEn = text.match(WHATSAPP_MESSAGE_EN_PATTERN);
  if (whatsappMessageEn && whatsappMessageEn[1]?.trim()) {
    return messageContact(whatsappMessageEn[1], whatsappMessageEn[2] ?? '', 1.0);
  }
  // 1b. "scrie-i lui X pe WhatsApp că Y"
  const whatsappMessageRo = text.match(WHATSAPP_MESSAGE_RO_PATTERN);
  if (whatsappMessageRo && whatsappMessageRo[1]?.trim()) {
    return messageContact(whatsappMessageRo[1], whatsappMessageRo[2] ?? '', 1.0);
  }
  // 1c. channel-less "scrie-i lui X că Y" (defaults to WhatsApp).
  const messageMatch = text.match(MESSAGE_CONTACT_PATTERN);
  if (messageMatch && messageMatch[1]?.trim()) {
    return messageContact(messageMatch[1], messageMatch[2] ?? '', 0.9);
  }

  // 1d. message-family verb + contact but NO body → MESSAGE_CONTACT with an empty body. The
  //     router turns this into MESSAGE_BODY_MISSING ("ce să-i scriu?"), NEVER an open-chat.
  const whatsappMessageNoBody = firstMatch(WHATSAPP_MESSAGE_NOBODY_PATTERNS, text);
  if (whatsappMessageNoBody) {
    return {
      intent: 'MESSAGE_CONTACT',
      parameters: { contactName: whatsappMessageNoBody, message: '', channel: 'whatsapp' },
      confidence: 0.9,
    };
  }

  // 2. explicit OPEN-the-chat (no send) — a distinct intent, never a messaging fallback.
  const whatsappOpenChat = firstMatch(WHATSAPP_OPEN_CHAT_PATTERNS, text);
  if (whatsappOpenChat) {
    return {
      intent: 'OPEN_WHATSAPP_CONTACT',
      parameters: { contactName: whatsappOpenChat, channel: 'whatsapp' },
      confidence: 1.0,
    };
  }

  if (OPEN_WHATSAPP_PATTERN.test(text)) {
    return { intent: 'OPEN_WHATSAPP', parameters: { targetApp: 'WhatsApp' }, confidence: 1.0 };
  }

  const callContactName = firstMatch(CALL_CONTACT_PATTERNS, text);
  if (callContactName) {
    return { intent: 'CALL_CONTACT', parameters: { contactName: callContactName }, confidence: 1.0 };
  }

  const familyLocationTarget = firstMatch([FAMILY_LOCATION_PATTERN], text);
  if (familyLocationTarget) {
    return { intent: 'FAMILY_LOCATION', parameters: { contactName: familyLocationTarget }, confidence: 1.0 };
  }

  const appName = firstMatch([OPEN_APP_PATTERN], text);
  if (appName) {
    return { intent: 'OPEN_APP', parameters: { appName, targetApp: appName }, confidence: 0.5 };
  }

  return { intent: 'CHAT', parameters: {}, confidence: 0 };
}

const RISK_BY_INTENT: Partial<Record<ActionIntent, ActionRiskLevel>> = {
  OPEN_APP: 'LOW',
  CLOSE_APP: 'LOW',
  RETURN_TO_BENSON: 'LOW',
  OPEN_WAZE: 'LOW',
  OPEN_GOOGLE_MAPS: 'LOW',
  NAVIGATE_TO_PLACE: 'LOW',
  OPEN_WHATSAPP: 'LOW',
  CHAT: 'LOW',
  CALL_CONTACT: 'MEDIUM',
  OPEN_WHATSAPP_CONTACT: 'MEDIUM',
  MESSAGE_CONTACT: 'MEDIUM',
  FAMILY_LOCATION: 'MEDIUM',
  EMAIL_ACTION: 'MEDIUM',
  CALENDAR_ACTION: 'LOW',
  CONTACTS_LIST: 'LOW',
  CONTACTS_SEARCH: 'LOW',
  READ_MESSAGES: 'LOW',
  MEDIA_PLAY: 'LOW',
  HELP: 'LOW',
};

const CONFIRMATION_REQUIRED_INTENTS: ActionIntent[] = [
  'CALL_CONTACT',
  'OPEN_WHATSAPP_CONTACT',
  'MESSAGE_CONTACT',
  'FAMILY_LOCATION',
];

export function parseCommandToActionRequest(rawText: string, source: ActionSource): ActionRequest {
  const normalizedText = normalizeTranscript(rawText);
  const cleanedText = cleanDiscourse(normalizedText);
  const { intent, parameters, confidence } = classify(cleanedText);

  return createActionRequest({
    source,
    rawText,
    intent,
    parameters: { ...parameters, confidence, normalizedText, cleanedText },
    riskLevel: RISK_BY_INTENT[intent] ?? 'LOW',
    requiresConfirmation: CONFIRMATION_REQUIRED_INTENTS.includes(intent),
  });
}
