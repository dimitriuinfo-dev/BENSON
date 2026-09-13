// ROUND_MEDIA_GOVERNANCE_1 / ROUND_ENTERTAINMENT_GOVERNANCE_1 — generic, provider-parameterized
// media/content search. This generalizes youtubeExecutor.ts's proven OPEN->FIND_SEARCH->
// ACTIVATE->TYPE->VERIFY->SUBMIT->OBSERVE->EXTRACT pipeline for OTHER providers (Spotify,
// Netflix, Prime Video, ...), instead of rewriting the already-proven, already-tested YouTube path
// — per CLAUDE.md's "don't rewrite what works, add alongside" rule, "caută X pe YouTube" keeps
// going through youtubeExecutor.ts, untouched, exactly as ROUND_YOUTUBE_GOVERNANCE_1/2 left it.
//
// IMPORTANT — HONESTY NOTE (see ROUND_MEDIA_GOVERNANCE_1_REPORT.md): this file's search/select
// pipeline has been real-device-confirmed for YouTube (via youtubeExecutor.ts, a prior round) but
// NOT independently re-confirmed here per provider. Spotify was reachable on the test device;
// Netflix/Prime Video's actual on-screen selectors were NOT device-verified this round — the
// per-language noise list and semantic search-icon match are a reasonable generic starting point,
// not a proven one, for those two.

import { executeCommand, getScreenSnapshot } from 'benson-accessibility';
import { getPlaybackState } from 'benson-notification-listener';
import { logAudioDiag } from 'benson-foreground-service';
import { isPackageInstalled, launchPackage, waitForPackageForeground } from '../core/action-engine/androidActionExecutor';
// ROUND_SPOTIFY_SELECT_2 — reuses the proven, unmodified generic transport/verification layer
// (read-only import; mediaGovernor.ts itself has zero diff this round, per the round's explicit
// "do not modify the generic MediaSession transport layer" instruction).
import { verifyPlaying } from './mediaGovernor';

export interface MediaProvider {
  id: string;
  displayName: string;
  packageName: string;
  domain: 'video' | 'music' | 'movie';
  /** Multi-language contentDescription hints for this provider's search icon/entry point. */
  searchIconHints: string[];
  // CONFIRMED LIVE (2026-09-12) — Spotify's search screen shows a tap-through PLACEHOLDER
  // (a non-editable, non-focusable TextView) rather than a directly editable field; tapping it
  // activates a genuinely IME-focused input elsewhere in the tree (confirmed via `dumpsys
  // input_method`'s mServedView + a real cursor/keyboard in a screenshot) that does not itself
  // report isEditable/expose readable text the way a classic EditText does. This is data (a
  // per-provider UI-locator hint list, same category as searchIconHints), not a phrase/intent
  // script — the round's own "smallest provider-specific adapter detail" allowance. Providers
  // whose search field is directly editable (YouTube) simply omit this.
  searchActivationHints?: string[];
  // ROUND_SPOTIFY_SELECT_2 — CONFIRMED LIVE: a result row's title TextView and its trailing
  // Add/Save button both matched a plain {textContains: title} search — the button's OWN
  // contentDescription ("<title> wurde zu Meine Bibliothek hinzugefügt") contains the title as a
  // substring. Constraining the match to the title node's own (real, observed) resource-id
  // resolves the ambiguity deterministically instead of guessing a screen coordinate. Optional:
  // providers without this collision (YouTube) simply omit it.
  resultTitleViewId?: string;
  // ROUND_SPOTIFY_SELECT_2 — CONFIRMED LIVE: opening a playlist/album result does NOT auto-play
  // it — it opens a detail screen with its own semantic Play control ("Playlist wiedergeben").
  // Multi-language hints, same category/idiom as searchIconHints — not a phrase/intent script.
  playControlHints?: string[];
}

