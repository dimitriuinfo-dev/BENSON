const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const ts = require('typescript');

const file = path.resolve(__dirname, '../src/core/mission/tools/whatsappTool.ts');
const source = fs.readFileSync(file, 'utf8');
const compiled = ts.transpileModule(source, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const moduleExports = {};
const emptyModule = new Proxy({}, { get: () => () => undefined });
vm.runInNewContext(compiled, {
  exports: moduleExports,
  require: () => emptyModule,
  console,
  setTimeout,
  Date,
  JSON,
  Promise,
  String,
  RegExp,
  Array,
  Set,
  Map,
}, { filename: file });

const safeName = moduleExports.safeWhatsAppDisplayName;
const hasVideoState = moduleExports.hasObservedVideoCallState;
assert.equal(safeName("David's Papa Jochen (+491726472274)"), "David's Papa Jochen");
assert.equal(safeName('Baby +4917621234567'), 'Baby');
assert.equal(safeName('Baby 4917621234567'), 'Baby');
assert.equal(safeName('Hannah'), 'Hannah');
assert.equal(hasVideoState([{ viewId: 'com.whatsapp:id/call_screen' }, { viewId: 'com.whatsapp:id/end_call_button' }]), false);
assert.equal(hasVideoState([{ viewId: 'com.whatsapp:id/menuitem_video_call' }]), false);
assert.equal(hasVideoState([{ viewId: 'com.whatsapp:id/camera_button' }, { viewId: 'com.whatsapp:id/end_call_button' }]), false);
assert.equal(hasVideoState([{ viewId: 'com.whatsapp:id/switch_camera' }]), false);
assert.equal(hasVideoState([{ viewId: 'com.whatsapp:id/switch_camera' }, { viewId: 'com.whatsapp:id/end_call_button' }]), true);
assert.match(source, /WA_CALL_POST_TAP_OBSERVE/);
assert.match(source, /mode === 'video_call' && !videoState/);
assert.match(source, /const nameMatches = !!lastName && normNameLoose\(lastName\) === normNameLoose\(expectedTitle\)/);
assert.match(source, /backFromObservedMissionScreen/);
assert.match(source, /isObservedCallScreen\(beforeIds\)/);

const missionFile = path.resolve(__dirname, '../src/core/mission/missionExecutor.ts');
const missionSource = fs.readFileSync(missionFile, 'utf8');
assert.match(missionSource, /lastAction:\s*\{/);
assert.match(missionSource, /mission\.lastAction\.observedPackage/);
assert.match(missionSource, /MISSION_CONTEXT_CORRECTION/);

const guardFile = path.resolve(__dirname, '../src/core/mission/missionUtteranceGuards.ts');
const guardSource = fs.readFileSync(guardFile, 'utf8');
const guardCompiled = ts.transpileModule(guardSource, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020 },
}).outputText;
const guardExports = {};
vm.runInNewContext(guardCompiled, { exports: guardExports, RegExp, String }, { filename: guardFile });
const isCorrection = guardExports.isWhatsAppRecipientCorrection;
for (const phrase of ['contactul greșit', 'contactul este incorect', 'wrong contact', 'nu este Baby', 'falscher Kontakt']) {
  assert.equal(isCorrection(phrase), true, phrase);
}
for (const phrase of ['da', 'sună Baby pe WhatsApp', 'scrie-i lui Baby', 'anulează']) {
  assert.equal(isCorrection(phrase), false, phrase);
}
const appSource = fs.readFileSync(path.resolve(__dirname, '../app/index.tsx'), 'utf8');
assert.match(appSource, /WA_CALL_CONFIRM_INVALIDATED/);
assert.ok(appSource.indexOf('isWhatsAppRecipientCorrection(msg)') < appSource.indexOf('const verdict = isAwaitingMessageBody'));

console.log('PASS: WhatsApp video post-tap observation, number-safe labels, mission-scoped back, and confirmation invalidation contracts');
