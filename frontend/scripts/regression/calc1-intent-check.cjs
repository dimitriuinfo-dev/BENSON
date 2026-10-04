const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');

const sourcePath = path.resolve(__dirname, '../../lib/tools/toolRegistry.ts');
const source = fs.readFileSync(sourcePath, 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const sandbox = { exports: {}, require: (name) => {
  if (name === 'benson-accessibility') return { runCalculatorRecipe: async () => ({ success: false }) };
  if (name === 'benson-foreground-service') return { logAudioDiag: () => {} };
  throw new Error(`Unexpected runtime import: ${name}`);
} };
vm.runInNewContext(compiled, sandbox, { filename: sourcePath });
const { looksLikeCalculatorRequest, parseCalculatorRequest } = sandbox.exports;

assert.equal(looksLikeCalculatorRequest('Calculează cinci plus trei'), true);
assert.equal(looksLikeCalculatorRequest('Calculeaza cinci plus trei'), true);
assert.deepEqual(JSON.parse(JSON.stringify(parseCalculatorRequest('Calculează cinci plus trei'))), {
  symbols: ['5', '+', '3', '='], spokenOperation: '5 plus 3',
});
assert.deepEqual(JSON.parse(JSON.stringify(parseCalculatorRequest('cinci plus trei'))), {
  symbols: ['5', '+', '3', '='], spokenOperation: '5 plus 3',
});
console.log('CALC1 intent: Romanian diacritic routing and both expression parses passed.');