// Only providers actually installed on the test device were exercised live (YouTube, Spotify —
// see the report). Netflix/Prime Video are registered so the architecture is provider-agnostic
// (per the round's explicit requirement) but are IMPLEMENTED_ONLY, not device-verified.
export const MEDIA_PROVIDERS: MediaProvider[] = [
  {
    id: 'youtube', displayName: 'YouTube', packageName: 'com.google.android.youtube', domain: 'video',
    searchIconHints: ['search', 'căutare', 'cautare', 'suche', 'suchen'],
  },
  {
    id: 'spotify', displayName: 'Spotify', packageName: 'com.spotify.music', domain: 'music',
    searchIconHints: ['search', 'căutare', 'cautare', 'suche', 'suchen'],
    searchActivationHints: ['möchtest du hören', 'what do you want to listen', 'ce vrei să asculți'],
    resultTitleViewId: 'com.spotify.music:id/title',
    // CONFIRMED LIVE (2026-09-12) — bare "play"/"reda" are unsafe substring hints here: German
    // "Playlist" (Play-list) and its own action-button labels ("Playlist zu Bibliothek
    // hinzufügen") ALL contain "play" as a literal substring — a real match collision, not a
    // hypothetical one. Kept to longer, more specific words that don't collide with "Playlist".
    playControlHints: ['wiedergeben', 'abspielen', 'redare'],
  },
  {
    id: 'netflix', displayName: 'Netflix', packageName: 'com.netflix.mediaclient', domain: 'movie',
    searchIconHints: ['search', 'căutare', 'cautare', 'suche', 'suchen'],
  },
  {
    id: 'prime', displayName: 'Prime Video', packageName: 'com.amazon.avod.thirdpartyclient', domain: 'movie',
    searchIconHints: ['search', 'căutare', 'cautare', 'suche', 'suchen'],
  },
];

export function findProviderByMention(text: string): MediaProvider | null {
  const t = text.toLowerCase();
  if (/\bspotify\b/.test(t)) return MEDIA_PROVIDERS.find((p) => p.id === 'spotify') ?? null;
  if (/\bnetflix\b/.test(t)) return MEDIA_PROVIDERS.find((p) => p.id === 'netflix') ?? null;
  if (/\bprime\s*video\b|\bamazon\s*prime\b/.test(t)) return MEDIA_PROVIDERS.find((p) => p.id === 'prime') ?? null;
  if (/\byoutube\b/.test(t)) return MEDIA_PROVIDERS.find((p) => p.id === 'youtube') ?? null;
  return null;
}

export function installedProviders(): MediaProvider[] {
  return MEDIA_PROVIDERS.filter((p) => isPackageInstalled(p.packageName));
}

export interface MediaCandidate {
  title: string;
  top: number;
  providerId: string;
}

export interface MediaSearchOutcome {
  ok: boolean;
  message: string;
  candidates?: MediaCandidate[];
}

async function nativeWait(ms: number): Promise<void> {
  await safeExecuteCommand({ steps: [{ action: 'wait', ms }] });
}

type CmdResult = { success?: boolean; status?: string; detail?: string | null; itemsJson?: string };

async function safeExecuteCommand(command: unknown): Promise<CmdResult> {
  try {
    return ((await executeCommand(command as any)) as CmdResult) ?? { success: false };
  } catch (e) {
    return { success: false, status: 'invalid', detail: e instanceof Error ? e.message : String(e) };
  }
}

async function verifyQueryTypedOnce(query: string): Promise<boolean> {
  try {
    const json = await getScreenSnapshot();
    const snap = JSON.parse(json) as { nodes?: Array<{ editable?: boolean; text?: string }> };
    const q = query.trim().toLowerCase();
    if (!q) return false;
    return (snap.nodes ?? []).some((n) => !!n.editable && (n.text ?? '').toLowerCase().includes(q));
  } catch {
    return false;
  }
}

async function verifyQueryTyped(query: string): Promise<boolean> {
  await nativeWait(250);
  if (await verifyQueryTypedOnce(query)) return true;
  await nativeWait(300);
  return verifyQueryTypedOnce(query);
}

