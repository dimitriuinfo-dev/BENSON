// Run with: node scripts/contact-phonetic-regression.cjs
// Loads the production TypeScript directly with local native-boundary stubs. Every contact below
// is synthetic; this script never reads the phone address book, opens an app, sends, or calls.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const Module = require('node:module');
const ts = require('typescript');

const originalResolve = Module._resolveFilename;
Module._extensions['.ts'] = (mod, filename) => {
  const source = fs.readFileSync(filename, 'utf8');
  const output = ts.transpileModule(source, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true },
  }).outputText;
  mod._compile(output, filename);
};
Module._resolveFilename = function (request, parent, isMain, options) {
  if (parent && request.startsWith('.')) {
    const candidate = path.resolve(path.dirname(parent.filename), request);
    if (!path.extname(candidate) && fs.existsSync(`${candidate}.ts`)) return `${candidate}.ts`;
    if (!path.extname(candidate) && fs.existsSync(path.join(candidate, 'index.ts'))) return path.join(candidate, 'index.ts');
  }
  return originalResolve.call(this, request, parent, isMain, options);
};
const originalRequire = Module.prototype.require;
Module.prototype.require = function (id) {
  if (id === '../action-engine' && this.filename?.endsWith(`${path.sep}goalExtractor.ts`)) {
    return require(path.resolve(path.dirname(this.filename), '../action-engine/commandParser.ts'));
  }
  if (id === 'benson-foreground-service') return { logAudioDiag() {} };
  if (id === 'benson-app-registry') return { getInstalledApps: async () => [] };
  if (id === 'expo-contacts') return { Fields: { PhoneNumbers: 1, Emails: 2, Image: 3 } };
  return originalRequire.call(this, id);
};

const frontend = path.resolve(__dirname, '..');
const { resolveContact, searchContacts } = require(path.join(frontend, 'src/core/contacts/contactResolver.ts'));
const { enrichContactAction } = require(path.join(frontend, 'src/core/action-engine/contactActionBridge.ts'));
const { parseCommandToActionRequest } = require(path.join(frontend, 'src/core/action-engine/commandParser.ts'));
const { extractGoals } = require(path.join(frontend, 'src/core/orchestrator/goalExtractor.ts'));
const { matchApps } = require(path.join(frontend, 'lib/appIndex.ts'));
const { createWhatsAppReadDialogue } = require(path.join(frontend, 'src/core/orchestrator/whatsappReadDialogue.ts'));
require(path.join(frontend, 'src/core/contacts/contactResolver.test.ts'));
require(path.join(frontend, 'src/core/contacts/contextResolver.test.ts'));

function contact(id, displayName, extra = {}) {
  return { id, displayName, phoneNumbers: ['+4915112345678'], emailAddresses: [`${id}@example.test`], ...extra };
}

const seeds = [
  'Hannah', 'Michael', 'Georg', 'Rareș', 'Alexandra', 'Christopher', 'Müller', 'Schmidt', 'Ioana', 'Andrei',
  'Jürgen', 'Stefan', 'Johannes', 'Yvonne', 'Friedrich', 'Anja', 'Cătălin', 'Constantin', 'Michaela', 'Alexandra Müller',
];

