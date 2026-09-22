// CALC1 (2026-09-22) — Task 1: deterministic text->operation parser for the Calculator (NOT sent
// to the LLM — no cost/latency for "cinci plus trei"). Task 2/3 (button-press execution + result
// readback) are native (CalculatorRecipe.kt); this file only builds the ordered symbol list and
// calls the bridge (runCalculatorRecipe), reusing it exactly, never a second execution path.
//
// Supported: addition, subtraction, multiplication, division, square root. Percent (op_pct
// exists natively, per the real button map discovered on-device) is deliberately NOT wired to a
// recognized phrase here — its expression syntax on this calculator was not verified this round;
// see CALC1_REPORT.md. An utterance that doesn't match one of the patterns below returns `null`
// — the caller must say so, never guess a symbol sequence.
import { runCalculatorRecipe } from 'benson-accessibility';
import { logAudioDiag } from 'benson-foreground-service';

const ONES: Record<string, number> = {
  zero: 0, unu: 1, una: 1, un: 1, doi: 2, doua: 2, două: 2, trei: 3, patru: 4,
  cinci: 5, sase: 6, șase: 6, sapte: 7, șapte: 7, opt: 8, noua: 9, nouă: 9,
  zece: 10, unsprezece: 11, doisprezece: 12, douasprezece: 12, douăsprezece: 12,
  treisprezece: 13, paisprezece: 14, cincisprezece: 15, cinsprezece: 15,
  saisprezece: 16, șaisprezece: 16, saptesprezece: 17, șaptesprezece: 17,
  optsprezece: 18, nouasprezece: 19, nouăsprezece: 19,
};
const TENS: Record<string, number> = {
  douazeci: 20, douăzeci: 20, treizeci: 30, patruzeci: 40, cincizeci: 50,
  saizeci: 60, șaizeci: 60, saptezeci: 70, șaptezeci: 70, optzeci: 80,
  nouazeci: 90, nouăzeci: 90,
};

// Minimal 0-999 word->number conversion — no existing project utility found for this (checked
// lib/ and src/ before writing it, per the round's own instruction). Bails (returns null) on any
// unrecognized token instead of guessing a partial number.
export function wordsToNumber(raw: string): number | null {
  const norm = raw.toLowerCase().trim();
  if (!norm) return null;
  if (/^\d+$/.test(norm)) return parseInt(norm, 10);

  const tokens = norm.split(/[\s-]+/).filter(Boolean);
  let total = 0;
  let hundredPending = 0;
  let matchedAny = false;

  for (const tok of tokens) {
    if (tok === 'si' || tok === 'și') continue;
    if (tok === 'suta' || tok === 'sută' || tok === 'sute') {
      hundredPending = (hundredPending || 1) * 100;
      matchedAny = true;
      continue;
    }
    if (tok in ONES) {
      if (hundredPending) { total += hundredPending; hundredPending = 0; }
      total += ONES[tok];
      matchedAny = true;
      continue;
    }
    if (tok in TENS) {
      if (hundredPending) { total += hundredPending; hundredPending = 0; }
      total += TENS[tok];
      matchedAny = true;
      continue;
    }
    return null; // unrecognized token — don't guess
  }
  if (hundredPending) total += hundredPending;
  return matchedAny ? total : null;
}

function digitsOf(n: number): string[] {
  return Math.trunc(Math.abs(n)).toString().split('');
}

// Task 4 — a BROADER gate than parseCalculatorRequest: catches a calculator-SHAPED utterance
// even when the specific operation isn't in the supported set (e.g. "sinus"), so the caller
// answers "not supported" instead of silently falling through to general conversation. The
// narrower parseCalculatorRequest below decides what's actually executable.
const CALC_TRIGGER = /\bcalculeaz[ăa]\b|\br[ăa]d[ăa]cin[ăa]\b|\bsinus(?:ul)?\b|\bcosinus(?:ul)?\b|\btangent[ăa]\b|\blogaritm(?:ul)?\b|\b(?:cat|c[ăa]t)\s+fac(?:e)?\b/i;
export function looksLikeCalculatorRequest(rawText: string): boolean {
  return CALC_TRIGGER.test((rawText || '').toLowerCase());
}

