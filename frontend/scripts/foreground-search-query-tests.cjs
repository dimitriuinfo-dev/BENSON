const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('typescript');
const file = path.resolve(__dirname, '../src/executors/foregroundSearchQuery.ts');
const js = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const sandboxExports = {};
vm.runInNewContext(js, { exports: sandboxExports }, { filename: file });
const extract = sandboxExports.extractForegroundSearchQuery;
const isBare = sandboxExports.isBareForegroundSearchRequest;
assert.equal(extract('caută vremea mâine'), 'vremea mâine');
assert.equal(extract('Cauta-mi farmacie aproape'), 'farmacie aproape');
assert.equal(isBare('Cauta.'), true);
assert.equal(isBare('te rog cauta-mi'), true);
assert.equal(isBare('search for'), true);
assert.equal(isBare('cauta vremea'), false);
assert.equal(extract('search for train times'), 'train times');
assert.equal(extract('suche nach berlin wetter'), 'berlin wetter');
assert.equal(extract('deschide Google'), null);
assert.equal(extract('caută'), null);
assert.equal(extract('caută ' + 'x'.repeat(301)), null);
console.log('PASS foreground search query parsing: Romanian, English, German, non-search and empty/oversized queries.');
