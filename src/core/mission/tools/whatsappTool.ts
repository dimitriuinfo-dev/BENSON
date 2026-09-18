// BENSON Mission Governance — WhatsApp Tool Layer.
// The ONLY place allowed to call Linking for WhatsApp. Nothing outside this file may open a
// wa.me / whatsapp:// deep link — src/core/mission/missionExecutor.ts is the only caller.
// Contact resolution reuses the existing contact path (src/core/contacts) rather than
// reimplementing matching — per instruction, extend what exists, don't parallel-build it.

import * as Contacts from 'expo-contacts';
import { endWhatsAppCall, muteWhatsAppCall, executeCommand, setWhatsAppAutomationActive, getForegroundPackage, runWhatsAppCallNative, runWhatsAppOpenConversationCall, runWhatsAppOpenConversationType, pressWhatsAppSendVerified, getWhatsAppWriteState } from 'benson-accessibility';
import type { CommandStep, CommandResult, CommandMatch, BensonNode } from 'benson-accessibility';
import { logAudioDiag } from 'benson-foreground-service';
import { getLastScreenSnapshot, getScreenSnapshot } from '../../../../lib/screenBridge';
import { resolveContact as resolveAgainstList, loadDeviceContacts, getContactsPermissionState } from '../../contacts';
import type { ContactResolveResult, TrustedContact } from '../../contacts';
import { isPackageInstalled, launchPackage, openUriWithPackage } from '../../action-engine/androidActionExecutor';
import { enterPipMode } from 'benson-app-registry';
import { hasOverlayPermission, showBubble } from 'benson-overlay';
import { waitForBackground } from '../appStateSignal';
import type { ToolCallResult } from '../missionTypes';
import { ensureAccessibilityReady, ACCESSIBILITY_DISCONNECTED_ERROR } from '../../safety';
import { recordVerifiedIdentity } from '../../contacts/verifiedIdentityStore';

const LOG_TAG = '[WhatsAppTool]';
function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

const WHATSAPP_PACKAGE = 'com.whatsapp';

// Linking.canOpenURL('whatsapp://send') was used here originally and reported a false negative
// on a real device with WhatsApp actually installed — Android's package-visibility rules treat
// package-IDENTITY checks (PackageManager.getPackageInfo, what <queries><package> grants) and
// implicit-INTENT-resolution checks (queryIntentActivities, what canOpenURL performs) as
// separate concerns; a <package> queries entry doesn't reliably satisfy the latter. The native
// isPackageInstalled() (benson-app-registry) already does the identity check correctly and is
// proven working for Waze detection elsewhere in this codebase — reused here instead.
export async function isInstalled(): Promise<boolean> {
  return isPackageInstalled(WHATSAPP_PACKAGE);
}

export async function hasContactsPermission(): Promise<boolean> {
  const perm = await Contacts.getPermissionsAsync();
  return perm.status === 'granted';
}

// deviceContacts is injected by the caller when a test/stub list should take priority (e.g. the
// TEST_CONTACTS stand-in already used elsewhere in this app before real contact memory exists) —
// falls back to real expo-contacts data otherwise. Never a second, parallel matching algorithm.
export async function resolveContact(name: string, extraContacts: TrustedContact[] = []): Promise<ContactResolveResult> {
  const deviceContacts = await loadDeviceContacts();
  const merged = [...extraContacts, ...deviceContacts];
  return resolveAgainstList({ rawName: name, preferredChannel: 'whatsapp', contacts: merged });
}

// Only a leading '+' followed by 7-15 digits is treated as a confirmed E.164 number — a bare
// digit string with no country code prefix can't be reliably normalized (a Romanian 07xx number
// and a German 07xx number look identical without it), so this returns null rather than guess.
export function toE164(raw: string): string | null {
  const trimmed = raw.trim();
  if (!trimmed.startsWith('+')) return null;
  const digits = trimmed.replace(/[^\d]/g, '');
  if (digits.length < 7 || digits.length > 15) return null;
  return `+${digits}`;
}

// "Benson stays a bubble" (product-owner-directed 2026-07-17, replacing the earlier real-PiP
// approach): BENSON shows as a small, draggable overlay bubble at the bottom of the screen right
// before WhatsApp takes the foreground — a plain WindowManager overlay, not Android's real
// Picture-in-Picture/multi-window. PiP was dropped because ColorOS's own flexible-window layer
// kept reinterpreting it unpredictably (confirmed live: sometimes a tiny illegible window,
// sometimes indistinguishable from Expo's own dev-tools overlay); a system overlay bubble has no
// such OS-level ambiguity — it's the exact same mechanism the wake-ring already uses successfully
// on top of any foreground app. The reverse (WhatsApp shrunk, BENSON full-screen) still isn't
// possible; Android has no API to force a different app into a small window. Falls back to real
// PiP only if the user hasn't granted the overlay permission yet, so the handoff still does
// *something* visible rather than nothing.
async function enterPipBeforeWhatsApp(): Promise<void> {
  try {
    if (await hasOverlayPermission()) {
      showBubble();
    } else {
      enterPipMode();
    }
  } catch (err) {
    devLog('enterPipBeforeWhatsApp: bubble/PiP threw', err);
  }
}

export function maskPhone(raw: string): string {
  const digits = raw.replace(/[^\d]/g, '');
  if (digits.length <= 4) return '••••';
  return `••••${digits.slice(-4)}`;
}

// Logs must never carry a raw phone number (standing privacy constraint) — this redacts any
// 7+ digit run (the phone number embedded in a wa.me/<digits> URL) before a url is ever logged,
// keeping the rest of the URL/query readable for debugging.
function redactUrlForLog(url: string): string {
  return url.replace(/\d{7,}/g, (digits) => `••••${digits.slice(-4)}`);
}

export function buildWaMeUrl(e164Phone: string, message?: string): string {
  const digits = e164Phone.replace(/[^\d]/g, ''); // wa.me wants bare digits, no leading '+'
  const base = `https://wa.me/${digits}`;
  return message ? `${base}?text=${encodeURIComponent(message)}` : base;
}

// Explicit-package intent (Intent.setPackage), not a plain implicit ACTION_VIEW — confirmed live
// 2026-07-17: Linking.openURL('https://wa.me/...') let Android silently pick whichever app is the
// current default handler for that link, which on a phone with both WhatsApp and WhatsApp
// Business installed turned out to be Business, sending the message from the wrong account.
// Targeting WHATSAPP_PACKAGE explicitly guarantees regular WhatsApp regardless of the OS default.
async function tryOpen(url: string): Promise<boolean> {
  const outcome = openUriWithPackage(url, WHATSAPP_PACKAGE);
  if (!outcome.success) {
    devLog('open failed', redactUrlForLog(url), outcome.error);
  }
  return outcome.success;
}

async function attemptAndConfirm(url: string): Promise<ToolCallResult> {
  const accepted = await tryOpen(url);
  if (!accepted) return { outcome: 'launch_failed', error: 'Could not open WhatsApp.' };
  const wentBackground = await waitForBackground(3000);
  devLog('accepted', redactUrlForLog(url), 'app_switch_observed=', wentBackground);
  return { outcome: wentBackground ? 'app_switch_observed' : 'launch_requested' };
}

// Bare "open WhatsApp" (no chat/contact) — deliberately NOT a Linking deep link. whatsapp://
// with no path was tried first and reported a silent no-op on a real device (Linking.openURL
// resolved without throwing, but no activity actually handled it — WhatsApp apparently doesn't
// register a bare-scheme handler). Native package launch is the proven mechanism already used
// for this exact case elsewhere in the codebase (appLauncherExecutor.ts's OPEN_WHATSAPP branch).
export async function openApp(): Promise<ToolCallResult> {
  enterPipBeforeWhatsApp();
  const outcome = launchPackage(WHATSAPP_PACKAGE);
  if (!outcome.success) {
    return { outcome: 'launch_failed', error: outcome.error ?? 'Could not launch WhatsApp.' };
  }
  const wentBackground = await waitForBackground(3000);
  devLog('launchPackage', WHATSAPP_PACKAGE, 'app_switch_observed=', wentBackground);
  return { outcome: wentBackground ? 'app_switch_observed' : 'launch_requested' };
}

// Opens the conversation with the message pre-filled — WhatsApp itself is what "sends". Used
// directly only for the no-message "openContact" case; sendMessage (below) is what actually gets
// the message sent when there is one.
export async function openConversation(e164Phone: string, message?: string): Promise<ToolCallResult> {
  return attemptAndConfirm(buildWaMeUrl(e164Phone, message));
}

// ROUND_WA_GOVERNANCE_ROUTING — `sendMessage` (wa.me?text= prefill + a blind send-button tap,
// after a single blind confirmation) and `sendMessageByName` (search-recipe + type + send) are
// REMOVED. The only path to WhatsApp's send button is now the two-phase governed write:
// prepareMessageDirect (open → verify identity → type → verify-typed → STOP) then, on explicit
// YES, confirmSendMessageDirect → native pressWhatsAppSendVerified (idempotent, sends once).

// Doctrine (product-owner-directed 2026-07-31): BENSON does not read the phone's contact list
// for the WhatsApp call route at all, and does not resolve a "contact" — it governs WhatsApp's
// own UI, the same way a human would: open it, tap search, type a search string, tap the first
// result, tap the call button. Resolving that string against a real person is WhatsApp's job, not
// BENSON's. The caller (missionValidator.ts's placeCall branch) is responsible for turning the
// raw spoken text into a clean search string via buildCallSearchString before this ever runs.
//
// Every step below is a generic accessibility step through executeCommand (BensonCommandExecutor,
// unchanged native code) — the exact same primitive already used by sendMessage() above. No
// device contacts, no native placeWhatsAppCall call, no fallback if a step doesn't find its node:
// executeCommand already stops at the first problem and reports which step, honored as-is here.

