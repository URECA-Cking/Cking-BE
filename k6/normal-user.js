// Abuse Detection 정상군: 격리된 데이터에서 한 번만 실행한다.
import http from 'k6/http';
import { check, sleep } from 'k6';

const WINDOWS_MS = [1000, 5000, 10000, 30000, 60000];
const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
const fixtures = __ENV.NORMAL_FIXTURES_FILE
  ? JSON.parse(open(__ENV.NORMAL_FIXTURES_FILE))
  : null;

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: { checks: ['rate==1.00'] },
};

export default function () {
  if (__ENV.NORMAL_K6_ALLOW_MUTATION !== 'true' || !BASE_URL ||
      __ENV.NORMAL_K6_CONFIRM_TARGET !== BASE_URL) {
    check(false, { '격리 환경 변경 명시 확인': () => false });
    throw new Error('격리 환경에서만 NORMAL_K6_ALLOW_MUTATION=true 및 NORMAL_K6_CONFIRM_TARGET=BASE_URL을 설정하세요.');
  }
  validateFixtures(fixtures);

  const click = fixtures.clickReplay;
  const clickId = uuid();
  mission(click, click.creatorId, click.missionId, clickId, 202, 'EARN_ACCEPTED', 'click-first');
  pause();
  mission(click, click.creatorId, click.missionId, clickId, 200, 'ALREADY_PROCESSED', 'click-replay-1');
  pause();
  mission(click, click.creatorId, click.missionId, clickId, 200, 'ALREADY_PROCESSED', 'click-replay-2');
  pause();
  mission(click, click.creatorId, click.missionId, uuid(), 409, 'DUPLICATE_MISSION', 'click-new-id');

  const multi = fixtures.multiEntry;
  entry(multi, multi.eventId, multi.creatorId, multi.couponType, uuid(), 200, 'SUCCESS', 'entry-first');
  pause();
  entry(multi, multi.eventId, multi.creatorId, multi.couponType, uuid(), 200, 'SUCCESS', 'entry-second');

  const recovery = fixtures.insufficientThenEarn;
  entry(recovery, recovery.eventId, recovery.creatorId, 'CREATOR', uuid(), 409,
    'INSUFFICIENT_BALANCE', 'insufficient-first');
  pause();
  entry(recovery, recovery.eventId, recovery.creatorId, 'CREATOR', uuid(), 409,
    'INSUFFICIENT_BALANCE', 'insufficient-second');
  mission(recovery, recovery.creatorId, recovery.missionId, uuid(), 202,
    'EARN_ACCEPTED', 'earn-after-insufficient');
  const spendId = uuid();
  entry(recovery, recovery.eventId, recovery.creatorId, 'CREATOR', spendId, 200,
    'SUCCESS', 'spend-after-earn');
  entry(recovery, recovery.eventId, recovery.creatorId, 'CREATOR', spendId, 200,
    'DUPLICATE_REPLAY', 'entry-replay');

  const sequence = fixtures.creatorSequence;
  mission(sequence, sequence.first.creatorId, sequence.first.missionId, uuid(), 202,
    'EARN_ACCEPTED', 'creator-first');
  pause();
  mission(sequence, sequence.second.creatorId, sequence.second.missionId, uuid(), 202,
    'EARN_ACCEPTED', 'creator-second');

  const common = fixtures.commonEarnSpend;
  commonMission(common, common.missionId, uuid(), 202, 'EARN_ACCEPTED', 'common-earn');
  entry(common, common.eventId, common.creatorId, 'COMMON', uuid(), 200,
    'SUCCESS', 'common-spend');
}

function mission(actor, creatorId, missionId, requestId, status, code, label) {
  send(actor, `/api/creators/${creatorId}/missions/${missionId}/complete`, requestId,
    { action: 'MISSION_COMPLETE', creatorId, missionId, balanceScope: `CREATOR:${creatorId}`, label },
    status, code);
}

function commonMission(actor, missionId, requestId, status, code, label) {
  send(actor, `/api/missions/${missionId}/complete`, requestId,
    { action: 'MISSION_COMPLETE', missionId, balanceScope: 'COMMON', label }, status, code);
}

