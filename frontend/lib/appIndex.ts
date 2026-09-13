// BENSON App Index (2026-08-28) — the device's REAL launchable-app list as the primary source
// for resolving "open X" / "close X", replacing the closed curated allowlists that made BENSON
// say "nu găsesc aplicația" for anything not hand-listed.
//
// Source: benson-app-registry's getInstalledApps() (queryIntentActivities ACTION_MAIN +
// CATEGORY_LAUNCHER — the same set the home-screen app drawer shows, exempt from Android 11+
// package-visibility filtering, no <queries> entry needed). Cached in memory; refreshAppIndex()
// drops the cache so a later call re-reads (call it after a package install/uninstall).
//
// The curated registries (lib/appRegistry, src/core/action-engine/appRegistry) stay ONLY as an
// alias layer ON TOP of this index (e.g. "harta" -> Google Maps, deep-link schemes) — never as a
// closed list. A miss in the curated layer falls through to matchApps() here.
//
// Matching is case- AND diacritic-insensitive, token-based, over BOTH the display label AND the
// package name: "you tube" == "youtube" == "YouTube" == "com.google.android.youtube".

import { getInstalledApps } from 'benson-app-registry';
import { logAudioDiag } from 'benson-foreground-service';

export type IndexedApp = { packageName: string; appName: string };

let cache: IndexedApp[] | null = null;
let inFlight: Promise<IndexedApp[]> | null = null;

function stripDiacritics(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '');
}

/** lowercase, diacritics removed, every non-alphanumeric run collapsed to a single space. */
export function normalizeName(s: string): string {
  return stripDiacritics((s || '').toLowerCase()).replace(/[^a-z0-9]+/g, ' ').trim();
}

function nameTokens(s: string): string[] {
  return normalizeName(s).split(' ').filter(Boolean);
}

/** "com.google.android.youtube" -> ["com","google","android","youtube"] */
function pkgTokens(pkg: string): string[] {
  return (pkg || '').toLowerCase().split(/[^a-z0-9]+/).filter(Boolean);
}

// ── E2-fix / Fix 2 (2026-09-07, product-owner-directed): potrivire fonetică ────────────────────
// Un singur slip de vocală din STT ("Flexparkon" vs "FlexParken") pica la scor 0 cu regulile
// exacte/substring/startsWith de mai jos — nicio toleranță la greșeli. Adaug două semnale ieftine
// peste numele afișat ȘI ultimul segment de pachet: (1) scheletul de consoane (vocalele scoase —
// prinde exact slip-ul de vocală), (2) distanța de editare mărginită (prinde un slip de consoană
// / literă lipsă). Revert: PHONETIC_MATCH = false → exact comportamentul dinainte.
const PHONETIC_MATCH = true;

/** vocalele scoase: "flexparken" -> "flxprkn", "flexparkon" -> "flxprkn" (egale). */
function consonantSkeleton(s: string): string {
  return (s || '').replace(/[aeiou]/g, '');
}

/** Levenshtein mărginit — se oprește devreme dacă distanța depășește `max`. */
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
    const tmp = prev; prev = cur; cur = tmp;
  }
  return prev[n];
}

export async function loadAppIndex(force = false, reason: 'warm' | 'lazy' = 'lazy'): Promise<IndexedApp[]> {
  if (cache && cache.length && !force) {
    logAudioDiag('APP_INDEX', `count=${cache.length} source=cache elapsedMs=0`);
    return cache;
  }
  if (inFlight && !force) return inFlight;
  const startedAt = Date.now();
  inFlight = (async () => {
    try {
      const raw = await getInstalledApps();
      const list: IndexedApp[] = (Array.isArray(raw) ? raw : [])
        .map((a: { packageName?: unknown; appName?: unknown }) => ({
          packageName: String(a?.packageName ?? ''),
          appName: String(a?.appName ?? ''),
        }))
        .filter((a) => a.packageName && a.appName);
      cache = list;
      logAudioDiag('APP_INDEX', `count=${list.length} source=${reason} elapsedMs=${Date.now() - startedAt}`);
      return list;
    } catch (e) {
      logAudioDiag('APP_INDEX', `count=0 source=${reason} elapsedMs=${Date.now() - startedAt} error=${JSON.stringify(String(e)).slice(0, 120)}`);
      cache = cache ?? [];
      return cache;
    } finally {
      inFlight = null;
    }
  })();
  return inFlight;
}