function variantsFor(name) {
  const out = new Set();
  const add = (value) => { if (value && value.toLowerCase() !== name.toLowerCase()) out.add(value); };
  const s = name;
  add(s.normalize('NFD').replace(/[\u0300-\u036f]/g, ''));
  add(s.replace(/([bcdfghjklmnpqrstvwxyz])/i, '$1h'));
  add(s.replace(/h/ig, ''));
  add(s.replace(/ph/ig, 'f').replace(/sch/ig, 's').replace(/gh(?=[ei])/ig, 'g'));
  add(s.replace(/x/ig, 'ks').replace(/c/ig, 'k'));
  add(s.replace(/^[iyj]/i, (c) => ({ i: 'y', y: 'j', j: 'i' }[c.toLowerCase()] || c)));
  add(s.replace(/(.)\1/ig, '$1'));
  add(s.replace(/e$/i, ''));
  add(s.replace(/a/ig, 'e'));
  add(s.replace(/i/ig, 'e'));
  // Fill any unchanged slots with distinct one-vowel STT substitutions, keeping the test set
  // deterministic and broad across both long and compound names.
  const vowels = [...s.matchAll(/[aeiouäöüăâî]/ig)].map((m) => m.index);
  for (let posIndex = 0; out.size < 10 && posIndex < vowels.length * 6; posIndex++) {
    const pos = vowels[posIndex % vowels.length];
    const replacement = ['a', 'e', 'i', 'o', 'u', 'y'][Math.floor(posIndex / vowels.length)];
    add(`${s.slice(0, pos)}${replacement}${s.slice(pos + 1)}`);
  }
  // A few short names have fewer than ten distinct one-edit forms; two local vowel shifts remain
  // within the resolver's bounded fuzzy threshold and let the suite keep its 200-case floor.
  for (let posIndex = 0; out.size < 10 && posIndex < Math.max(1, vowels.length) * 24; posIndex++) {
    const pos = vowels[posIndex % Math.max(1, vowels.length)];
    const replacement = ['a', 'e', 'i', 'o', 'u', 'y'][Math.floor(posIndex / Math.max(1, vowels.length)) % 6];
    if (pos !== undefined) add(`${s.slice(0, pos)}${replacement}${s.slice(pos + 1)}`);
    if (out.size < 10 && pos !== undefined) {
      const next = vowels[(posIndex + 1) % vowels.length];
      if (next !== undefined && next !== pos) {
        const second = ['a', 'e', 'i', 'o', 'u', 'y'][(posIndex + 2) % 6];
        add(`${s.slice(0, pos)}${replacement}${s.slice(pos + 1, next)}${second}${s.slice(next + 1)}`);
      }
    }
  }
  for (let pos = 1; out.size < 10 && pos < s.length - 1; pos++) add(`${s.slice(0, pos)}${s.slice(pos + 1)}`);
  return [...out].slice(0, 10);
}

let generated = 0;
let resolved = 0;
let ambiguous = 0;
for (const [i, name] of seeds.entries()) {
  const target = contact(`fake-${i}`, name);
  const variants = variantsFor(name);
  assert.equal(variants.length, 10, `expected 10 distinct generated variants for ${name}`);
  for (const variant of variants) {
    const result = resolveContact({ rawName: variant, preferredChannel: 'whatsapp', contacts: [target] });
    assert.ok(result.status === 'resolved' && result.contact.id === target.id || result.status === 'ambiguous',
      `${name} <- ${variant}: expected target match or safe ambiguity, got ${result.status}`);
    if (result.status === 'resolved') resolved++; else ambiguous++;
    generated++;
  }
}
assert.ok(generated >= 200, `generated ${generated}, expected at least 200`);

const explicitExamples = {
  Hannah: ['Hana', 'Hanna', 'Ana', 'HAnna'],
  Michael: ['Maichel', 'Maikal', 'Michel'],
  Georg: ['George', 'Gheorg', 'Ghiog'],
  Rareș: ['Rares', 'Raresh', 'Rarăș'],
  Alexandra: ['Aleksandra', 'Alexsandra'],
  Christopher: ['Cristofer', 'Kristopher'],
  Müller: ['Muller', 'Müler', 'Mueller'],
  Schmidt: ['Schmitt', 'Șmit', 'Schmid'],
  Ioana: ['Yoana', 'Joana', 'Iona'],
  Andrei: ['Andrea', 'Andre', 'Andrey'],
};
let explicitCount = 0;
for (const [name, variants] of Object.entries(explicitExamples)) {
  for (const variant of variants) {
    const target = contact(`explicit-${explicitCount}`, name);
    const result = resolveContact({ rawName: variant, preferredChannel: 'phone', contacts: [target] });
    assert.ok(result.status === 'resolved' && result.contact.id === target.id || result.status === 'ambiguous',
      `${name} <- ${variant}: expected match or safe ambiguity, got ${result.status}`);
    explicitCount++;
  }
}

