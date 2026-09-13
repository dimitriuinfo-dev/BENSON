// ROUND_YOUTUBE_GOVERNANCE_1 / ROUND_YOUTUBE_GOVERNANCE_2 — YouTube search-and-play governance.
//
// Pure mechanics only (open -> find search -> activate -> type -> verify -> submit -> observe ->
// extract -> [select -> verify playback]). No conversation state here — pending selection /
// candidate memory lives in missionOrchestrator.ts, same separation every other executor already
// follows (executors don't know about "what was asked last turn").
//
// Reuses the EXISTING generic step-DSL (executeCommand, benson-accessibility) end to end — no new
// hardcoded WhatsApp-style native flow. extract_list / ime_action (added this round to
// BensonCommandExecutor.kt) are themselves generic, reusable primitives, not YouTube-specific.

import { executeCommand, getScreenSnapshot } from 'benson-accessibility';
import { logAudioDiag } from 'benson-foreground-service';
import { isPackageInstalled, launchPackage, waitForPackageForeground } from '../core/action-engine/androidActionExecutor';

const LOG_TAG = '[YouTubeExecutor]';
function devLog(...args: unknown[]): void {
  console.log(LOG_TAG, ...args);
}

export const YOUTUBE_PACKAGE = 'com.google.android.youtube';

export interface YtCandidate {
  title: string;
  top: number;
}

export interface YtSearchOutcome {
  ok: boolean;
  message: string;
  candidates?: YtCandidate[];
}

export interface YtSelectOutcome {
  ok: boolean;
  message: string;
}

type CmdResult = { success?: boolean; status?: string; detail?: string | null; itemsJson?: string };

async function safeExecuteCommand(command: unknown): Promise<CmdResult> {
  try {
    const r = (await executeCommand(command as any)) as CmdResult;
    return r ?? { success: false };
  } catch (e) {
    return { success: false, status: 'invalid', detail: e instanceof Error ? e.message : String(e) };
  }
}

// CONFIRMED LIVE (2026-09-12) — a plain JS `setTimeout` goes inert the instant BENSON's own
// Activity backgrounds (which it does as soon as YouTube reaches the foreground): a real device
// test froze forever right after YT_FOREGROUND_OK, at the very next line, which used to be
// `await new Promise((res) => setTimeout(res, 700))`. This is the exact "JS runtime suspended
// while backgrounded" root cause behind the wake-engine/bubble-timer work earlier in this project
// — same fix here: every "let the UI settle" delay in this file runs as a native `wait` step
// (BensonCommandExecutor's `delay()` runs inside the accessibility service's own coroutine, not
// the JS timer subsystem, so it is immune to this).
async function nativeWait(ms: number): Promise<void> {
  await safeExecuteCommand({ steps: [{ action: 'wait', ms }] });
}

// Multi-language semantic labels for the search icon, mirroring the existing German-first idiom
// used elsewhere in the accessibility layer for this device (system language German; app UI
// language varies by Google account).
const SEARCH_ICON_MATCH = {
  textContainsAny: ['search', 'căutare', 'cautare', 'suche', 'suchen'],
  clickable: true,
  maxTopPercent: 20,
};

// CONFIRMED LIVE (2026-09-12) — a real run logged YT_QUERY_TYPED then YT_INPUT_VERIFY_FAIL only
// 18ms later, yet a screenshot taken right after showed "INNA" correctly typed in the field: a
// genuine race between set_text's ACTION_SET_TEXT and the accessibility tree actually reflecting
// it, not a logic error. One short native settle wait plus one retry (same idiom
// BensonCommandExecutor's own poll loops already use) fixes it without weakening the check itself.
async function verifyQueryTypedOnce(query: string): Promise<boolean> {
  try {
    const json = await getScreenSnapshot();
    const snap = JSON.parse(json) as { nodes?: Array<{ editable?: boolean; text?: string }> };
    const q = query.trim().toLowerCase();
    if (!q) return false;
    return (snap.nodes ?? []).some((n) => !!n.editable && (n.text ?? '').toLowerCase().includes(q));
  } catch (e) {
    devLog('verifyQueryTyped threw', e);
    return false;
  }
}

async function verifyQueryTyped(query: string): Promise<boolean> {
  await nativeWait(250);
  if (await verifyQueryTypedOnce(query)) return true;
  await nativeWait(300);
  return verifyQueryTypedOnce(query);
}

