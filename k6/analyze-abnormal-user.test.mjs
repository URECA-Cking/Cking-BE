import assert from 'node:assert/strict';
import { test } from 'node:test';
import { analyze, parseObservations, SCENARIOS } from './analyze-abnormal-user.mjs';

const start = Date.UTC(2026, 9, 6, 3);
function row(scenario, offset, code, action = 'MISSION_COMPLETE', extra = {}) {
  return { kind: 'abuse-abnormal-observation', scenario,
    label: `${scenario}-${offset}`, memberId: 111, creatorId: 211,
    missionId: action === 'MISSION_COMPLETE' ? 311 : undefined,
    eventId: action === 'EVENT_ENTRY' ? 411 : undefined,
    missionType: 'LIKE', balanceScope: 'CREATOR:211',
    requestedAtMs: start + offset, observedAtMs: start + offset,
    requestId: `id-${offset}`, action, code, expectedCode: code,
    status: code === 'EARN_ACCEPTED' ? 202 : code === 'SUCCESS' ? 200 : 409,
    expectedStatus: code === 'EARN_ACCEPTED' ? 202 : code === 'SUCCESS' ? 200 : 409,
    ...extra };
}

test('k6 로그에서 비정상군 행만 읽고 손상된 행을 거부한다', () => {
  const value = row('entryRequestBurst', 0, 'SUCCESS', 'EVENT_ENTRY');
  const line = `time="x" level=info msg=${JSON.stringify(JSON.stringify(value))}`;
  assert.deepEqual(parseObservations(`other\n${line}\n`), [JSON.parse(JSON.stringify(value))]);
  assert.throws(() => parseObservations('time="x" level=info msg="abuse-abnormal-observation invalid"'));
});

test('요청 Burst와 Rotation은 Scope·Window·distinct requestId별로 계산한다', () => {
  const scenario = 'requestIdRotation';
  const rows = [row(scenario, 0, 'EARN_ACCEPTED'),
    row(scenario, 100, 'DUPLICATE_MISSION'),
    row(scenario, 200, 'DUPLICATE_MISSION', 'MISSION_COMPLETE', { requestId: 'id-100' }),
    row(scenario, 1500, 'DUPLICATE_MISSION')];
  const result = analyze(rows, scenario, 4);
  assert.equal(result.observedMaxByWindowMs[1000].missionRequest, 3);
  assert.equal(result.observedMaxByWindowMs[5000].missionRequest, 4);
  assert.equal(result.observedMaxByWindowMs[5000].rotation, 3);
  assert.equal(result.observedMaxByWindowMs[5000].duplicateMission, 3);
});

test('Replay는 요청·실패·Rotation 집계에서 제외한다', () => {
  const scenario = 'missionRequestBurst';
  const rows = [row(scenario, 0, 'EARN_ACCEPTED'),
    row(scenario, 100, 'ALREADY_PROCESSED', 'MISSION_COMPLETE', {
      requestId: 'id-0', status: 200, expectedStatus: 200 }),
    row(scenario, 200, 'DUPLICATE_MISSION')];
  const result = analyze(rows, scenario, 3);
  assert.equal(result.observedMaxByWindowMs[1000].missionRequest, 2);
  assert.equal(result.observedMaxByWindowMs[1000].rotation, 2);
  assert.equal(result.observedMaxByWindowMs[1000].failure, 1);
});

test('연속 실패는 각 Window 경계에서 초기화하고 성공 시 끊는다', () => {
  const scenario = 'insufficientBalanceBurst';
  const failure = (at) => row(scenario, at, 'INSUFFICIENT_BALANCE', 'EVENT_ENTRY');
  const rows = [failure(0), failure(20000),
    row(scenario, 20100, 'SUCCESS', 'EVENT_ENTRY'), failure(20200)];
  const result = analyze(rows, scenario, 4);
  assert.equal(result.observedMaxByWindowMs[1000].insufficientConsecutive, 1);
  assert.equal(result.observedMaxByWindowMs[30000].insufficientConsecutive, 2);
  assert.equal(result.observedMaxByWindowMs[30000].failureConsecutive, 2);
});

test('두 EARN-SPEND pair는 한 잔액 Scope에서 각각 한 번만 계산한다', () => {
  const scenario = 'rapidEarnAndSpend';
  const rows = [row(scenario, 0, 'EARN_ACCEPTED'),
    row(scenario, 100, 'SUCCESS', 'EVENT_ENTRY'),
    row(scenario, 200, 'EARN_ACCEPTED', 'MISSION_COMPLETE', { missionId: 312, missionType: 'SHARE' }),
    row(scenario, 300, 'SUCCESS', 'EVENT_ENTRY')];
  const result = analyze(rows, scenario);
  assert.equal(result.observedMaxByWindowMs[1000].rapidEarnSpendPair, 2);
  assert.deepEqual(result.earnSpendClientDelayMs, [100, 100]);
});

test('실패 유형은 distinct code이며 예상 밖 응답이나 불완전 실행은 수치에서 제외한다', () => {
  const scenario = 'failureBurst';
  const rows = [row(scenario, 0, 'EARN_ACCEPTED'),
    row(scenario, 50, 'SUCCESS', 'EVENT_ENTRY'),
    ...[100, 200, 300].map((at) => row(scenario, at, 'DUPLICATE_MISSION')),
    ...[400, 500, 600].map((at) => row(scenario, at, 'INSUFFICIENT_BALANCE', 'EVENT_ENTRY')),
    ...[700, 800].map((at) => row(scenario, at, 'MISSION_INACTIVE', 'MISSION_COMPLETE',
      { creatorId: 212, missionId: 313 }))];
  const result = analyze(rows, scenario);
  assert.equal(result.observedMaxByWindowMs[1000].failure, 8);
  assert.equal(result.observedMaxByWindowMs[1000].failureConsecutive, 8);
  assert.equal(result.observedMaxByWindowMs[1000].failureDistinctType, 3);
  assert.throws(() => analyze(rows.slice(0, -1), scenario));
  assert.throws(() => analyze(rows.map((item, index) => index === 1
    ? { ...item, code: 'SYSTEM_ERROR' } : item), scenario));
});

test('모든 일곱 시나리오 명칭이 계약에 포함된다', () => {
  assert.equal(SCENARIOS.length, 7);
  assert.equal(new Set(SCENARIOS).size, 7);
});
