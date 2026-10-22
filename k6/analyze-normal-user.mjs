// k6 --console-output 결과에서 정상군의 window별 client-side 상한을 계산한다.
import { readFileSync } from 'node:fs';

export const WINDOWS_MS = [1000, 5000, 10000, 30000, 60000];
export function parseObservations(text) {
  return text.split(/\r?\n/).filter(Boolean).map((line) => {
    const wrapped = line.match(/\bmsg=("(?:\\.|[^"\\])*")/);
    if (!wrapped) return null;
    let row;
    try { row = JSON.parse(JSON.parse(wrapped[1])); } catch (error) {
      if (line.includes('abuse-normal-observation')) throw new Error('손상된 정상군 관찰 로그', { cause: error });
      return null;
    }
    if (row.kind !== 'abuse-normal-observation') return null;
    if (!Number.isFinite(row.observedAtMs) || !Number.isSafeInteger(row.memberId) ||
        !row.action || !row.code || !row.requestId) {
      throw new Error(`불완전한 관찰 기록: ${row.label ?? 'unknown'}`);
    }
    return row;
  }).filter(Boolean).sort((left, right) => left.observedAtMs - right.observedAtMs);
}

export function analyze(rows) {
  const samples = Object.fromEntries(WINDOWS_MS.map((window) => [window, {}]));
  const latency = [];
  const clean = rows.filter((row) => row.code === row.expectedCode && row.status === row.expectedStatus);
  if (clean.length !== rows.length) throw new Error('기대 응답과 다른 관찰이 있어 normalMax를 계산하지 않습니다.');
  const observations = clean.filter((row) => !['ALREADY_PROCESSED', 'DUPLICATE_REPLAY'].includes(row.code));
  const keys = {
    missionRequest: (row) => row.action === 'MISSION_COMPLETE' ? String(row.memberId) : null,
    duplicateMission: (row) => row.code === 'DUPLICATE_MISSION' ? businessKey(row) : null,
    entryRequest: (row) => row.action === 'EVENT_ENTRY' ? `${row.memberId}:${row.eventId}` : null,
    insufficient: (row) => row.code === 'INSUFFICIENT_BALANCE' ? `${row.memberId}:${row.balanceScope}` : null,
    failure: (row) => ['DUPLICATE_MISSION', 'INSUFFICIENT_BALANCE'].includes(row.code) ? String(row.memberId) : null,
    rotation: (row) => row.action === 'MISSION_COMPLETE' ? businessKey(row) : null,
  };
  for (const [rule, getKey] of Object.entries(keys)) {
    for (const window of WINDOWS_MS) {
      const byScope = new Map();
      let max = 0;
      for (const row of observations) {
        const key = getKey(row);
        if (!key) continue;
        const prior = byScope.get(key) || [];
        prior.push(row);
        const active = prior.filter((item) => item.observedAtMs > row.observedAtMs - window);
        byScope.set(key, active);
        const count = rule === 'rotation' ? new Set(active.map((item) => item.requestId)).size : active.length;
        max = Math.max(max, count);
      }
      samples[window][rule] = max;
    }
  }
  const failureTypes = new Map();
  const pairs = [];
  const lastEarn = new Map();
  for (const row of observations) {
    const user = String(row.memberId);
    const scope = `${row.memberId}:${row.balanceScope}`;
    if (['DUPLICATE_MISSION', 'INSUFFICIENT_BALANCE'].includes(row.code)) {
      const history = failureTypes.get(user) || [];
      history.push(row);
      failureTypes.set(user, history);
    }
    if (row.code === 'EARN_ACCEPTED') lastEarn.set(scope, row.observedAtMs);
    if (row.code === 'SUCCESS' && lastEarn.has(scope)) {
      latency.push(row.observedAtMs - lastEarn.get(scope));
      pairs.push({ scope, at: row.observedAtMs });
      lastEarn.delete(scope);
    }
  }
  for (const window of WINDOWS_MS) {
    const insufficientSequence = new Map();
    const failureSequence = new Map();
    let insufficientConsecutiveMax = 0;
    let failureConsecutiveMax = 0;
    for (const row of observations) {
      const user = String(row.memberId);
      const scope = `${row.memberId}:${row.balanceScope}`;
      if (row.code === 'EARN_ACCEPTED' || row.code === 'SUCCESS') {
        insufficientSequence.delete(scope);
        failureSequence.delete(user);
      }
      if (row.code === 'INSUFFICIENT_BALANCE') {
        insufficientConsecutiveMax = Math.max(insufficientConsecutiveMax,
          recordConsecutive(insufficientSequence, scope, row.observedAtMs, window));
      }
      if (['DUPLICATE_MISSION', 'INSUFFICIENT_BALANCE'].includes(row.code)) {
        failureConsecutiveMax = Math.max(failureConsecutiveMax,
          recordConsecutive(failureSequence, user, row.observedAtMs, window));
      }
    }
    let rapidPairMax = 0;
    for (const pair of pairs) {
      const count = pairs.filter((item) => item.scope === pair.scope &&
        item.at <= pair.at && item.at > pair.at - window).length;
      rapidPairMax = Math.max(rapidPairMax, count);
    }
    let failureDistinctTypeMax = 0;
    for (const history of failureTypes.values()) {
      for (const row of history) {
        const distinct = new Set(history.filter((item) => item.observedAtMs <= row.observedAtMs &&
          item.observedAtMs > row.observedAtMs - window).map((item) => item.code)).size;
        failureDistinctTypeMax = Math.max(failureDistinctTypeMax, distinct);
      }
    }
    samples[window].rapidEarnSpendPair = rapidPairMax;
    samples[window].failureDistinctType = failureDistinctTypeMax;
    samples[window].insufficientConsecutive = insufficientConsecutiveMax;
    samples[window].failureConsecutive = failureConsecutiveMax;
  }
  // 실제 Feature Store는 Redis Lua 실행 시각으로 집계한다. 이것은 HTTP 완료 시각의 근사치다.
  return { observationCount: rows.length, nonReplayCount: observations.length,
    normalMaxByWindowMs: samples,
    earnSpendClientDelayMs: latency,
    note: 'HTTP 완료 시각 근사치입니다. 최종 Calibration은 Redis Feature/Evidence와 대조해야 합니다.' };
}