// Chrome/noise labels that show up as text/contentDescription nodes below the search bar but are
// never a result title — filtered here (in JS, app-specific) so the native extract_list step stays
// completely generic. Never invents a title; only removes known non-title UI chrome.
//
// CONFIRMED LIVE (2026-09-12) — a real "caută INNA pe YouTube" run surfaced a top channel-card
// block (this device's YouTube UI renders in German) ahead of the actual per-video results, and
// its chrome leaked through as fake "candidates": "Zum Kanal", "@INNA @INNA", "Offizieller
// Künstlerkanal INNA", and a metadata line "8,39 Millionen Abonnenten • 1356 Videos" — none of
// them a video title. English/Romanian-only noise words weren't enough. Fixed with signals that
// generalize across languages instead of a longer per-language word list alone: a bullet
// separator ("•") or an "@handle" token is essentially never part of a real video title.
const CHROME_NOISE = [
  'home', 'shorts', 'subscriptions', 'library', 'notifications', 'search', 'cast', 'account',
  'more videos', 'more options', 'options', 'filters', 'filter',
  'acasă', 'abonamente', 'bibliotecă', 'notificări', 'notificari', 'cont', 'distribuie', 'filtre', 'mai multe',
  // German — this device's confirmed live YouTube UI language.
  'abonnieren', 'abonnent', 'zum kanal', 'kanal aufrufen', 'künstlerkanal', 'startseite',
  'mein youtube', 'abos', 'weitere informationen', 'benachrichtigungen',
  // CONFIRMED LIVE (2026-09-12), second retest — a promo banner ("YouTube Music") and the
  // per-result "⋮" overflow button (German: "Aktionsmenü" = "action menu") also leaked through.
  'youtube music', 'aktionsmenü', 'action menu', 'overflow menu',
  // CONFIRMED LIVE (2026-09-12), third retest — a results-list SECTION HEADER ("Neueste Videos
  // von INNA" = "Latest videos from INNA") is not a selectable video either.
  'neueste videos', 'latest videos', 'videos noi', 'top videos',
];

// A node's label is built from `text + " " + contentDescription` (BensonCommandExecutor.kt);
// when both properties hold the identical string, that produces a literal doubled label
// ("Mix Mix", "YouTube Music YouTube Music") — collapse an exact repeated half before any other
// check runs, since a real video title is never its own exact duplicate joined by one space.
function collapseDuplicatedHalf(label: string): string {
  const m = /^(.+?)\s+\1$/i.exec(label.trim());
  return m ? m[1] : label;
}

function isChromeNoise(label: string, query: string): boolean {
  const low = label.trim().toLowerCase();
  if (!low) return true;
  // A candidate identical to the raw search query is far more likely to be the channel-card
  // name/handle than a genuine per-video title.
  if (low === query.trim().toLowerCase()) return true;
  if (low.includes('•') || low.includes('@')) return true;
  if (CHROME_NOISE.some((n) => low.includes(n))) return true;
  // view-count / metadata lines ("1,2 mil vizualizări", "3 days ago", "120K views", "1356 Videos")
  if (/^\d[\d.,]*\s*(k|m|mil|mii|mio\.?|tsd\.?)?\s*(views|vizualiz|urm[ăa]ritori|subscribers|abonat|abonnent)/i.test(low)) return true;
  if (/^\d+\s*(videos?|clipuri)$/i.test(low)) return true;
  return false;
}

async function extractYouTubeCandidates(query: string): Promise<YtCandidate[]> {
  const r = await safeExecuteCommand({
    steps: [{ action: 'extract_list', match: { minTopPercent: 15, withinScrollable: true }, limit: 15 }],
  });
  if (!r.success) return [];
  let raw: Array<{ label?: string; top?: number }> = [];
  try {
    raw = JSON.parse(r.itemsJson || '[]');
  } catch {
    raw = [];
  }
  const out: YtCandidate[] = [];
  const seen = new Set<string>();
  for (const item of raw) {
    const label = collapseDuplicatedHalf((item.label ?? '').trim());
    if (label.length < 4 || label.length > 90) continue;
    if (isChromeNoise(label, query)) continue;
    const key = label.toLowerCase();
    if (seen.has(key)) continue; // the same header/label can appear on more than one node
    seen.add(key);
    out.push({ title: label, top: item.top ?? 0 });
    if (out.length >= 5) break;
  }
  return out;
}

