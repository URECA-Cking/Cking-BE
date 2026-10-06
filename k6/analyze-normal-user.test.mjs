import assert from 'node:assert/strict';
import { test } from 'node:test';
import { analyze, latestCompleteRun, parseObservations } from './analyze-normal-user.mjs';

function row(at, code, requestId, extra = {}) {
  return { kind: 'abuse-normal-observation', observedAtMs: at, memberId: 1,
    action: 'MISSION_COMPLETE', creatorId: 10, missionId: 20,
    balanceScope: 'CREATOR:10', code, expectedCode: code,
    status: code === 'DUPLICATE_MISSION' ? 409 : 202,
    expectedStatus: code === 'DUPLICATE_MISSION' ? 409 : 202,
    requestId, ...extra };
}

test('k6 console prefix를 제거하고 관찰 기록만 정렬한다', () => {
  const a = row(Date.UTC(2026, 9, 6), 'EARN_ACCEPTED', 'a');
  const b = row(Date.UTC(2026, 9, 6) + 500, 'ALREADY_PROCESSED', 'a',
    { status: 200, expectedStatus: 200 });
  const lines = `other line\ntime="x" level=info msg=${JSON.stringify(JSON.stringify(b))}\ntime="x" level=info msg=${JSON.stringify(JSON.stringify(a))}\n`;
  assert.deepEqual(parseObservations(lines), [a, b]);
});

test('replay를 제외하고 scope/window별 count와 distinct requestId를 계산한다', () => {
  const start = Date.UTC(2026, 9, 6);
  const rows = [row(start, 'EARN_ACCEPTED', 'a'),
    row(start + 200, 'ALREADY_PROCESSED', 'a', { status: 200, expectedStatus: 200 }),
    row(start + 400, 'DUPLICATE_MISSION', 'b'),
    row(start + 1200, 'DUPLICATE_MISSION', 'c'),
    row(start + 1400, 'SUCCESS', 'd', { action: 'EVENT_ENTRY', eventId: 30,
      status: 200, expectedStatus: 200 })];
  const result = analyze(rows);
  assert.equal(result.normalMaxByWindowMs[1000].missionRequest, 2);
  assert.equal(result.normalMaxByWindowMs[5000].missionRequest, 3);
  assert.equal(result.normalMaxByWindowMs[5000].rotation, 3);
  assert.equal(result.normalMaxByWindowMs[5000].duplicateMission, 2);
  assert.equal(result.normalMaxByWindowMs[5000].entryRequest, 1);
  assert.equal(result.normalMaxByWindowMs[5000].failureDistinctType, 1);
  assert.equal(result.normalMaxByWindowMs[5000].rapidEarnSpendPair, 1);
  assert.equal(result.normalMaxByWindowMs[5000].failureConsecutive, 2);
  assert.deepEqual(result.earnSpendClientDelayMs, [1400]);
});

test('예상 밖 응답이 있으면 정상 상한을 산출하지 않는다', () => {
  assert.throws(() => analyze([row(1000, 'SYSTEM_ERROR', 'a', { expectedCode: 'EARN_ACCEPTED' })]));
});

test('서로 다른 사용자·잔액 Scope를 합치지 않고 Window 경계를 제외한다', () => {
  const start = Date.UTC(2026, 9, 6);
  const rows = [
    row(start, 'INSUFFICIENT_BALANCE', 'a', { action: 'EVENT_ENTRY', eventId: 30,
      status: 409, expectedStatus: 409 }),
    row(start + 999, 'INSUFFICIENT_BALANCE', 'b', { action: 'EVENT_ENTRY', eventId: 30,
      status: 409, expectedStatus: 409 }),
    row(start + 1000, 'INSUFFICIENT_BALANCE', 'c', { action: 'EVENT_ENTRY', eventId: 30,
      status: 409, expectedStatus: 409 }),
    row(start + 1001, 'INSUFFICIENT_BALANCE', 'd', { action: 'EVENT_ENTRY', eventId: 30,
      memberId: 2, status: 409, expectedStatus: 409 }),
  ];
  const result = analyze(rows);
  assert.equal(result.normalMaxByWindowMs[1000].insufficient, 2);
  assert.equal(result.normalMaxByWindowMs[5000].insufficient, 3);
  assert.equal(result.normalMaxByWindowMs[5000].insufficientConsecutive, 3);
});

test('Lua와 같이 이전 실패가 Window 밖이면 연속 횟수를 1로 재시작한다', () => {
  const start = Date.UTC(2026, 9, 6);
  const rows = [
    row(start, 'INSUFFICIENT_BALANCE', 'a', { action: 'EVENT_ENTRY', eventId: 30,
      status: 409, expectedStatus: 409 }),
    row(start + 20000, 'INSUFFICIENT_BALANCE', 'b', { action: 'EVENT_ENTRY', eventId: 30,
      status: 409, expectedStatus: 409 }),
  ];
  const result = analyze(rows);
  for (const window of [1000, 5000, 10000]) {
    assert.equal(result.normalMaxByWindowMs[window].insufficientConsecutive, 1);
    assert.equal(result.normalMaxByWindowMs[window].failureConsecutive, 1);
  }
  for (const window of [30000, 60000]) {
    assert.equal(result.normalMaxByWindowMs[window].insufficientConsecutive, 2);
    assert.equal(result.normalMaxByWindowMs[window].failureConsecutive, 2);
  }
});

test('Window 경계와 성공 요청에서 연속 횟수를 초기화한다', () => {
  const start = Date.UTC(2026, 9, 6);
  const failure = (at, requestId) => row(at, 'INSUFFICIENT_BALANCE', requestId,
    { action: 'EVENT_ENTRY', eventId: 30, status: 409, expectedStatus: 409 });
  const rows = [failure(start, 'a'), failure(start + 1000, 'b'),
    row(start + 1100, 'EARN_ACCEPTED', 'c'), failure(start + 1200, 'd')];
  const result = analyze(rows);
  assert.equal(result.normalMaxByWindowMs[1000].insufficientConsecutive, 1);
  assert.equal(result.normalMaxByWindowMs[1000].failureConsecutive, 1);
  assert.equal(result.normalMaxByWindowMs[5000].insufficientConsecutive, 2);
  assert.equal(result.normalMaxByWindowMs[5000].failureConsecutive, 2);
});

test('추가 기록 파일에서는 마지막 완전 실행만 선택한다', () => {
  const earlier = Array.from({ length: 14 }, (_, index) => row(index + 1, 'EARN_ACCEPTED', String(index),
    { label: index === 0 ? 'click-first' : 'earlier' }));
  const latest = Array.from({ length: 15 }, (_, index) => row(index + 100, 'EARN_ACCEPTED', String(index),
    { label: index === 0 ? 'click-first' : index === 14 ? 'common-spend' : 'latest' }));
  assert.deepEqual(latestCompleteRun([...earlier, ...latest]), latest);
  assert.throws(() => latestCompleteRun([...latest, ...earlier]));
});
