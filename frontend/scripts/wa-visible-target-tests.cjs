const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('typescript');

const file = path.resolve(__dirname, '../src/core/mission/tools/whatsappVisibleTarget.ts');
const source = fs.readFileSync(file, 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const moduleExports = {};
vm.runInNewContext(compiled, { exports: moduleExports, require }, { filename: file });
const resolve = moduleExports.resolveVisibleWhatsAppTarget;
const wa = (...nodes) => ({ packageName: 'com.whatsapp', nodes });
const row = (text, top, contentDescription = '') => ({
  text, contentDescription, clickable: true, editable: false,
  bounds: { top, bottom: top + 56 },
});
const label = (text, top, bottom = top + 24) => ({
  text, contentDescription: '', viewId: 'com.whatsapp:id/contact_name', clickable: false, editable: false,
  bounds: { left: 120, top, right: 360, bottom },
});
const clickableRow = (top, bottom = top + 56) => ({
  text: '', contentDescription: '', viewId: 'com.whatsapp:id/contact_row', clickable: true, editable: false,
  bounds: { left: 24, top, right: 1040, bottom },
});

const result = (query, snapshot) => JSON.parse(JSON.stringify(resolve(query, snapshot)));
assert.deepEqual(result('Baby', wa(row('', 100, 'Baby, 2 unread messages'))), { status: 'resolved', title: 'Baby' });
assert.deepEqual(result('Baby', wa(label('Baby', 116, 140), clickableRow(100))), { status: 'resolved', title: 'Baby' });
assert.deepEqual(result('Hana', wa(row('Hannah', 100))), { status: 'not_found' });
assert.deepEqual(result('Baby', wa(row('Baby', 100), row('Baby', 180))), { status: 'ambiguous' });
assert.deepEqual(result('Baby', wa(label('Baby', 116, 140))), { status: 'not_found' });
assert.deepEqual(result('Baby', wa(label('Baby', 116, 140), { ...clickableRow(100), bounds: { left: 0, top: 0, right: 1080, bottom: 2400 } })), { status: 'not_found' });
assert.deepEqual(result('Baby', { packageName: 'com.benson.butler', nodes: [row('Baby', 100)] }), { status: 'not_found' });
console.log('PASS: unique exact WhatsApp result only; fuzzy, duplicate, non-clickable and wrong-app targets fail closed.');