// OPEN_YOUTUBE -> FIND_SEARCH -> ACTIVATE_SEARCH -> TYPE_QUERY -> SUBMIT_SEARCH -> VERIFY_RESULTS
// -> OBSERVE_RESULTS -> EXTRACT_CANDIDATES. Returns candidates for the caller to present/ask about
// — never auto-plays, never claims success on "handled=true"/app-opened alone.
export async function searchYouTube(query: string): Promise<YtSearchOutcome> {
  logAudioDiag('YT_GOV_START', `query=${JSON.stringify(query)}`);

  if (!isPackageInstalled(YOUTUBE_PACKAGE)) {
    logAudioDiag('YT_OPEN_FAIL', 'reason=not_installed');
    return { ok: false, message: 'YouTube nu este instalat pe telefon.' };
  }

  const launch = launchPackage(YOUTUBE_PACKAGE, 'YouTubeExecutor:searchYouTube');
  if (!launch.success) {
    logAudioDiag('YT_OPEN_FAIL', `reason=${JSON.stringify(launch.error ?? 'LAUNCH_FAILED')}`);
    return { ok: false, message: 'Nu am putut deschide YouTube.' };
  }

  const fg = await waitForPackageForeground(YOUTUBE_PACKAGE, 4000);
  if (!fg.reached) {
    logAudioDiag('YT_FOREGROUND_VERIFY_FAIL', `observed=${JSON.stringify(fg.lastSeen ?? '')}`);
    return { ok: false, message: 'Am deschis YouTube, dar nu pot confirma că a ajuns în prim-plan.' };
  }
  logAudioDiag('YT_FOREGROUND_OK', '');

  // Let YouTube's own UI finish laying out before probing for the search icon.
  await nativeWait(700);

  const clickSearch = await safeExecuteCommand({ steps: [{ action: 'click', match: SEARCH_ICON_MATCH, timeoutMs: 5000 }] });
  if (!clickSearch.success) {
    const stage = clickSearch.status === 'not_found' ? 'YT_SEARCH_CONTROL_NOT_FOUND' : 'YT_SEARCH_CLICK_FAIL';
    logAudioDiag(stage, `status=${clickSearch.status} detail=${JSON.stringify(clickSearch.detail ?? '')}`);
    return { ok: false, message: 'Nu am găsit butonul de căutare în YouTube.' };
  }
  logAudioDiag('YT_SEARCH_FOUND', '');
  logAudioDiag('YT_SEARCH_CLICKED', '');

  const inputPresent = await safeExecuteCommand({ steps: [{ action: 'assert_present', match: { editable: true }, timeoutMs: 3000 }] });
  if (!inputPresent.success) {
    logAudioDiag('YT_INPUT_NOT_FOUND', `status=${inputPresent.status}`);
    return { ok: false, message: 'Am deschis căutarea, dar nu găsesc câmpul de scris.' };
  }
  logAudioDiag('YT_INPUT_FOUND', '');

  const typed = await safeExecuteCommand({ steps: [{ action: 'set_text', match: { editable: true }, text: query, timeoutMs: 3000 }] });
  if (!typed.success) {
    logAudioDiag('YT_TYPE_FAIL', `status=${typed.status}`);
    return { ok: false, message: `N-am putut scrie „${query}" în căutare.` };
  }
  logAudioDiag('YT_QUERY_TYPED', `query=${JSON.stringify(query)}`);

  const verified = await verifyQueryTyped(query);
  if (!verified) {
    logAudioDiag('YT_INPUT_VERIFY_FAIL', `query=${JSON.stringify(query)}`);
    return { ok: false, message: 'Am scris căutarea, dar nu pot confirma textul exact.' };
  }
  logAudioDiag('YT_QUERY_VERIFIED', '');

  let submitted = (await safeExecuteCommand({ steps: [{ action: 'ime_action' }] })).success === true;
  if (!submitted) {
    submitted = (await safeExecuteCommand({
      steps: [{ action: 'click', match: { textContainsAny: ['search', 'căutare', 'suche'], clickable: true }, timeoutMs: 2000 }],
    })).success === true;
  }
  if (!submitted) {
    logAudioDiag('YT_SUBMIT_FAIL', '');
    return { ok: false, message: `Am scris „${query}", dar nu am putut trimite căutarea.` };
  }
  logAudioDiag('YT_SEARCH_SUBMITTED', '');

  // Let the results list load/render.
  await nativeWait(1300);

  const candidates = await extractYouTubeCandidates(query);
  if (candidates.length === 0) {
    logAudioDiag('YT_RESULT_VERIFY_FAIL', 'reason=no_candidates_extracted');
    return { ok: false, message: `Am căutat „${query}", dar nu văd rezultate pe ecran.` };
  }
  logAudioDiag('YT_RESULTS_VERIFIED', `count=${candidates.length}`);
  logAudioDiag('YT_GOV_DONE', `query=${JSON.stringify(query)} candidates=${candidates.length}`);

  return { ok: true, message: 'Gata.', candidates };
}

