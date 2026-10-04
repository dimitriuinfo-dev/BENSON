export interface WhatsAppVisibleNode {
  text?: string | null;
  contentDescription?: string | null;
  viewId?: string | null;
  editable?: boolean;
  clickable?: boolean;
  bounds?: { left: number; top: number; right: number; bottom: number };
}

export type WhatsAppVisibleTarget =
  | { status: 'resolved'; title: string }
  | { status: 'ambiguous' | 'not_found' };

function normalize(value: string): string {
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().replace(/[^\p{L}\p{N}]+/gu, ' ').trim();
}

/** Resolves only a unique exact clickable row observed in WhatsApp's current Accessibility tree. */
export function resolveVisibleWhatsAppTarget(
  query: string,
  snapshot: { packageName: string; nodes: WhatsAppVisibleNode[] } | null,
): WhatsAppVisibleTarget {
  if (!snapshot || snapshot.packageName !== 'com.whatsapp') return { status: 'not_found' };
  const wanted = normalize(query);
  if (!wanted) return { status: 'not_found' };

  const matches: Array<{ title: string; row: number }> = [];
  const isSearchControl = (viewId: string) => /\/(?:search_input|search_bar_inner_layout|menuitem_search|search_edit_text)$/.test(viewId.toLowerCase());
  const hasClickableRowAncestor = (labelNode: WhatsAppVisibleNode): boolean => {
    const child = labelNode.bounds;
    if (!child) return false;
    const childHeight = Math.max(1, child.bottom - child.top);
    const childCenterX = (child.left + child.right) / 2;
    const childCenterY = (child.top + child.bottom) / 2;
    return snapshot.nodes.some((parent) => {
      if (!parent.clickable || parent.editable || isSearchControl(parent.viewId ?? '') || !parent.bounds) return false;
      const box = parent.bounds;
      const parentHeight = box.bottom - box.top;
      // Accessibility emits clickable list rows as a parent and their title as a non-clickable
      // child. Accept only a compact containing row around that child; a broad screen/container
      // cannot authorize the contact selection.
      return box.left <= childCenterX && box.right >= childCenterX
        && box.top <= child.top && box.bottom >= child.bottom
        && parentHeight >= childHeight && parentHeight <= childHeight * 5;
    });
  };
  for (const node of snapshot.nodes) {
    if (node.editable || isSearchControl(node.viewId ?? '')) continue;
    const bounds = node.bounds;
    if (!bounds || !Number.isFinite(bounds.top) || !Number.isFinite(bounds.bottom)) continue;
    if (!node.clickable && !hasClickableRowAncestor(node)) continue;
    const row = Math.round((bounds.top + bounds.bottom) / 2 / 8);
    const labels = [node.text ?? '', (node.contentDescription ?? '').split(/[,\n·|•]/)[0]];
    for (const label of labels) {
      const title = label.trim();
      if (title && normalize(title) === wanted) matches.push({ title, row });
    }
  }

  const rows = new Map<number, string>();
  for (const match of matches) if (!rows.has(match.row)) rows.set(match.row, match.title);
  if (rows.size === 1) return { status: 'resolved', title: [...rows.values()][0] };
  return { status: rows.size > 1 ? 'ambiguous' : 'not_found' };
}
