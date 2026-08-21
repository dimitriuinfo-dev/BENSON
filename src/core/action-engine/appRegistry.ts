// BENSON Action Engine — App Registry (Module 5).
// The controlled allowlist of apps BENSON is permitted to open, in the directive's exact shape.
// Deliberately NOT the broader curated lib/appRegistry.ts (35+ apps, no installed-check
// discipline) — this is the minimum-viable, always-verified set this MVP governs.

export type AppRegistryEntry = {
  name: string;
  packageName?: string; // undefined = not yet integrated (Life360) or resolved dynamically (radio/music)
  matchWords: string[];
  deeplinkTemplate?: string; // {destination} placeholder, filled in by NavigationExecutor
  fallbackTemplate?: string;
};

export const APP_REGISTRY: AppRegistryEntry[] = [
  {
    name: 'Waze',
    packageName: 'com.waze',
    // 'waz' covers the real STT truncation seen live ("open Waz" instead of "open Waze").
    // 'oaza'/'oazei' covers a confirmed ro-RO phonetic mishearing of the English brand name
    // ("am solicitat deschiderea oazei" instead of "...Waze-ului") — see commandParser.ts's
    // MENTIONS_WAZE for the matching rationale.
    matchWords: ['waze', 'waz', 'oaza', 'oazei'],
    deeplinkTemplate: 'waze://?q={destination}&navigate=yes',
    fallbackTemplate: 'https://waze.com/ul?q={destination}&navigate=yes',
  },
  {
    name: 'Google Maps',
    packageName: 'com.google.android.apps.maps',
    matchWords: ['google maps', 'maps', 'harta', 'hărți', 'harti'],
    deeplinkTemplate: 'google.navigation:q={destination}',
  },
  { name: 'WhatsApp', packageName: 'com.whatsapp', matchWords: ['whatsapp'] },
  { name: 'YouTube', packageName: 'com.google.android.youtube', matchWords: ['youtube'] },
  { name: 'BlueMail', packageName: 'me.bluemail.mail', matchWords: ['bluemail', 'blue mail', 'mail', 'email'] },
  // Calendar: data-provider action, not an app to launch — Module 3's CALENDAR_ACTION executor
  // (Phase B) reads via the Android Calendar Provider, no package to check/launch here.
  { name: 'Calendar', matchWords: ['calendar', 'calendarul', 'programul'] },
  // Life360: no real integration yet (Phase B) — placeholder entry so the registry's shape
  // already reflects the directive; packageName intentionally left unset until confirmed.
  { name: 'Life360', matchWords: ['life360', 'familia', 'family'] },
];

// No single fixed package for these two — "radio"/"music" resolve to whichever matching app is
// actually installed, found by name against the device's own launcher list (see
// androidActionExecutor's sibling lookup in appLauncherExecutor.ts). Radio hints go well beyond
// the literal word "radio" — most real radio apps are named after a station/brand instead
// (TuneIn, MyTuner, Radio România Actualități, Antena, Europa FM, Kiss FM, ...), so matching only
// on "radio" in the app name missed real installed radio apps entirely.
export const RADIO_NAME_HINTS = [
  'radio',
  'fm',
  'tuner',
  'tunein',
  'tune in',
  'mytuner',
  'my tuner',
  'audials',
  'românia actualități',
  'romania actualitati',
  'radio romania',
  'radio ro',
  'antena',
  'europa fm',
  'digi fm',
  'kiss fm',
  'magic fm',
  'rock fm',
  'online radio',
];
export const MUSIC_NAME_HINTS = ['music', 'muzică', 'muzica', 'spotify', 'deezer'];

export function findAppRegistryEntry(appName: string): AppRegistryEntry | undefined {
  const n = appName.toLowerCase().trim();
  return APP_REGISTRY.find((e) => e.matchWords.some((w) => n.includes(w)));
}

export function isPhoneRequest(appName: string): boolean {
  return /\b(telefon|phone|dialer|apelator)\b/i.test(appName);
}

export function isSmsRequest(appName: string): boolean {
  return /\b(sms|mesaj\s*text|text\s*message)\b/i.test(appName);
}

export function isRadioRequest(appName: string): boolean {
  return RADIO_NAME_HINTS.some((w) => appName.toLowerCase().includes(w));
}

export function isMusicRequest(appName: string): boolean {
  return MUSIC_NAME_HINTS.some((w) => appName.toLowerCase().includes(w));
}

export function fillTemplate(template: string, destination: string): string {
  return template.replace('{destination}', encodeURIComponent(destination));
}
