const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

const source = path.join(__dirname, '../src/core/contacts/contactResolver.ts');
const js = ts.transpileModule(fs.readFileSync(source, 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const loaded = new Module(source, module);
loaded.filename = source;
loaded.paths = module.paths;
const originalLoad = Module._load;
Module._load = function(request, parent, isMain) {
  if (request === 'benson-foreground-service') return { logAudioDiag(...args) { console.log(...args); } };
  return originalLoad.call(this, request, parent, isMain);
};
loaded._compile(js, source);
Module._load = originalLoad;
const { resolveContact } = loaded.exports;
const c = (id, displayName) => ({ id, displayName, phoneNumbers: ['+40712345678'] });

const distractors = ['Alica Barbu', 'BASS', 'Bebe Rom', 'Boby Razvan Hulea', 'Bub\u0103 Alex', 'Gabi Nicodin', 'Gabi Ritivoi', 'Gabi Ziegler Elektrotechnik', 'Grado Piper Beach Bar', 'HR Pietkun Grossmann Bau', 'Hachinger Bau', 'Herr Widy Bali', 'List Bau M\u00fcnchen', 'Rasinger List Bau', 'Sebi Barbu', 'Universale Bau Moritz'];
const baby = resolveContact({ rawName: 'baby', preferredChannel: 'whatsapp', contacts: [c('baby', 'Baby'), ...distractors.map((n, i) => c(`d${i}`, n))] });
assert.equal(baby.status, 'resolved', `exact Baby should beat weak fuzzy collisions: ${baby.status}`);
assert.equal(baby.contact.id, 'baby');

const sameName = resolveContact({ rawName: 'Baby', preferredChannel: 'whatsapp', contacts: [c('1', 'Baby'), c('2', 'Baby Work')] });
assert.equal(sameName.status, 'ambiguous', 'a real same-name token collision must remain ambiguous');

const phonetic = resolveContact({ rawName: 'Hana', preferredChannel: 'whatsapp', contacts: [c('hannah', 'Hannah'), c('hana', 'Hana')] });
assert.equal(phonetic.status, 'ambiguous', 'Hana/Hannah must remain a genuine ambiguity');
console.log('PASS exact Baby ignores weak fuzzy contacts; true duplicate and Hana/Hannah ambiguity still stop safely.');

