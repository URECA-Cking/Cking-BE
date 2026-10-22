// #441: 격리된 합성 계정만 변경하는 Abuse Detection 비정상군 공통 실행기.
import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const fixtures = __ENV.ABNORMAL_FIXTURES_FILE
  ? JSON.parse(open(__ENV.ABNORMAL_FIXTURES_FILE)) : null;

export const options = { vus: 1, iterations: 1, thresholds: { checks: ['rate==1.00'] } };

export function runScenario(name) {
  if (__ENV.ABNORMAL_K6_ALLOW_MUTATION !== 'true' ||
      __ENV.ABNORMAL_K6_CONFIRM_TARGET !== BASE_URL ||
      !/^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(BASE_URL)) {
    throw new Error('격리 localhost 대상과 ABNORMAL_K6_ALLOW_MUTATION=true, ABNORMAL_K6_CONFIRM_TARGET을 확인하세요.');
  }
  validateFixtures(fixtures);
  const actor = fixtures[name];
  if (!actor) throw new Error(`알 수 없는 시나리오: ${name}`);
  const n = positiveCount(__ENV.ABNORMAL_REQUEST_COUNT || '8');
  const pauseSeconds = Number(__ENV.ABNORMAL_PAUSE_SECONDS || '0');
  if (!Number.isFinite(pauseSeconds) || pauseSeconds < 0 || pauseSeconds > 1) {
    throw new Error('ABNORMAL_PAUSE_SECONDS는 0~1이어야 합니다.');
  }

  if (name === 'missionRequestBurst' || name === 'duplicateMissionBurst' || name === 'requestIdRotation') {
    mission(name, actor, fixtures.likeMissionId, uuid(), 202, 'EARN_ACCEPTED', 'first');
    repeat(n - 1, () => mission(name, actor, fixtures.likeMissionId, uuid(), 409,
      'DUPLICATE_MISSION', 'duplicate'), pauseSeconds);
  } else if (name === 'entryRequestBurst') {
    repeat(n, () => entry(name, actor, uuid(), 200, 'SUCCESS', 'entry'), pauseSeconds);
  } else if (name === 'insufficientBalanceBurst') {
    repeat(n, () => entry(name, actor, uuid(), 409, 'INSUFFICIENT_BALANCE', 'insufficient'), pauseSeconds);
  } else if (name === 'rapidEarnAndSpend') {
    // 같은 사용자·Creator BalanceScope에서 독립적인 LIKE와 SHARE EARN을 한 번씩 사용한다.
    mission(name, actor, fixtures.likeMissionId, uuid(), 202, 'EARN_ACCEPTED', 'like-earn');
    entry(name, actor, uuid(), 200, 'SUCCESS', 'like-spend');
    share(name, actor, uuid(), 202, 'EARN_ACCEPTED', 'share-earn');
    entry(name, actor, uuid(), 200, 'SUCCESS', 'share-spend');
  } else if (name === 'failureBurst') {
    mission(name, actor, fixtures.likeMissionId, uuid(), 202, 'EARN_ACCEPTED', 'seed-success');
    entry(name, actor, uuid(), 200, 'SUCCESS', 'seed-spend');
    repeat(3, () => mission(name, actor, fixtures.likeMissionId, uuid(), 409,
      'DUPLICATE_MISSION', 'duplicate'), pauseSeconds);
    repeat(3, () => entry(name, actor, uuid(), 409, 'INSUFFICIENT_BALANCE', 'insufficient'), pauseSeconds);
    repeat(2, () => mission(name, actor, fixtures.inactiveMissionId, uuid(), 409,
      'MISSION_INACTIVE', 'inactive'), pauseSeconds);
  } else {
    throw new Error(`지원하지 않는 시나리오: ${name}`);
  }
}

function repeat(count, action, pauseSeconds) {
  for (let i = 0; i < count; i++) {
    action();
    if (pauseSeconds && i < count - 1) sleep(pauseSeconds);
  }
}

