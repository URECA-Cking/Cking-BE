// 응모 지속 측정: SLO v0의 처리량으로 오래 유지하며 DB 반영이 따라가는지 본다.
// 합격·불합격은 SLO v0(p95, 성공률)로 판정한다.
import exec from 'k6/execution';
import { enter, entryEventOf, pairOf, positiveInt } from './lib/api.js';

export const options = {
  scenarios: {
    entry_sustain: {
      executor: 'constant-arrival-rate',
      rate: positiveInt('RATE'),
      timeUnit: '1s',
      duration: __ENV.DURATION || '30m',
      preAllocatedVUs: positiveInt('PRE_VUS', 50),
      maxVUs: positiveInt('MAX_VUS', 1000),
    },
  },
  thresholds: {
    'http_req_duration{name:entry}': [`p(95)<${positiveInt('SLO_P95_MS', 1000)}`],
    'http_req_failed{name:entry}': [`rate<${__ENV.SLO_FAIL_RATE || '0.001'}`],
    dropped_iterations: [{ threshold: 'count<1', abortOnFail: true }],
  },
};

export default function () {
  const { memberId, creatorId } = pairOf(exec.scenario.iterationInTest);
  enter(memberId, entryEventOf(creatorId), { phase: 'sustain' });
}
