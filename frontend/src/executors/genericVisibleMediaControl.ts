export type VisibleMediaControl = 'play' | 'pause' | 'stop' | 'next' | 'previous';

export interface VisibleMediaControlIntent {
  action: VisibleMediaControl;
  labels: string[];
}

/** Parse an explicit request to operate a currently visible media control. */
export function extractVisibleMediaControlIntent(input: string): VisibleMediaControlIntent | null {
  const text = (input || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim();
  const match = text.match(/\bapas[a](?![a-z0-9_])\s+(?:pe\s+)?(.+?)\s*\.?$/i);
  if (!match) return null;
  const target = match[1].replace(/\b(?:sageata|butonul|buton|iconita|pictograma)\b/g, ' ').replace(/\s+/g, ' ').trim();

  if (/\b(play|live|reda(?:re)?|continua(?:re)?|continue|reia|abspielen|wiedergabe|fortsetzen)\b/.test(target)) {
    const labels: string[] = [];
    if (/\blive\b/.test(target)) labels.push('live');
    if (/\bplay\b/.test(target)) labels.push('play');
    labels.push('redare', 'reda', 'continua', 'continue', 'resume');
    return { action: 'play', labels: [...new Set(labels)] };
  }
  if (/\b(pauza|pause|anhalten)\b/.test(target)) return { action: 'pause', labels: ['pauza', 'pause', 'anhalten'] };
  if (/\b(opreste|stop|beenden)\b/.test(target)) return { action: 'stop', labels: ['opreste', 'stop', 'beenden'] };
  if (/\b(urmatoarea|next|weiter)\b/.test(target)) return { action: 'next', labels: ['urmatoarea', 'next', 'weiter'] };
  if (/\b(anterioara|previous|zuruck)\b/.test(target)) return { action: 'previous', labels: ['anterioara', 'previous', 'zuruck'] };
  return null;
}