/** Drop the cache; next loadAppIndex() re-reads from the device. */
export function refreshAppIndex(): void {
  cache = null;
}

export type AppMatch =
  | { kind: 'exact'; app: IndexedApp }
  | { kind: 'single'; app: IndexedApp } // one close-but-not-exact candidate -> ask "O deschid?"
  | { kind: 'multiple'; apps: IndexedApp[] } // 2..3 -> ask "pe care?"
  | { kind: 'none' };

// 0 = no signal. >=90 counts as an exact/near-exact hit that can open without asking.
function scoreApp(qTokens: string[], qJoined: string, app: IndexedApp): number {
  if (!qTokens.length) return 0;
  const nTok = nameTokens(app.appName);
  const nJoined = nTok.join('');
  const pTok = pkgTokens(app.packageName);
  const pLast = pTok[pTok.length - 1] ?? '';

  if (nJoined && nJoined === qJoined) return 100; // "you tube" -> "YouTube"
  if (nTok.length === qTokens.length && nTok.every((t, i) => t === qTokens[i])) return 98;
  if (pLast && pLast === qJoined) return 92; // query is exactly the last package segment
  if (nJoined && qJoined && nJoined.startsWith(qJoined) && qJoined.length >= 3) return 88;

  let score = 0;
  const nSet = new Set(nTok);
  if (qTokens.every((t) => nSet.has(t))) score = Math.max(score, 72 + qTokens.length); // all query words are whole name words
  if (nJoined && qJoined && (nJoined.includes(qJoined) || qJoined.includes(nJoined))) score = Math.max(score, 58);
  if (pLast && qJoined && (pLast.includes(qJoined) || qJoined.includes(pLast)) && qJoined.length >= 3) score = Math.max(score, 55);
  if (pTok.some((t) => t.length >= 3 && qTokens.includes(t))) score = Math.max(score, 44);
  const partial = qTokens.filter((qt) => nTok.some((nt) => nt.includes(qt) || qt.includes(nt))).length;
  if (partial) score = Math.max(score, 18 + partial * 6);

  // ── E2-fix / Fix 2: phonetic / typo tolerance on BOTH the display name and the last package
  // segment. Only for queries of real length so a 2-3 char query can't fuzzy-match everything.
  if (PHONETIC_MATCH && qJoined.length >= 5) {
    // (1) consonant skeleton — a pure vowel slip ("flexparkon" -> "flexparken") lands here.
    if (nJoined.length >= 5 && consonantSkeleton(nJoined) === consonantSkeleton(qJoined)) {
      score = Math.max(score, 82);
    } else if (pLast.length >= 5 && consonantSkeleton(pLast) === consonantSkeleton(qJoined)) {
      score = Math.max(score, 70);
    }
    // (2) bounded edit distance — 1 slip on shortish names, 2 on longer ones.
    const maxEdits = qJoined.length >= 9 ? 2 : 1;
    if (nJoined.length >= 5) {
      const d = boundedLevenshtein(qJoined, nJoined, maxEdits);
      if (d <= maxEdits) score = Math.max(score, d === 1 ? 80 : 66);
    }
    if (pLast.length >= 5) {
      const d = boundedLevenshtein(qJoined, pLast, maxEdits);
      if (d <= maxEdits) score = Math.max(score, d === 1 ? 68 : 56);
    }
  }
  return score;
}