// Same generic chrome-noise idiom proven live for YouTube (ROUND_YOUTUBE_GOVERNANCE_1/2's
// report §6.3/6.4) — a starting baseline, not independently re-validated per provider this round.
const CHROME_NOISE = [
  'home', 'shorts', 'subscriptions', 'library', 'notifications', 'search', 'cast', 'account',
  'more videos', 'more options', 'options', 'filters', 'filter', 'settings', 'profile',
  'acasă', 'abonamente', 'bibliotecă', 'notificări', 'notificari', 'cont', 'distribuie', 'filtre', 'mai multe',
  'abonnieren', 'abonnent', 'zum kanal', 'kanal aufrufen', 'künstlerkanal', 'startseite',
  'mein youtube', 'abos', 'weitere informationen', 'benachrichtigungen',
  'youtube music', 'aktionsmenü', 'action menu', 'overflow menu',
  'neueste videos', 'latest videos', 'videos noi', 'top videos',
  // CONFIRMED LIVE (2026-09-12) — Spotify's live-typeahead autocomplete SUGGESTION rows ("Add
  // suggestion 'inna hot'" / "Vorschlag „inna hot" hinzufügen") are not selectable playable
  // results — they refine the search text, they don't open anything.
  'vorschlag', 'hinzufügen', 'add suggestion', 'sugestie',
  // CONFIRMED LIVE (2026-09-12) — an already-saved result's Add/Save button's own
  // contentDescription ("<title> wurde zu Meine Bibliothek hinzugefügt" = "<title> was added to
  // My Library") leaked into extract_list's candidate enumeration (a separate code path from the
  // click-target fix — extract_list reads labels generically, it does not know about
  // resultTitleViewId). "hinzugefügt" (added) reliably marks a confirmation/action label, not a title.
  'hinzugefügt', 'bibliothek', 'added to your library', 'adăugat în bibliotecă',
];

// CONFIRMED LIVE (2026-09-12) — Spotify result-card metadata/category labels appear as their OWN
// separate node next to a real title ("Best of INNA" / "Playlist"), not embedded in it. EXACT
// match only (not substring) — unlike CHROME_NOISE's words, several of these ("Single", "Album")
// are also plausible whole/partial real titles ("Single Ladies"), so only reject a candidate that
// is the category label and NOTHING else.
const EXACT_LABEL_NOISE = new Set([
  'playlist', 'verifiziert', 'verified', 'künstler*in', 'künstlerin', 'künstler', 'artist',
  'album', 'single', 'podcast', 'episode', 'folge', 'song',
]);

// CONFIRMED LIVE (2026-09-12) — Spotify's autocomplete suggestion echoes are the literal typed
// query in all-lowercase ("inna hot", "inna deja vu"), unlike real result titles which use normal
// capitalization ("Best of INNA", "Hot", "INNA Radio"). A candidate that is nothing but lowercase
// letters/spaces and starts with the query itself is almost certainly an echoed suggestion, not a
// title — real song/artist/movie titles are essentially never written that way.
function looksLikeLowercaseEcho(label: string, query: string): boolean {
  const q = query.trim().toLowerCase();
  if (!q) return false;
  const low = label.trim();
  if (low !== low.toLowerCase()) return false; // has an uppercase letter somewhere -> not an echo
  return low.startsWith(q);
}

function collapseDuplicatedHalf(label: string): string {
  const m = /^(.+?)\s+\1$/i.exec(label.trim());
  return m ? m[1] : label;
}

function isChromeNoise(label: string, query: string): boolean {
  const low = label.trim().toLowerCase();
  if (!low) return true;
  if (EXACT_LABEL_NOISE.has(low)) return true;
  if (low === query.trim().toLowerCase()) return true;
  if (low.includes('•') || low.includes('@')) return true;
  if (CHROME_NOISE.some((n) => low.includes(n))) return true;
  if (looksLikeLowercaseEcho(label, query)) return true;
  if (/^\d[\d.,]*\s*(k|m|mil|mii|mio\.?|tsd\.?)?\s*(views|vizualiz|urm[ăa]ritori|subscribers|abonat|abonnent)/i.test(low)) return true;
  if (/^\d+\s*(videos?|clipuri|songs?|melodii)$/i.test(low)) return true;
  return false;
}

async function extractCandidates(query: string, providerId: string): Promise<MediaCandidate[]> {
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
  const out: MediaCandidate[] = [];
  const seen = new Set<string>();
  for (const item of raw) {
    const label = collapseDuplicatedHalf((item.label ?? '').trim());
    if (label.length < 2 || label.length > 90) continue;
    if (isChromeNoise(label, query)) continue;
    const key = label.toLowerCase();
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ title: label, top: item.top ?? 0, providerId });
    if (out.length >= 5) break;
  }
  return out;
}

