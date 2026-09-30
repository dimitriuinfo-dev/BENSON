// node --experimental-strip-types --test src/core/protocol/bensonProtocol.test.ts
// PHASE_A_PROTOCOL_AND_TIMEOUT — pure mapping tests. No runtime import of actionRequest.ts/
// actionResult.ts (both have their own extensionless internal imports the project's plain `node
// --test` runner can't resolve — see the ABBA/OPEN_APP round for the same finding); ActionRequest/
// ActionResult are used here only as `import type`, which the TS-stripping loader erases entirely
// (verified: a type-only import never triggers module resolution), so this file's own import graph
// stays clean.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  mapActionRequestToBensonIntent,
  mapActionResultToHandResult,
} from './bensonProtocol.ts';
import type { ActionRequest } from '../action-engine/actionRequest';
import type { ActionResult } from '../action-engine/actionResult';

function request(overrides: Partial<ActionRequest> & Pick<ActionRequest, 'intent'>): ActionRequest {
  return {
    id: 'action_1_1',
    source: 'voice',
    rawText: 'deschide netflix',
    parameters: {},
    riskLevel: 'LOW',
    requiresConfirmation: false,
    createdAt: 0,
    ...overrides,
  };
}

const deviceCtx = { missionId: 'mission_1', deviceId: 'primary-phone', deviceType: 'ANDROID_PHONE' as const };

test('OPEN_APP maps to BensonAction OPEN_APP with app name carried through', () => {
  const r = request({ intent: 'OPEN_APP', parameters: { appName: 'Netflix' } });
  const intent = mapActionRequestToBensonIntent(r, deviceCtx);
  assert.ok(intent);
  assert.equal(intent!.action, 'OPEN_APP');
  assert.equal(intent!.app, 'Netflix');
  assert.equal(intent!.intentId, 'action_1_1');
  assert.equal(intent!.missionId, 'mission_1');
  assert.equal(intent!.targetDevice.deviceType, 'ANDROID_PHONE');
  assert.equal(intent!.protocolVersion, 1);
});

test('requiresConfirmation is carried through verbatim from ActionRequest', () => {
  const r = request({ intent: 'CALL_CONTACT', requiresConfirmation: true, parameters: { target: 'Hannah' } });
  const intent = mapActionRequestToBensonIntent(r, deviceCtx);
  assert.equal(intent!.action, 'CALL');
  assert.equal(intent!.requiresConfirmation, true);
  assert.equal(intent!.target, 'Hannah');
});

test('an ActionIntent with no BensonAction equivalent yet maps to null, not a guess', () => {
  const r = request({ intent: 'SOS' });
  assert.equal(mapActionRequestToBensonIntent(r, deviceCtx), null);
  const r2 = request({ intent: 'CHAT' });
  assert.equal(mapActionRequestToBensonIntent(r2, deviceCtx), null);
});

test('the mapping is pure — same input always produces an equal-shaped output', () => {
  const r = request({ intent: 'MEDIA_PLAY', parameters: { query: 'ABBA' } });
  const a = mapActionRequestToBensonIntent(r, deviceCtx);
  const b = mapActionRequestToBensonIntent(r, deviceCtx);
  assert.deepEqual(a, b);
});

function result(overrides: Partial<ActionResult> & Pick<ActionResult, 'status'>): ActionResult {
  return { requestId: 'action_1_1', message: '', executed: false, ...overrides };
}

const resultCtx = { intentId: 'action_1_1', missionId: 'mission_1', deviceId: 'primary-phone', executionTimeMs: 42 };

test('success maps to SUCCESS, carries appOpened into observation.foregroundApp', () => {
  const r = result({ status: 'success', executed: true, appOpened: 'com.netflix.mediaclient' });
  const hr = mapActionResultToHandResult(r, resultCtx);
  assert.equal(hr.status, 'SUCCESS');
  assert.equal(hr.observation?.foregroundApp, 'com.netflix.mediaclient');
  assert.equal(hr.executionTimeMs, 42);
});

test('every ActionStatus has a defined HandStatus mapping (total, not partial)', () => {
  const statuses: ActionResult['status'][] = [
    'success', 'needs_confirmation', 'needs_permission', 'needs_disambiguation',
    'not_found', 'unsupported', 'failed', 'cancelled',
  ];
  for (const status of statuses) {
    const hr = mapActionResultToHandResult(result({ status }), resultCtx);
    assert.ok(hr.status, `status ${status} produced no HandStatus`);
  }
});

test('needs_disambiguation maps to REQUIRES_USER (never silently SUCCESS)', () => {
  const hr = mapActionResultToHandResult(result({ status: 'needs_disambiguation' }), resultCtx);
  assert.equal(hr.status, 'REQUIRES_USER');
});

test('errorCode is carried through verbatim', () => {
  const hr = mapActionResultToHandResult(result({ status: 'failed', errorCode: 'click_fail' }), resultCtx);
  assert.equal(hr.errorCode, 'click_fail');
});
