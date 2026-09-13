/**
 * ROUND_WA_GOVERNANCE_ROUTING — parser + routing tests.
 *
 * No jest/vitest in this repo. This is a standalone script: it imports the (pure) deterministic
 * parser directly and mirrors the production routing decision
 * (missionOrchestrator.toGovernedCall + missionExecutor.maybeRunWhatsAppWritePhaseA), then
 * asserts the cases from the round spec.
 *
 * Run:  npx tsc scripts/wa-routing-tests.ts --outDir <tmp> --module commonjs --target es2020 \
 *         --moduleResolution node --esModuleInterop --skipLibCheck
 *       node <tmp>/scripts/wa-routing-tests.js
 */

import { parseCommandToActionRequest } from '../src/core/action-engine/commandParser';

type Route = 'prepareMessage' | 'openContact' | 'placeCall' | 'MESSAGE_BODY_MISSING' | string;

// Faithful mirror of the NEW routing: toGovernedCall (PREPARE_MESSAGE branch) + the empty-body
// guard in maybeRunWhatsAppWritePhaseA. A message intent NEVER becomes openContact.
function routeWhatsApp(intent: string, params: Record<string, unknown>): Route {
  const message = typeof params.message === 'string' ? params.message.trim() : '';
  const mode = typeof params.mode === 'string' ? params.mode : '';
  if (intent === 'CALL_CONTACT') return 'placeCall';
  if (intent === 'OPEN_WHATSAPP_CONTACT' && mode === 'voice_call') return 'placeCall';
  if (intent === 'OPEN_WHATSAPP_CONTACT') return 'openContact';
  if (intent === 'MESSAGE_CONTACT') return message ? 'prepareMessage' : 'MESSAGE_BODY_MISSING';
  return `other:${intent}`;
}

let failures = 0;
function check(label: string, cond: boolean, detail: string): void {
  const tag = cond ? 'PASS' : 'FAIL';
  if (!cond) failures += 1;
  console.log(`[${tag}] ${label}${cond ? '' : `  — ${detail}`}`);
}

function run(utterance: string) {
  const req = parseCommandToActionRequest(utterance, 'voice');
  const p = req.parameters as Record<string, unknown>;
  const contact = typeof p.contactName === 'string' ? p.contactName : '';
  const message = typeof p.message === 'string' ? p.message : '';
  const route = routeWhatsApp(req.intent, p);
  return { intent: req.intent, contact, message, route };
}

const norm = (s: string) =>
  s.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim();

console.log('=== ROUND_WA_GOVERNANCE_ROUTING — parser/routing ===\n');

// A — the acceptance command
{
  const r = run('scrie-i lui Hannah pe WhatsApp că ajung mai târziu');
  console.log('A:', JSON.stringify(r));
  check('A.intent = MESSAGE_CONTACT', r.intent === 'MESSAGE_CONTACT', r.intent);
  check('A.contact = Hannah', norm(r.contact) === 'hannah', r.contact);
  check('A.message = "ajung mai târziu"', norm(r.message) === norm('ajung mai târziu'), r.message);
  check('A.route = prepareMessage', r.route === 'prepareMessage', r.route);
  check('A.route != openContact', r.route !== 'openContact', r.route);
}

// B — explicit open-chat
{
  const r = run('deschide conversația cu Hannah pe WhatsApp');
  console.log('B:', JSON.stringify(r));
  check('B.intent = OPEN_WHATSAPP_CONTACT', r.intent === 'OPEN_WHATSAPP_CONTACT', r.intent);
  check('B.contact = Hannah', norm(r.contact) === 'hannah', r.contact);
  check('B.route = openContact', r.route === 'openContact', r.route);
}

// C — message verb, no body → MESSAGE_BODY_MISSING, NOT openContact
{
  const r = run('scrie-i lui Hannah pe WhatsApp');
  console.log('C:', JSON.stringify(r));
  check('C.intent = MESSAGE_CONTACT', r.intent === 'MESSAGE_CONTACT', r.intent);
  check('C.message empty', norm(r.message) === '', r.message);
  check('C.route = MESSAGE_BODY_MISSING', r.route === 'MESSAGE_BODY_MISSING', r.route);
  check('C.route != openContact', r.route !== 'openContact', r.route);
}