// OPEN -> FIND_SEARCH -> ACTIVATE -> TYPE -> VERIFY -> SUBMIT -> OBSERVE -> EXTRACT, generic over
// `provider`. Mirrors youtubeExecutor.ts's searchYouTube() structure exactly (same proven shape),
// parameterized instead of hardcoded — see that file for the original, device-confirmed version.
export async function searchMedia(provider: MediaProvider, query: string): Promise<MediaSearchOutcome> {
  logAudioDiag('MEDIA_SEARCH_START', `provider=${provider.id} query=${JSON.stringify(query)}`);

  if (!isPackageInstalled(provider.packageName)) {
    logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=not_installed`);
    return { ok: false, message: `${provider.displayName} nu este instalată pe telefon.` };
  }

  const launch = launchPackage(provider.packageName, 'MediaSearchExecutor:searchMedia');
  if (!launch.success) {
    logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=${JSON.stringify(launch.error ?? 'LAUNCH_FAILED')}`);
    return { ok: false, message: `Nu am putut deschide ${provider.displayName}.` };
  }
  const fg = await waitForPackageForeground(provider.packageName, 4000);
  if (!fg.reached) {
    logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=foreground_verify_fail observed=${JSON.stringify(fg.lastSeen ?? '')}`);
    return { ok: false, message: `Am deschis ${provider.displayName}, dar nu pot confirma că a ajuns în prim-plan.` };
  }
  await nativeWait(700);

  // CONFIRMED LIVE (2026-09-12) — unlike YouTube's top-bar search icon, Spotify's search entry
  // point is a BOTTOM-nav tab ("Suchen"), and its label text lives on a non-clickable child node
  // (the tab's own clickable container carries no text of its own) — requiring `clickable:true`
  // in the MATCH filter rejected it before `clickableAncestor` ever got a chance to climb to the
  // real target. Matching on label alone + `clickableAncestor` (the same pattern already proven
  // for WhatsApp contact rows / YouTube result titles) generalizes across both tab-bar and
  // top-bar search entry points. No maxTopPercent either — a safe default for YouTube, not generic.
  const clickSearch = await safeExecuteCommand({
    steps: [{ action: 'click', match: { textContainsAny: provider.searchIconHints, clickableAncestor: true }, timeoutMs: 5000 }],
  });
  if (!clickSearch.success) {
    logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=search_control_not_found status=${clickSearch.status}`);
    return { ok: false, message: `Nu am găsit butonul de căutare în ${provider.displayName}.` };
  }

  // ROUND_SPOTIFY_GOVERNANCE_1 — CONFIRMED LIVE: Spotify's search box is a tap-through
  // PLACEHOLDER, not a directly editable field. If this provider declares activation hints, tap
  // that placeholder first (clickableAncestor — the placeholder text itself is not clickable) to
  // bring up the real, IME-focused input before trying to type anywhere.
  if (provider.searchActivationHints && provider.searchActivationHints.length > 0) {
    const activate = await safeExecuteCommand({
      steps: [{ action: 'click', match: { textContainsAny: provider.searchActivationHints, clickableAncestor: true }, timeoutMs: 3000 }],
    });
    if (!activate.success) {
      logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=search_activation_not_found status=${activate.status}`);
      return { ok: false, message: `Am deschis căutarea, dar nu am putut activa câmpul de scris în ${provider.displayName}.` };
    }
    await nativeWait(400);
  }

  const usesFocusInput = !!provider.searchActivationHints;
  if (!usesFocusInput) {
    const inputPresent = await safeExecuteCommand({ steps: [{ action: 'assert_present', match: { editable: true }, timeoutMs: 3000 }] });
    if (!inputPresent.success) {
      logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=input_not_found`);
      return { ok: false, message: `Am deschis căutarea, dar nu găsesc câmpul de scris în ${provider.displayName}.` };
    }
  }

  // ROUND_SPOTIFY_GOVERNANCE_1 — a provider whose activated field doesn't reliably report
  // {editable:true} in the tree (Spotify, confirmed live) types via Android's own input-focus
  // tracking instead of a tree-match; providers with a classic editable field (YouTube) keep the
  // proven match-based set_text, unchanged.
  const typed = usesFocusInput
    ? await safeExecuteCommand({ steps: [{ action: 'set_text_on_focus', text: query }] })
    : await safeExecuteCommand({ steps: [{ action: 'set_text', match: { editable: true }, text: query, timeoutMs: 3000 }] });
  if (!typed.success) {
    logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=type_fail`);
    return { ok: false, message: `N-am putut scrie „${query}" în căutare.` };
  }

  const verified = await verifyQueryTyped(query);
  if (!verified) {
    logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=input_verify_fail`);
    return { ok: false, message: 'Am scris căutarea, dar nu pot confirma textul exact.' };
  }

  // CONFIRMED LIVE (2026-09-12) — Spotify shows LIVE/incremental results as soon as the query is
  // typed; no explicit submit exists or is needed (both ime_action and the icon-click fallback
  // failed, yet a screenshot at that exact moment already showed real "INNA" results on screen).
  // Submit is therefore best-effort here, not a hard failure — if the provider needed it, the
  // subsequent "no candidates" check still catches a genuine failure; if it didn't, this no longer
  // reports failure for a component search flow that already succeeded.
  let submitted = (await safeExecuteCommand({ steps: [{ action: 'ime_action' }] })).success === true;
  if (!submitted) {
    submitted = (await safeExecuteCommand({
      steps: [{ action: 'click', match: { textContainsAny: provider.searchIconHints, clickable: true }, timeoutMs: 2000 }],
    })).success === true;
  }
  logAudioDiag('MEDIA_SEARCH_SUBMIT', `provider=${provider.id} submitted=${submitted}`);

  await nativeWait(1300);
  const candidates = await extractCandidates(query, provider.id);
  if (candidates.length === 0) {
    logAudioDiag('MEDIA_SEARCH_FAIL', `provider=${provider.id} reason=no_candidates`);
    return { ok: false, message: `Am căutat „${query}" pe ${provider.displayName}, dar nu văd rezultate pe ecran.` };
  }
  logAudioDiag('MEDIA_SEARCH_DONE', `provider=${provider.id} query=${JSON.stringify(query)} candidates=${candidates.length}`);
  return { ok: true, message: 'Gata.', candidates };
}