// Per-language WhatsApp UI labels (product-owner-directed extraction — these were previously two
// bare hardcoded German strings). Only 'de' is confirmed live on this device (see
// BensonAccessibilityService.kt's SEARCH_KEYWORDS/CALL_KEYWORDS comments, which found German
// labels working live and kept EN/RO only as an untested fallback) — 'en'/'ro' reuse the exact
// same fallback words already chosen there for consistency; 'fr' has no prior art anywhere in
// this codebase and is an unverified best guess. Keyed by base language (first two letters of a
// BCP-47 tag), not the full tag, matching how the rest of this codebase already normalizes
// language codes (e.g. localWhisperEngine.ts's lang.split('-')[0]).
const WHATSAPP_UI_LABELS: Record<string, { search: string; voiceCall: string; send: string }> = {
  de: { search: 'suchen', voiceCall: 'sprachanruf', send: 'senden' }, // confirmed live on this device
  en: { search: 'search', voiceCall: 'voice call', send: 'send' }, // unverified — reused from CALL_KEYWORDS/SEARCH_KEYWORDS fallback list
  ro: { search: 'căutare', voiceCall: 'apel vocal', send: 'trimite' }, // unverified — reused from CALL_KEYWORDS/SEARCH_KEYWORDS fallback list
  fr: { search: 'rechercher', voiceCall: 'appel vocal', send: 'envoyer' }, // unverified — no prior art in this codebase for French at all
};
const DEFAULT_WHATSAPP_UI_LANG = 'de'; // preserves exact prior behavior when no language is supplied

function whatsappUiLabels(uiLang: string): { search: string; voiceCall: string; send: string } {
  const base = uiLang.split('-')[0].toLowerCase();
  return WHATSAPP_UI_LABELS[base] ?? WHATSAPP_UI_LABELS[DEFAULT_WHATSAPP_UI_LANG];
}

// "voice call" label is distinct from "videoanruf"/"video call" in every language above, so no
// separate video-exclusion match is needed.
const WHATSAPP_SEARCH_INPUT_VIEW_ID = 'com.whatsapp:id/search_input';
// Message compose field, confirmed live elsewhere in this codebase (BensonAccessibilityService.kt
// already uses this exact viewId to detect "inside an individual chat"). Reused here for
// set_text — same accessibility action (ACTION_SET_TEXT) already proven on search_input above,
// applied to a different but structurally identical EditText. UNTESTED for this specific
// combination (typing via accessibility into THIS field, as opposed to WhatsApp's own wa.me
// URL-prefill mechanism the old sendMessage() below uses) — first thing to verify live.
const WHATSAPP_ENTRY_VIEW_ID = 'com.whatsapp:id/entry';

// Revert switch (product-owner-directed 2026-08-01, doctrine enforcement): true routes
// openContact/prepareMessage through the same no-contacts-read accessibility recipe pattern as
// placeCall (search by name string, tap first result, [type+send]). false restores the exact old
// path below (openConversation/sendMessage, wa.me deep link, phoneNumber from resolved device
// contacts) — untouched, still present, still exported. Flip this one constant to go back.
export const WHATSAPP_MESSAGE_VIA_ACCESSIBILITY = true;

// Strips leading Romanian clitic pronouns/prepositions that attach to a spoken name (sună-O PE
// Hannah, caută-L Ion) so the governance recipe below searches WhatsApp for a clean name, never a
// raw transcript fragment. commandParser.ts's own CALL_CONTACT_PATTERNS/WHATSAPP_CALL_PATTERNS
// already strip most of these inside their own capture groups — this is a safety net for whatever
// leaks through when the STT-produced word order doesn't match one of those patterns' optional
// groups exactly. Confirmed live (2026-07-31): STT fused the clitic "o" directly onto "pe" with no
// space ("sună-o pe Hannah" -> captured contactName "Upe Hana"), which no single-token strip could
// catch — "upe" is listed explicitly as its own fused alternative for that reason. Runs in a loop
// so more than one leaked token in a row ("-l pe Ion") is fully stripped, not just the first.
const LEADING_CLITIC_OR_PREPOSITION = /^[\s-]*(?:upe|o|u|l|le|îl|il|i|pe|la|lui)\b[\s-]*/i;

export function buildCallSearchString(rawName: string): string {
  let text = rawName.trim();
  let previous: string;
  do {
    previous = text;
    text = text.replace(LEADING_CLITIC_OR_PREPOSITION, '').trim();
  } while (text !== previous && text.length > 0);
  return text;
}

interface RecipeStep {
  label: string; // Romanian, human-facing — what to say if THIS step's node isn't found.
  step: CommandStep;
}

// ── Tolerant contact matching (2026-08-28) ─────────────────────────────────────────────────────
// BENSON still does NOT read the phone's contact list. It types the user's spoken name into
// WhatsApp's own search field, then reads back the RESULT ROWS via the Accessibility snapshot and
// picks tolerantly. The text typed into WhatsApp stays exactly what the user said — normalization
// below is applied ONLY when comparing the rows WhatsApp shows.

// no diacritics · doubled letters -> one · 'h' ignored · case-insensitive · letters/digits only.
function phoneticKey(s: string): string {
  return (s || '')
    .normalize('NFD').replace(/[̀-ͯ]/g, '')
    .toLowerCase()
    .replace(/h/g, '')
    .replace(/[^a-z0-9]/g, '')
    .replace(/(.)\1+/g, '$1');
}

type MatchStrategy = 'exact' | 'prefix' | 'phonetic';

function matchCandidates(query: string, candidates: string[]): { strategy: MatchStrategy; matches: string[] } | null {
  const ql = query.trim().toLowerCase();
  const uniq = [...new Set(candidates.map((c) => c.trim()).filter(Boolean))];
  const exact = uniq.filter((c) => c.toLowerCase() === ql);
  if (exact.length) return { strategy: 'exact', matches: exact };
  const prefix = uniq.filter((c) => c.toLowerCase().startsWith(ql));
  if (prefix.length) return { strategy: 'prefix', matches: prefix };
  const qp = phoneticKey(query);
  if (qp) {
    const phon = uniq.filter((c) => phoneticKey(c) === qp);
    if (phon.length) return { strategy: 'phonetic', matches: phon };
  }
  return null;
}

// ── B-fix2 (2026-09-07) — potrivire fonetică pe rândurile din ecran ───────────────────────────
// Cauza din log: `textContains:"HANA"` exact nu găsea rândul „HANNAH". Reia algoritmul din
// lib/appIndex.ts (consonantSkeleton + boundedLevenshtein) — copiat aici verbatim fiindcă acolo
// nu e exportat, iar lib/appIndex.ts nu e în scope. „HANA" → skeleton „N" (fără h, fără vocale),
// „HANNAH" → „NN" → distanță 1 → potrivire. Se scorează TOATE rândurile vizibile, câștigă scorul
// maxim; la egalitate, primul rând din listă.
function consonantSkeleton(s: string): string {
  return (s || '').replace(/[aeiou]/g, '');
}
function boundedLevenshtein(a: string, b: string, max: number): number {
  if (a === b) return 0;
  if (Math.abs(a.length - b.length) > max) return max + 1;
  const m = a.length;
  const n = b.length;
  if (m === 0) return n;
  if (n === 0) return m;
  let prev = new Array<number>(n + 1);
  let cur = new Array<number>(n + 1);
  for (let j = 0; j <= n; j++) prev[j] = j;
  for (let i = 1; i <= m; i++) {
    cur[0] = i;
    let rowMin = cur[0];
    for (let j = 1; j <= n; j++) {
      const cost = a.charCodeAt(i - 1) === b.charCodeAt(j - 1) ? 0 : 1;
      cur[j] = Math.min(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost);
      if (cur[j] < rowMin) rowMin = cur[j];
    }
    if (rowMin > max) return max + 1;
    const tmp = prev;
    prev = cur;
    cur = tmp;
  }
  return prev[n];
}
function normNameLoose(s: string): string {
  return (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9]+/g, '');
}
// consonant skeleton with 'h' dropped first — 'h' is a weak/silent consonant across RO/DE/EN, so
// "HANA" and "HANNAH" land on comparable skeletons ("n" vs "nn", edit distance 1).
function skelKey(s: string): string {
  return consonantSkeleton(normNameLoose(s).replace(/h/g, ''));
}
// Higher = better. exact → 1000; otherwise a blend of consonant-skeleton distance, full-string
// distance, and a prefix bonus (a first-name query can surface a longer "First Last" row).
function phoneticContactScore(query: string, cand: string): number {
  const q = normNameLoose(query);
  const c = normNameLoose(cand);
  if (!q || !c) return 0;
  if (q === c) return 1000;
  const qs = skelKey(query);
  const cs = skelKey(cand);
  const dSkel = boundedLevenshtein(qs, cs, 4);
  const dFull = boundedLevenshtein(q, c, 4);
  let s = 0;
  if (qs && qs === cs) s += 450;
  s += Math.max(0, 380 - dSkel * 150);
  s += Math.max(0, 200 - dFull * 45);
  if (c.startsWith(q) && q.length >= 3) s += 260;
  else if (q.startsWith(c) && c.length >= 3) s += 140;
  return s;
}
const CONTACT_SCORE_FLOOR = 260;
function pickBestPhonetic(query: string, candidates: string[]): { name: string; score: number } | null {
  let best: { name: string; score: number } | null = null;
  for (const cand of candidates) {
    const score = phoneticContactScore(query, cand);
    // strict '>' so that on a tie the FIRST row in the list wins.
    if (score > (best?.score ?? -1)) best = { name: cand, score };
  }
  if (!best || best.score < CONTACT_SCORE_FLOOR) return null;
  return best;
}

