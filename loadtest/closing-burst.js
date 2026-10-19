// 마감 직전 몰림: 종료 시각 전후로 일정한 도착률로 응모를 보낸다.
// 종료 시각 전 성공 응모는 모두 Snapshot에 들어가고, 종료 시각 뒤 요청은 모두 거절돼야 한다.
import exec from 'k6/execution';
import { enter, memberOf, positiveInt } from './lib/api.js';

const EVENT_ID = positiveInt('CLOSING_EVENT_ID');
const END_AT_MS = positiveInt('END_AT_MS');

export const options = {
  scenarios: {
    closing_burst: {
      executor: 'constant-arrival-rate',
      rate: positiveInt('RATE'),
      timeUnit: '1s',
      duration: __ENV.DURATION || '3m',
      preAllocatedVUs: positiveInt('PRE_VUS', 50),
      maxVUs: positiveInt('MAX_VUS', 1000),
    },
  },
  thresholds: {
    dropped_iterations: [{ threshold: 'count<1', abortOnFail: true }],
  },
};

export default function () {
  const memberId = memberOf(exec.scenario.iterationInTest);
  const sentAt = Date.now();
  enter(memberId, EVENT_ID, { phase: sentAt < END_AT_MS ? 'before_end' : 'after_end' });
}