// D — multi-word body with punctuation
{
  const r = run('scrie-i lui Hannah pe WhatsApp că ajung în 10 minute, adu pâine și lapte');
  console.log('D:', JSON.stringify(r));
  check('D.intent = MESSAGE_CONTACT', r.intent === 'MESSAGE_CONTACT', r.intent);
  check('D.contact = Hannah', norm(r.contact) === 'hannah', r.contact);
  check('D.message keeps the body', norm(r.message).startsWith('ajung in 10 minute'), r.message);
  check('D.message keeps content after the comma', norm(r.message).includes('adu paine'), r.message);
  check('D.route = prepareMessage', r.route === 'prepareMessage', r.route);
}

// D2 — colon form
{
  const r = run('scrie-i lui Hannah pe WhatsApp: ne vedem la 8');
  console.log('D2:', JSON.stringify(r));
  check('D2.intent = MESSAGE_CONTACT', r.intent === 'MESSAGE_CONTACT', r.intent);
  check('D2.message = "ne vedem la 8"', norm(r.message) === 'ne vedem la 8', r.message);
  check('D2.route = prepareMessage', r.route === 'prepareMessage', r.route);
}

// D3 — channel-less message ("scrie-i lui X că Y")
{
  const r = run('scrie-i lui Baby că vin acum');
  console.log('D3:', JSON.stringify(r));
  check('D3.intent = MESSAGE_CONTACT', r.intent === 'MESSAGE_CONTACT', r.intent);
  check('D3.contact = Baby', norm(r.contact) === 'baby', r.contact);
  check('D3.contact has no "pe whatsapp"', !norm(r.contact).includes('whatsapp'), r.contact);
  check('D3.message = "vin acum"', norm(r.message) === 'vin acum', r.message);
  check('D3.route = prepareMessage', r.route === 'prepareMessage', r.route);
}

// E — WhatsApp voice call unchanged
{
  const r = run('sună pe Hannah pe WhatsApp');
  console.log('E:', JSON.stringify(r));
  check('E.route = placeCall', r.route === 'placeCall', r.route);
  check('E.contact = Hannah', norm(r.contact) === 'hannah', r.contact);
}

// F — old open/search/confirm message fallback is unreachable for valid message intents
{
  const forms = [
    'scrie-i lui Hannah pe WhatsApp că ajung mai târziu',
    'scrie-i lui Hannah pe WhatsApp că ajung în 10 minute, adu pâine și lapte',
    'scrie-i lui Hannah pe WhatsApp: ne vedem la 8',
    'write Hannah on WhatsApp that I am late',
  ];
  for (const f of forms) {
    const r = run(f);
    check(`F: "${f.slice(0, 45)}…" never routes to openContact`, r.route !== 'openContact', `${r.intent}/${r.route}`);
    check(`F: "${f.slice(0, 45)}…" intent != OPEN_WHATSAPP_CONTACT`, r.intent !== 'OPEN_WHATSAPP_CONTACT', r.intent);
  }
}

// G — ROUND_WA_WRITE_MESSAGE_PAYLOAD: a wake-word bleed must NEVER become the message body.
{
  const r = run('scrie-i lui Hannah pe WhatsApp că Benzim');
  console.log('G:', JSON.stringify(r));
  check('G.intent = MESSAGE_CONTACT', r.intent === 'MESSAGE_CONTACT', r.intent);
  check('G.contact = Hannah', norm(r.contact) === 'hannah', r.contact);
  check('G.message is EMPTY (wake-word bleed stripped)', norm(r.message) === '', r.message);
  check('G.route = MESSAGE_BODY_MISSING', r.route === 'MESSAGE_BODY_MISSING', r.route);
  check('G.message != contact', norm(r.message) !== norm(r.contact), r.message);
}
// G2 — wake-word bleed AFTER a real body → real body kept, wake token removed.
{
  const r = run('scrie-i lui Hannah pe WhatsApp că ajung mai târziu Benson');
  console.log('G2:', JSON.stringify(r));
  check('G2.message keeps the real body', norm(r.message).startsWith('ajung mai t'), r.message);
  check('G2.message has no wake token', !/benson|benzin|benzim/i.test(r.message), r.message);
  check('G2.route = prepareMessage', r.route === 'prepareMessage', r.route);
}
// G3 — body that is just the recipient echoed back → empty → MESSAGE_BODY_MISSING.
{
  const r = run('scrie-i lui Hannah pe WhatsApp că Hannah');
  console.log('G3:', JSON.stringify(r));
  check('G3.message empty (echo of contact removed)', norm(r.message) === '', r.message);
  check('G3.route = MESSAGE_BODY_MISSING', r.route === 'MESSAGE_BODY_MISSING', r.route);
}

console.log(`\n=== ${failures === 0 ? 'ALL PASS' : failures + ' FAILURE(S)'} ===`);
if (failures > 0 && typeof process !== 'undefined') process.exitCode = 1;
