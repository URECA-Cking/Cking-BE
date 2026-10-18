import http from 'k6/http';
import { SharedArray } from 'k6/data';
import { Counter } from 'k6/metrics';
import { tokenFactory } from './token.js';

// 실행 대상은 부하 테스트 환경만 허용한다. 개발 서버 데이터를 바꾸지 않게 한다.
export const BASE_URL = (__ENV.BASE_URL || '').replace(/\/$/, '');
if (!/^https:\/\/[a-z0-9-]*loadtest[a-z0-9-]*\.cking\.co\.kr$/.test(BASE_URL)) {
  throw new Error('BASE_URL은 부하 테스트 환경 주소여야 합니다(예: https://loadtest-api.cking.co.kr).');
}

// seed.sql과 같은 값. 회원 ID 1..CREATORS는 크리에이터 본인, 그 뒤 MEMBERS명이 응모한다.
export const CREATORS = positiveInt('CREATORS');
export const MEMBERS = positiveInt('MEMBERS');
const TICKET_COUNT = Number(__ENV.TICKET_COUNT || '1');

// VU마다 전역 변수가 따로 생겨, VU별로 캐시하면 VU 수만큼 토큰이 복제된다.
// SharedArray는 처음 한 번만 만들고 모든 VU가 읽기만 한다(회원 1만 명이면 수 MB).
const tokens = new SharedArray('member-tokens', () => {
  const sign = tokenFactory(__ENV.JWT_SECRET || '', positiveInt('TOKEN_TTL_SECONDS', 7200));
  const list = [];
  for (let memberId = CREATORS + 1; memberId <= CREATORS + MEMBERS; memberId += 1) {
    list.push(sign(memberId));
  }
  return list;
});

const entryResults = new Counter('loadtest_entry_results');

// 요청 번호로 회원·크리에이터 조합을 순서대로 돌려 쓴다. 조합마다 사용량이 고르게 나뉘어
// 한 실행에서 조합당 사용량은 ceil(요청 수 / 조합 수)를 넘지 않는다(시작 잔액 계산의 근거).
export function pairOf(index) {
  return { creatorId: 1 + (index % CREATORS), memberId: CREATORS + 1 + (Math.floor(index / CREATORS) % MEMBERS) };
}

// 한 크리에이터의 이벤트만 쓰는 시나리오(마감 직전 몰림)는 회원만 순서대로 돌려 쓴다.
export function memberOf(index) {
  return CREATORS + 1 + (index % MEMBERS);
}

// seed.sql은 크리에이터마다 이벤트 하나를 같은 ID로 만든다.
export function entryEventOf(creatorId) {
  return creatorId;
}

export function enter(memberId, eventId, tags = {}) {
  const requestId = uuid();
  const sentAt = Date.now();
  const response = http.post(`${BASE_URL}/api/events/${eventId}/entries`,
    JSON.stringify({ requestId, ticketCount: TICKET_COUNT, couponType: 'CREATOR' }), {
      headers: { Authorization: `Bearer ${tokenOf(memberId)}`, 'Content-Type': 'application/json' },
      responseCallback: http.expectedStatuses(200),
      tags: { name: 'entry', ...tags },
    });
  const code = codeOf(response);
  entryResults.add(1, { code });
  // 정합성 검증(성공 요청 ID와 DB 대조)의 입력. 토큰·본문은 남기지 않는다.
  console.log(JSON.stringify({ kind: 'entry', sentAt, at: Date.now(), requestId, memberId, eventId,
    status: response.status, code }));
  return { requestId, status: response.status, code };
}

function tokenOf(memberId) {
  return tokens[memberId - CREATORS - 1];
}

function codeOf(response) {
  try {
    return response.json('code') || `HTTP_${response.status}`;
  } catch (_) {
    return `HTTP_${response.status}`;
  }
}

function uuid() {
  if (globalThis.crypto && typeof globalThis.crypto.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r & 3) | 8).toString(16);
  });
}

export function positiveInt(name, fallback) {
  const raw = __ENV[name] ?? (fallback === undefined ? undefined : String(fallback));
  const value = Number(raw);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${name}은 양의 정수여야 합니다.`);
  }
  return value;
}
