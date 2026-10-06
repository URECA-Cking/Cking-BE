// #441: k6 console 로그의 비정상군 HTTP 완료 시각을 Window별로 분석한다.
import { readFileSync } from 'node:fs';

export const WINDOWS_MS = [1000, 5000, 10000, 30000, 60000];
export const SCENARIOS = ['missionRequestBurst', 'duplicateMissionBurst', 'entryRequestBurst',
  'insufficientBalanceBurst', 'requestIdRotation', 'rapidEarnAndSpend', 'failureBurst'];
const BUSINESS_FAILURES = new Set(['DUPLICATE_MISSION', 'REQUEST_ID_CONFLICT', 'MISSION_INACTIVE',
  'INSUFFICIENT_BALANCE', 'EVENT_NOT_OPEN', 'EVENT_CLOSED', 'IDEMPOTENCY_CONFLICT']);
const REPLAY = new Set(['ALREADY_PROCESSED', 'DUPLICATE_REPLAY']);
const SUCCESS = new Set(['EARN_ACCEPTED', 'SUCCESS']);

export function parseObservations(text) {
  return text.split(/\r?\n/).filter(Boolean).map((line) => {
    const wrapped = line.match(/\bmsg=("(?:\\.|[^"\\])*")/);
    if (!wrapped) return null;
    let row;
    try { row = JSON.parse(JSON.parse(wrapped[1])); } catch (error) {
      if (line.includes('abuse-abnormal-observation')) throw new Error('손상된 비정상군 관찰 로그', { cause: error });
      return null;
    }
    return row.kind === 'abuse-abnormal-observation' ? row : null;
  }).filter(Boolean);
}

export function analyze(rows, scenario, requestCount = 8) {
  if (!SCENARIOS.includes(scenario)) throw new Error(`알 수 없는 시나리오: ${scenario}`);
  const expectedLength = scenario === 'rapidEarnAndSpend' ? 4
    : scenario === 'failureBurst' ? 10 : requestCount;
  if (rows.length !== expectedLength || rows.some((row) => row.scenario !== scenario)) {
    throw new Error(`${scenario}: 완결된 단일 실행 ${expectedLength}건이 필요합니다. 실제 ${rows.length}건`);
  }
  if (rows.some((row) => !Number.isFinite(row.observedAtMs) ||
      !Number.isSafeInteger(row.memberId) || !row.requestId || !row.action ||
      row.code !== row.expectedCode || row.status !== row.expectedStatus)) {
    throw new Error(`${scenario}: 누락되거나 예상과 다른 업무 응답이 있습니다.`);
  }
  if (new Set(rows.map((row) => row.memberId)).size !== 1) {
    throw new Error(`${scenario}: 하나의 합성 Member만 사용해야 합니다.`);
  }
  const ordered = [...rows].sort((a, b) => a.observedAtMs - b.observedAtMs);
  const results = {};
  for (const windowMs of WINDOWS_MS) {
    const histories = new Map();
    const insufficientSequence = new Map();
    const failureSequence = new Map();
    const lastEarn = new Map();
    const pairHistory = new Map();
    const metrics = { missionRequest: 0, duplicateMission: 0, entryRequest: 0,
      insufficient: 0, insufficientConsecutive: 0, rotation: 0,
      rapidEarnSpendPair: 0, failure: 0, failureConsecutive: 0, failureDistinctType: 0 };
    for (const row of ordered) {
      if (REPLAY.has(row.code)) continue;
      const user = String(row.memberId);
      const scope = `${user}:${row.balanceScope}`;
      const key = businessKey(row);
      if (row.action === 'MISSION_COMPLETE') {
        metrics.missionRequest = Math.max(metrics.missionRequest,
          count(histories, `mission:${user}`, row, windowMs));
        if (key) metrics.rotation = Math.max(metrics.rotation,
          distinctRequestIds(histories, `rotation:${key}`, row, windowMs));
      }
      if (row.code === 'DUPLICATE_MISSION' && key) {
        metrics.duplicateMission = Math.max(metrics.duplicateMission,
          count(histories, `duplicate:${key}`, row, windowMs));
      }
      if (row.action === 'EVENT_ENTRY') {
        metrics.entryRequest = Math.max(metrics.entryRequest,
          count(histories, `entry:${user}:${row.eventId}`, row, windowMs));
      }
      if (row.code === 'INSUFFICIENT_BALANCE') {
        metrics.insufficient = Math.max(metrics.insufficient,
          count(histories, `insufficient:${scope}`, row, windowMs));
        metrics.insufficientConsecutive = Math.max(metrics.insufficientConsecutive,
          consecutive(insufficientSequence, scope, row.observedAtMs, windowMs));
      }
      if (BUSINESS_FAILURES.has(row.code)) {
        metrics.failure = Math.max(metrics.failure,
          count(histories, `failure:${user}`, row, windowMs));
        metrics.failureConsecutive = Math.max(metrics.failureConsecutive,
          consecutive(failureSequence, user, row.observedAtMs, windowMs));
        metrics.failureDistinctType = Math.max(metrics.failureDistinctType,
          distinctCodes(histories, `failure-types:${user}`, row, windowMs));
      }
      if (SUCCESS.has(row.code)) {
        failureSequence.delete(user);
        insufficientSequence.delete(scope);
      }
      if (row.code === 'EARN_ACCEPTED') lastEarn.set(scope, row.observedAtMs);
      if (row.code === 'SUCCESS' && lastEarn.has(scope)) {
        const delay = row.observedAtMs - lastEarn.get(scope);
        if (delay >= 0) {
          metrics.rapidEarnSpendPair = Math.max(metrics.rapidEarnSpendPair,
            count(pairHistory, `pair:${scope}`, row, windowMs));
        }
        lastEarn.delete(scope);
      }
    }
    results[windowMs] = metrics;
  }
  const earnSpendClientDelayMs = [];
  const lastEarn = new Map();
  for (const row of ordered) {
    const scope = `${row.memberId}:${row.balanceScope}`;
    if (row.code === 'EARN_ACCEPTED') lastEarn.set(scope, row.observedAtMs);
    if (row.code === 'SUCCESS' && lastEarn.has(scope)) {
      earnSpendClientDelayMs.push(row.observedAtMs - lastEarn.get(scope));
      lastEarn.delete(scope);
    }
  }
  return { scenario, observationCount: rows.length, observedMaxByWindowMs: results,
    earnSpendClientDelayMs,
    note: 'HTTP 완료 시각 근사치입니다. 여러 비정상 실행의 최솟값(abuseMin)과 Redis Feature/Evidence를 후속 Calibration에서 비교합니다.' };
}