// Similar real contacts must never silently choose one from a phonetic collision.
for (const query of ['Ana', 'Hana', 'Hanna']) {
  const result = resolveContact({ rawName: query, preferredChannel: 'whatsapp', contacts: [contact('hannah', 'Hannah'), contact('ana', 'Ana'), contact('hanna', 'Hanna')] });
  assert.equal(result.status, 'ambiguous', `${query} must ask when similar contacts coexist`);
}

const actionCases = [
  ['Trimite mesaj lui Hannah pe WhatsApp.', 'MESSAGE_CONTACT', 'Hannah', 'whatsapp'],
  ['Fă videocall cu Hana.', 'OPEN_WHATSAPP_CONTACT', 'Hana', 'whatsapp'],
  ['Sun-o pe Hanna.', 'CALL_CONTACT', 'Hanna', 'phone'],
  ['Trimite email lui Michael.', 'EMAIL_ACTION', undefined, 'email'],
];
for (const [phrase, intent, expectedName, channel] of actionCases) {
  const request = parseCommandToActionRequest(phrase, 'voice');
  assert.equal(request.intent, intent, `${phrase}: intent`);
  assert.equal(request.requiresConfirmation, true, `${phrase}: external action confirmation`);
  if (expectedName) assert.equal(request.parameters.contactName, expectedName, `${phrase}: parsed name`);
  const bridgeRequest = { ...request, parameters: { ...request.parameters, ...(expectedName ? {} : { recipientName: 'Michaël' }) } };
  const mapped = enrichContactAction(bridgeRequest, [contact('target', expectedName ?? 'Michael', { emailAddresses: ['michael@example.test'] })]);
  assert.equal(mapped.status, 'resolved', `${phrase}: central resolver ${channel}`);
  if (channel === 'email') assert.equal(mapped.request.parameters.recipientEmail, 'michael@example.test');
}
const readRequest = {
  id: 'synthetic-read', source: 'voice', rawText: 'Citește mesajele de la Hana', intent: 'READ_MESSAGES',
  parameters: { contactName: 'Hana', channel: 'whatsapp' }, riskLevel: 'LOW', requiresConfirmation: false, createdAt: 0,
};
const readMapped = enrichContactAction(readRequest, [contact('hannah-read', 'Hannah')]);
assert.equal(readMapped.status, 'resolved', 'WhatsApp read uses the shared resolver for Hana -> Hannah');
assert.equal(readMapped.request.parameters.contactName, 'Hannah');
const openChat = parseCommandToActionRequest('Deschide conversația cu Hana pe WhatsApp.', 'voice');
assert.equal(openChat.intent, 'OPEN_WHATSAPP_CONTACT', 'explicit WhatsApp chat open is distinct from an address-book search');
const openChatMapped = enrichContactAction(openChat, [contact('hannah-chat', 'Hannah')]);
assert.equal(openChatMapped.status, 'resolved', 'WhatsApp chat opening resolves Hana -> Hannah before app navigation');
assert.equal(openChatMapped.request.parameters.contactName, 'Hannah');
const missionGoals = extractGoals('Deschide conversația cu Hana pe WhatsApp.', 'Deschide conversația cu Hana pe WhatsApp.');
assert.equal(missionGoals[0]?.sourceIntent, 'OPEN_WHATSAPP_CONTACT', 'full mission goal extraction preserves WhatsApp open intent');
assert.equal(missionGoals[0]?.entities.contact, 'Hana', 'full mission goal extraction preserves spoken contact for central resolution');
const spokenLookup = parseCommandToActionRequest('Caută contactul Hana.', 'voice');
assert.equal(spokenLookup.intent, 'CONTACTS_SEARCH', 'natural Romanian contact lookup is parsed as a person search');
assert.equal(searchContacts(spokenLookup.parameters.contactName, [contact('hannah-lookup', 'Hannah')])[0]?.displayName, 'Hannah', 'spoken person lookup uses central fuzzy resolver');
const noEmail = enrichContactAction({ id: 'no-email', source: 'voice', rawText: 'email Hannah', intent: 'EMAIL_ACTION', parameters: { recipientName: 'Hannah' }, riskLevel: 'MEDIUM', requiresConfirmation: true, createdAt: 0 }, [contact('no-mail', 'Hannah', { emailAddresses: [] })]);
assert.equal(noEmail.status, 'missing_email', 'email requires a real address-book email field');

