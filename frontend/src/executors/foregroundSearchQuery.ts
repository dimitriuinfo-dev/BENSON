/** Extract a query only when the user explicitly asked to search for non-empty text. */
export function extractForegroundSearchQuery(input: string): string | null {
  const text = (input || '').trim().replace(/[.!?]+$/g, '').trim();
  const patterns = [
    /^(?:te rog\s+)?(?:caută|cauta|caută-mi|cauta-mi|caută pentru|cauta pentru)\s+(.+)$/i,
    /^(?:please\s+)?(?:search(?:\s+for)?|look\s+up)\s+(.+)$/i,
    /^(?:suche(?:\s+nach)?|recherche(?:\s+sur)?)\s+(.+)$/i,
  ];
  for (const pattern of patterns) {
    const match = text.match(pattern);
    const query = match?.[1]?.trim();
    if (query && query.length <= 300 && !/^(?:în|in|pe|on|for|nach|sur)$/i.test(query)) return query;
  }
  return null;
}

/** Detect a search cue without a query so the next utterance can supply the search terms. */
export function isBareForegroundSearchRequest(input: string): boolean {
  const text = (input || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').trim().replace(/[.!?]+$/g, '').trim().toLowerCase();
  return /^(?:(?:te rog|please)\s+)?(?:cauta(?:-mi)?|search(?:\s+for)?|look\s+up|suche(?:\s+nach)?)$/.test(text);
}
