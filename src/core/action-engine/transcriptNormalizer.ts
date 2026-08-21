// BENSON Action Engine — Transcript Normalizer (Module 2).
// Pure text transform between raw STT output and the Intent Engine. Fixes known STT split-word
// mishears for brand names and strips a stray leading wake word (the same-utterance case is
// already stripped natively before this ever runs; this covers the second-session capture,
// where the user sometimes repeats "Benson" out of habit).

// Mirrors BensonForegroundService.kt's WAKE_REGEX variant list — kept in sync manually since one
// is Kotlin (native hotword loop) and this one is TypeScript (second-session JS capture).
const LEADING_WAKE_WORD_PATTERN =
  /^\s*(?:benson|bensen|bensson|benzon|bentson|bennson|bänson|bensn|benzine|benzin|penson|penzon|benz[ăa]|ben\s+son)\b[,.!?]?\s*/i;

// STT frequently splits or mishears these brand names as separate common words.
const KNOWN_MISHEARS: [RegExp, string][] = [
  [/\bwhats\s*app\b/gi, 'whatsapp'],
  [/\byou\s*tube\b/gi, 'youtube'],
  [/\bblue\s*mail\b/gi, 'bluemail'],
  [/\bways\b/gi, 'waze'], // STT very commonly mishears "Waze" as "ways"
  [/\bgoogle\s+h[ăa]r[țt]i\b/gi, 'google maps'],
  [/\bgoogle\s+harti\b/gi, 'google maps'],
];

export function normalizeTranscript(raw: string): string {
  let text = raw.trim().replace(LEADING_WAKE_WORD_PATTERN, '').trim();
  for (const [pattern, replacement] of KNOWN_MISHEARS) {
    text = text.replace(pattern, replacement);
  }
  return text;
}
