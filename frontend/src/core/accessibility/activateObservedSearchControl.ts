import { executeCommand, getScreenSnapshot, type BensonNode } from 'benson-accessibility';
import { logAudioDiag } from 'benson-foreground-service';
import { resolveObservedSearchTarget, searchTargetClickMatch, searchTargetInputMatch, type ObservedScreen } from './observedSearchTarget';

type CommandResult = { success?: boolean; status?: string; detail?: string | null };
export type SearchActivationResult =
  | { status: 'ready'; inputMatch: Record<string, unknown>; used: 'existing_input' | 'clicked_observed_control' }
  | { status: 'ambiguous' | 'not_found' | 'wrong_package' | 'click_failed' | 'input_not_observed' };

function parseSnapshot(raw: string): ObservedScreen | null {
  try {
    const parsed = JSON.parse(raw) as { packageName?: string; nodes?: BensonNode[] };
    return typeof parsed.packageName === 'string' && Array.isArray(parsed.nodes)
      ? { packageName: parsed.packageName, nodes: parsed.nodes }
      : null;
  } catch { return null; }
}

function searchInputs(screen: ObservedScreen, expectedPackage: string): BensonNode[] {
  if (screen.packageName !== expectedPackage) return [];
  const nodes = screen.nodes as BensonNode[];
  const semantic = nodes.filter((node) => node.editable && (
    /(?:^|[:/_-])(search|query|find)(?:$|[:/_-])/i.test(node.viewId || '') ||
    /\b(search|find|query|lupa|lupe|cautare|cauta|suche|suchen|suchfeld|recherche|buscar|busqueda|ricerca|cerca)\b/i.test(`${node.text || ''} ${node.contentDescription || ''} ${(node as BensonNode & { hintText?: string }).hintText || ''}`)
  ));
  if (semantic.length) return semantic;
  const editable = nodes.filter((node) => node.editable);
  return editable.length === 1 ? editable : [];
}

export async function activateObservedSearchControl(expectedPackage: string): Promise<SearchActivationResult> {
  let before: ObservedScreen | null = null;
  try { before = parseSnapshot(await getScreenSnapshot()); } catch {}
  if (!before || before.packageName !== expectedPackage) {
    logAudioDiag('OBSERVED_SEARCH_TARGET', `package=${expectedPackage} status=wrong_package`);
    return { status: 'wrong_package' };
  }
  const target = resolveObservedSearchTarget(before, expectedPackage);
  if (target.status !== 'resolved') {
    logAudioDiag('OBSERVED_SEARCH_TARGET', `package=${expectedPackage} status=${target.status}`);
    return { status: target.status };
  }
  if (target.kind === 'input') {
    const inputs = searchInputs(before, expectedPackage);
    if (inputs.length !== 1) return { status: 'ambiguous' };
    logAudioDiag('OBSERVED_SEARCH_TARGET', `package=${expectedPackage} status=ready kind=input via=observed_tree`);
    return { status: 'ready', inputMatch: searchTargetInputMatch(target), used: 'existing_input' };
  }

  let clicked: CommandResult | null = null;
  try {
    clicked = await executeCommand({ steps: [{ action: 'click', match: searchTargetClickMatch(target), timeoutMs: 3500, requirePackage: expectedPackage }] } as any) as CommandResult;
  } catch {}
  if (!clicked?.success) {
    logAudioDiag('OBSERVED_SEARCH_TARGET', `package=${expectedPackage} status=click_failed`);
    return { status: 'click_failed' };
  }
  try { await executeCommand({ steps: [{ action: 'wait', ms: 250 }] } as any); } catch {}
  let after: ObservedScreen | null = null;
  try { after = parseSnapshot(await getScreenSnapshot()); } catch {}
  if (!after || after.packageName !== expectedPackage) {
    logAudioDiag('OBSERVED_SEARCH_TARGET', `package=${expectedPackage} status=wrong_package after_click=true`);
    return { status: 'wrong_package' };
  }
  const inputs = searchInputs(after, expectedPackage);
  if (inputs.length !== 1) {
    logAudioDiag('OBSERVED_SEARCH_TARGET', `package=${expectedPackage} status=input_not_observed fieldCount=${inputs.length}`);
    return { status: inputs.length > 1 ? 'ambiguous' : 'input_not_observed' };
  }
  const input = inputs[0];
  const inputMatch = input.viewId ? { viewId: input.viewId, editable: true } : { editable: true };
  logAudioDiag('OBSERVED_SEARCH_TARGET', `package=${expectedPackage} status=ready kind=button fieldCount=1 verified=true`);
  return { status: 'ready', inputMatch, used: 'clicked_observed_control' };
}
