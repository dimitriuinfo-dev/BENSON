/** Fail-closed utterance checks used while an externally consequential WhatsApp action awaits confirmation. */
export function isWhatsAppRecipientCorrection(text: string): boolean {
  const normalized = text.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
  return /\b(?:contact(?:ul)?|persoana|destinatar(?:ul)?)\s+(?:(?:e|este|pare)\s+)?(?:gresit|incorect|wrong)\b/.test(normalized)
    || /\b(?:wrong\s+(?:contact|person)|falscher\s+kontakt|nicht\s+der(?:\s+richtige)?\s+kontakt)\b/.test(normalized)
    || /\bnu\s+(?:e|este)\s+(?:acesta|aceasta|acela|aceea|el|ea|persoana|contactul|baby|hannah)\b/.test(normalized);
}
