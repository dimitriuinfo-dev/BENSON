// Zero-dependency Node ESM loader hook: lets `node --experimental-strip-types --test` resolve
// this project's extensionless relative TS imports (its normal, Metro/tsc-resolved style —
// deliberately NOT changed in source files just to suit a standalone test runner). Tries the
// specifier as given first, then with .ts / .tsx appended. Test-tooling only; never imported by
// app code.
import { existsSync } from 'node:fs';
import { fileURLToPath, pathToFileURL } from 'node:url';
import path from 'node:path';

export async function resolve(specifier, context, nextResolve) {
  try {
    return await nextResolve(specifier, context);
  } catch (err) {
    if (specifier.startsWith('.') && context.parentURL) {
      const base = fileURLToPath(new URL(specifier, context.parentURL));
      for (const ext of ['.ts', '.tsx', '/index.ts']) {
        if (existsSync(base + ext)) {
          return nextResolve(pathToFileURL(base + ext).href, context);
        }
      }
    }
    throw err;
  }
}