// Taps the previously-extracted candidate (re-resolved fresh on the live tree, by its own exact
// label text — never a stale node reference) and verifies a playback signal before reporting
// success. `title` MUST be one of the labels searchYouTube() already returned this session.
export async function selectYouTubeCandidate(title: string): Promise<YtSelectOutcome> {
  logAudioDiag('YT_GOV_START', `stage=select title=${JSON.stringify(title)}`);

  // CONFIRMED LIVE (2026-09-12) — a real run showed the click landing on the WRONG tree: BENSON's
  // own screen, not YouTube's, because something (in that test, the manual-text-input reply itself
  // needing BENSON's Activity visible) had brought BENSON back to the foreground between the
  // search and the selection turn. A stray notification/overlay could do the same in normal use.
  // Reassert YouTube's foreground first — re-launching an already-running app just resumes its
  // existing task (the search results screen), it does not reset to the home feed.
  const alreadyYouTube = (await safeExecuteCommand({
    steps: [{ action: 'assert_package', package: YOUTUBE_PACKAGE, timeoutMs: 1200 }],
  })).success === true;
  if (!alreadyYouTube) {
    launchPackage(YOUTUBE_PACKAGE, 'YouTubeExecutor:selectYouTubeCandidate');
    const fg = await waitForPackageForeground(YOUTUBE_PACKAGE, 3000);
    if (!fg.reached) {
      logAudioDiag('YT_FOREGROUND_VERIFY_FAIL', `stage=select observed=${JSON.stringify(fg.lastSeen ?? '')}`);
      return { ok: false, message: 'YouTube nu mai e în prim-plan și nu am putut reveni la el.' };
    }
    await nativeWait(400);
  }

  const click = await safeExecuteCommand({
    steps: [{ action: 'click', match: { textContains: title, clickable: true, clickableAncestor: true }, timeoutMs: 4000 }],
  });
  if (!click.success) {
    logAudioDiag('YT_SEARCH_CLICK_FAIL', `title=${JSON.stringify(title)} status=${click.status}`);
    return { ok: false, message: `N-am putut selecta „${title}".` };
  }

  // Let the player screen open, then check for a pause control right away — CONFIRMED LIVE
  // (2026-09-12): a real click genuinely started playback (verified visually via screenshot), but
  // this check ran too late — YouTube's on-screen controls (including the pause button's label)
  // auto-hide a few seconds into playback, so a first attempt here can race that fade-out.
  await nativeWait(500);
  let playbackConfirmed = (await safeExecuteCommand({
    steps: [{ action: 'assert_present', match: { textContainsAny: ['pause', 'pauză', 'pauza'], clickable: true }, timeoutMs: 2500 }],
  })).success === true;
  if (!playbackConfirmed) {
    // Fallback signal: the video's scrub bar (SeekBar) is a structural element, not a label — it
    // does not fade away the way the play/pause icon's accessible name does.
    playbackConfirmed = (await safeExecuteCommand({
      steps: [{ action: 'assert_present', match: { classNameContains: 'SeekBar' }, timeoutMs: 2000 }],
    })).success === true;
  }
  if (!playbackConfirmed) {
    logAudioDiag('YT_RESULT_VERIFY_FAIL', `title=${JSON.stringify(title)} reason=no_playback_signal`);
    return { ok: false, message: `Am deschis „${title}", dar nu pot confirma că redă.` };
  }

  logAudioDiag('YT_GOV_DONE', `title=${JSON.stringify(title)} verified=true`);
  return { ok: true, message: `Redau „${title}".` };
}