const WHATSAPP_SECTION_HEADERS =
  /^(chats?|mesaje|messages|contacte|contacts|contacts on whatsapp|contacte pe whatsapp|kontakte|nachrichten|status|apeluri|calls)$/i;

// Best-effort: native gives a flat node list, no tree. A result-row name is a short, non-editable
// `text` that isn't the search field and isn't a section header. The exact/prefix/phonetic
// cascade above filters out whatever noise (message previews, timestamps) slips through. Empty
// return -> caller falls back to a loose native substring match.
function readWhatsAppResultNames(snapshot?: { packageName: string; timestamp: number; nodes: BensonNode[] } | null): string[] {
  const snap = snapshot ?? getLastScreenSnapshot();
  if (!snap || snap.packageName !== WHATSAPP_PACKAGE) return [];
  // Age check only applies to the passive pushed cache; an on-demand snapshot passed in is fresh
  // by construction (its timestamp is "now").
  if (!snapshot && Date.now() - snap.timestamp > 6000) return [];
  const names: string[] = [];
  for (const n of snap.nodes) {
    if (n.editable) continue;
    if ((n.viewId || '').toLowerCase().includes('search')) continue;
    // B-fix2: harvest from BOTH `text` AND `contentDescription` (first segment). On this WhatsApp
    // build the result-row name lives on the row container's contentDescription ("Hannah, 2 unread
    // messages"), not a `text` node — which is why `candidates=0` in the failing log.
    const raw = [
      (n.text || '').trim(),
      (n.contentDescription || '').split(/[,\n·|•]/)[0].trim(),
    ];
    for (const t of raw) {
      if (!t || t.length > 40) continue;
      if (WHATSAPP_SECTION_HEADERS.test(t)) continue;
      names.push(t);
    }
  }
  return names;
}

function buildChoiceQuestion(_choices: string[]): string {
  // BENSON_STABILIZATION_1 — no enumeration.
  return 'Nu sunt sigur de nume. Spune numele din nou.';
}

type ResultPick =
  | { kind: 'pick'; clickText: string | null } // null -> loose native substring match on the query
  | { kind: 'ask'; question: string };

function resolveResultPick(query: string, snapshot?: { packageName: string; timestamp: number; nodes: BensonNode[] } | null): ResultPick {
  const candidates = [...new Set(readWhatsAppResultNames(snapshot).map((c) => c.trim()).filter(Boolean))];
  if (candidates.length === 0) {
    logAudioDiag('CONTACT_MATCH', `query=${JSON.stringify(query)} candidates=0 matched="" score=0`);
    return { kind: 'pick', clickText: null };
  }
  // B-fix2: score EVERY visible row phonetically, take the highest; tie → first row (pickBestPhonetic
  // uses strict '>'). No "which one?" — the product-owner-directed rule is highest score wins.
  const best = pickBestPhonetic(query, candidates);
  if (!best) {
    logAudioDiag('CONTACT_MATCH', `query=${JSON.stringify(query)} candidates=${candidates.length} matched="" score=0`);
    return { kind: 'pick', clickText: null };
  }
  logAudioDiag('CONTACT_MATCH', `query=${JSON.stringify(query)} candidates=${candidates.length} matched=${JSON.stringify(best.name)} score=${best.score}`);
  return { kind: 'pick', clickText: best.name };
}

// ── Resource-id-first step execution (2026-08-28) ──────────────────────────────────────────────
// WhatsApp's icon buttons (search, voice call, send) carry NO visible `text` node — matching them
// by a translated label ("suchen" / "search" / "căutare") fails on every non-German install and,
// for a pure icon, even in German. Every language-dependent control is now tried by RESOURCE-ID
// first, then by a de/en/ro label list. The ids below are the long-standing WhatsApp ones; they
// are NOT verified against this exact WhatsApp build — the label list is the safety net, and the
// RECIPE_STEP log line records which strategy actually hit.
type SelStrategy = { by: 'viewId' | 'text'; match: CommandMatch; note: string };

const WA_ID = {
  searchMenu: 'com.whatsapp:id/menuitem_search',
  searchInput: WHATSAPP_SEARCH_INPUT_VIEW_ID, // com.whatsapp:id/search_input — already used below
  entry: WHATSAPP_ENTRY_VIEW_ID,             // com.whatsapp:id/entry — already used below
  send: 'com.whatsapp:id/send',
  voiceCallMenu: 'com.whatsapp:id/menuitem_call',
};

function labelStrategies(kind: 'search' | 'voiceCall' | 'send', extra: Partial<CommandMatch> = {}): SelStrategy[] {
  const seen = new Set<string>();
  const out: SelStrategy[] = [];
  for (const lang of ['de', 'en', 'ro'] as const) {
    const word = WHATSAPP_UI_LABELS[lang][kind];
    const key = word.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ by: 'text', match: { textContains: word, clickable: true, ...extra }, note: `text:${lang}:${word}` });
  }
  return out;
}

const SEARCH_STRATEGIES: SelStrategy[] = [
  { by: 'viewId', match: { viewId: WA_ID.searchMenu }, note: 'viewId:menuitem_search' },
  { by: 'viewId', match: { viewIdContains: 'menuitem_search' }, note: 'viewIdContains:menuitem_search' },
  ...labelStrategies('search'),
];
const VOICECALL_STRATEGIES: SelStrategy[] = [
  { by: 'viewId', match: { viewId: WA_ID.voiceCallMenu }, note: 'viewId:menuitem_call' },
  { by: 'viewId', match: { viewIdContains: 'menuitem_call' }, note: 'viewIdContains:menuitem_call' },
  ...labelStrategies('voiceCall', { maxTopPercent: 15 }),
];
const SEND_STRATEGIES: SelStrategy[] = [
  { by: 'viewId', match: { viewId: WA_ID.send }, note: 'viewId:send' },
  { by: 'viewId', match: { viewIdContains: ':id/send' }, note: 'viewIdContains::id/send' },
  ...labelStrategies('send'),
];

// Statuses where a different strategy might still work; anything else (blocked / tap_rejected /
// wrong_package) is a hard stop.
const TRY_NEXT_STATUSES = new Set(['not_found', 'timeout', 'ambiguous', 'invalid']);

async function clickResilient(strategies: SelStrategy[], stepLabel: string, index: number, timeoutMs = 3500): Promise<CommandResult> {
  let last: CommandResult = { success: false, stepIndex: index, action: 'click', status: 'not_found', detail: null };
  for (const s of strategies) {
    const r = await executeCommand({ steps: [{ action: 'click', match: s.match, timeoutMs, requirePackage: WHATSAPP_PACKAGE }] });
    if (r.success) {
      logAudioDiag('RECIPE_STEP', `index=${index} label=${JSON.stringify(stepLabel)} strategy=${s.by} matched=${JSON.stringify(s.note)}`);
      return r;
    }
    last = r;
    if (!TRY_NEXT_STATUSES.has(r.status)) break;
  }
  logAudioDiag('RECIPE_STEP', `index=${index} label=${JSON.stringify(stepLabel)} strategy=none matched="" status=${last.status}`);
  return last;
}

// A group of plain (non-resilient) steps run as one executeCommand, with a RECIPE_STEP line per
// step that actually ran and one marking the step that failed.
async function runPlainSteps(steps: RecipeStep[], baseIndex: number): Promise<CommandResult> {
  const r = await executeCommand({ steps: steps.map((s) => s.step) });
  const ranThrough = r.success ? steps.length : Math.min(steps.length, r.stepIndex + 1);
  for (let i = 0; i < ranThrough; i++) {
    const st = steps[i];
    const m = 'match' in st.step ? (st.step as { match?: CommandMatch }).match : undefined;
    const by = m?.viewId || m?.viewIdContains ? 'viewId' : m ? 'text' : 'action';
    const failedHere = !r.success && i === r.stepIndex;
    logAudioDiag(
      'RECIPE_STEP',
      `index=${baseIndex + i} label=${JSON.stringify(st.label)} strategy=${by} matched=${failedHere ? `FAILED:${r.status}` : JSON.stringify(st.step.action)}`,
    );
  }
  return r;
}

function failAt(result: CommandResult, phase: string, name: string): ToolCallResult {
  logAudioDiag(
    'EXEC_ERROR',
    `phase=${phase} action=${result.action} status=${result.status} detail=${JSON.stringify((result.detail ?? '').replace(/\s+/g, ' ').slice(0, 300))}`,
  );
  const userError =
    phase === 'contact_result' ? `Nu am găsit contactul "${name}" în WhatsApp.`
    : phase === 'message_typed' ? 'Am pregătit mesajul în WhatsApp, dar nu am reușit să apăs trimite — apasă-l tu.'
    : 'Nu am reușit să duc comanda la capăt în WhatsApp.';
  return { outcome: 'opened_manual_action_required', error: userError };
}

type TailKind = { kind: 'call' } | { kind: 'open' } | { kind: 'send'; message: string };

