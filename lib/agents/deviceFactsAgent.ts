import * as Location from 'expo-location';

// Deterministic time/location answers (product-owner-directed 2026-09-18) — Claude already had
// getCurrentDateTime/getLocation TOOLS available on every conversational turn (lib/agents/tools.ts),
// with the system prompt explicitly forbidding "I don't know the time/location" — but the user
// reported live failures for exactly these two questions. Rather than trust an LLM to reliably
// choose to call a tool for what it might think it can just answer, these bypass tool-calling
// entirely for the two most common phrasings, same architectural idiom as WEATHER_PATTERN/
// runWeatherAgent — deterministic, instant, reads the real device clock/GPS directly, always
// localized (the tools.ts implementations return hardcoded English, unlike this pair).
export const TIME_PATTERN =
  /\b(cât e ceasul|cat e ceasul|ce or[ăa] e|ce zi e azi|ce zi este azi|ce dat[ăa] e|ce dat[ăa] este|what time is it|what'?s the time|what day is it|what'?s today'?s date|wie sp[äa]t ist es|welcher tag ist heute)\b/i;
// Deliberately NOT bare "unde sunt" — that also matches "unde sunt cheile"/"unde sunt copiii"
// (where are my keys/the kids), unrelated location-of-an-object questions, not "where am I".
export const LOCATION_PATTERN =
  /\b(unde suntem|unde m[ăa] aflu|unde ne afl[ăa]m|where am i|where are we|wo bin ich)\b/i;

function localized(lang: string, parts: { ro: string; de: string; en: string }): string {
  const l = (lang || '').toLowerCase();
  if (l.startsWith('ro')) return parts.ro;
  if (l.startsWith('de')) return parts.de;
  return parts.en;
}

export function runTimeAgent(lang: string, address: string): { reply: string } {
  const now = new Date();
  const formatted = new Intl.DateTimeFormat(lang || undefined, {
    weekday: 'long', year: 'numeric', month: 'long', day: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(now);
  return { reply: localized(lang, {
    ro: `Este ${formatted}, ${address}.`,
    de: `Es ist ${formatted}, ${address}.`,
    en: `It's ${formatted}, ${address}.`,
  }) };
}

export async function runLocationAgent(lang: string, address: string): Promise<{ reply: string }> {
  try {
    const perm = await Location.requestForegroundPermissionsAsync();
    if (!perm.granted) {
      return { reply: localized(lang, {
        ro: `Am nevoie de acces la locație ca să-ți spun unde suntem, ${address}.`,
        de: `Ich brauche Standortzugriff, um dir zu sagen, wo wir sind, ${address}.`,
        en: `I need location access to tell you where we are, ${address}.`,
      }) };
    }
    const pos = await Location.getCurrentPositionAsync({});
    const { latitude, longitude } = pos.coords;
    const places = await Location.reverseGeocodeAsync({ latitude, longitude });
    const place = places[0];
    const placeName = place
      ? [place.city ?? place.subregion, place.region, place.country].filter(Boolean).join(', ')
      : null;
    if (placeName) {
      return { reply: localized(lang, {
        ro: `Suntem lângă ${placeName}, ${address}.`,
        de: `Wir sind in der Nähe von ${placeName}, ${address}.`,
        en: `We're near ${placeName}, ${address}.`,
      }) };
    }
    return { reply: localized(lang, {
      ro: `Suntem la coordonatele ${latitude.toFixed(4)}, ${longitude.toFixed(4)}, ${address}.`,
      de: `Wir befinden uns bei ${latitude.toFixed(4)}, ${longitude.toFixed(4)}, ${address}.`,
      en: `We're at ${latitude.toFixed(4)}, ${longitude.toFixed(4)}, ${address}.`,
    }) };
  } catch {
    return { reply: localized(lang, {
      ro: `Nu am putut obține poziția GPS acum, ${address}.`,
      de: `Ich konnte gerade keine GPS-Position ermitteln, ${address}.`,
      en: `I couldn't get a GPS fix right now, ${address}.`,
    }) };
  }
}