function record(history, key, row, windowMs) {
  const prior = history.get(key) || [];
  const active = prior.filter((item) => item.observedAtMs > row.observedAtMs - windowMs);
  active.push(row);
  history.set(key, active);
  return active;
}
function count(history, key, row, windowMs) { return record(history, key, row, windowMs).length; }
function distinctRequestIds(history, key, row, windowMs) {
  return new Set(record(history, key, row, windowMs).map((item) => item.requestId)).size;
}
function distinctCodes(history, key, row, windowMs) {
  return new Set(record(history, key, row, windowMs).map((item) => item.code)).size;
}
function consecutive(history, key, at, windowMs) {
  const previous = history.get(key);
  const value = previous && previous.at > at - windowMs ? previous.value + 1 : 1;
  history.set(key, { at, value });
  return value;
}
function businessKey(row) {
  if (row.action !== 'MISSION_COMPLETE' || !row.missionId) return null;
  const base = `${row.memberId}:${row.creatorId ?? 'COMMON'}:${row.missionId}`;
  return row.missionType === 'SHARE' ? base
    : `${base}:${new Date(row.observedAtMs).toISOString().slice(0, 10)}`;
}

if (process.argv[1]?.endsWith('/analyze-abnormal-user.mjs')) {
  const scenario = process.argv[2];
  const path = process.argv[3];
  if (!scenario || !path) {
    throw new Error('사용법: node k6/analyze-abnormal-user.mjs <scenario> <k6-console-log> [requestCount]');
  }
  const requestCount = process.argv[4] ? Number(process.argv[4]) : 8;
  console.log(JSON.stringify(analyze(parseObservations(readFileSync(path, 'utf8')),
    scenario, requestCount), null, 2));
}