// Open WhatsApp → search icon (resilient) → type the name (viewId) → pick a result row in JS →
// tap it → confirm chat open → run the tail (call / open / send). Every step logs RECIPE_STEP;
// every failure logs EXEC_ERROR with the technical detail and returns ONE clean Romanian sentence.
async function runTwoPhase(name: string, _uiLang: string, tail: TailKind): Promise<ToolCallResult> {
  const open = await runPlainSteps([
    { label: 'lansare WhatsApp', step: { action: 'launch_app', package: WHATSAPP_PACKAGE } },
    { label: 'așteptare deschidere', step: { action: 'wait', ms: 900 } },
    { label: 'WhatsApp în prim-plan', step: { action: 'assert_package', package: WHATSAPP_PACKAGE, timeoutMs: 4000 } },
    { label: 'așteptare ecran principal', step: { action: 'wait', ms: 400 } },
  ], 0);
  if (!open.success) return failAt(open, 'launch', name);

  const searchClick = await clickResilient(SEARCH_STRATEGIES, 'butonul de căutare', 4, 4000);
  if (!searchClick.success) return failAt(searchClick, 'search', name);

  const typed = await runPlainSteps([
    { label: 'așteptare câmp de căutare', step: { action: 'wait', ms: 500 } },
    { label: 'câmpul de căutare', step: { action: 'set_text', match: { viewId: WA_ID.searchInput }, text: name, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE } },
    { label: 'așteptare rezultate', step: { action: 'wait', ms: 1000 } },
  ], 5);
  if (!typed.success) return failAt(typed, 'search_input', name);

  const pick = resolveResultPick(name);
  if (pick.kind === 'ask') return { outcome: 'opened_manual_action_required', error: pick.question };
  const rowText = pick.clickText ?? name;

  const rowFlags = { wholeWord: false, excludeSearchUi: true, excludeAvatars: true, clickableAncestor: true } as const;
  const rowClick = await clickResilient([
    { by: 'text', match: { textContains: rowText, ...rowFlags }, note: `text:row:${rowText}` },
    ...(rowText !== name ? [{ by: 'text' as const, match: { textContains: name, ...rowFlags }, note: `text:row:${name}` }] : []),
  ], `contactul "${rowText}"`, 8, 3000);
  if (!rowClick.success) return failAt(rowClick, 'contact_result', name);

  const chatOpen = await runPlainSteps([
    { label: 'așteptare deschidere conversație', step: { action: 'wait', ms: 900 } },
    { label: 'confirmare părăsire ecran de căutare', step: { action: 'assert_gone', match: { viewIdContains: 'search_input' }, timeoutMs: 3000 } },
  ], 9);
  if (!chatOpen.success) return failAt(chatOpen, 'chat_open', name);

  if (tail.kind === 'open') {
    await runPlainSteps([{ label: 'revenire la BENSON', step: { action: 'return_to_benson' } }], 11);
    return { outcome: 'app_switch_observed', via: 'accessibility_command_executor' };
  }

  if (tail.kind === 'call') {
    const callClick = await clickResilient(VOICECALL_STRATEGIES, 'butonul de apel vocal', 11, 3000);
    if (!callClick.success) return failAt(callClick, 'voice_call', name);
    await runPlainSteps([
      { label: 'așteptare pornire apel', step: { action: 'wait', ms: 600 } },
      { label: 'revenire la BENSON', step: { action: 'return_to_benson' } },
    ], 12);
    return { outcome: 'app_switch_observed', via: 'accessibility_command_executor' };
  }

  const msgField = await runPlainSteps([
    { label: 'câmpul de scriere', step: { action: 'set_text', match: { viewId: WA_ID.entry }, text: tail.message, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE } },
    { label: 'așteptare scriere', step: { action: 'wait', ms: 400 } },
  ], 11);
  if (!msgField.success) return failAt(msgField, 'message_field', name);
  const sendClick = await clickResilient(SEND_STRATEGIES, 'butonul de trimitere', 13, 3000);
  if (!sendClick.success) return failAt(sendClick, 'message_typed', name);
  await runPlainSteps([
    { label: 'așteptare trimitere', step: { action: 'wait', ms: 1200 } },
    { label: 'revenire la BENSON', step: { action: 'return_to_benson' } },
  ], 14);
  return { outcome: 'app_switch_observed', via: 'accessibility_command_executor' };
}

// ── waitForNode primitive (Round B, 2026-08-29) ───────────────────────────────────────────────
// Replaces every fixed `wait ms` in the call recipe. Polls a FRESH on-demand snapshot
// (getScreenSnapshot — reads the live tree, retries natively) every `pollMs` until a node
// matching `predicate` appears or `timeoutMs` elapses. Diacritic- and case-insensitive on
// text/contentDescription. `predicate` may be a single object or a list (matches ANY).
type NodePredicate = {
  viewId?: string;
  viewIdContains?: string;
  text?: string;
  textContains?: string;
  contentDescription?: string;
  className?: string;
};