export interface MediaSelectOutcome {
  ok: boolean;
  message: string;
  /** ROUND_SPOTIFY_SELECT_2 — true only when playback was verified via MediaSession AND the
   *  session's metadata was found consistent with the selected content (see checkMetadataMatch). */
  metadataVerified?: boolean;
}

function readMeta(packageName?: string): { title?: string | null; artist?: string | null } {
  try {
    return JSON.parse(getPlaybackState(packageName ?? ''));
  } catch {
    return {};
  }
}

// ROUND_SPOTIFY_SELECT_2 — CONFIRMED LIVE: selecting a playlist auto-plays its first TRACK, whose
// own title/artist will not literally equal the playlist's title ("Best of INNA" -> e.g. "Body and
// the Sun" / "INNA"). "Consistent with the selected content" is checked generically: the session's
// title or artist shares a real token (>=3 chars, diacritic-insensitive) with either the selected
// candidate's own title or the original search query — not an exact-string match, which would be
// wrong by construction for a playlist/album result.
function stripDiacriticsLocal(s: string): string {
  return (s || '').normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();
}
function tokens(s: string): string[] {
  return stripDiacriticsLocal(s).split(/[^a-z0-9]+/).filter((w) => w.length >= 3);
}
function checkMetadataMatch(meta: { title?: string | null; artist?: string | null }, title: string, query?: string): boolean {
  const haystack = tokens(`${meta.title ?? ''} ${meta.artist ?? ''}`);
  if (haystack.length === 0) return false;
  const needles = [...tokens(title), ...tokens(query ?? '')];
  return needles.some((n) => haystack.includes(n));
}