const apps = [
  { appName: 'YouTube', packageName: 'com.google.android.youtube' },
  { appName: 'Amazon Shopping', packageName: 'com.amazon.mShop.android.shopping' },
  { appName: 'Waze', packageName: 'com.waze' },
  { appName: 'WhatsApp', packageName: 'com.whatsapp' },
];
for (const [query, expected] of [['You Tube', 'YouTube'], ['Amzon Shopping', 'Amazon Shopping'], ['Waz', 'Waze'], ['Whatsap', 'WhatsApp']]) {
  const match = matchApps(query, apps);
  assert.ok(match.kind !== 'none', `${query}: should find a plausible app`);
  const resultNames = match.kind === 'multiple' ? match.apps.map((a) => a.appName) : [match.app.appName];
  assert.ok(resultNames.includes(expected), `${query}: expected ${expected}, got ${resultNames.join(', ')}`);
}

const readDialogue = createWhatsAppReadDialogue({
  readChat: async (spoken) => {
    const match = resolveContact({ rawName: spoken, preferredChannel: 'whatsapp', contacts: [contact('read-hannah', 'Hannah')] });
    return match.status === 'resolved'
      ? { ok: true, displayName: match.contact.displayName, messages: [] }
      : { ok: false, reason: 'not_found' };
  },
  notifications: () => '[]', formatNotifications: () => ({ text: '', spokenCount: 0 }), formatChat: (name) => `chat:${name}`,
});
readDialogue('Citește mesajele de la Hana.').then((r) => assert.equal(r.message, 'chat:Hannah', 'read dialogue must resolve the STT name through the central resolver'));

const appCommands = [
  ['Deschide YouTube.', 'OPEN_APP', 'youtube'],
  ['Deschide Amazon Shopping.', 'OPEN_APP', 'Amazon Shopping'],
  ['Navighează cu Waze.', 'OPEN_WAZE', undefined],
];
for (const [phrase, intent, appName] of appCommands) {
  const parsed = parseCommandToActionRequest(phrase, 'voice');
  assert.equal(parsed.intent, intent, `${phrase}: app/command intent`);
  if (appName) assert.equal(parsed.parameters.appName, appName, `${phrase}: app target`);
}

const misheardWhatsAppChat = parseCommandToActionRequest("Open the what's up chat with Baby.", 'voice');
assert.equal(misheardWhatsAppChat.intent, 'OPEN_WHATSAPP_CONTACT', 'WhatsApp STT mishear must stay in the contact route');
assert.equal(misheardWhatsAppChat.parameters.contactName, 'Baby', 'misheard WhatsApp chat must preserve the contact');
const deepSeekPackageMatch = matchApps("the what's up chat with baby", [
  { appName: 'DeepSeek', packageName: 'com.deepseek.chat' },
]);
assert.equal(deepSeekPackageMatch.kind, 'none', 'generic chat token must never launch DeepSeek for a WhatsApp request');
assert.equal(matchApps('chat', [{ appName: 'DeepSeek', packageName: 'com.deepseek.chat' }]).kind, 'none', 'generic package suffix must not resolve as an app name');
assert.notEqual(matchApps('DeepSeek', [{ appName: 'DeepSeek', packageName: 'com.deepseek.chat' }]).kind, 'none', 'real app label remains resolvable');

console.log(`PASS contact phonetics: ${generated} generated + ${explicitCount} requested variants (${resolved} generated resolved, ${ambiguous} generated safely ambiguous); similar-contact ambiguity: 3/3; message/video/phone/email/read resolver: 5/5; app-name slips: 4/4.`);