function normLoose(s: string): string {
  return (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().trim();
}

// RUNDA B (B2) — the native getScreenSnapshot() used to be able to BLOCK (tree walk on the main
// thread, stalled on getChild() IPC while WhatsApp launched — r.txt), which froze waitForNode's
// poll loop past its own timeout. The native side is now off-main + capped (captureSnapshot), and
// this is the belt-and-braces: every poll races the snapshot against a hard cap and moves on with
// null rather than waiting on a call that isn't coming back.
type SnapshotResult = Awaited<ReturnType<typeof getScreenSnapshot>>;
async function cappedSnapshot(capMs = 350): Promise<SnapshotResult> {
  return Promise.race<SnapshotResult>([
    getScreenSnapshot(),
    new Promise<null>((r) => setTimeout(() => r(null), capMs)),
  ]);
}

function nodeMatchesPredicate(n: BensonNode, p: NodePredicate): boolean {
  const vid = n.viewId || '';
  if (p.viewId && vid !== p.viewId) return false;
  if (p.viewIdContains && !vid.includes(p.viewIdContains)) return false;
  if (p.className && (n.className || '') !== p.className) return false;
  const label = `${normLoose(n.text)} ${normLoose(n.contentDescription)}`.trim();
  if (p.text && normLoose(n.text) !== normLoose(p.text)) return false;
  if (p.textContains && !label.includes(normLoose(p.textContains))) return false;
  if (p.contentDescription && !normLoose(n.contentDescription).includes(normLoose(p.contentDescription))) return false;
  return true;
}

type WaitNodeResult = { found: boolean; node: BensonNode | null; predicate: NodePredicate | null; elapsedMs: number };

async function waitForNode(
  predicate: NodePredicate | NodePredicate[],
  timeoutMs = 3000,
  pollMs = 100,
): Promise<WaitNodeResult> {
  const preds = Array.isArray(predicate) ? predicate : [predicate];
  const key = JSON.stringify(preds);
  const started = Date.now();
  for (;;) {
    const snap = await cappedSnapshot();
    if (snap && snap.packageName === WHATSAPP_PACKAGE) {
      for (const p of preds) {
        const node = snap.nodes.find((n) => nodeMatchesPredicate(n, p));
        if (node) {
          const elapsedMs = Date.now() - started;
          logAudioDiag('WAIT_NODE', `predicate=${JSON.stringify(key)} foundAfterMs=${elapsedMs} result=found`);
          return { found: true, node, predicate: p, elapsedMs };
        }
      }
    }
    if (Date.now() - started >= timeoutMs) {
      const elapsedMs = Date.now() - started;
      logAudioDiag('WAIT_NODE', `predicate=${JSON.stringify(key)} foundAfterMs=${elapsedMs} result=timeout`);
      return { found: false, node: null, predicate: null, elapsedMs };
    }
    await new Promise((r) => setTimeout(r, pollMs));
  }
}

// Inverse: resolves once NO node matches (screen moved on). Returns true if it went away in time.
// BF1-b: accepts a list too — "gone" means NONE of the predicates match any node.
async function waitForNodeGone(predicate: NodePredicate | NodePredicate[], timeoutMs = 3000, pollMs = 100): Promise<boolean> {
  const preds = Array.isArray(predicate) ? predicate : [predicate];
  const started = Date.now();
  for (;;) {
    const snap = await cappedSnapshot();
    const stillThere = !!snap && snap.packageName === WHATSAPP_PACKAGE &&
      snap.nodes.some((n) => preds.some((p) => nodeMatchesPredicate(n, p)));
    if (!stillThere) {
      logAudioDiag('WAIT_NODE', `predicate=${JSON.stringify(predicate)} foundAfterMs=${Date.now() - started} result=gone`);
      return true;
    }
    if (Date.now() - started >= timeoutMs) {
      logAudioDiag('WAIT_NODE', `predicate=${JSON.stringify(predicate)} foundAfterMs=${Date.now() - started} result=timeout`);
      return false;
    }
    await new Promise((r) => setTimeout(r, pollMs));
  }
}

function logRecipeStep(index: number, name: string, anchor: string, found: boolean, elapsedMs: number): void {
  logAudioDiag('RECIPE_STEP', `index=${index} name=${JSON.stringify(name)} anchor=${JSON.stringify(anchor)} found=${found} elapsedMs=${elapsedMs}`);
}

// One clean Romanian sentence + honest stop — never a blind tap.
function stopNotFound(step: string): ToolCallResult {
  logAudioDiag('EXEC_ERROR', `phase=wait_node step=${JSON.stringify(step)} status=timeout`);
  return { outcome: 'opened_manual_action_required', error: `Nu găsesc ${step}, deschid WhatsApp și preiei tu.` };
}

// ── BF1-b (2026-09-08) — selectori WhatsApp în cascadă strictă: viewId → contentDescription →
// text. Cauza dovedită (log 17:01:10): selectorul de căutare căuta un singur text românesc
// ("căutare"), WhatsApp e în germană → not_found. Ordinea: mai întâi id-ul (independent de limbă),
// apoi listă de contentDescription DE→RO(ambele forme prin normLoose)→EN, apoi listă de text.
// Prima potrivire câștigă. Revert: BF1B_SELECTORS_VIEWID = false → listele vechi de mai jos.
const BF1B_SELECTORS_VIEWID = true;

// Listele vechi (revert target).
const SEARCH_ANCHORS_LEGACY: NodePredicate[] = [
  { viewIdContains: 'menuitem_search' },
  { viewIdContains: 'search' },
  { contentDescription: 'suchen' }, { contentDescription: 'search' }, { contentDescription: 'caut' },
];
const SEARCH_INPUT_ANCHOR_LEGACY: NodePredicate[] = [{ viewIdContains: 'search_input' }];
const VOICECALL_ANCHORS_LEGACY: NodePredicate[] = [
  { viewIdContains: 'menuitem_call' },
  { contentDescription: 'sprachanruf' }, { contentDescription: 'voice call' }, { contentDescription: 'apel vocal' },
  { contentDescription: 'apel' },
];

// Listele BF1-b. viewId-urile: `menuitem_search`, `search_input`, `menuitem_call` sunt atestate în
// istoricul de inspecție live al acestui cod (SEARCH_STRATEGIES/WA_ID, SESSION_REPORT, comentarii
// native). `search_src_text` și `voip_call` NU sunt atestate pe acest build — rămân doar ca
// fallback de rang inferior, iar textul acoperă pasul dacă lipsesc.
const SEARCH_ANCHORS_BF1B: NodePredicate[] = [
  { viewId: 'com.whatsapp:id/menuitem_search' },
  { viewIdContains: 'menuitem_search' },
  { contentDescription: 'suchen' }, { contentDescription: 'cauta' }, { contentDescription: 'cautare' }, { contentDescription: 'search' },
  { textContains: 'suchen' }, { textContains: 'cauta' }, { textContains: 'cautare' }, { textContains: 'search' },
];
const SEARCH_FIELD_ANCHORS_BF1B: NodePredicate[] = [
  { viewIdContains: 'search_input' },     // com.whatsapp:id/search_input — atestat
  { viewIdContains: 'search_src_text' },  // fallback legacy — NEatestat pe acest build
];
const VOICECALL_ANCHORS_BF1B: NodePredicate[] = [
  { viewId: 'com.whatsapp:id/menuitem_call' },   // buton apel VOCAL (specific), atestat
  { viewIdContains: 'menuitem_call' },
  { viewIdContains: 'voip_call' },               // fallback — NEatestat pe acest build
  // contentDescription — formele specifice apelului vocal PRIMELE, ca prima potrivire să fie
  // întotdeauna cea corectă. „anrufen" (DE) e sigur: „Videoanruf" nu-l conține (se termină în
  // „…anruf", fără „en"). „Apel"/„Call" simple rămân OMISE — predicateToClickMatch le-ar face
  // `textContains` substring, iar „apel" prinde „Apel video" și „call" prinde „Video call"
  // (coliziune cu apelul VIDEO — vezi raport, abaterea per regula 4 din CLAUDE.md).
  { contentDescription: 'sprachanruf' }, { contentDescription: 'apel vocal' },
  { contentDescription: 'voice call' }, { contentDescription: 'apelare' }, { contentDescription: 'suna ' },
  { contentDescription: 'anrufen' },
  { textContains: 'sprachanruf' }, { textContains: 'apel vocal' }, { textContains: 'voice call' },
  { textContains: 'anrufen' },
];

const SEARCH_ANCHORS: NodePredicate[] = BF1B_SELECTORS_VIEWID ? SEARCH_ANCHORS_BF1B : SEARCH_ANCHORS_LEGACY;
const SEARCH_INPUT_ANCHOR: NodePredicate[] = BF1B_SELECTORS_VIEWID ? SEARCH_FIELD_ANCHORS_BF1B : SEARCH_INPUT_ANCHOR_LEGACY;
const VOICECALL_ANCHORS: NodePredicate[] = BF1B_SELECTORS_VIEWID ? VOICECALL_ANCHORS_BF1B : VOICECALL_ANCHORS_LEGACY;
const CHAT_ENTRY_ANCHOR: NodePredicate = { viewIdContains: ':id/entry' };

function predicateToClickMatch(p: NodePredicate): CommandMatch {
  // clickableAncestor:true on every branch — a matched viewId node (menu item, icon) may not be
  // clickable itself; the native executor then walks up to the row/button container.
  if (p.viewId) return { viewId: p.viewId, clickableAncestor: true };
  if (p.viewIdContains) return { viewIdContains: p.viewIdContains, clickableAncestor: true };
  // contentDescription is matched by the native executor's textContains (text + contentDescription).
  const needle = p.contentDescription ?? p.textContains ?? p.text ?? '';
  return { textContains: needle, clickable: true, clickableAncestor: true };
}

// BF1-b — one line per resolved cascade selector: which tier won and what it matched.
function selectorKind(p: NodePredicate): 'viewId' | 'contentDesc' | 'text' {
  if (p.viewId || p.viewIdContains) return 'viewId';
  if (p.contentDescription) return 'contentDesc';
  return 'text';
}
function logSelector(step: number, label: string, p: NodePredicate | null, node: BensonNode | null): void {
  if (!p) return;
  const kind = selectorKind(p);
  const value = kind === 'viewId'
    ? (node?.viewId || '')
    : ((node?.text || '').trim() || (node?.contentDescription || '').trim());
  logAudioDiag('SELECTOR', `step=${step} label=${JSON.stringify(label)} won=${kind} value=${JSON.stringify(value)}`);
}

// ── Call-recipe revert switch (2026-08-29) ────────────────────────────────────────────────────
// true  = LEGACY runTwoPhase({kind:'call'}) — the exact recipe proven working end-to-end on
//         device 2026-08-29 13:24. This is the default; do not change it without a 5/5 device
//         pass of the alternative.
// false = runCallRecipe() below (Round B waitForNode/getScreenSnapshot rewrite). Disabled: it
//         hung ~60s on device (waitForNode did not honour its timeout when getScreenSnapshot()
//         blocked; captureSnapshot() walks the whole tree on Dispatchers.Main and stalls on
//         AccessibilityNodeInfo.getChild() IPC while WhatsApp is still launching — see r.txt
//         diagnosis). The native captureSnapshot() primitive is kept for a future, fixed rewrite.
// RUNDA B (2026-09-07): flipped to false — runCallRecipe() below is now the active call path.
// The two bugs that disabled it are fixed: (1) native captureSnapshot() runs off Dispatchers.Main
// with a hard cap (BensonAccessibilityService.captureSnapshot), (2) every poll here races the
// snapshot against a cap (cappedSnapshot). Plus: the service now subscribes to
// typeWindowContentChanged, so rootInActiveWindow is kept fresh for WhatsApp's search results.
// Revert: set back to true → the legacy fixed-`wait ms` runTwoPhase({kind:'call'}) recipe.
const USE_LEGACY_CALL_RECIPE = false;

// WA-NATIVE-FINAL (2026-09-08) — THE active call path. One bridge call → BensonAccessibilityService
// .runWhatsAppCallNative() runs the whole launch→search→type→match→verify→call→verify sequence in
// Kotlin, on its own coroutine, independent of the RN Activity being foregrounded. This removes
// the JS step-by-step executor (runCallRecipe) from the placeCall path entirely — the audit
// proved runCallRecipe stalls because its waitForNode/setTimeout freeze when WhatsApp opens and
// BENSON backgrounds. Revert: set false → falls back to the runCallRecipe / runTwoPhase path.
const USE_NATIVE_CALL_RECIPE = true;

// Map the native executor's { success, step, error, contact, elapsedMs } to a ToolCallResult.
function mapNativeCallResult(r: { success: boolean; step: string; error: string | null; contact: string; elapsedMs: number }): ToolCallResult {
  logAudioDiag('WA_NATIVE_RESULT', `success=${r.success} step=${r.step} elapsedMs=${r.elapsedMs} contact=${JSON.stringify(r.contact)}`);
  if (r.success) return { outcome: 'app_switch_observed', via: 'native_wa_executor' };
  if (r.step === 'SERVICE') return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  const friendly =
    r.step.startsWith('CONTACT') || r.step.startsWith('CHAT_WRONG') ? `Nu am găsit contactul "${r.contact}" în WhatsApp.`
    : r.step.startsWith('CALL') ? `Am deschis conversația cu "${r.contact}", dar nu am reușit să pornesc apelul — pornește-l tu.`
    : r.step === 'PACKAGE' || r.step === 'LAUNCH' ? 'Nu am reușit să deschid WhatsApp.'
    : `Nu am reușit să duc apelul la capăt în WhatsApp (pas: ${r.step}).`;
  return { outcome: 'opened_manual_action_required', error: friendly };
}

// Round B — the WhatsApp voice-call recipe, rebuilt on getScreenSnapshot() + waitForNode().
// No fixed sleeps: every step waits for its anchor, acts, then waits for the screen to change.
// A missing anchor STOPS with "nu găsesc <pasul>" — never a blind tap. Does NOT return to BENSON:
// the WhatsApp call screen keeps the foreground (Round B4). DISABLED behind USE_LEGACY_CALL_RECIPE
// until the timeout + main-thread-walk bugs are fixed.
async function runCallRecipe(name: string): Promise<ToolCallResult> {
  // 0 — launch + confirm WhatsApp is foreground
  const t0 = Date.now();
  const launch = await executeCommand({ steps: [{ action: 'launch_app', package: WHATSAPP_PACKAGE }] });
  if (!launch.success) { logRecipeStep(0, 'lansare WhatsApp', 'launch_app', false, Date.now() - t0); return stopNotFound('WhatsApp'); }
  logRecipeStep(0, 'lansare WhatsApp', 'launch_app', true, Date.now() - t0);

  const fg = await waitForNode([{ viewIdContains: 'com.whatsapp' }, { className: 'android.widget.FrameLayout' }], 5000, 150);
  // The predicate above is loose on purpose — assert_package is the real gate:
  const inWa = await executeCommand({ steps: [{ action: 'assert_package', package: WHATSAPP_PACKAGE, timeoutMs: 4000 }] });
  logRecipeStep(1, 'WhatsApp în prim-plan', 'assert_package', inWa.success, fg.elapsedMs);
  if (!inWa.success) return stopNotFound('WhatsApp');

  // 2 — search button (cascade: viewId → contentDescription → text; first match wins)
  const search = await waitForNode(SEARCH_ANCHORS, 4000, 150);
  logRecipeStep(2, 'butonul de căutare', search.predicate ? JSON.stringify(search.predicate) : 'none', search.found, search.elapsedMs);
  if (!search.found || !search.predicate) return stopNotFound('butonul de căutare');
  logSelector(2, 'butonul de căutare', search.predicate, search.node);
  const searchClick = await executeCommand({ steps: [{ action: 'click', match: predicateToClickMatch(search.predicate), requirePackage: WHATSAPP_PACKAGE }] });
  if (!searchClick.success) return stopNotFound('butonul de căutare');

  // 3 — search field appears, type a 3-CHAR PREFIX of the name (B3): "Han" surfaces "Hannah"
  // without depending on WhatsApp's own fuzzy tolerance for the full string. Row matching below
  // (resolveResultPick → pickBestPhonetic: consonantSkeleton + boundedLevenshtein over every
  // visible row, diacritic-insensitive) still runs against the FULL spoken name.
  const field = await waitForNode(SEARCH_INPUT_ANCHOR, 3000, 100);
  logRecipeStep(3, 'câmpul de căutare', JSON.stringify(SEARCH_INPUT_ANCHOR), field.found, field.elapsedMs);
  if (!field.found) return stopNotFound('câmpul de căutare');
  logSelector(3, 'câmpul de căutare', field.predicate, field.node);
  // set_text targets whichever search-input id actually won (search_input or search_src_text).
  const inputIdMatch: CommandMatch = field.predicate?.viewIdContains
    ? { viewIdContains: field.predicate.viewIdContains }
    : { viewIdContains: 'search_input' };
  const prefix = name.trim().slice(0, 3) || name.trim();
  const typed = await executeCommand({ steps: [{ action: 'set_text', match: inputIdMatch, text: prefix, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE }] });
  if (!typed.success) return stopNotFound('câmpul de căutare');

  // 4 — poll a FRESH snapshot until the result rows are readable, then score every row phonetically
  // and take the best (B-fix2). Wider window + bigger per-poll cap than before, since the row name
  // can take a beat to populate via TYPE_WINDOW_CONTENT_CHANGED.
  const t4 = Date.now();
  let pick: ResultPick = { kind: 'pick', clickText: null };
  while (Date.now() - t4 < 8000) {
    const snap = await cappedSnapshot(900);
    pick = resolveResultPick(name, snap);
    if (pick.kind === 'pick' && pick.clickText) break;
    await new Promise((r) => setTimeout(r, 120));
  }
  const picked = pick.kind === 'pick' && !!pick.clickText;
  logRecipeStep(4, 'rezultatele căutării', 'search_results', picked, Date.now() - t4);
  if (!picked) {
    // No row scored above the floor after the full window — honest stop, never a blind tap.
    return stopNotFound(`contactul "${name}" în rezultatele căutării`);
  }

  // 5 — tap the row, by its ACTUAL on-screen name (from the phonetic pick), not the raw query.
  const rowText = (pick as { clickText: string }).clickText;
  const rowFlags = { wholeWord: false, excludeSearchUi: true, excludeAvatars: true, clickableAncestor: true } as const;
  const t5 = Date.now();
  const rowClick = await executeCommand({ steps: [{ action: 'click', match: { textContains: rowText, ...rowFlags }, timeoutMs: 3000, requirePackage: WHATSAPP_PACKAGE }] });
  logRecipeStep(5, `contactul "${rowText}"`, `text:${rowText}`, rowClick.success, Date.now() - t5);
  if (!rowClick.success) {
    logAudioDiag('EXEC_ERROR', `phase=contact_result status=${rowClick.status}`);
    return { outcome: 'opened_manual_action_required', error: `Nu am găsit contactul "${name}" în WhatsApp.` };
  }

  // 6 — confirm the chat opened (search field gone) + the compose field present
  const t6 = Date.now();
  const searchGone = await waitForNodeGone(SEARCH_INPUT_ANCHOR, 3000, 100);
  const entry = await waitForNode(CHAT_ENTRY_ANCHOR, 2500, 100);
  const chatOpen = searchGone || entry.found;
  logRecipeStep(6, 'conversație deschisă', JSON.stringify(CHAT_ENTRY_ANCHOR), chatOpen, Date.now() - t6);
  if (!chatOpen) return stopNotFound('conversația');

  // 7 — voice-call button (cascade: viewId → contentDescription → text; voice-specific only)
  const call = await waitForNode(VOICECALL_ANCHORS, 3000, 150);
  logRecipeStep(7, 'butonul de apel vocal', call.predicate ? JSON.stringify(call.predicate) : 'none', call.found, call.elapsedMs);
  if (!call.found || !call.predicate) return stopNotFound('butonul de apel vocal');
  logSelector(7, 'butonul de apel vocal', call.predicate, call.node);
  const callClick = await executeCommand({ steps: [{ action: 'click', match: predicateToClickMatch(call.predicate), requirePackage: WHATSAPP_PACKAGE }] });
  if (!callClick.success) return stopNotFound('butonul de apel vocal');

  // 8 — done. NO return_to_benson: the WhatsApp call screen stays foreground (Round B4).
  const foreground = getForegroundPackage();
  logAudioDiag('FOREGROUND_AFTER_ACTION', `pkg=${foreground ?? 'unknown'} expected=${WHATSAPP_PACKAGE}`);
  return { outcome: 'app_switch_observed', via: 'accessibility_command_executor' };
}

// ── WA-FIX-4 — DIRECT-CONTACT-DEEPLINK (PRIMARY route for a named WhatsApp call) ──────────────
// "sună pe Baby pe WhatsApp" → resolve Baby locally (address book) → exact number → open the
// exact conversation via whatsapp://send?phone= (native) → verify header → reuse the proven
// call-button + verify. No Chats list, no scroll, no search_bar_inner_layout. Falls back to the
// UI-search route (runWhatsAppCallNative) ONLY when local resolution is unavailable — never
// silently on a post-deep-link failure (that is reported, with the cause logged natively).
// Privacy: contacts are read on-device only, never sent anywhere; only the single resolved
// number for THIS call enters mission state; logs carry name + status + a 4-digit tail, never
// the address book. ROUND_WA_ROUTE_DIAG_REPORT.md → RECOMMENDATION = DIRECT_CONTACT_DEEPLINK.
const WA_DIRECT_CONTACT_DEEPLINK = true;

// International digits, no punctuation, no leading '+'. Matches whatsappExecutor.sanitizeForWaMe;
// additionally collapses a '00' international prefix to bare country-code digits.
function sanitizeWaPhone(raw: string): string {
  let d = raw.replace(/\D/g, '');
  if (d.startsWith('00')) d = d.slice(2);
  return d;
}

type DirectAttempt = { handled: true; result: ToolCallResult } | { handled: false };

async function tryDirectContactCall(name: string): Promise<DirectAttempt> {
  if (!WA_DIRECT_CONTACT_DEEPLINK) return { handled: false };
  logAudioDiag('WA_DIRECT_RESOLVE_START', `name=${JSON.stringify(name)}`);

  let perm: 'granted' | 'denied' | 'undetermined';
  try { perm = await getContactsPermissionState(); } catch { perm = 'undetermined'; }
  if (perm !== 'granted') {
    logAudioDiag('WA_DIRECT_FALLBACK', `reason=contacts_permission state=${perm}`);
    return { handled: false };
  }

  let contacts: TrustedContact[] = [];
  try { contacts = await loadDeviceContacts(); } catch (e) { devLog('loadDeviceContacts threw', e); }
  const res: ContactResolveResult = resolveAgainstList({ rawName: name, contacts, preferredChannel: 'whatsapp' });
  const count = res.candidates?.length ?? (res.contact ? 1 : 0);
  logAudioDiag('WA_DIRECT_RESOLVE_RESULT', `status=${res.status} count=${count}`);

  if (res.status === 'ambiguous') {
    // BENSON_STABILIZATION_1 — no enumeration. One short bounded prompt.
    return { handled: true, result: { outcome: 'opened_manual_action_required',
      error: 'Nu sunt sigur de nume. Spune numele din nou.' } };
  }
  if (res.status === 'not_found') {
    logAudioDiag('WA_DIRECT_FALLBACK', 'reason=contact_not_found');
    return { handled: false };
  }
  if (res.status === 'missing_phone') {
    logAudioDiag('WA_DIRECT_FALLBACK', 'reason=contact_phone_unavailable');
    return { handled: false };
  }

  const contact = res.contact!;
  const numbers = (contact.phoneNumbers ?? []).map(sanitizeWaPhone).filter((d) => d.length >= 6);
  if (numbers.length === 0) {
    logAudioDiag('WA_DIRECT_FALLBACK', 'reason=contact_phone_unavailable');
    return { handled: false };
  }
  if (new Set(numbers).size > 1) {
    return { handled: true, result: { outcome: 'opened_manual_action_required',
      error: `„${contact.displayName}” are mai multe numere. Spune-mi pe care să sun.` } };
  }

  const phone = numbers[0];
  logAudioDiag('WA_DIRECT_NUMBER_READY', `name=${JSON.stringify(contact.displayName)} tail=${phone.slice(-4)}`);

  try {
    const r = await runWhatsAppOpenConversationCall(phone, contact.displayName);
    logAudioDiag('WA_NATIVE_RESULT', `route=direct success=${r.success} step=${r.step} elapsedMs=${r.elapsedMs}`);
    if (r.success) {
      // ROUND_VERIFIED_IDENTITY_BRIDGE_1 — same rule as the write flow: only on a real, matched
      // header read; `name` is verbatim what the user said, never corrected.
      if (r.nameMatch && r.verifiedHeaderText) {
        recordVerifiedIdentity({
          stableContactId: contact.id,
          normalizedPhoneNumber: phone,
          verifiedDisplayName: r.verifiedHeaderText,
          spokenAlias: name,
          verificationSource: 'call_header',
        }).catch(() => {});
      }
      return { handled: true, result: { outcome: 'app_switch_observed', via: 'wa_direct_deeplink' } };
    }
    // The deep link ran; a later step failed. Report it — do NOT fall back to the UI-search route
    // (rule 8). The specific cause is already in the native log (WA_DIRECT_FAIL stage=…).
    const friendly =
      r.step === 'WHATSAPP_CONTACT_VERIFY_FAILED'
        ? `Am deschis WhatsApp dar nu am putut confirma că e conversația cu „${contact.displayName}”. Nu am sunat.`
      : r.step === 'WHATSAPP_DEEPLINK_FAILED'
        ? `Nu am reușit să deschid conversația cu „${contact.displayName}” în WhatsApp.`
      : r.step.startsWith('CALL')
        ? `Am deschis conversația cu „${contact.displayName}”, dar nu am reușit să pornesc apelul — pornește-l tu.`
      : `Nu am reușit să duc apelul la capăt în WhatsApp (pas: ${r.step}).`;
    return { handled: true, result: { outcome: 'opened_manual_action_required', error: friendly } };
  } catch (e) {
    devLog('runWhatsAppOpenConversationCall threw', e);
    return { handled: true, result: { outcome: 'opened_manual_action_required',
      error: 'Nu am reușit să duc apelul la capăt în WhatsApp.' } };
  }
}

// ── ROUND_WA_GOVERNANCE_WRITE_1 — DIRECT WhatsApp message write ───────────────────────────────
// Two phases around the confirmation gate:
//   prepareMessageDirect()      RESOLVE_CONTACT → OPEN_CHAT → VERIFY_CHAT → FIND_MESSAGE_INPUT →
//                               TYPE_MESSAGE → VERIFY_TYPED_TEXT → (stop, WAITING_CONFIRMATION)
//   confirmSendMessageDirect()  (after YES) SEND_ON_YES → VERIFY_OUTGOING_MESSAGE
// Contact resolution reuses the same local ContactResolver path as tryDirectContactCall (address
// book on-device only, phone tail only in logs, single resolved number into mission state).
// Explicit com.whatsapp. Native holds the idempotency state — SEND is pressed at most once per
// missionId. Revert: WA_WRITE_DIRECT = false → this path is inert (callers fall back to the
// existing sendMessage/sendMessageByName).
export const WA_WRITE_DIRECT = true;

type WriteAttempt =
  | { handled: true; result: ToolCallResult; displayName?: string }
  | { handled: false; reason: string };

async function resolveWaNumber(name: string): Promise<
  | { ok: true; phone: string; displayName: string }
  | { ok: false; result?: ToolCallResult; reason: string }
> {
  let perm: 'granted' | 'denied' | 'undetermined';
  try { perm = await getContactsPermissionState(); } catch { perm = 'undetermined'; }
  if (perm !== 'granted') return { ok: false, reason: 'contacts_permission' };

  let contacts: TrustedContact[] = [];
  try { contacts = await loadDeviceContacts(); } catch (e) { devLog('loadDeviceContacts threw', e); }
  const res: ContactResolveResult = resolveAgainstList({ rawName: name, contacts, preferredChannel: 'whatsapp' });
  const count = res.candidates?.length ?? (res.contact ? 1 : 0);
  logAudioDiag('WA_WRITE_CONTACT_RESOLVED', `status=${res.status} count=${count}`);
  // ROUND_CONTACT_DIAG — query, candidate names, selection + how confident. method: EXACT =
  // normalized displayName equals the query; NONE = a looser tier or no selection.
  logAudioDiag('CONTACT_RESOLVE_QUERY', `value=${JSON.stringify(name)} normalized=${JSON.stringify(res.normalizedQuery)}`);
  const candNames = (res.candidates ?? (res.contact ? [res.contact] : [])).map((c) => c.displayName);
  logAudioDiag('CONTACT_RESOLVE_CANDIDATES', `values=${JSON.stringify(candNames)}`);
  const fold = (s: string) => s.trim().toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
  const method = res.status === 'resolved' && res.contact && fold(res.contact.displayName) === fold(name)
    ? 'EXACT'
    : res.status === 'resolved' && res.contact && res.normalizedQuery === fold(res.contact.displayName)
      ? 'NORMALIZED'
      : 'NONE';
  logAudioDiag('CONTACT_RESOLVE_SELECTED', `value=${JSON.stringify(res.contact?.displayName ?? '')} status=${res.status} method=${method}`);

  if (res.status === 'ambiguous') {
    // BENSON_STABILIZATION_1 — no enumeration; one short bounded prompt, then wait for the user.
    return { ok: false, reason: 'ambiguous', result: { outcome: 'opened_manual_action_required',
      error: 'Nu sunt sigur de nume. Spune numele din nou.' } };
  }
  if (res.status === 'not_found') return { ok: false, reason: 'contact_not_found' };
  if (res.status === 'missing_phone') return { ok: false, reason: 'contact_phone_unavailable' };

  const contact = res.contact!;
  const numbers = (contact.phoneNumbers ?? []).map(sanitizeWaPhone).filter((d) => d.length >= 6);
  if (numbers.length === 0) return { ok: false, reason: 'contact_phone_unavailable' };
  if (new Set(numbers).size > 1) {
    return { ok: false, reason: 'multiple_numbers', result: { outcome: 'opened_manual_action_required',
      error: `„${contact.displayName}” are mai multe numere. Spune-mi pe care să-i scriu.` } };
  }
  return { ok: true, phone: numbers[0], displayName: contact.displayName };
}

function mapWriteFailure(step: string, displayName: string): string {
  switch (step) {
    case 'RESOLVE_CONTACT': return `Nu am putut pregăti mesajul pentru „${displayName}”.`;
    case 'OPEN_CHAT': return `Nu am reușit să deschid conversația cu „${displayName}” în WhatsApp.`;
    case 'VERIFY_CHAT': return `Am deschis WhatsApp dar nu am putut confirma că e conversația cu „${displayName}”. N-am scris nimic.`;
    case 'FIND_MESSAGE_INPUT': return `Am deschis conversația cu „${displayName}” dar nu am găsit câmpul de scriere.`;
    case 'TYPE_MESSAGE':
    case 'VERIFY_TYPED_TEXT': return `Am deschis conversația cu „${displayName}” dar nu am putut scrie mesajul în câmp. Scrie-l tu.`;
    case 'SEND_ON_YES': return `Mesajul e scris în conversația cu „${displayName}” dar nu am reușit să apăs trimite. Apasă-l tu.`;
    case 'VERIFY_OUTGOING_MESSAGE': return `Am apăsat trimite pentru „${displayName}” dar nu am putut confirma că mesajul a plecat. Verifică în WhatsApp.`;
    default: return `Nu am reușit să duc mesajul la capăt în WhatsApp (pas: ${step}).`;
  }
}

// PHASE A — resolve + open + verify + type + verify-typed. STOPS before send. Returns
// { handled:true, result } with result.via === 'wa_write_typed_verified' when the message is
// staged and verified in the input and is now awaiting an explicit YES.
export async function prepareMessageDirect(searchString: string, message: string, missionId: string): Promise<WriteAttempt> {
  if (!WA_WRITE_DIRECT) return { handled: false, reason: 'wa_write_direct_disabled' };
  const name = searchString.trim();
  const msg = (message ?? '').trim();
  logAudioDiag('WA_WRITE_START', `mission=${missionId} name=${JSON.stringify(name)} msgLen=${msg.length}`);
  if (!name) return { handled: true, result: { outcome: 'launch_failed', error: 'Nu mi-ai spus cui să scriu.' } };
  if (!msg) return { handled: true, result: { outcome: 'launch_failed', error: 'Nu mi-ai spus ce mesaj să scriu.' } };
  if (!missionId) return { handled: false, reason: 'missing_mission_id' };

  const guard = await ensureAccessibilityReady();
  if (!guard.ready) return { handled: true, result: { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR } };

  const resolved = await resolveWaNumber(name);
  if (!resolved.ok) {
    if (resolved.result) return { handled: true, result: resolved.result };
    logAudioDiag('WA_WRITE_FAIL', `stage=RESOLVE_CONTACT reason=${resolved.reason}`);
    return { handled: false, reason: resolved.reason };
  }
  logAudioDiag('WA_WRITE_NUMBER_READY', `name=${JSON.stringify(resolved.displayName)} tail=${resolved.phone.slice(-4)}`);

  try {
    // ROUND_WA_WRITE_MESSAGE_PAYLOAD — the EXACT text handed across the JS→native bridge as the
    // message to type. Must equal the user's body verbatim; never the contact / wake word / STT
    // partial. BENSON_STABILIZATION_1: by construction `msg` came through handleIncomingText,
    // which only receives USER_MIC-gated transcripts or typed submits.
    logAudioDiag('WA_MESSAGE_BODY_ORIGIN', `turnId=${missionId} origin=user_mic_or_typed len=${msg.length}`);
    logAudioDiag('WA_PAYLOAD_NATIVE_TYPE', `text=${JSON.stringify(msg)} contact=${JSON.stringify(resolved.displayName)}`);
    const r = await runWhatsAppOpenConversationType(resolved.phone, resolved.displayName, msg, missionId);
    logAudioDiag('WA_WRITE_RESULT', `phase=A success=${r.success} step=${r.step} elapsedMs=${r.elapsedMs}`);
    if (r.success) {
      // ROUND_VERIFIED_IDENTITY_BRIDGE_1 — save ONLY when native reports a real header read that
      // matched (r.nameMatch); `name` (line 1048) is exactly what the user said ("Hana"), never
      // corrected — that's the point of the store. Never called on failure/abort/ambiguity.
      if (r.nameMatch && r.verifiedHeaderText) {
        recordVerifiedIdentity({
          normalizedPhoneNumber: resolved.phone,
          verifiedDisplayName: r.verifiedHeaderText,
          spokenAlias: name,
          verificationSource: 'write_header',
        }).catch(() => {});
      }
      return { handled: true, displayName: resolved.displayName, result: { outcome: 'app_switch_observed', via: 'wa_write_typed_verified' } };
    }
    if (r.step === 'SERVICE') return { handled: true, displayName: resolved.displayName, result: { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR } };
    return { handled: true, displayName: resolved.displayName, result: { outcome: 'opened_manual_action_required', error: mapWriteFailure(r.step, resolved.displayName) } };
  } catch (e) {
    devLog('runWhatsAppOpenConversationType threw', e);
    return { handled: true, displayName: resolved.displayName, result: { outcome: 'opened_manual_action_required', error: mapWriteFailure('EXCEPTION', resolved.displayName) } };
  }
}

// PHASE B — call ONLY after an explicit YES. Presses Send once for missionId, verifies the
// outgoing message. Idempotent in native: a re-call after SEND_ATTEMPTED re-verifies, never
// re-presses.
export async function confirmSendMessageDirect(missionId: string, message: string, displayName = 'contact'): Promise<ToolCallResult> {
  if (!missionId) return { outcome: 'opened_manual_action_required', error: 'Nu mai am mesajul pregătit. Spune-mi din nou ce să scriu.' };
  const state = getWhatsAppWriteState();
  logAudioDiag('WA_WRITE_SEND_ATTEMPT', `mission=${missionId} state=${JSON.stringify(state)}`);
  try {
    const r = await pressWhatsAppSendVerified(missionId, (message ?? '').trim());
    logAudioDiag('WA_WRITE_RESULT', `phase=B success=${r.success} step=${r.step} elapsedMs=${r.elapsedMs}`);
    if (r.success) return { outcome: 'app_switch_observed', via: 'wa_write_sent_verified' };
    if (r.step === 'SERVICE') return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
    return { outcome: 'opened_manual_action_required', error: mapWriteFailure(r.step, displayName) };
  } catch (e) {
    devLog('pressWhatsAppSendVerified threw', e);
    return { outcome: 'opened_manual_action_required', error: mapWriteFailure('SEND_ON_YES', displayName) };
  }
}

export async function placeCall(searchString: string, uiLang: string = DEFAULT_WHATSAPP_UI_LANG): Promise<ToolCallResult> {
  const name = searchString.trim();
  // WA-CONTACT-TRACE — the value handed to the native executor. `searchString` is exactly the
  // parsed transcript name after buildCallSearchString() clitic-strip in missionValidator (no
  // contact-list resolution, no fuzzy match, no fallback for placeCall). `name` is what
  // runWhatsAppCallNative receives and types into WhatsApp's own search. WhatsApp then does its
  // OWN fuzzy row match ("Hana" -> the "Hannah" row), logged natively as WA_NATIVE_CONTACT_MATCH.
  logAudioDiag('WA_CONTACT_INPUT', `stage=placeCall received=${JSON.stringify(searchString)} native=${JSON.stringify(name)}`);
  if (!name) return { outcome: 'launch_failed', error: 'Nu mi-ai spus pe cine să caut.' };

  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('placeCall: accessibility not ready, state=', guard.state, '- aborting without touching WhatsApp');
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }

  // WA-FIX-4 — PRIMARY: resolve the name locally and open the exact conversation directly. Only
  // when local resolution is unavailable (no permission / not found / no number) does this return
  // { handled: false } and control drops through to the UI-search route below.
  const direct = await tryDirectContactCall(name);
  if (direct.handled) return direct.result;
  logAudioDiag('WA_DIRECT_FALLBACK', 'reason=falling_back_to_ui_search');

  // WA-NATIVE-FINAL — one native call runs the whole sequence, background-proof. No JS
  // step-by-step executor, no enterPipMode (the native flow owns the foreground now).
  if (USE_NATIVE_CALL_RECIPE) {
    try {
      const r = await runWhatsAppCallNative(name);
      // ROUND_VERIFIED_IDENTITY_BRIDGE_1 — no phone/contactId known on this fallback (UI-search)
      // route by definition; still worth recording the verified display name <-> spoken alias so
      // resolvePerson()'s alias match works next time, even without a stable merge key.
      if (r.success && r.nameMatch && r.verifiedHeaderText) {
        recordVerifiedIdentity({
          verifiedDisplayName: r.verifiedHeaderText,
          spokenAlias: name,
          verificationSource: 'call_header',
        }).catch(() => {});
      }
      return mapNativeCallResult(r);
    } catch (e) {
      devLog('placeCall: native executor threw', e);
      return { outcome: 'opened_manual_action_required', error: 'Nu am reușit să duc apelul la capăt în WhatsApp.' };
    }
  }

  enterPipBeforeWhatsApp();
  setWhatsAppAutomationActive(true);
  try {
    return USE_LEGACY_CALL_RECIPE
      ? await runTwoPhase(name, uiLang, { kind: 'call' })
      : await runCallRecipe(name);
  } finally {
    setWhatsAppAutomationActive(false);
  }
}