// Reasserts the provider's foreground (same fix ROUND_YOUTUBE_GOVERNANCE_2 needed live — a
// backgrounded provider means the click would run against whatever else is foreground), taps the
// exact previously-extracted label, then hands off to mediaGovernor.verifyPlaying() for the
// generic, provider-agnostic playback check instead of a per-provider label guess.
export async function selectMediaCandidate(provider: MediaProvider, title: string, query?: string): Promise<MediaSelectOutcome> {
  logAudioDiag('MEDIA_SELECT_START', `provider=${provider.id} title=${JSON.stringify(title)}`);

  const alreadyForeground = (await safeExecuteCommand({
    steps: [{ action: 'assert_package', package: provider.packageName, timeoutMs: 1200 }],
  })).success === true;
  if (!alreadyForeground) {
    launchPackage(provider.packageName, 'MediaSearchExecutor:selectMediaCandidate');
    const fg = await waitForPackageForeground(provider.packageName, 3000);
    if (!fg.reached) {
      logAudioDiag('MEDIA_SELECT_FAIL', `provider=${provider.id} reason=foreground_verify_fail`);
      return { ok: false, message: `${provider.displayName} nu mai e în prim-plan și nu am putut reveni la el.` };
    }
    await nativeWait(400);
  }

  // ROUND_SPOTIFY_SELECT_2 — CONFIRMED LIVE (real tree inspection, see the report): a plain
  // {textContains: title, clickable:true} match found the WRONG node — a trailing Add/Save
  // button whose own contentDescription ("<title> wurde zu Meine Bibliothek hinzugefügt")
  // contains the title as a substring, while the real title TextView (clickable=false, needing
  // clickableAncestor to climb to the actual result row) was filtered OUT by requiring
  // clickable:true on the match itself. Fix: never require clickable:true when clickableAncestor
  // is also set (that combination is self-defeating); when the provider knows the title node's
  // real resource-id (confirmed live, not guessed), constrain to it so the Add/Save button's
  // coincidental substring match can never win even if a label collision exists.
  const clickMatch: Record<string, unknown> = { textContains: title, clickableAncestor: true };
  if (provider.resultTitleViewId) clickMatch.viewIdContains = provider.resultTitleViewId;
  const click = await safeExecuteCommand({ steps: [{ action: 'click', match: clickMatch, timeoutMs: 4000 }] });
  if (!click.success) {
    logAudioDiag('MEDIA_SELECT_FAIL', `provider=${provider.id} title=${JSON.stringify(title)} reason=click_fail status=${click.status}`);
    return { ok: false, message: `N-am putut selecta „${title}".` };
  }

  // ROUND_SPOTIFY_SELECT_2 — CONFIRMED LIVE: opening a playlist/album result does not itself
  // start playback — it opens a detail screen with its own Play control. Verify first (covers a
  // provider/result where the click DOES start playback directly, e.g. a single track); only if
  // that fails, look for a semantic play control and tap it, then verify again. Never claims
  // success from the click alone.
  await nativeWait(900);
  let playing = await verifyPlaying(provider.packageName, 2000);
  if (!playing && provider.playControlHints && provider.playControlHints.length > 0) {
    // CONFIRMED LIVE (2026-09-12) — a first attempt at this click landed on an unrelated,
    // transient EditText near the very top of the screen (bounds y=0-90), not the real Play
    // control (which sits well below the header/app-bar — confirmed live at y~1216-1420 out of
    // 2414, i.e. ~50-59% down). Very likely a stale tree read caught mid-transition from the
    // previous (search) screen. `minTopPercent` excludes that header/app-bar band entirely — a
    // generic, position-based constraint (not a fixed coordinate); doClick()'s own existing
    // "final target must be clickable" check already guards against landing on a non-actionable
    // node regardless.
    const playClick = await safeExecuteCommand({
      steps: [{ action: 'click', match: { textContainsAny: provider.playControlHints, clickableAncestor: true, minTopPercent: 25 }, timeoutMs: 3000 }],
    });
    logAudioDiag('MEDIA_SELECT_PLAY_CONTROL', `provider=${provider.id} clicked=${playClick.success === true}`);
    if (playClick.success) {
      playing = await verifyPlaying(provider.packageName, 3000);
    }
  }
  if (!playing) {
    logAudioDiag('MEDIA_SELECT_FAIL', `provider=${provider.id} title=${JSON.stringify(title)} reason=playback_not_verified`);
    return { ok: false, message: `Am deschis „${title}", dar nu pot confirma că redă.` };
  }

  const meta = readMeta(provider.packageName);
  const metadataOk = checkMetadataMatch(meta, title, query);
  logAudioDiag(
    'MEDIA_SELECT_METADATA',
    `provider=${provider.id} title=${JSON.stringify(meta.title ?? '')} artist=${JSON.stringify(meta.artist ?? '')} consistent=${metadataOk}`,
  );
  if (!metadataOk) {
    // Playback genuinely started (verified above) but the session's own metadata could not be
    // tied back to what was selected — reported honestly as a distinct, weaker outcome rather
    // than silently upgraded to a full match.
    return {
      ok: true,
      metadataVerified: false,
      message: `Redau ceva pe ${provider.displayName}, dar nu pot confirma că e „${title}".`,
    };
  }

  logAudioDiag('MEDIA_SELECT_DONE', `provider=${provider.id} title=${JSON.stringify(title)} metadataTitle=${JSON.stringify(meta.title ?? '')}`);
  return { ok: true, metadataVerified: true, message: `Redau „${title}".` };
}
