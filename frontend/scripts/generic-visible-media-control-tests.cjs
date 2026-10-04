const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('typescript');
const file = path.resolve(__dirname, '../src/executors/genericVisibleMediaControl.ts');
const js = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const sandboxExports = {};
vm.runInNewContext(js, { exports: sandboxExports }, { filename: file });
const extract = sandboxExports.extractVisibleMediaControlIntent;
assert.deepEqual(JSON.parse(JSON.stringify(extract('apasă săgeata live sau play'))), {
  action: 'play', labels: ['live', 'play', 'redare', 'reda', 'continua', 'continue', 'resume'],
});
assert.equal(extract('Apasă play')?.action, 'play');
assert.equal(extract('apasă continue')?.action, 'play');
assert.equal(extract('apasă redare')?.action, 'play');
assert.equal(extract('apasă butonul pauză')?.action, 'pause');
assert.equal(extract('apasă săgeata următoarea')?.action, 'next');
assert.equal(extract('derulează mai jos'), null);
assert.equal(extract('deschide Magic FM'), null);
console.log('PASS visible media-control intent: live/play alternatives, Romanian diacritics, other transport actions, and unrelated commands.');