// Search-by-name equivalent of openConversation() above — no phone number, no device contacts.
// Opens the chat and stops there (no message to send).
export async function openContactByName(searchString: string, uiLang: string = DEFAULT_WHATSAPP_UI_LANG): Promise<ToolCallResult> {
  const name = searchString.trim();
  if (!name) return { outcome: 'launch_failed', error: 'Nu mi-ai spus pe cine să caut.' };
  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('openContactByName: accessibility not ready, state=', guard.state);
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }
  enterPipBeforeWhatsApp();
  setWhatsAppAutomationActive(true);
  try {
    return await runTwoPhase(name, uiLang, { kind: 'open' });
  } finally {
    setWhatsAppAutomationActive(false);
  }
}

// ROUND_WA_GOVERNANCE_ROUTING — `sendMessageByName` REMOVED (see the note where `sendMessage`
// was). Message writing goes exclusively through prepareMessageDirect + confirmSendMessageDirect.

// endCall/muteCall: control an ALREADY-active WhatsApp call (placed via placeCall or manually).
// Same native, non-JS-timer execution as placeCall, and the same honest-failure contract — never
// claims the call was ended/muted unless the native side actually found and tapped the button.
export async function endCall(): Promise<ToolCallResult> {
  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('endCall: accessibility not ready, state=', guard.state, '- aborting without touching WhatsApp');
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }
  enterPipBeforeWhatsApp();
  const result = await endWhatsAppCall();
  devLog('endCall (native):', result);
  if (result.success) return { outcome: 'app_switch_observed', via: 'accessibility_native_state_machine' };
  return { outcome: 'opened_manual_action_required', error: result.error ?? `Stopped at step: ${result.step}` };
}

export async function muteCall(): Promise<ToolCallResult> {
  const guard = await ensureAccessibilityReady();
  if (!guard.ready) {
    devLog('muteCall: accessibility not ready, state=', guard.state, '- aborting without touching WhatsApp');
    return { outcome: 'launch_failed', error: ACCESSIBILITY_DISCONNECTED_ERROR };
  }
  enterPipBeforeWhatsApp();
  const result = await muteWhatsAppCall();
  devLog('muteCall (native):', result);
  if (result.success) return { outcome: 'app_switch_observed', via: 'accessibility_native_state_machine' };
  return { outcome: 'opened_manual_action_required', error: result.error ?? `Stopped at step: ${result.step}` };
}
