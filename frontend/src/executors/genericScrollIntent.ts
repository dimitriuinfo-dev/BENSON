export type ScrollDirection = 'forward' | 'backward' | 'left' | 'right';

/** Recognize direct requests to reveal more/earlier content on the current page. */
export function extractGenericScrollDirection(input: string): ScrollDirection | null {
  const text = (input || '').normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
  const up = /\b(?:mai\s+sus|in\s+sus|deruleaz\w*\s+(?:in\s+)?sus|scroll\s+up|scroll\s+back|swipe\s+down|scroll\w*\s+nach\s+oben|weiter\s+oben)\b/.test(text);
  const down = /\b(?:mai\s+jos|in\s+jos|deruleaz\w*|scroll\s+down|scroll\s+lower|swipe\s+up|arata\w*\s+.+mai\s+jos|vezi\w*\s+.+mai\s+jos|urmatoarele|mai\s+multe|weiter\s+runter|scroll\w*\s+nach\s+unten)\b/.test(text);
  const left = /\b(?:deruleaz\w*\s+(?:spre\s+)?stanga|mai\s+la\s+stanga|scroll\s+left|swipe\s+right|weiter\s+links|nach\s+links\s+scrollen)\b/.test(text);
  const right = /\b(?:deruleaz\w*\s+(?:spre\s+)?dreapta|mai\s+la\s+dreapta|scroll\s+right|swipe\s+left|weiter\s+rechts|nach\s+rechts\s+scrollen)\b/.test(text);
  if (left) return 'left';
  if (right) return 'right';
  if (up) return 'backward';
  if (down) return 'forward';
  return null;
}
