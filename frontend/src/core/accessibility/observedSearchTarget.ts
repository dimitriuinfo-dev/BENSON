/** Generic, fail-closed search-control resolution from the current Accessibility tree.
 * This intentionally uses only controls and labels observed in the requested foreground app.
 * It never infers coordinates or searches another app's window.
 */
export interface ObservedNode {
  id?: string;
  viewId?: string | null;
  text?: string | null;
  contentDescription?: string | null;
  hintText?: string | null;
  className?: string | null;
  editable?: boolean;
  clickable?: boolean;
  bounds?: { left: number; top: number; right: number; bottom: number };
}

export interface ObservedScreen {
  packageName: string;
  nodes: ObservedNode[];
}

export type SearchTarget =
  | { status: 'resolved'; kind: 'input' | 'button'; viewId: string | null; label: string }
  | { status: 'ambiguous' | 'not_found' };

function normalize(s: string): string {
  return (s || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

const SEARCH_WORDS = new Set([
  'search', 'find', 'lookup', 'query', 'lupa', 'lupe', 'cautare', 'cauta',
  'suche', 'suchen', 'suchfeld', 'recherche', 'rechercher', 'buscar', 'busqueda', 'ricerca', 'cerca',
]);
const NON_CONTROL_WORDS = new Set(['results', 'result', 'filter', 'filters', 'settings', 'history', 'help']);

function labelContainsSearchAction(label: string): boolean {
  const words = normalize(label).split(' ').filter(Boolean);
  if (!words.length || words.length > 5 || words.some((w) => NON_CONTROL_WORDS.has(w))) return false;
  return words.some((w) => SEARCH_WORDS.has(w));
}

function boundsKey(node: ObservedNode): string {
  const b = node.bounds;
  return b ? `${Math.round(b.left / 12)}:${Math.round(b.top / 12)}:${Math.round(b.right / 12)}:${Math.round(b.bottom / 12)}` : '';
}

/**
 * Resolve one observed search control. Priority: an already visible search input, a semantic
 * resource ID, an accessible content description, then visible text. If equal-priority distinct
 * controls exist, return ambiguous so the caller can ask rather than click the wrong one.
 */
export function resolveObservedSearchTarget(screen: ObservedScreen | null, expectedPackage: string): SearchTarget {
  if (!screen || screen.packageName !== expectedPackage || !screen.nodes?.length) return { status: 'not_found' };
  const candidates: Array<{ node: ObservedNode; kind: 'input' | 'button'; score: number; label: string }> = [];
  for (const node of screen.nodes) {
    const viewId = (node.viewId || '').toLowerCase();
    const text = (node.text || '').trim();
    const desc = (node.contentDescription || '').trim();
    const hint = (node.hintText || '').trim();
    const idIsSearch = /(?:^|[:/_-])(search|query|find|magnif)(?:$|[:/_-])/.test(viewId)
      && !/(result|filter|history|setting)/.test(viewId);
    const editableSearch = !!node.editable && (idIsSearch || labelContainsSearchAction(`${text} ${desc} ${hint}`));
    if (editableSearch) {
      candidates.push({ node, kind: 'input', score: 100, label: text || desc || 'search input' });
      continue;
    }
    if (node.editable) continue;
    const label = desc || text || hint;
    const labelIsSearch = labelContainsSearchAction(label);
    if (!idIsSearch && !labelIsSearch) continue;
    const controlLike = !!node.clickable || !!node.className?.match(/Button|ImageView|ImageButton|Tab|MenuItem/i) || labelIsSearch;
    if (!controlLike) continue;
    const score = idIsSearch ? 90 : desc && labelIsSearch ? 80 : 70;
    candidates.push({ node, kind: 'button', score, label: label || 'search' });
  }
  if (!candidates.length) return { status: 'not_found' };

  const best = Math.max(...candidates.map((c) => c.score));
  const top = candidates.filter((c) => c.score === best);
  // Accessibility trees can expose one visual control as both a label and a container. Collapse
  // only nodes at the same observed bounds; same text at different places remains ambiguous.
  const unique = new Map<string, typeof top[number]>();
  for (const candidate of top) {
    const key = `${normalize(candidate.label)}|${candidate.kind}|${boundsKey(candidate.node) || candidate.node.viewId || candidate.node.id || ''}`;
    if (!unique.has(key)) unique.set(key, candidate);
  }
  if (unique.size !== 1) return { status: 'ambiguous' };
  const chosen = [...unique.values()][0];
  return { status: 'resolved', kind: chosen.kind, viewId: chosen.node.viewId || null, label: chosen.label };
}

/** Construct a live command against the exact observed target; caller must still requirePackage. */
export function searchTargetClickMatch(target: Extract<SearchTarget, { status: 'resolved' }>): Record<string, unknown> {
  return target.viewId
    ? { viewId: target.viewId, clickableAncestor: true }
    : { textContainsAny: [target.label], clickableAncestor: true };
}

export function searchTargetInputMatch(target: Extract<SearchTarget, { status: 'resolved' }>): Record<string, unknown> {
  return target.viewId ? { viewId: target.viewId, editable: true } : { editable: true };
}