function entry(actor, eventId, creatorId, couponType, requestId, status, code, label) {
  send(actor, `/api/events/${eventId}/entries`, requestId,
    { action: 'EVENT_ENTRY', eventId, creatorId,
      balanceScope: couponType === 'COMMON' ? 'COMMON' : `CREATOR:${creatorId}`, label },
    status, code, { ticketCount: 1, couponType });
}

function send(actor, path, requestId, context, expectedStatus, expectedCode, extraBody = {}) {
  const requestedAtMs = Date.now();
  const response = http.post(`${BASE_URL}${path}`, JSON.stringify({ requestId, ...extraBody }), {
    headers: { Authorization: `Bearer ${actor.token}`, 'Content-Type': 'application/json' },
    responseCallback: http.expectedStatuses(200, 202, 409),
    tags: { name: context.label },
  });
  const observedAtMs = Date.now();
  let code = null;
  try { code = response.json('code'); } catch (_) { /* 응답 실패는 아래 check에서 보고한다. */ }
  const valid = check(response, {
    [`${context.label}: HTTP ${expectedStatus}`]: (r) => r.status === expectedStatus,
    [`${context.label}: ${expectedCode}`]: () => code === expectedCode,
  });
  // 콘솔 파일에는 JWT·본문·실명·이메일을 남기지 않는다. 실제 Redis 집계 시각은 별도다.
  console.log(JSON.stringify({ kind: 'abuse-normal-observation',
    requestedAtMs, observedAtMs, memberId: actor.memberId, requestId,
    ...context, status: response.status, code,
    expectedStatus, expectedCode }));
  if (!valid) throw new Error(`${context.label}: 기대한 업무 응답이 아니므로 후속 데이터 변경을 중단합니다.`);
}

function validateFixtures(value) {
  if (!value || typeof value !== 'object') throw new Error('NORMAL_FIXTURES_FILE이 필요합니다.');
  const actors = ['clickReplay', 'multiEntry', 'insufficientThenEarn', 'creatorSequence', 'commonEarnSpend']
    .map((name) => value[name]);
  if (actors.some((actor) => !actor || !Number.isSafeInteger(actor.memberId) || actor.memberId <= 0 || !actor.token)) {
    throw new Error('다섯 시나리오 각각 양수 memberId와 token이 필요합니다.');
  }
  if (new Set(actors.map((actor) => actor.memberId)).size !== actors.length) {
    throw new Error('시나리오 간 테스트 Member는 서로 달라야 합니다.');
  }
  const ids = [value.clickReplay.creatorId, value.clickReplay.missionId,
    value.multiEntry.creatorId, value.multiEntry.eventId,
    value.insufficientThenEarn.creatorId, value.insufficientThenEarn.eventId,
    value.insufficientThenEarn.missionId,
    value.creatorSequence.first?.creatorId, value.creatorSequence.first?.missionId,
    value.creatorSequence.second?.creatorId, value.creatorSequence.second?.missionId,
    value.commonEarnSpend.creatorId, value.commonEarnSpend.eventId, value.commonEarnSpend.missionId];
  if (ids.some((id) => !Number.isSafeInteger(id) || id <= 0) ||
      !['COMMON', 'CREATOR'].includes(value.multiEntry.couponType)) {
    throw new Error('Creator/Mission/Event ID는 양수, couponType은 COMMON 또는 CREATOR여야 합니다.');
  }
  if (value.creatorSequence.first.creatorId === value.creatorSequence.second.creatorId) {
    throw new Error('Creator 순차 수행은 서로 다른 Creator여야 합니다.');
  }
  if (value.windowsMs && JSON.stringify(value.windowsMs) !== JSON.stringify(WINDOWS_MS)) {
    throw new Error('Window 후보는 1/5/10/30/60초로 고정합니다.');
  }
}

function pause() { sleep(Number(__ENV.NORMAL_CLICK_PAUSE_SECONDS || '0.2')); }

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (character) => {
    const random = Math.floor(Math.random() * 16);
    return (character === 'x' ? random : (random & 3) | 8).toString(16);
  });
}
