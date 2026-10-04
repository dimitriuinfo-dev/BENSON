const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

const source = path.join(__dirname, '../src/core/mission/whatsappDraftEdit.ts');
const js = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const loaded = new Module(source, module);
loaded.filename = source;
loaded.paths = module.paths;
loaded._compile(js, source);
const { editWhatsAppDraft } = loaded.exports;
const diacritics = 'Ajung \u00een zece minute';

const cases = [
  ['append preserves Romanian diacritics', editWhatsAppDraft(diacritics, 'Adaug\u0103: mul\u021bumesc'), { kind: 'updated', operation: 'append', message: 'Ajung \u00een zece minute mul\u021bumesc' }],
  ['replace a unique fragment', editWhatsAppDraft(diacritics, '\u00cenlocuie\u0219te zece cu cinci'), { kind: 'updated', operation: 'replace_fragment', message: 'Ajung \u00een cinci minute' }],
  ['replace full draft', editWhatsAppDraft(diacritics, 'Nu, scrie \u00een schimb: ajung \u00een cinci minute'), { kind: 'updated', operation: 'replace_all', message: 'ajung \u00een cinci minute' }],
  ['missing fragment is fail-closed', editWhatsAppDraft(diacritics, '\u00cenlocuie\u0219te m\u00e2ine cu azi'), { kind: 'clarify', reason: 'fragment_missing' }],
  ['repeated fragment is fail-closed', editWhatsAppDraft('azi \u0219i azi', '\u00cenlocuie\u0219te azi cu m\u00e2ine'), { kind: 'clarify', reason: 'fragment_repeated' }],
  ['confirmation is not an edit', editWhatsAppDraft(diacritics, 'Da, trimite'), { kind: 'none' }],
];
for (const [name, actual, expected] of cases) {
  assert.deepEqual(actual, expected, name);
  console.log(`PASS ${name}`);
}
console.log(`${cases.length} WhatsApp draft edit cases passed; no native actions or network.`);