function recordConsecutive(sequence, scope, observedAtMs, windowMs) {
  const previous = sequence.get(scope);
  // Lua recordSequence(): 이전 시각 <= 현재 시각 - window 이면 1로 재시작한다.
  const count = previous && previous.at > observedAtMs - windowMs ? previous.count + 1 : 1;
  sequence.set(scope, { at: observedAtMs, count });
  return count;
}

export function latestCompleteRun(rows) {
  const start = rows.findLastIndex((row) => row.label === 'click-first');
  if (start < 0) throw new Error('정상군 실행 시작(click-first) 기록이 없습니다.');
  const latest = rows.slice(start);
  if (latest.length !== 15 || latest.at(-1).label !== 'common-spend') {
    throw new Error(`마지막 정상군 실행은 15건이 완결돼야 합니다. 실제 ${latest.length}건입니다.`);
  }
  return latest;
}

function businessKey(row) {
  // 정상군은 LIKE(DAILY)와 공용 ATTENDANCE(DAILY)만 사용한다.
  // UTC 자정 경계에서는 서버 periodKey와 다를 수 있으므로 그 시간에는 실행하지 않는다.
  const period = new Date(row.observedAtMs).toISOString().slice(0, 10);
  return `${row.memberId}:${row.creatorId ?? 'COMMON'}:${row.missionId}:${period}`;
}

if (process.argv[1]?.endsWith('/analyze-normal-user.mjs')) {
  if (!process.argv[2]) throw new Error('사용법: node k6/analyze-normal-user.mjs <k6-console-log>');
  const rows = latestCompleteRun(parseObservations(readFileSync(process.argv[2], 'utf8')));
  console.log(JSON.stringify(analyze(rows), null, 2));
}
