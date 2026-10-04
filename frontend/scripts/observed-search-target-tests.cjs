const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('typescript');

const file = path.resolve(__dirname, '../src/core/accessibility/observedSearchTarget.ts');
const js = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const sandboxExports = {};
vm.runInNewContext(js, { exports: sandboxExports }, { filename: file });
const resolve = sandboxExports.resolveObservedSearchTarget;
const screen = (packageName, ...nodes) => ({ packageName, nodes });
const n = (fields) => ({ id: 'n1', className: 'android.widget.ImageButton', clickable: true,
  bounds: { left: 900, top: 80, right: 980, bottom: 160 }, ...fields });

assert.deepEqual(JSON.parse(JSON.stringify(resolve(screen('com.google.android.youtube',
  n({ contentDescription: 'Search' }),
  n({ id: 'bottom', className: 'android.widget.TextView', clickable: false, text: 'Search', bounds: { left: 0, top: 2100, right: 80, bottom: 2180 } }),
), 'com.google.android.youtube'))),
{ status: 'resolved', kind: 'button', viewId: null, label: 'Search' });

assert.equal(resolve(screen('com.example.app', n({ viewId: 'com.example.app:id/menu_item_search' })), 'com.example.app').status, 'resolved');
assert.equal(resolve(screen('com.example.app', n({ viewId: 'com.example.app:id/search_src_text', editable: true })), 'com.example.app').kind, 'input');
assert.equal(resolve(screen('com.example.app', n({ hintText: 'Suche', editable: true })), 'com.example.app').kind, 'input');
assert.equal(resolve(screen('com.example.app', n({ text: 'Suchfeld' })), 'com.example.app').status, 'resolved');
assert.equal(resolve(screen('com.example.app', n({ text: 'Search results' })), 'com.example.app').status, 'not_found');
assert.equal(resolve(screen('com.example.app', n({ text: 'Search' }), n({ id: 'n2', text: 'Search', bounds: { left: 100, top: 80, right: 180, bottom: 160 } })), 'com.example.app').status, 'ambiguous');
assert.equal(resolve(screen('com.other', n({ text: 'Search' })), 'com.example.app').status, 'not_found');
assert.equal(resolve(screen('com.example.app', n({ text: '', contentDescription: '' })), 'com.example.app').status, 'not_found');
console.log('PASS observed search target: YouTube top-control priority, search IDs, localized labels, exact screen package, ambiguous controls, and unlabeled/irrelevant nodes fail closed.');
