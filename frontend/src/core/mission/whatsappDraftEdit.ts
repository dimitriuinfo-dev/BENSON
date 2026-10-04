export type WhatsAppDraftEdit =
  | { kind: 'none' }
  | { kind: 'updated'; operation: 'append' | 'replace_fragment' | 'replace_all'; message: string }
  | { kind: 'clarify'; reason: 'empty_text' | 'fragment_missing' | 'fragment_repeated' };

function escapeRegex(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** Explicit edits to a staged WhatsApp draft; recipient, mission and send gate stay outside. */
export function editWhatsAppDraft(current: string, utterance: string): WhatsAppDraftEdit {
  const fullReplace = utterance.match(/^\s*nu[,\s]+scrie\s+(?:\u00een|in)\s+schimb\s*[:,]?\s*(.+?)\s*[.!?]*\s*$/i);
  if (fullReplace) {
    const message = fullReplace[1].trim();
    return message ? { kind: 'updated', operation: 'replace_all', message } : { kind: 'clarify', reason: 'empty_text' };
  }

  const replace = utterance.match(/^\s*(?:\u00eenlocuie\u0219te|inlocuie\u0219te|inlocuieste|replace)\s+[\u201e\u201c"]?(.+?)[\u201d"]?\s+cu\s+[\u201e\u201c"]?(.+?)[\u201d"]?\s*[.!?]*\s*$/i);
  if (replace) {
    const before = replace[1].trim();
    const after = replace[2].trim();
    if (!before || !after) return { kind: 'clarify', reason: 'empty_text' };
    const matcher = new RegExp(escapeRegex(before), 'giu');
    const matches = [...current.matchAll(matcher)];
    if (matches.length === 0) return { kind: 'clarify', reason: 'fragment_missing' };
    if (matches.length > 1) return { kind: 'clarify', reason: 'fragment_repeated' };
    return { kind: 'updated', operation: 'replace_fragment', message: current.replace(matcher, after) };
  }

  const append = utterance.match(/^\s*adaug(?:a|\u0103)\s*[:,]?\s*(.+?)\s*[.!?]*\s*$/i);
  if (append) {
    const addition = append[1].trim();
    return addition ? { kind: 'updated', operation: 'append', message: `${current.trim()} ${addition}`.trim() } : { kind: 'clarify', reason: 'empty_text' };
  }
  return { kind: 'none' };
}
