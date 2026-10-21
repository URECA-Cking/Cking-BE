// 응모 한계 측정: 도착률을 단계마다 올린다. 각 단계는 RAMP 동안 올리고 HOLD 동안 유지한다.
// 한계 판단(p95, 실패율, 자원)은 대시보드와 계획의 중단 조건으로 한다.
import exec from 'k6/execution';
import { enter, entryEventOf, pairOf, positiveInt } from './lib/api.js';

const START = positiveInt('START_RATE');
const STEP = positiveInt('STEP_RATE');
const STEPS = positiveInt('STEPS');
const RAMP = __ENV.RAMP || '15s';
const HOLD = __ENV.HOLD || '3m';

const stages = [];
for (let i = 0; i < STEPS; i += 1) {
  const target = START + STEP * i;
  stages.push({ target, duration: RAMP }, { target, duration: HOLD });
}

export const options = {
  scenarios: {
    entry_limit: {
      executor: 'ramping-arrival-rate',
      startRate: START,
      timeUnit: '1s',
      preAllocatedVUs: positiveInt('PRE_VUS', 50),
      maxVUs: positiveInt('MAX_VUS', 1000),
      stages,
    },
  },
  // 부하 발생기 한계로 목표 도착률을 못 맞추면 측정이 무의미하므로 멈춘다.
  thresholds: {
    dropped_iterations: [{ threshold: 'count<1', abortOnFail: true }],
  },
};

export default function () {
  const { memberId, creatorId } = pairOf(exec.scenario.iterationInTest);
  enter(memberId, entryEventOf(creatorId), { phase: 'limit' });
}
