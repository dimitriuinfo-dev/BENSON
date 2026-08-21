// BENSON Action Engine — Discourse Cleaner.
// Strips conversational filler/politeness padding real speech is full of ("te rog frumos",
// "mulțumesc", "master", ...) before intent parsing, WITHOUT destroying the meaningful part of
// the utterance. Deliberately a separate file from transcriptNormalizer.ts (which only fixes
// STT brand mishears and strips a stray leading wake word) — filler-stripping is a fundamentally
// different, riskier kind of transform and should be easy to isolate/roll back on its own.
//
// Ordered longest-phrase-first so e.g. "te rog frumos" is consumed whole before the shorter
// "te rog" alternative would otherwise leave a stray "frumos" behind. Each phrase is replaced
// with a single space (never deleted outright) so word boundaries on either side survive, then
// whitespace is collapsed once at the end.
//
// Known limitation: a few of the required removals ("tu", "mie", "poți") are common short words
// stripped context-free, not real NLU — a name that happened to be spelled "Tu" would be affected
// too. Real utterances tested against this (see commandParser.ts's regression suite) don't hit
// that edge case; the CLEANED field in the Debug Panel exists precisely so a future misfire is
// visible immediately rather than silently swallowed.
const DISCOURSE_FILLER_PATTERNS: RegExp[] = [
  /\bte\s+rog\s+frumos\b/gi,
  /\bspune-?mi\s+te\s+rog\b/gi,
  /\bte\s+rog\b/gi,
  /\bpo(?:ț|t)i\s+s[ăa]\b/gi,
  /\bpo(?:ț|t)i\b/gi,
  /\bvreau\s+s[ăa]\b/gi,
  /\bam\s+nevoie\s+s[ăa]\b/gi,
  /\ba[șs]\s+vrea\s+s[ăa]\b/gi,
  /\bcu\s+pl[ăa]cere\b/gi,
  /\bmul[țt]umesc\b/gi,
  /\bpardon\b/gi,
  /\bscuze\b/gi,
  /\bmaster\b/gi,
  /\bfrumos\b/gi,
  // "hai" is filler EXCEPT when it's the NAVIGATE trigger commandParser.ts already relies on
  // ("hai spre Sibiu" / "hai cu Waze") — stripping it there would silently break an existing,
  // already-tested pattern. Only strip the standalone/hesitation use.
  /\bhai\b(?!\s+(?:spre|la|cu)\b)/gi,
  /\bok\b/gi,
  /\bbine\b/gi,
  /\btu\b/gi,
  /\bmie\b/gi,
];

export function cleanDiscourse(text: string): string {
  let cleaned = text;
  for (const pattern of DISCOURSE_FILLER_PATTERNS) {
    cleaned = cleaned.replace(pattern, ' ');
  }
  // Removing a filler phrase next to a comma ("te rog frumos, spune-mi...") leaves a stray
  // ", ," artifact behind — tidy those up too, not just whitespace.
  cleaned = cleaned.replace(/\s*,\s*/g, ', ').replace(/(,\s*)+/g, ', ');
  cleaned = cleaned.replace(/^[,.\s]+|[,.\s]+$/g, '');
  return cleaned.replace(/\s+/g, ' ').trim();
}
