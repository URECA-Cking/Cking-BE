// 정상·비정상 반복 실행의 HTTP 관찰값을 비교한다. 이 출력은 운영 설정이 아니다.
import { readFileSync } from 'node:fs';
import { WINDOWS_MS, SCENARIOS, analyze as analyzeAbnormal,
  parseObservations as parseAbnormal } from './analyze-abnormal-user.mjs';
import { analyze as analyzeNormal, latestCompleteRun,
  parseObservations as parseNormal } from './analyze-normal-user.mjs';

const METRICS = {
  missionRequest: ['missionRequestBurst', 'missionRequest'],
  duplicateMission: ['duplicateMissionBurst', 'duplicateMission'],
  entryRequest: ['entryRequestBurst', 'entryRequest'],
  insufficient: ['insufficientBalanceBurst', 'insufficient'],
  insufficientConsecutive: ['insufficientBalanceBurst', 'insufficientConsecutive'],
  rotation: ['requestIdRotation', 'rotation'],
  rapidEarnSpendPair: ['rapidEarnAndSpend', 'rapidEarnSpendPair'],
  failure: ['failureBurst', 'failure'],
  failureConsecutive: ['failureBurst', 'failureConsecutive'],
  failureDistinctType: ['failureBurst', 'failureDistinctType'],
};

export function calibrate(manifest) {
  const normal = manifest.normalRuns;
  const abnormal = manifest.abnormalRuns;
  if (!Array.isArray(normal) || normal.length < 3 || !abnormal || typeof abnormal !== 'object') {
    throw new Error('정상군과 각 비정상 시나리오의 독립 실행이 최소 3회 필요합니다.');
  }
  const all = [...normal];
  for (const scenario of SCENARIOS) {
    if (!Array.isArray(abnormal[scenario]) || abnormal[scenario].length < 3) {
      throw new Error(`${scenario}: 비정상군 독립 실행이 최소 3회 필요합니다.`);
    }
    all.push(...abnormal[scenario]);
  }
  const ids = all.map((run) => run.runId);
  if (ids.some((id) => typeof id !== 'string' || !id.trim()) || new Set(ids).size !== ids.length) {
    throw new Error('모든 실행에 서로 다른 비어 있지 않은 runId가 필요합니다.');
  }
  for (const run of all) {
    if (!run.result || run.result.observationCount <= 0) {
      throw new Error(`${run.runId}: 분석 결과가 비어 있습니다.`);
    }
  }
  const candidates = {};
  for (const [name, [scenario, metric]] of Object.entries(METRICS)) {
    const comparisons = WINDOWS_MS.map((windowMs) => {
      const normalMax = Math.max(...normal.map((run) => value(run, 'normalMaxByWindowMs', windowMs, metric)));
      const abuseMin = Math.min(...abnormal[scenario].map((run) =>
        value(run, 'observedMaxByWindowMs', windowMs, metric)));
      return { windowMs, normalMax, abuseMin, separated: normalMax < abuseMin };
    });
    const first = comparisons.find((item) => item.separated);
    candidates[name] = { comparisons,
      candidate: first ? { windowMs: first.windowMs, threshold: first.normalMax + 1 } : null };
  }
  return {
    status: 'HTTP_ONLY_NOT_OPERATIONAL',
    reason: 'HTTP 완료 시각은 Redis Lua 시각과 다릅니다. 실제 Feature/Evidence 및 maxDelay 대조 전 운영 설정에 적용하지 마세요.',
    runs: { normal: normal.length, abnormal: Object.fromEntries(SCENARIOS.map((s) => [s, abnormal[s].length])) },
    candidates,
  };
}

export function calibrateFromLogs(manifest) {
  const normalRuns = manifest.normalRuns?.map((run) => ({ runId: run.runId,
    result: analyzeNormal(latestCompleteRun(parseNormal(readFileSync(run.logPath, 'utf8')))) }));
  const abnormalRuns = Object.fromEntries(SCENARIOS.map((scenario) => [scenario,
    manifest.abnormalRuns?.[scenario]?.map((run) => ({ runId: run.runId,
      result: analyzeAbnormal(parseAbnormal(readFileSync(run.logPath, 'utf8')),
        scenario, run.requestCount ?? 8) }))]));
  return calibrate({ normalRuns, abnormalRuns });
}

function value(run, field, windowMs, metric) {
  const measured = run.result[field]?.[windowMs]?.[metric];
  if (!Number.isSafeInteger(measured) || measured < 0) {
    throw new Error(`${run.runId}: ${field}.${windowMs}.${metric} 측정값이 없습니다.`);
  }
  return measured;
}

if (process.argv[1]?.endsWith('/calibrate-abuse.mjs')) {
  if (process.argv.length !== 3) {
    throw new Error('사용법: node k6/calibrate-abuse.mjs <manifest.json>');
  }
  console.log(JSON.stringify(calibrateFromLogs(JSON.parse(readFileSync(process.argv[2], 'utf8'))), null, 2));
}
