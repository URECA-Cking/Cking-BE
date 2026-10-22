import test from 'node:test';
import assert from 'node:assert/strict';
import { calibrate, calibrateFromLogs } from './calibrate-abuse.mjs';
import { SCENARIOS, WINDOWS_MS } from './analyze-abnormal-user.mjs';

const metrics = ['missionRequest', 'duplicateMission', 'entryRequest', 'insufficient',
  'insufficientConsecutive', 'rotation', 'rapidEarnSpendPair', 'failure',
  'failureConsecutive', 'failureDistinctType'];
function run(id, field, count) {
  return { runId: id, result: { observationCount: 10,
    [field]: Object.fromEntries(WINDOWS_MS.map((window) =>
      [window, Object.fromEntries(metrics.map((metric) => [metric, count]))])) } };
}
function manifest() {
  return { normalRuns: [1, 2, 3].map((n) => run(`normal-${n}`, 'normalMaxByWindowMs', n)),
    abnormalRuns: Object.fromEntries(SCENARIOS.map((scenario) => [scenario,
      [5, 6, 7].map((n) => run(`${scenario}-${n}`, 'observedMaxByWindowMs', n))])) };
}

test('반복 정상 최대값과 비정상 최소값으로 첫 분리 Window를 계산하되 운영값으로 표시하지 않는다', () => {
  const result = calibrate(manifest());
  assert.equal(result.status, 'HTTP_ONLY_NOT_OPERATIONAL');
  assert.deepEqual(result.candidates.missionRequest.candidate, { windowMs: 1000, threshold: 4 });
  assert.deepEqual(result.candidates.rotation.comparisons[0],
    { windowMs: 1000, normalMax: 3, abuseMin: 5, separated: true });
});

test('겹치는 Window에는 임계값을 만들지 않는다', () => {
  const data = manifest();
  data.abnormalRuns.missionRequestBurst = [2, 3, 4].map((n) =>
    run(`mission-request-overlap-${n}`, 'observedMaxByWindowMs', n));
  assert.equal(calibrate(data).candidates.missionRequest.candidate, null);
});

test('반복 횟수, 고유 실행 ID, 측정값 누락을 거부한다', () => {
  const data = manifest();
  data.normalRuns.pop();
  assert.throws(() => calibrate(data), /최소 3회/);
  data.normalRuns.push(run('normal-1', 'normalMaxByWindowMs', 3));
  assert.throws(() => calibrate(data), /runId/);
  data.normalRuns[2].runId = 'normal-3';
  delete data.normalRuns[2].result.normalMaxByWindowMs[1000].rotation;
  assert.throws(() => calibrate(data), /측정값/);
});

test('서로 다른 runId라도 동일한 로그 경로를 재사용하면 거부한다', () => {
  const data = manifest();
  data.normalRuns.forEach((item, index) => { item.logPath = `/tmp/normal-${index}.log`; });
  for (const [scenario, runs] of Object.entries(data.abnormalRuns)) {
    runs.forEach((item, index) => { item.logPath = `/tmp/${scenario}-${index}.log`; });
  }
  data.normalRuns[1].logPath = '/tmp/./normal-0.log';
  assert.throws(() => calibrateFromLogs(data), /동일한 logPath/);
});