function mission(scenario, actor, missionId, requestId, status, code, label) {
  const creatorId = missionId === fixtures.inactiveMissionId
    ? fixtures.inactiveCreatorId : fixtures.creatorId;
  send(scenario, actor, `/api/creators/${creatorId}/missions/${missionId}/complete`,
    { action: 'MISSION_COMPLETE', creatorId, missionId, missionType: 'LIKE',
      balanceScope: `CREATOR:${creatorId}` }, requestId, status, code, label);
}

function share(scenario, actor, requestId, status, code, label) {
  send(scenario, actor, `/api/creators/${fixtures.creatorId}/missions/share/complete`,
    { action: 'MISSION_COMPLETE', creatorId: fixtures.creatorId,
      missionId: fixtures.shareMissionId, missionType: 'SHARE',
      balanceScope: `CREATOR:${fixtures.creatorId}` }, requestId, status, code, label);
}

function entry(scenario, actor, requestId, status, code, label) {
  send(scenario, actor, `/api/events/${fixtures.eventId}/entries`,
    { action: 'EVENT_ENTRY', creatorId: fixtures.creatorId, eventId: fixtures.eventId,
      balanceScope: `CREATOR:${fixtures.creatorId}` }, requestId, status, code, label,
    { ticketCount: 1, couponType: 'CREATOR' });
}

function send(scenario, actor, path, context, requestId, expectedStatus, expectedCode, label, extra = {}) {
  const requestedAtMs = Date.now();
  const response = http.post(`${BASE_URL}${path}`, JSON.stringify({ requestId, ...extra }), {
    headers: { Authorization: `Bearer ${actor.token}`, 'Content-Type': 'application/json' },
    responseCallback: http.expectedStatuses(200, 202, 409),
    tags: { name: `${scenario}-${label}` },
  });
  const observedAtMs = Date.now();
  let code = null;
  try { code = response.json('code'); } catch (_) { /* check가 비정상 응답을 보고한다. */ }
  const valid = check(response, {
    [`${scenario}-${label}: HTTP ${expectedStatus}`]: (r) => r.status === expectedStatus,
    [`${scenario}-${label}: ${expectedCode}`]: () => code === expectedCode,
  });
  // JWT·본문·개인정보는 로그에 남기지 않는다. 시각은 Redis Lua 시각의 근사치다.
  console.log(JSON.stringify({ kind: 'abuse-abnormal-observation', scenario, label,
    requestedAtMs, observedAtMs, memberId: actor.memberId, requestId,
    ...context, status: response.status, code, expectedStatus, expectedCode }));
  if (!valid) throw new Error(`${scenario}-${label}: 업무 응답이 달라 측정과 후속 요청을 중단합니다.`);
}

function validateFixtures(value) {
  if (!value || typeof value !== 'object') throw new Error('ABNORMAL_FIXTURES_FILE이 필요합니다.');
  const names = ['missionRequestBurst', 'duplicateMissionBurst', 'entryRequestBurst',
    'insufficientBalanceBurst', 'requestIdRotation', 'rapidEarnAndSpend', 'failureBurst'];
  const actors = names.map((name) => value[name]);
  if (actors.some((actor) => !actor || !Number.isSafeInteger(actor.memberId) ||
      actor.memberId <= 0 || !actor.token)) throw new Error('일곱 시나리오 각각 합성 Member/JWT가 필요합니다.');
  if (new Set(actors.map((actor) => actor.memberId)).size !== actors.length) {
    throw new Error('시나리오별 Member는 달라야 합니다.');
  }
  const ids = ['creatorId', 'inactiveCreatorId', 'likeMissionId', 'shareMissionId',
    'inactiveMissionId', 'eventId'];
  if (ids.some((field) => !Number.isSafeInteger(value[field]) || value[field] <= 0)) {
    throw new Error('fixture 식별자는 양수 정수여야 합니다.');
  }
}

function positiveCount(raw) {
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value < 3 || value > 12) {
    throw new Error('ABNORMAL_REQUEST_COUNT는 fixture 잔액을 넘지 않는 3~12여야 합니다.');
  }
  return value;
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    return (character === 'x' ? random : (random & 3) | 8).toString(16);
  });
}
