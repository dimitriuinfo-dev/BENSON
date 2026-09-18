// The ONE user-facing fallback line, in BENSON's active language. Spoken EVERYWHERE a brain or
// provider path fails to produce a real answer: no key, network error, timeout, an empty or
// non-schema response, or an exhausted tool-use loop. After 2026-08-28 no English fallback string
// is hardcoded in any conversational agent — claudeAgent / openaiAgent / geminiAgent /
// openAiCompatibleBrain all route their empty-result branch through here (see ROUND3B_REPORT.md
// for the full grep).
//
// Zero imports on purpose: orchestrator.ts imports the three agents AND this line, and each agent
// also imports this line — a dependency-free leaf module is what keeps that graph acyclic.
export function conversationFallbackLine(lang: string, address?: string): string {
  const l = (lang || '').toLowerCase();
  const who = address ? `, ${address}` : '';
  if (l.startsWith('ro')) return `Nu am putut procesa asta acum${who}. Verifică conexiunea sau cheia din setări.`;
  if (l.startsWith('de')) return `Ich konnte das gerade nicht verarbeiten${who}. Prüfe die Verbindung oder den Schlüssel in den Einstellungen.`;
  return `I couldn't process that right now${who}. Check the connection or the key in settings.`;
}
