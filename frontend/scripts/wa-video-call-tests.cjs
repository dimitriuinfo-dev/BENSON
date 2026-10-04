const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('typescript');

const file = path.resolve(__dirname, '../src/core/action-engine/commandParser.ts');
const source = fs.readFileSync(file, 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const moduleExports = {};
const mocks = {
  './actionRequest': { createActionRequest: (request) => request },
  './transcriptNormalizer': { normalizeTranscript: (text) => text.trim() },
  './discourseCleaner': { cleanDiscourse: (text) => text },
  '../../../lib/engines/actionSanity': { looksLikePersonName: (name) => ({ ok: name.trim().length > 1 }) },
};
vm.runInNewContext(compiled, {
  exports: moduleExports,
  require: (id) => mocks[id] ?? {},
  RegExp,
  String,
  Object,
  Array,
  console,
}, { filename: file });

const parse = moduleExports.parseCommandToActionRequest;
const normalize = (value) => value.toLowerCase().normalize('NFD').replace(/[\u0300-\u036f]/g, '').replace(/[^a-z0-9 ]/g, ' ').replace(/\s+/g, ' ').trim();

for (const phrase of [
  'sun-o pe Hannah pe WhatsApp video',
  'sună video pe Hannah pe WhatsApp',
  'Videoanruf mit Hannah auf WhatsApp',
]) {
  const result = parse(phrase, 'voice');
  assert.equal(result.intent, 'OPEN_WHATSAPP_CONTACT', phrase);
  assert.equal(result.parameters.mode, 'video_call', phrase);
  assert.equal(normalize(result.parameters.contactName), 'hannah', `${phrase}: ${result.parameters.contactName}`);
}

const audio = parse('sună pe Hannah pe WhatsApp', 'voice');
assert.equal(audio.intent, 'OPEN_WHATSAPP_CONTACT');
assert.equal(audio.parameters.mode, 'voice_call');
assert.equal(normalize(audio.parameters.contactName), 'hannah');

console.log('PASS: WhatsApp video/audio intent, mode and recipient routing');
