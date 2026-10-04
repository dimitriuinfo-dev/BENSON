import { executeCommand, getScreenSnapshot } from 'benson-accessibility';
import { logAudioDiag } from 'benson-foreground-service';
import { activateObservedSearchControl } from '../core/accessibility/activateObservedSearchControl';
export interface ForegroundSearchOutcome {
  status: 'done' | 'no_foreground_app' | 'no_search_control' | 'type_failed' | 'text_unverified' | 'submit_failed' | 'result_unverified';
  message?: string;
}

function parseSnapshot(raw: string): { packageName: string; nodes: Array<{ text?: string | null; editable?: boolean }> } | null {
  try {
    const value = JSON.parse(raw);
    return typeof value?.packageName === 'string' && Array.isArray(value.nodes) ? value : null;
  } catch { return null; }
}

function snapshotSignature(snapshot: NonNullable<ReturnType<typeof parseSnapshot>>): string {
  return snapshot.nodes.map((n) => `${n.text || ''}:${n.editable ? 1 : 0}`).join('|');
}

/** Search in the currently foregrounded app, relying only on its fresh Accessibility tree. */
export async function searchForegroundApp(query: string, expectedPackage: string | null): Promise<ForegroundSearchOutcome> {
  if (!expectedPackage || expectedPackage === 'com.benson.butler') return { status: 'no_foreground_app' };
  const target = await activateObservedSearchControl(expectedPackage);
  if (target.status !== 'ready') {
    logAudioDiag('FOREGROUND_SEARCH_FAIL', `package=${expectedPackage} phase=resolve status=${target.status}`);
    return { status: 'no_search_control', message: 'Nu găsesc o căutare utilizabilă pe ecranul aplicației deschise.' };
  }

  const typed = await executeCommand({ steps: [{ action: 'set_text', match: target.inputMatch, text: query, timeoutMs: 3500, requirePackage: expectedPackage }] } as any);
  if (!typed?.success) {
    logAudioDiag('FOREGROUND_SEARCH_FAIL', `package=${expectedPackage} phase=type status=${typed?.status ?? 'failed'}`);
    return { status: 'type_failed', message: 'Am găsit căutarea, dar nu am putut introduce textul.' };
  }
  const beforeSubmit = parseSnapshot(await getScreenSnapshot());
  if (beforeSubmit?.packageName !== expectedPackage || !beforeSubmit.nodes.some((node) => node.editable && (node.text || '').trim() === query)) {
    logAudioDiag('FOREGROUND_SEARCH_FAIL', `package=${expectedPackage} phase=verify_text exact=false`);
    return { status: 'text_unverified', message: 'Nu pot verifica textul exact în câmpul de căutare.' };
  }

  const submitted = await executeCommand({ steps: [{ action: 'ime_action', requirePackage: expectedPackage }] } as any);
  if (!submitted?.success) {
    logAudioDiag('FOREGROUND_SEARCH_FAIL', `package=${expectedPackage} phase=submit status=${submitted?.status ?? 'failed'}`);
    return { status: 'submit_failed', message: 'Textul este în căutare, dar aplicația nu a pornit căutarea.' };
  }
  await executeCommand({ steps: [{ action: 'wait', ms: 700 }] } as any).catch(() => undefined);
  const after = parseSnapshot(await getScreenSnapshot());
  const changed = after?.packageName === expectedPackage && snapshotSignature(after) !== snapshotSignature(beforeSubmit);
  logAudioDiag('FOREGROUND_SEARCH_RESULT', `package=${expectedPackage} typedExact=true submitted=true screenChanged=${changed}`);
  if (!changed) return { status: 'result_unverified', message: 'Am lansat căutarea, dar nu pot confirma schimbarea rezultatelor pe ecran.' };
  return { status: 'done', message: `Am căutat „${query}” în aplicația deschisă.` };
}
