// BENSON_GROUNDED_CONVERSATION_1 (2026-09-20) — the deterministic pre-check that runs BEFORE
// routeThroughBrain() gets a chance to classify the utterance. Weather and news/current-info
// queries never reach the free-form brain classifier at all: an instruction in a system prompt is
// not enough to stop a model from inventing an answer (product-owner directive) — so these are
// intercepted here, answered from a REAL provider result, and only ever handed to the brain to
// phrase already-fetched, real data (search/news), never to answer from its own "knowledge".
//
// Reuses the EXISTING regexes from weatherAgent.ts/searchAgent.ts (no duplicate pattern set) and
// the EXISTING Open-Meteo/Tavily wiring via lib/providers/*. routeCommand()/orchestrator.ts (the
// legacy second-brain path those files were built for) is NOT activated by this — this module
// calls the providers directly.
import { logAudioDiag } from 'benson-foreground-service';
import { WEATHER_PATTERN } from '../agents/weatherAgent';
import { SEARCH_PATTERN, NEWS_PATTERN } from '../agents/searchAgent';
import { getWeather, type WeatherQuery } from '../providers/weatherProvider';
import { webSearch } from '../providers/webSearchProvider';
import { synthesizeGroundedAnswer } from './brainRouter';

// "și mâine?" / "mâine" / "morgen" / "tomorrow" — RO/DE/EN, mirrors the language coverage already
// used by WEATHER_PATTERN itself.
const TOMORROW_PATTERN = /\b(?:și\s+)?mâine\b|\bmorgen\b|\btomorrow\b/i;

// Optional trailing place — same idiom as weatherAgent.ts's own PLACE_PATTERN, kept separate
// (not imported) because that one is anchored to end-of-string with no trailing words allowed
// after the place, which is a pre-existing limitation this round doesn't need to fix — a query
// with no named place (the required test phrases) never reaches this branch anyway.
const PLACE_PATTERN = /(?:\bin\b|\bat\b|\bla\b|\bîn\b|\bà\b)\s+([a-zA-ZăâîșțĂÂÎȘȚ\s\-]+?)\s*[?.!]*$/i;

export type RememberedLocation = { place: string; latitude: number; longitude: number };

export type GroundedAnswerResult =
  | { handled: false }
  | { handled: true; reply: string; rememberedLocation?: RememberedLocation };

function weatherReplyRO(place: string, current: { tempC: number; description: string }, tomorrow?: { tempMaxC: number; tempMinC: number }): string {
  const now = `Acum, la ${place}, sunt ${Math.round(current.tempC)}°C, ${current.description === 'clear' ? 'cer senin' : 'cu precipitații'}.`;
  if (!tomorrow) return now;
  return `${now} Mâine: între ${Math.round(tomorrow.tempMinC)}°C și ${Math.round(tomorrow.tempMaxC)}°C.`;
}

async function tryAnswerGroundedInner(params: {
  text: string;
  lang: string;
  address: string;
  tavilyKey: string;
  rememberedLocation: RememberedLocation | null;
}): Promise<GroundedAnswerResult> {
  const { text, lang, address, tavilyKey, rememberedLocation } = params;
  const isWeather = WEATHER_PATTERN.test(text);
  logAudioDiag('GROUNDED_ANSWER_CHECK', `isWeather=${isWeather} textLen=${text.length}`);

  if (isWeather) {
    const placeMatch = text.match(PLACE_PATTERN);
    const explicitCity = placeMatch ? placeMatch[1].trim() : null;
    const includeTomorrow = TOMORROW_PATTERN.test(text);

    // FIX_ROUND_2 (2026-09-20) — "și mâine?" reuses the COORDINATES already resolved for the
    // first weather question this conversation (remembered_coords) — zero re-geocoding, not just
    // a saved city name.
    const query: WeatherQuery = explicitCity
      ? { kind: 'explicit_city', city: explicitCity }
      : rememberedLocation
      ? { kind: 'remembered_coords', place: rememberedLocation.place, latitude: rememberedLocation.latitude, longitude: rememberedLocation.longitude }
      : { kind: 'device_location' };

    const result = await getWeather(query, includeTomorrow);
    if (!result.ok) {
      const msg =
        result.reason === 'city_not_found' ? `Nu găsesc localitatea „${result.city}", ${address}.`
        : result.reason === 'needs_location_permission' ? `Am nevoie de acces la locație ca să-ți spun vremea, ${address}. Poți să-l activezi din Setări, sau îmi spui tu orașul.`
        : result.reason === 'needs_city' ? `În ce oraș, ${address}?`
        : `Nu pot ajunge acum la datele meteo, ${address}.`;
      return { handled: true, reply: msg };
    }
    const reply = weatherReplyRO(result.place, result.current, result.tomorrow);
    return { handled: true, reply, rememberedLocation: { place: result.place, latitude: result.latitude, longitude: result.longitude } };
  }

  const searchMatch = text.match(SEARCH_PATTERN);
  const isNews = NEWS_PATTERN.test(text);
  if (searchMatch || isNews) {
    const query = searchMatch ? searchMatch[1].trim() : text;
    const result = await webSearch(query, isNews, tavilyKey);
    if (!result.ok) {
      const msg =
        result.reason === 'no_api_key' ? `Nu am o cheie de căutare configurată, ${address}.`
        : `Nu pot ajunge acum la căutare, ${address}.`;
      return { handled: true, reply: msg };
    }
    const dataText = result.results
      .map((r, i) => `[${i + 1}] ${r.title} — ${r.content} (sursă: ${r.url})`)
      .join('\n');
    // The existing, unchanged 3s/no-retry LLM ceiling (openAiCompatibleBrain.ts) already bounds
    // this call — not touched or raised here.
    const synthesized = await synthesizeGroundedAnswer({ utterance: text, lang, groundedDataText: dataText });
    if (synthesized) return { handled: true, reply: synthesized };
    // Brain unavailable/failed — an honest, still-grounded fallback (real titles, no invention).
    const titles = result.results.slice(0, 3).map((r) => r.title).join('. ');
    return { handled: true, reply: `Am găsit: ${titles}.` };
  }

  return { handled: false };
}

// FIX_ROUND_2 (2026-09-20, product-owner-directed) — an explicit OUTER ceiling on the whole
// grounded-answer attempt (location + provider fetch + optional synthesis), separate from and in
// addition to each individual fetch's own timeout (10s each) and the LLM's own 3s ceiling
// (unchanged, never raised here). Those bound each STEP; this bounds the SUM, so independent
// per-step timeouts can never silently stack past a sane total for one user turn.
const GROUNDED_ANSWER_TOTAL_TIMEOUT_MS = 15000;

export async function tryAnswerGrounded(params: {
  text: string;
  lang: string;
  address: string;
  tavilyKey: string;
  rememberedLocation: RememberedLocation | null;
}): Promise<GroundedAnswerResult> {
  let timer: ReturnType<typeof setTimeout> | null = null;
  const timeout = new Promise<GroundedAnswerResult>((resolve) => {
    timer = setTimeout(() => {
      logAudioDiag('GROUNDED_ANSWER_TOTAL_TIMEOUT', `ms=${GROUNDED_ANSWER_TOTAL_TIMEOUT_MS}`);
      resolve({ handled: true, reply: `Îmi ia prea mult să ajung la date acum, ${params.address}.` });
    }, GROUNDED_ANSWER_TOTAL_TIMEOUT_MS);
  });
  try {
    return await Promise.race([tryAnswerGroundedInner(params), timeout]);
  } finally {
    if (timer) clearTimeout(timer);
  }
}
