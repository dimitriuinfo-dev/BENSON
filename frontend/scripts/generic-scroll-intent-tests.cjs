const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('typescript');
const file = path.resolve(__dirname, '../src/executors/genericScrollIntent.ts');
const js = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const sandboxExports = {};
vm.runInNewContext(js, { exports: sandboxExports }, { filename: file });
const extract = sandboxExports.extractGenericScrollDirection;
assert.equal(extract('vreau să deruleze mai jos, să văd și următoarele'), 'forward');
assert.equal(extract('arată-mi ce-i mai jos pe pagină'), 'forward');
assert.equal(extract('scroll down'), 'forward');
assert.equal(extract('scrolle nach unten'), 'forward');
assert.equal(extract('derulează mai sus'), 'backward');
assert.equal(extract('scroll up'), 'backward');
assert.equal(extract('deruleaza spre dreapta'), 'right');
assert.equal(extract('mai la stanga'), 'left');
assert.equal(extract('scroll right'), 'right');
assert.equal(extract('deschide primul rezultat'), null);
console.log('PASS generic scroll intent: Romanian down/up, English, German, and unrelated selection commands.');