export type CalcParseResult = { symbols: string[]; spokenOperation: string } | null;

const BINARY_OPS: { re: RegExp; symbol: string; verb: string }[] = [
  { re: /\bplus\b|\badunat(?:e|\s+cu)?\b/i, symbol: '+', verb: 'plus' },
  { re: /\bminus\b|\bsc[ăa]zut(?:e|\s+din)?\b/i, symbol: '-', verb: 'minus' },
  { re: /\b[îi]nmul[țt]it(?:e|\s+cu)?\b|\bori\b/i, symbol: '*', verb: 'ori' },
  { re: /\b[îi]mp[ăa]r[țt]it(?:e|\s+la)?\b/i, symbol: '/', verb: 'împărțit la' },
];

const LEADING_FILLER = /^(cat\s+fac|c[ăa]t\s+fac|c[ăa]t\s+e|c[ăa]t\s+face|calculeaz[ăa])\s*/i;

// Task 1 — returns the ordered button-symbol list + a natural phrase to echo back in the spoken
// answer ("radacina din 9 este 3", not a bare "rezultatul este 3" for every operation).
export function parseCalculatorRequest(rawText: string): CalcParseResult {
  const text = (rawText || '').toLowerCase().trim();
  if (!text) return null;

  const sqrtMatch = /\br[ăa]d[ăa]cin[ăa]\s*(?:p[ăa]tr[ăa]t[ăa])?\s*(?:din|lui|a)\s+(.+)/i.exec(text);
  if (sqrtMatch) {
    const n = wordsToNumber(sqrtMatch[1].trim());
    if (n == null || n < 0) return null;
    return { symbols: ['√', ...digitsOf(n), '='], spokenOperation: `rădăcina din ${n}` };
  }

  for (const { re, symbol, verb } of BINARY_OPS) {
    const m = re.exec(text);
    if (!m) continue;
    const left = text.slice(0, m.index).replace(LEADING_FILLER, '').trim();
    const right = text.slice(m.index + m[0].length).trim();
    const n1 = wordsToNumber(left);
    const n2 = wordsToNumber(right);
    if (n1 == null || n2 == null) return null;
    return {
      symbols: [...digitsOf(n1), symbol, ...digitsOf(n2), '='],
      spokenOperation: `${n1} ${verb} ${n2}`,
    };
  }

  return null;
}

export type CalculatorRunResult = { handled: true; spoken: string };

// Task 2+3+4 glue — calls the native recipe (button presses + result readback), never a second
// execution mechanism. Always returns handled:true (a "not supported"/"failed" spoken answer IS
// handling the request, per the round's own instruction: never silently fall through to general
// conversation for a calculator-shaped utterance).
export async function runCalculatorOperation(rawText: string): Promise<CalculatorRunResult> {
  const parsed = parseCalculatorRequest(rawText);
  if (!parsed) {
    logAudioDiag('CALC_UNSUPPORTED', `text=${JSON.stringify(rawText)}`);
    return { handled: true, spoken: 'Operația asta nu e încă suportată de Calculator.' };
  }

  logAudioDiag('ROUTE', 'decision=command reason=calculator_operation');
  const outcome = await runCalculatorRecipe(parsed.symbols);
  if (!outcome.success) {
    logAudioDiag('CALC_STEP_FAILED', `symbol=${JSON.stringify(parsed.symbols.join(' '))} step=${JSON.stringify(outcome.failedStep ?? '')}`);
    return { handled: true, spoken: `Nu am reușit să calculez ${parsed.spokenOperation}: ${outcome.error ?? 'pas necunoscut'}.` };
  }

  const op = parsed.spokenOperation;
  const spoken = `${op.charAt(0).toUpperCase()}${op.slice(1)} este ${outcome.resultText}.`;
  return { handled: true, spoken };
}