/** Rank installed apps against a free-text query. Never throws. */
export function matchApps(query: string, apps: IndexedApp[]): AppMatch {
  const qTokens = nameTokens(query);
  const qJoined = qTokens.join('');
  if (!qTokens.length || !apps.length) return { kind: 'none' };

  const ranked = apps
    .map((app) => ({ app, s: scoreApp(qTokens, qJoined, app) }))
    .filter((x) => x.s > 0)
    .sort((a, b) => b.s - a.s || a.app.appName.length - b.app.appName.length);
  if (!ranked.length) return { kind: 'none' };

  const top = ranked[0];
  const second = ranked[1];
  if (top.s >= 90 && (!second || top.s - second.s >= 6)) return { kind: 'exact', app: top.app };
  if (top.s >= 50 && (!second || top.s - second.s >= 14)) return { kind: 'single', app: top.app };

  const shortlist = ranked.filter((x) => x.s >= Math.max(18, top.s - 22)).slice(0, 3).map((x) => x.app);
  if (shortlist.length === 1) return { kind: 'single', app: shortlist[0] };
  if (shortlist.length >= 2) return { kind: 'multiple', apps: shortlist };
  return { kind: 'none' };
}

/** load (cached) + match + emit APP_MATCH. `label` is only for the log line. */
export async function resolveAppQuery(query: string, label = 'open'): Promise<AppMatch> {
  // Never match against an empty index: if the first load came back empty (or hasn't happened
  // yet), force one synchronous load here before answering — the caller waits on this, so a
  // response is never produced from a not-yet-ready index.
  let apps = await loadAppIndex();
  if (!apps.length) apps = await loadAppIndex(true, 'lazy');

  const m = matchApps(query, apps);
  const candidates = m.kind === 'multiple' ? m.apps.length : m.kind === 'none' ? 0 : 1;
  const chosen =
    m.kind === 'exact' || m.kind === 'single'
      ? m.app.appName
      : m.kind === 'multiple'
        ? m.apps.map((a) => a.appName).join('|')
        : '';
  const asked = m.kind === 'single' || m.kind === 'multiple';
  // E2-fix / Fix 2 — surface the resolved display name + package so a mismatch between what the
  // user said and what PackageManager calls the app is readable straight from the log.
  const matchedName = m.kind === 'exact' || m.kind === 'single' ? m.app.appName : m.kind === 'multiple' ? m.apps[0].appName : '';
  const matchedPkg = m.kind === 'exact' || m.kind === 'single' ? m.app.packageName : m.kind === 'multiple' ? m.apps[0].packageName : '';
  let extra = '';
  if (m.kind === 'none') {
    // Diagnostic: did the query text appear ANYWHERE in the index (raw substring on name or
    // package)? rawContains=0 with indexCount>0 means the app simply isn't in the list BENSON can
    // see (package-visibility truncation), not a scoring miss.
    const qJoined = normalizeName(query).replace(/ /g, '');
    const rawContains = apps.filter(
      (a) => normalizeName(a.appName).replace(/ /g, '').includes(qJoined) || a.packageName.toLowerCase().includes(qJoined),
    ).length;
    extra = ` indexCount=${apps.length} rawContains=${rawContains}`;
  }
  logAudioDiag(
    'APP_MATCH',
    `intent=${label} query=${JSON.stringify(query)} matched=${JSON.stringify(matchedName)} package=${JSON.stringify(matchedPkg)} candidates=${candidates} chosen=${JSON.stringify(chosen)} asked=${asked}${extra}`,
  );
  return m;
}

/** "A, B, C" for the "pe care?" prompt. */
export function listAppNames(apps: IndexedApp[]): string {
  return apps.map((a) => a.appName).join(', ');
}

// Warm the index shortly after startup so `APP_INDEX count=…` shows up in the log without waiting
// for the first "open X", and the first real command doesn't pay the enumeration cost. Fired from
// module load (this file is pulled in via the app-launcher import chain, itself loaded at boot);
// the small delay lets the native bridge come up first. Failure is swallowed + logged inside
// loadAppIndex(), and the cache stays empty so a later real call retries.
setTimeout(() => { void loadAppIndex(false, 'warm'); }, 3000);
